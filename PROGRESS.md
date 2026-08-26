# PROGRESS

**Project:** Merchant Payments & Ledger Back-Office
**Purpose:** learn Java/Spring Boot; portfolio centrepiece for backend/fintech roles
**Repo:** github.com/KAboelnaga/ledger
**Stack:** Java, Spring Boot, PostgreSQL 5433 (Docker Compose), Flyway, Maven

Read `DECISIONS.md` first — it carries the reasoning. `SCHEMA.md` has current table state.

---

## Working method

One milestone per chat. Claude explains the next step and the concepts; Kareem writes the code by hand and sends it for review. No large generated code blocks. Questions both directions before moving on. Milestone ends with interview-form questions and an update to these three files.

---

## Done

**Environment**
- Spring Boot project, Maven, IntelliJ (Flatpak — run `docker compose` from a normal terminal, not IntelliJ's)
- Postgres in Docker Compose, host port 5433
- `application.properties`: `ddl-auto=validate`, Flyway enabled, `flyway.clean-disabled=true`
- Repo public on GitHub

**Schema — V1 to V4, all applied**
- `V1__create_account.sql`
- `V2__create_posting.sql` — with `CHECK (amount <> 0)`, index on `account_id`, FK to `account`
- `V3__create_journal_entry.sql` — two timestamps, unique `external_reference`, index on `occurred_at`
- `V4__add_posting_journal_entry_fk.sql` — FK with `ON DELETE RESTRICT`

Verified: full schema rebuilds from empty database via `docker compose up -d` + app start.

---

## Next

**Milestone: JPA entities** — `Account`, `JournalEntry`, `Posting`.

Expect this to be harder than the SQL was, and differently hard: annotations generate behaviour you can't see, lazy loading throws at unexpected moments, the object graph pretends the database isn't there. Knowing exactly what the tables look like is an advantage most people learning JPA don't have.

Open questions to hit along the way:
- `long` vs `Long` for the id field, and why it matters to Hibernate
- Relationship mapping direction — does `Posting` need a reference to `JournalEntry`, both ways, or neither
- `FetchType.LAZY` vs `EAGER`, and where `LazyInitializationException` comes from
- Whether entities are the right thing to expose over the API (they aren't — DTOs come later)

---

## Backlog

- Repositories + balance query
- Service layer, `@Transactional`, sum-to-zero enforcement
- REST API, DTOs, validation
- Idempotency on `external_reference` — duplicate webhook handling
- Reconciliation against a provider statement
- Testing: unit + integration with Testcontainers
- Observability, README, deployment

---

## Cleanup owed

- `posting_account_fk` needs explicit `ON DELETE RESTRICT` (new migration)
- Index naming convention is inconsistent — settle it before adding more
- `ledger_pass_123` is plaintext in `application.properties` — move to environment variable / `.env`
- `SCHEMA.md` account table section is unfilled
