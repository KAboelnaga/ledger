CREATE TABLE posting(
    id                     BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    journal_entry_id       BIGINT       NOT NULL,
    account_id             BIGINT       NOT NULL,
    amount                 BIGINT       NOT NULL,
    currency               CHAR(3)      NOT NULL,
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT posting_account_fk
        FOREIGN KEY (account_id) REFERENCES account (id),

    CONSTRAINT posting_amount_not_zero
        CHECK (amount <> 0)

);

CREATE INDEX posting_account_index on posting (account_id);