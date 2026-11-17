CREATE TABLE demo_receiver.receipts (
    delivery_id VARCHAR(128) PRIMARY KEY,
    payload JSONB NOT NULL CHECK (jsonb_typeof(payload) = 'object'),
    receipt_count INTEGER NOT NULL DEFAULT 1 CHECK (receipt_count > 0),
    received_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

CREATE TABLE demo_receiver.business_effects (
    delivery_id VARCHAR(128) PRIMARY KEY REFERENCES demo_receiver.receipts(delivery_id),
    applied_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
