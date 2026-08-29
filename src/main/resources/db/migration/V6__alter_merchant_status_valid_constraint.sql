ALTER TABLE merchant DROP CONSTRAINT merchant_status_valid;

ALTER TABLE merchant
    ADD CONSTRAINT merchant_status_valid
    CHECK (status IN ('ACTIVE', 'PENDING_VERIFICATION', 'SUSPENDED', 'BLOCKED'));