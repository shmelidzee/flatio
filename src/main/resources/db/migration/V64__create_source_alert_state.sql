CREATE TABLE source_alert_state (
    id                 BIGSERIAL                NOT NULL,
    source_id          VARCHAR(50)              NOT NULL,
    alert_type         VARCHAR(30)              NOT NULL,
    active             BOOLEAN                  NOT NULL DEFAULT TRUE,
    first_triggered_at TIMESTAMP WITH TIME ZONE NOT NULL,
    last_notified_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at         TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_source_alert_state PRIMARY KEY (id),
    CONSTRAINT uq_source_alert_state_source_type UNIQUE (source_id, alert_type)
);
