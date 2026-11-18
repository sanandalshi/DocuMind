from alembic import context
from sqlalchemy import create_engine, pool

config = context.config


def run_migrations_online() -> None:
    engine = create_engine(config.get_main_option("sqlalchemy.url"), poolclass=pool.NullPool)
    with engine.connect() as connection:
        context.configure(connection=connection, version_table="alembic_version_ai")
        with context.begin_transaction():
            context.run_migrations()


run_migrations_online()
