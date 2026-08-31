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
| 4 | JPA entities | `Merchant`, `Account`, `JournalEntry`, `Posting` map cleanly; app starts with `ddl-auto=validate` | done |
| 5 | Repositories + balance query | `SUM(amount)` balance returns correct value against seeded data | done |
| 6 | Service layer | One `@Transactional` method writes a balanced entry; unbalanced input is rejected | **next** |
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

## Entities

`com.kareem.ledger.domain` — `Merchant`, `MerchantStatus`, `Account`, `AccountType`, `JournalEntry`, `Posting`.

App starts clean under `ddl-auto=validate` against all six migrations. Reasoning for every mapping choice is in `DECISIONS.md`; the short version:

| | |
|---|---|
| ids | `Long` + `GenerationType.IDENTITY` |
| enums | `@Enumerated(EnumType.STRING)` |
| `TIMESTAMPTZ` | `Instant` |
| `created_at` | `insertable = false, updatable = false` — database default owns it |
| `CHAR(3)` | `@JdbcTypeCode(SqlTypes.CHAR)` — plain `String` fails validation |
| relationships | `@ManyToOne(fetch = LAZY)` on `Account.merchant`, `Posting.journalEntry`, `Posting.account` |
| collections | `JournalEntry.postings` only. `Merchant` has no `List<Account>`. |
| constructors | `protected` no-arg for Hibernate; public one takes only caller-owned fields |

Answered from the milestone 4 question list:

- **`long` vs `Long`** — `Long` for ids (null distinguishes unsaved), `long` for `amount` (never legitimately absent)
- **Relationship direction** — `Posting → JournalEntry` owns the FK; the inverse collection exists because entry + postings are one aggregate. `Merchant → Account` is one-directional for the opposite reason.
- **`LAZY` vs `EAGER`** — LAZY everywhere; `LazyInitializationException` not yet encountered, expected once repositories exist and proxies outlive the context

Every mapping above has now been exercised by a real INSERT — see milestone 5.

---

## Milestone 5 — repositories + balance query (done)

`com.kareem.ledger.repository`, four interfaces:

| Repository | Extends | Methods |
|---|---|---|
| `AccountRepository` | `JpaRepository<Account, Long>` | inherited |
| `MerchantRepository` | `JpaRepository<Merchant, Long>` | inherited |
| `JournalEntryRepository` | `Repository<JournalEntry, Long>` | `save`, `findById` |
| `PostingRepository` | `Repository<Posting, Long>` | `save`, `findById`, `balanceByAccountId` |

Spring Data reported `Found 4 JPA repository interfaces` — the bare `Repository` marker is picked up by the scanner exactly like `JpaRepository`.

A temporary `SeedRunner` (`CommandLineRunner`) wrote a merchant, two EGP accounts, and one balanced entry: six INSERTs, then the balance query returned 50000. Runner deleted; milestone 6 replaces it with a service method.

Generated SQL for the balance:

```sql
select coalesce(sum(p1_0.amount),0) from posting p1_0 where p1_0.account_id=?
```

No join. `p.account.id` in JPQL resolves to the FK column already on `posting`; `p.account.code` would have forced one.

**Confirmed by running, not by reading:**

- `insertable = false` keeps `created_at` out of every INSERT, so `DEFAULT now()` fires
- `cascade = PERSIST` writes the postings from a single `save(entry)` — `postingRepository.save()` was never called
- `mappedBy` is the inverse side. A posting added to the collection but constructed with a null entry inserts with a null FK and dies on the not-null constraint — **not** an extra UPDATE. The FK value comes only from `Posting.journalEntry`.
- Each `save()` opens and commits its own transaction. When the entry insert failed, the merchant and accounts stayed committed — a half-written ledger. This is what milestone 6 fixes.
- `account.code` and `journal_entry.external_reference` being UNIQUE means the seed cannot run twice without `docker compose down -v`. Correct behaviour, and the mechanism milestone 8 is built on.

Not yet met: `LazyInitializationException`. Nothing has loaded an entity and touched a proxy after the persistence context closed.

---

## Next: milestone 6 — service layer

One `@Transactional` method that builds and writes a whole journal entry, validates sum-to-zero before committing, and rejects unbalanced input.

Questions to hit:

- What `@Transactional` actually does — where the proxy sits, and why calling a `@Transactional` method from inside the same class doesn't work
- A factory method on `JournalEntry` that constructs a posting *and* adds it, closing the two gaps found in milestone 5
- Where the sum-to-zero check lives and what it throws
- Rollback semantics: which exceptions roll back by default, and which silently commit
- The N+1 problem: what it looks like in the SQL log, and why LAZY `@ManyToOne` is where it comes from
- Whether a balance read should return a projection or an entity, and why entities are the wrong answer

## Cleanup owed

- `posting.journal_entry_id` has no index — hot path, needs one
- `posting_account_fk` needs explicit `ON DELETE RESTRICT`
- Composite FK on `(account_id, currency)` so the DB enforces posting currency matches account currency (needs `UNIQUE (id, currency)` on `account` first)
- Index naming convention is inconsistent — settle it before adding more
- `ledger_pass_123` is plaintext in `application.properties` — move to env var / `.env`
- Merchant status transitions unenforced — deferred deliberately, not forgotten
- Audit log on `journal_entry.description` changes — it is the only mutable column; without a log, a typo fix is indistinguishable from rewriting history
- `cascade = PERSIST` only reaches postings added via `addPosting`. A `Posting` constructed and never added is silently never written — and one added but constructed with a null entry is written *wrong*. Close both in milestone 6 with a single factory path.
- `balanceByAccountId` returns 0 for a nonexistent account id, indistinguishable from an account with no postings. The REST layer must 404 on a bad id before asking for a balance.
- `SeedRunner` deleted at the end of milestone 5. If anything like it comes back, it needs `@Profile("seed")` so it can't run in a normal startup.
