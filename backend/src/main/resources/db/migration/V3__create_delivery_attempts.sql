CREATE TABLE public.delivery_attempts (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    delivery_id BIGINT NOT NULL UNIQUE REFERENCES public.deliveries (id),
    started_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    finished_at TIMESTAMPTZ,
    outcome TEXT NOT NULL DEFAULT 'UNKNOWN'
        CHECK (outcome IN ('UNKNOWN', 'SUCCESS', 'HTTP_ERROR', 'TIMEOUT', 'NETWORK_ERROR', 'URL_BLOCKED')),
    http_status INTEGER CHECK (http_status BETWEEN 100 AND 599),
    duration_ms BIGINT CHECK (duration_ms >= 0),
    response_body BYTEA CHECK (octet_length(response_body) <= 4096),
    response_truncated BOOLEAN NOT NULL DEFAULT false
);
