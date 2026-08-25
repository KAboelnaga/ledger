CREATE TABLE account(
    id      BIGINT          GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code    VARCHAR(64)     NOT NULL UNIQUE,
    name    VARCHAR(255)    NOT NULL,
    type    VARCHAR(16)     NOT NULL,
    currency    CHAR(3)     NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT account_type_valid
        CHECK (type IN ('ASSET', 'LIABILITY', 'REVENUE', 'EXPENSE'))
);