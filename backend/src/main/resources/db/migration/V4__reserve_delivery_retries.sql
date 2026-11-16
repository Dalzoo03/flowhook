ALTER TABLE public.deliveries
    ADD COLUMN attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    ADD COLUMN next_attempt_at TIMESTAMPTZ;

ALTER TABLE public.delivery_attempts ADD COLUMN attempt_no INTEGER;

UPDATE public.delivery_attempts a
SET attempt_no = numbered.number
FROM (
    SELECT id, row_number() OVER (PARTITION BY delivery_id ORDER BY id)::integer AS number
    FROM public.delivery_attempts
) numbered
WHERE a.id = numbered.id;

UPDATE public.deliveries d
SET attempt_count = counts.number
FROM (
    SELECT delivery_id, count(*)::integer AS number
    FROM public.delivery_attempts GROUP BY delivery_id
) counts
WHERE d.id = counts.delivery_id;

ALTER TABLE public.delivery_attempts
    ALTER COLUMN attempt_no SET NOT NULL,
    ADD CONSTRAINT delivery_attempts_positive_number CHECK (attempt_no > 0),
    DROP CONSTRAINT delivery_attempts_delivery_id_key,
    ADD CONSTRAINT delivery_attempts_delivery_number_unique UNIQUE (delivery_id, attempt_no);

CREATE INDEX idx_deliveries_retry_due ON public.deliveries (next_attempt_at, id)
    WHERE status = 'RETRY_WAIT';
