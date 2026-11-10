CREATE TABLE public.events (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    project_id BIGINT NOT NULL,
    event_id VARCHAR(128) NOT NULL CHECK (length(trim(event_id)) > 0),
    type VARCHAR(128) NOT NULL CHECK (length(trim(type)) > 0),
    data JSONB NOT NULL CHECK (jsonb_typeof(data) = 'object'),
    matched_delivery_count INTEGER NOT NULL DEFAULT 0 CHECK (matched_delivery_count >= 0),
    received_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT events_project_event_id_unique UNIQUE (project_id, event_id)
);

CREATE INDEX idx_events_project_received ON public.events (project_id, received_at, id);

CREATE TABLE public.deliveries (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id BIGINT NOT NULL REFERENCES public.events (id),
    endpoint_id BIGINT NOT NULL REFERENCES public.endpoints (id),
    target_url TEXT NOT NULL,
    payload JSONB NOT NULL CHECK (jsonb_typeof(payload) = 'object'),
    status TEXT NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'PROCESSING', 'RETRY_WAIT', 'SUCCEEDED', 'DEAD')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT deliveries_event_endpoint_unique UNIQUE (event_id, endpoint_id)
);
