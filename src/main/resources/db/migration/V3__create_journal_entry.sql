CREATE TABLE journal_entry(
    id                  BIGINT              GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    created_at          TIMESTAMPTZ         NOT NULL DEFAULT now(),
    occurred_at         TIMESTAMPTZ         NOT NULL,
    description         VARCHAR(255),
    external_reference  VARCHAR(64)         NOT NULL UNIQUE

);

CREATE INDEX journal_entry_occurred_at_index ON journal_entry (occurred_at);