CREATE TABLE merchant(
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT merchant_status_valid
        CHECK (status IN ('ACTIVE', 'SUSPENDED', 'BLOCKED'))
);

INSERT INTO merchant (name, status) VALUES ('PLATFORM', 'ACTIVE');

ALTER TABLE account
    ADD COLUMN merchant_id BIGINT NOT NULL,
    ADD CONSTRAINT account_merchant_fk
        FOREIGN KEY (merchant_id) REFERENCES merchant (id)
        ON DELETE RESTRICT;

CREATE INDEX account_merchant_id_index ON account (merchant_id);
