CREATE TABLE sync_runs (
    id               BIGSERIAL                NOT NULL,
    source_id        VARCHAR(50)              NOT NULL,
    sync_type        VARCHAR(10)              NOT NULL,
    status           VARCHAR(10)              NOT NULL,
    started_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    finished_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    listings_fetched INTEGER                  NOT NULL DEFAULT 0,
    listings_added   INTEGER                  NOT NULL DEFAULT 0,
    listings_updated INTEGER                  NOT NULL DEFAULT 0,
    listings_errors  INTEGER                  NOT NULL DEFAULT 0,
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_sync_runs PRIMARY KEY (id)
);
