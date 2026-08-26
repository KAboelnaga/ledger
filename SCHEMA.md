# SCHEMA

Current database state. **Overwrite this file when the schema changes — it is not a history.**

Migrations applied: V1–V4. Managed by Flyway, `src/main/resources/db/migration/`.

---

## account

> **TODO:** fill in from `V1__create_account.sql` — I don't have the file contents.
> Run `\d account` in psql and paste the columns here.

Referenced by `posting.account_id`.

---

## journal_entry

The transaction. Groups postings together. One journal entry = one economic event.

| Column | Type | Null | Default | Notes |
|---|---|---|---|---|
| `id` | `BIGINT` | no | identity | PK |
| `created_at` | `TIMESTAMPTZ` | no | `now()` | When the row was written. Audit trail. Never set by caller. |
| `occurred_at` | `TIMESTAMPTZ` | no | — | When the event happened in the real world. Supplied by caller. All financial queries filter and sort on this. |
| `description` | `VARCHAR(255)` | yes | — | Human-readable. Nullable — `external_reference` carries traceability. |
| `external_reference` | `VARCHAR(64)` | no | — | UNIQUE. Identifier from the source system. Basis of idempotency. |

**Indexes**

- `journal_entry_pkey` — PK, automatic
- `journal_entry_external_reference_key` — UNIQUE constraint, automatic (no separate index needed)
- `journal_entry_occurred_at_index` — explicit, for range queries and `ORDER BY`

---

## posting

One side of a transaction. A debit or a credit against one account.

| Column | Type | Null | Default | Notes |
|---|---|---|---|---|
| `id` | `BIGINT` | no | identity | PK |
| `journal_entry_id` | `BIGINT` | no | — | FK → `journal_entry.id`, `ON DELETE RESTRICT` |
| `account_id` | `BIGINT` | no | — | FK → `account.id` |
| `amount` | `BIGINT` | no | — | Minor units. Signed: negative = credit, positive = debit. |
| `currency` | `CHAR(3)` | no | — | ISO 4217 |
| `created_at` | `TIMESTAMPTZ` | no | `now()` | |

**Indexes**

- `posting_pkey` — PK, automatic
- `posting_account_index` — explicit, on `account_id`. The hot path: balance is `SUM(amount) WHERE account_id = ?`

**Constraints**

- `posting_amount_not_zero` — `CHECK (amount <> 0)`
- `posting_account_fk` — FK to `account`, no explicit `ON DELETE` (defaults to `NO ACTION`)
- `posting_journal_entry_fk` — FK to `journal_entry`, `ON DELETE RESTRICT`

**Not enforced in the database:** postings within a journal entry must sum to zero. A `CHECK` is per-row and cannot see sibling rows. Enforced in the service layer inside a single transaction.

---

## Known inconsistencies

- `posting_account_fk` has no explicit `ON DELETE`. Should be `RESTRICT` for consistency with the other FK. Fix in a future migration.
- Index naming is inconsistent: `posting_account_index` vs `journal_entry_occurred_at_index` vs Postgres-generated `_pkey` / `_key`. Pick a convention before adding more.
