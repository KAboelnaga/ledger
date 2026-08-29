# PROGRESS

**Project:** Merchant Payments & Ledger Back-Office
**Purpose:** learn Java/Spring Boot; portfolio centrepiece for backend/fintech roles
**Repo:** github.com/KAboelnaga/ledger
**Stack:** Java, Spring Boot, PostgreSQL 5433 (Docker Compose), Flyway, Maven

Read `DECISIONS.md` first — it carries the reasoning. `SCHEMA.md` has current table state.

---

## Working method

One milestone per chat. Claude explains the concept and the next step; Kareem writes the code by hand and sends it for review. **No large generated code blocks.** Questions both directions before moving on. Each milestone ends with interview-form questions and an update to these three files.

Open a new chat with: *"Milestone N: <name>. See PROGRESS.md for method."*

---

## Milestones

| # | Milestone | Done when | Status |
|---|---|---|---|
| 1 | Environment | Postgres in Docker, Flyway wired, app starts clean, repo pushed | done |
| 2 | Schema V1–V4 | `account`, `posting`, `journal_entry` + FKs, rebuilds from empty DB | done |
| 3 | Merchant + tenancy | `merchant` table, `account.merchant_id` FK, V5–V6 applied | done |
| 4 | JPA entities | `Merchant`, `Account`, `JournalEntry`, `Posting` map cleanly; app starts with `ddl-auto=validate` | **next** |
| 5 | Repositories + balance query | `SUM(amount)` balance returns correct value against seeded data | |
| 6 | Service layer | One `@Transactional` method writes a balanced entry; unbalanced input is rejected | |
| 7 | REST API + DTOs | `POST /journal-entries`, `GET /accounts/{id}/balance`; entities not exposed | |
| 8 | Idempotency | Same `external_reference` twice → one entry, second returns the first | |
| 9 | Reconciliation | Given a provider statement, report matched / missing / extra | |
| 10 | Testing | Unit tests on the invariant + Testcontainers integration test on the full flow | |
| 11 | Polish | README with architecture + decisions, structured logging, deployed demo | |

**If you have to stop early, stop after 10, not 9.** A tested ledger with fewer features beats an untested complete one — in interviews and in reality.

---

## Migrations applied

- `V1__Create_account.sql`
- `V2__create_posting.sql` — `CHECK (amount <> 0)`, index on `account_id`, FK to `account`
- `V3__create_journal_entry.sql` — two timestamps, unique `external_reference`, index on `occurred_at`
- `V4__add_posting_journal_entry_fk.sql` — FK, `ON DELETE RESTRICT`
- `V5__add_merchant_and_account_merchant_fk.sql` — `merchant` table, `PLATFORM` seed row, `account.merchant_id` FK + index
- `V6__alter_merchant_status_valid_constraint.sql` — drop and recreate the check to add `PENDING_VERIFICATION`

Verified: full schema rebuilds from empty database via `docker compose down -v && docker compose up -d` + app start.

---

## Next: milestone 4 — JPA entities

Harder than SQL, and differently hard: annotations generate behaviour you can't see, lazy loading throws at unexpected moments, the object graph pretends the database isn't there. Knowing exactly what the tables look like is an advantage most people learning JPA don't have.

Questions to hit:
- `long` vs `Long` for the id field, and why Hibernate cares
- Relationship direction — does `Posting` reference `JournalEntry`, both ways, or neither
- `FetchType.LAZY` vs `EAGER`, and where `LazyInitializationException` comes from
- Why entities shouldn't be exposed over the API (DTOs come in milestone 7)

Debugging aid: `spring.jpa.show-sql=true` prints the SQL Hibernate actually generates. The gap between what you expected and what it sent is where the learning is.

---

## Cleanup owed

- `posting.journal_entry_id` has no index — hot path, needs one
- `posting_account_fk` needs explicit `ON DELETE RESTRICT`
- Composite FK on `(account_id, currency)` so the DB enforces posting currency matches account currency (needs `UNIQUE (id, currency)` on `account` first)
- Index naming convention is inconsistent — settle it before adding more
- `ledger_pass_123` is plaintext in `application.properties` — move to env var / `.env`
- Merchant status transitions unenforced — deferred deliberately, not forgotten
