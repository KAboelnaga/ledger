# DECISIONS

Why the design is the way it is. **Delete entries that get reversed — don't append "UPDATE: actually...".**

These double as interview answers. Rehearse them out loud; the written form is not the form you'll need them in.

---

## Balances are computed, not stored

`balance = SUM(amount) WHERE account_id = ?`

A stored balance column is a second source of truth that can drift out of sync with the postings that produced it. Computing it means the postings are the only truth.

Cost: the sum gets slower as postings accumulate. Mitigated by the index on `account_id`; if it ever becomes a real problem the fix is periodic balance snapshots (store the balance at a point in time, sum only the postings after it) — not a mutable running total.

---

## Amounts are BIGINT in minor units

Piastres, not pounds. `50000` = 500.00 EGP.

`FLOAT`/`DOUBLE` cannot represent 0.10 exactly; the error accumulates until balances stop reconciling. Never an option for money.

`DECIMAL`/`NUMERIC` would also be correct — it's exact. Chose `BIGINT` because:
- Native 64-bit integer arithmetic, much faster to aggregate than software-implemented `NUMERIC`
- Maps to Java `long` rather than `BigDecimal`, avoiding the `equals()` vs `compareTo()` scale trap and mandatory `RoundingMode` on division
- Forces one explicit decision about scale instead of leaving it implicit

Trade-off accepted: currency exponents differ (EGP/USD = 2, JPY = 0, KWD = 3), so the display layer must know each currency's exponent to format correctly.

---

## Two timestamps: occurred_at and created_at

They are different facts.

- `occurred_at` — when the money moved in the real world
- `created_at` — when we learned about it and wrote the row

A payment settles 23:47 Tuesday; the webhook arrives 02:15 Wednesday. With one timestamp, Tuesday's closing balance is permanently wrong, reconciliation against the provider's Tuesday statement fails, and monthly reports land revenue in the wrong month.

Accounting calls these value date and booking date. The gap between them is itself useful — it's how you detect a provider whose webhooks are lagging.

`occurred_at` is `NOT NULL` with **no default**. A default would silently paper over a caller that forgot to supply it, turning a loud failure into a wrong-but-plausible number.

---

## Sum-to-zero is enforced in the service layer

A `CHECK` constraint is evaluated per-row and can only see the columns of the row being inserted. No subqueries, no aggregates, no sibling rows. It is structurally incapable of expressing "these N rows sum to zero."

Even if it could: inserting the debit leaves the sum at 500, and only the credit brings it to 0. The invariant is violated mid-transaction *by design*. Any per-row check would reject a legitimate entry.

The invariant is a property of a **set of rows at commit time**, not of a row.

Enforcement: one service method builds the entire journal entry, validates the sum in Java, writes everything, commits. Nothing ever inserts a lone posting.

Alternative considered: a deferred constraint trigger firing at `COMMIT`. More airtight — survives someone bypassing the service — but more complexity. Revisit if needed.

Contrast: `posting_amount_not_zero` *is* a `CHECK`, because it only needs one column of one row. Same mechanism, opposite outcome — the difference is entirely scope.

---

## Ledger rows are immutable

Never updated, never deleted. A wrong entry is corrected by writing a **reversing entry** that cancels it, then a correct one. Both stay in the record forever.

This is the point of a ledger. The moment rows can be edited, the history stops being evidence — an auditor can't trust a table someone might have quietly changed.

Consequence: there is no "fill this in later" step anywhere in the system. Every column must be knowable at insert time.

---

## ON DELETE RESTRICT

`CASCADE` would silently delete every posting under a journal entry — that's how financial records get lost to a stray `DELETE`.

`RESTRICT` over the `NO ACTION` default: `NO ACTION` can be deferred to end of transaction, so a delete-both-then-commit sequence could succeed. `RESTRICT` refuses immediately and can never be deferred.

Given immutability, this should never fire in normal operation. It's a backstop against a bug or a careless hand at the psql prompt — the database enforcing what the application promises never to do.

Layering these gives conditional behaviour no single constraint can express. `merchant ←RESTRICT— account ←— posting`: deleting a merchant requires deleting their accounts first, which is refused if those accounts have postings. So the sequence succeeds exactly when the merchant has no financial history, and fails when they do. In practice `status = 'BLOCKED'` is the real delete; hard deletion is for the merchant who signed up and never traded.

---

## Index on account_id, not on currency

Selectivity, not just query frequency.

`account_id`: millions of postings across thousands of accounts. Filtering to one account narrows to a small slice. The index earns its keep.

`currency`: three or four distinct values. `WHERE currency = 'EGP'` might match 80% of the table. The index yields row locations, then Postgres does millions of random heap fetches — **slower than reading the table sequentially**. The planner will ignore it, and you'd have paid the write cost on every insert forever.

Rule: index columns that are both frequently filtered/joined *and* selective. `currency` fails the second test — it'll appear in queries, but always alongside `account_id`, which does the real narrowing.

`external_reference` needs no explicit index: `UNIQUE` creates one automatically, since enforcing uniqueness requires an index. Foreign keys get nothing automatically — a FK enforces existence, checked against the *target* table's PK index, so the referencing column is unindexed until you say otherwise.

---

## Flyway, not ddl-auto

`spring.jpa.hibernate.ddl-auto=validate`. Hibernate checks the entities match the schema; it never changes it.

Migrations are versioned, reviewable, and replayable from an empty database. Flyway checksums every applied file and refuses to start if one changed — so **migrations are append-only**. Schema changes are new files (`ALTER TABLE`), never edits to old ones.

This is why the `posting → journal_entry` FK is V4 rather than part of V2: `journal_entry` didn't exist yet when V2 ran. And why adding `PENDING_VERIFICATION` to a `CHECK` is V6 dropping and recreating the constraint — a `CHECK` cannot be modified in place.

---

## Merchants own many accounts

A merchant needs several accounts, not one: payable (settled balance owed), reserve (funds held against chargebacks), and one set per currency, since `account.currency` is single-valued. One account per merchant can't express any of this.

Modelled as a real `merchant` table with `account.merchant_id` as a foreign key pointing at it. Rejected the naming-convention alternative (`MERCHANT_1_PAYABLE`) — it can't be joined, can't be indexed usefully, and breaks silently on a typo'd code.

---

## account.merchant_id is NOT NULL; the platform is a merchant

The platform's own accounts — fee revenue, platform bank — don't belong to any customer. They belong to a seeded `PLATFORM` merchant, `id = 1`, created in V5.

The alternative was a nullable column where null means platform-owned. Chose `NOT NULL` because every account then has a real owner and no query has to handle a null case.

Cost, accepted: merchant-facing queries must exclude id 1, `status` is meaningless for that row, and the seed row has to live in the migration because the schema is unusable without it.

---

## merchant.status has no default

`NOT NULL`, no default. New merchants are created `PENDING_VERIFICATION`; something must explicitly activate them.

`DEFAULT 'ACTIVE'` would be convenient now and wrong later: the moment a real KYC step exists, a caller that forgets to set status silently marks an unverified merchant as active. That's a compliance failure, not a bug.

The test is who owns the value. `created_at DEFAULT now()` is safe because the database is the authority on when a row was written. Status is decided by business logic — verification, risk checks — so the service layer owns it, and a database default would be the database overriding a decision that isn't its to make.

Transitions are not enforced anywhere yet. Nothing stops `BLOCKED` → `ACTIVE`. Deliberate: the admin workflow is generic CRUD and adds nothing to what this project is demonstrating.
