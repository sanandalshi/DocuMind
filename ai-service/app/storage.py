import boto3
from botocore.config import Config

from .config import Settings


class Storage:
    """S3-compatible object storage (SeaweedFS, MinIO or AWS S3)."""

    def __init__(self, settings: Settings):
        self.bucket = settings.s3_bucket
        self.client = boto3.client(
            "s3",
            endpoint_url=settings.s3_endpoint,
            aws_access_key_id=settings.s3_access_key,
            aws_secret_access_key=settings.s3_secret_key,
            region_name=settings.s3_region,
            config=Config(s3={"addressing_style": "path"}, retries={"max_attempts": 3}),
        )

    def download(self, key: str) -> bytes:
        return self.client.get_object(Bucket=self.bucket, Key=key)["Body"].read()
