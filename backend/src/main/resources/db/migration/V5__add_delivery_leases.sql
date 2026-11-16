ALTER TABLE public.deliveries
    ADD COLUMN lease_token UUID,
    ADD COLUMN lease_expires_at TIMESTAMPTZ;

ALTER TABLE public.delivery_attempts ADD COLUMN recovery_reason TEXT
    CHECK (recovery_reason IS NULL OR recovery_reason = 'LEASE_EXPIRED');

-- 기존 PROCESSING은 전환 직후 바로 재전송하지 않고 처리 기한을 한 번 부여한다.
UPDATE public.deliveries
SET lease_token = gen_random_uuid(), lease_expires_at = clock_timestamp() + interval '30 seconds'
WHERE status = 'PROCESSING';

CREATE INDEX idx_deliveries_expired_lease ON public.deliveries (lease_expires_at, id)
    WHERE status = 'PROCESSING';
