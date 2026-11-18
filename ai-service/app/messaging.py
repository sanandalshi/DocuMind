import asyncio
import json
import logging
import uuid

import aio_pika
from aio_pika.abc import AbstractIncomingMessage

from .config import Settings
from .ingest import IngestError, Ingestor
from .logging_setup import correlation_id

log = logging.getLogger(__name__)

EXCHANGE = "documind.events"
DLX = "documind.dlx"
QUEUE = "ai.document.uploaded"
DLQ = "ai.document.uploaded.dlq"


class Messaging:
    def __init__(self, settings: Settings, ingestor: Ingestor):
        self.settings = settings
        self.ingestor = ingestor
        self.connection: aio_pika.abc.AbstractRobustConnection | None = None
        self.exchange: aio_pika.abc.AbstractExchange | None = None

    async def start(self) -> None:
        for attempt in range(30):
            try:
                self.connection = await aio_pika.connect_robust(self.settings.rabbitmq_url)
                break
            except Exception:
                log.info("Waiting for RabbitMQ (attempt %d)", attempt + 1)
                await asyncio.sleep(2)
        else:
            raise RuntimeError("Could not connect to RabbitMQ")

        channel = await self.connection.channel()
        await channel.set_qos(prefetch_count=1)  # one document at a time per worker
        self.exchange = await channel.declare_exchange(EXCHANGE, aio_pika.ExchangeType.TOPIC, durable=True)
        dlx = await channel.declare_exchange(DLX, aio_pika.ExchangeType.TOPIC, durable=True)
        dlq = await channel.declare_queue(DLQ, durable=True)
        await dlq.bind(dlx, routing_key="#")
        queue = await channel.declare_queue(QUEUE, durable=True, arguments={"x-dead-letter-exchange": DLX})
        await queue.bind(self.exchange, routing_key="document.uploaded")
        await queue.consume(self.on_message)
        log.info("Consuming %s", QUEUE)

    async def stop(self) -> None:
        if self.connection:
            await self.connection.close()

    async def publish(self, routing_key: str, payload: dict, headers: dict | None = None) -> None:
        await self.exchange.publish(
            aio_pika.Message(
                body=json.dumps(payload).encode(),
                content_type="application/json",
                delivery_mode=aio_pika.DeliveryMode.PERSISTENT,
                headers=headers or {},
            ),
            routing_key=routing_key,
        )

    async def on_message(self, message: AbstractIncomingMessage) -> None:
        """Ack only after success (NFR-15); retry with backoff, then dead-letter (FR-16)."""
        event = json.loads(message.body)
        correlation_id.set(event.get("correlationId") or "-")
        document_id = event["documentId"]
        retries = int((message.headers or {}).get("x-retries", 0))

        async def progress(percent: int, stage: str) -> None:
            await self.publish(
                "document.progress",
                {"documentId": document_id, "percent": percent, "stage": stage, "correlationId": correlation_id.get()},
            )

        try:
            result = await self.ingestor.process(
                uuid.UUID(document_id), uuid.UUID(event["userId"]), event["storageKey"], progress
            )
            await self.publish(
                "document.processed",
                {"documentId": document_id, **result, "correlationId": correlation_id.get()},
            )
            await message.ack()
        except IngestError as exc:
            log.warning("Document %s cannot be processed: %s", document_id, exc)
            await self._fail(message, document_id, str(exc))
        except Exception as exc:
            if retries < self.settings.max_retries:
                delay = 2 ** (retries + 1)
                log.warning("Processing %s failed (%s); retry %d in %ds", document_id, exc, retries + 1, delay)
                await asyncio.sleep(delay)
                await self.publish("document.uploaded", event, headers={"x-retries": retries + 1})
                await message.ack()
            else:
                log.exception("Processing %s failed after %d retries", document_id, retries)
                await self._fail(message, document_id, "Processing failed after several attempts")

    async def _fail(self, message: AbstractIncomingMessage, document_id: str, reason: str) -> None:
        await self.publish(
            "document.failed", {"documentId": document_id, "reason": reason, "correlationId": correlation_id.get()}
        )
        await message.reject(requeue=False)  # goes to the dead-letter queue
