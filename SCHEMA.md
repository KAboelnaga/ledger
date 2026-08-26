# SCHEMA

Current database state. **Overwrite this file when the schema changes — it is not a history.**

Migrations applied: V1–V4. Managed by Flyway, `src/main/resources/db/migration/`.

---

## account

The chart of accounts. One row per account that postings can be made against.

| Column | Type | Null | Default | Notes |
|---|---|---|---|---|
| `id` | `BIGINT` | no | identity | PK |
| `code` | `VARCHAR(64)` | no | — | UNIQUE. Stable identifier, e.g. `MERCHANT_1_PAYABLE`. |
| `name` | `VARCHAR(255)` | no | — | Human-readable label. |
| `type` | `VARCHAR(16)` | no | — | `ASSET`, `LIABILITY`, `REVENUE`, `EXPENSE`. Determines sign convention. |
| `currency` | `CHAR(3)` | no | — | ISO 4217. An account holds exactly one currency. |
| `created_at` | `TIMESTAMPTZ` | no | `now()` | |

**Indexes**

- `account_pkey` — PK, automatic
- `account_code_key` — UNIQUE constraint, automatic

**Constraints**

- `account_type_valid` — `CHECK (type IN ('ASSET', 'LIABILITY', 'REVENUE', 'EXPENSE'))`

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

## Relationships

```
account (1) ──────< (N) posting (N) >────── (1) journal_entry
```

A journal entry has two or more postings. Each posting belongs to exactly one account and one journal entry.

---

## Known inconsistencies

- `posting_account_fk` has no explicit `ON DELETE`. Should be `RESTRICT` for consistency with the other FK. Fix in a future migration.
- Index naming is inconsistent: `posting_account_index` vs `journal_entry_occurred_at_index` vs Postgres-generated `_pkey` / `_key`. Settle a convention before adding more.
- `currency` exists on both `account` and `posting`, with nothing enforcing that a posting's currency matches its account's. Only the service layer can catch a mismatch. Candidate for a composite FK or a trigger later.

---

## Open design question

`account` has no owner column — no `merchant_id` or `tenant_id`. Multi-tenancy was in the original project scope. Decide whether tenancy is modelled by naming convention (`MERCHANT_1_PAYABLE`) or by a real foreign key, and record it in `DECISIONS.md` before the schema gets harder to change.
