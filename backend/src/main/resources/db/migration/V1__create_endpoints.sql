CREATE TABLE public.endpoints (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    project_id BIGINT NOT NULL,
    name VARCHAR(80) NOT NULL,
    url TEXT NOT NULL,
    event_type TEXT NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    CONSTRAINT endpoints_name_not_blank CHECK (length(trim(name)) > 0),
    CONSTRAINT endpoints_event_type_not_blank CHECK (length(trim(event_type)) > 0)
);

CREATE INDEX idx_endpoints_project_id ON public.endpoints (project_id);
