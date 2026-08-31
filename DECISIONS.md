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

---

## Flyway owns the schema; entities describe it

When an entity and the schema disagree, the entity is wrong — unless there is an independent reason to think the schema is.

`account.currency` is `CHAR(3)`. Mapping it as a plain `String` made Hibernate assume `varchar(255)` and `validate` refused to start: found `bpchar (Types#CHAR)`, expected `varchar(255) (Types#VARCHAR)`. The fix was `@JdbcTypeCode(SqlTypes.CHAR)` on the field, not a migration.

`CHAR(3)` is deliberate. Postgres blank-pads `CHAR(n)`, which is a real trap — a short value returns with trailing spaces and `equals()` starts failing inexplicably. It cannot fire here: ISO 4217 codes are always exactly three characters. Fixed width also documents "this is a code, not free text" in a way `VARCHAR(3)` does not.

---

## Migration files are immutable, including their names

Renaming `V1__Create_account.sql` to lowercase broke startup:

```
Migration description mismatch for migration version 1
-> Applied to database : Create account
-> Resolved locally    : create account
```

`flyway_schema_history` records **version, description, and checksum**. The description is the filename text after the `__`. All three are identity — so a rename is as much a violation of append-only as an edit to the SQL.

Resolved by `docker compose down -v` and replaying all six migrations from empty, which also re-proved the rebuild property. In production the tool is `flyway repair`; dropping the database is not an option there.

This is the answer to "what happens if someone edits an applied migration?" — a hard startup failure, because a migration that ran is a historical fact and a file that no longer matches it means the history table is lying.

---

## `Long` for ids, `long` for amounts

Different questions with different answers.

**Id: `Long`.** JPA uses "is the id null?" to decide new-vs-existing. A primitive reports `0` before insert, which is indistinguishable from a real id of 0, so `save()` takes the merge path: a `SELECT` for a row that isn't there. `Long` gives `null`, which is unambiguous. Same problem in a `HashSet` — every unsaved instance hashes identically from id `0` and two distinct new objects silently deduplicate. Cost accepted: unboxing an unsaved id throws NPE. That is the correct behaviour — loud at the point of the mistake.

**Amount: `long`.** The column is `NOT NULL` with `CHECK (amount <> 0)`. A null amount is never valid, and unboxing null inside arithmetic throws far from the cause. The primitive makes the invalid state unrepresentable.

---

## `GenerationType.IDENTITY`, and what it costs

Matches `GENERATED ALWAYS AS IDENTITY`.

The persistence context is keyed by `(class, id)`. Under IDENTITY the id only exists once the row is written, so `persist()` cannot be deferred — it fires `INSERT` immediately and reads the id back via JDBC generated keys.

Consequence: **insert batching is impossible.** `hibernate.jdbc.batch_size` does nothing for these entities. A sequence strategy would fetch ids up front (`allocationSize` blocks amortise the `nextval` calls), register the objects, and flush N inserts as one round trip.

Irrelevant at this volume. Kept because it matches the DDL and the DDL is right.

---

## The database owns `created_at`

`@Column(insertable = false, updatable = false)` — Hibernate omits the column from every INSERT, so `DEFAULT now()` fires.

Rejected `@PrePersist` setting `Instant.now()`:

- **Clock authority.** Postgres has one clock. Multiple app instances have several, and they drift. Rows written seconds apart can land out of order. Wrong property to give up on an audit trail.
- **Two mechanisms, one column.** The V5 `PLATFORM` seed row came from the database default. So would anything written via psql or by a future job. `@PrePersist` covers only rows going through this Hibernate session.
- **Transaction semantics.** Postgres `now()` is *transaction start time* — identical for every row in the transaction. `Instant.now()` advances between calls. When one entry plus three postings are written as one act, identical timestamps are the more truthful record.

Cost accepted: after `persist()` the field is still `null` in memory. Needs `refresh()` or a re-read. `@Generated(event = INSERT)` would fix that with an extra `SELECT` per insert; not worth it yet.

---

## `@Enumerated(EnumType.STRING)`, never the default

The JPA default is `ORDINAL` — it stores the position in the declaration list. Inserting a constant in the middle of the enum silently reinterprets every existing row, and an `int` wouldn't match a `VARCHAR` column anyway.

`STRING` stores the constant name, which is what the `CHECK` constraints already expect. Enum constants must match the constraint values exactly.

---

## Immutability is structural, not documented

Expressed in annotations rather than left to convention. On `Posting`, everything is `updatable = false` with no setters — it is the financial record itself. On `JournalEntry`, `occurred_at` and `external_reference` are `updatable = false`; `external_reference` especially, since it is the basis of idempotency and a mutable one would let the guarantee be revoked.

`description` is the exception: amendable, because it carries no financial meaning and a typo shouldn't require a reversing entry. Financial fields immutable, descriptive metadata amendable.

**Owed:** an audit log on description changes. Without one, a typo fix is indistinguishable from someone quietly rewriting history — which is the entire reason immutability matters. Documented here rather than discovered later.

---

## `JournalEntry` owns its postings; `Merchant` does not own its accounts

Same shape of question, opposite answers.

**No `List<Account>` on `Merchant`.** A mapped collection is all-or-nothing — no `WHERE`, no `LIMIT`, no paging. A merchant with 10,000 accounts loads all of them because something called `.size()`, or a `toString()`, or a serializer walked the graph. A repository method (`findByMerchantId`) gives the same data with the query explicit at the call site.

Note the persistence context is not a cache that survives the user's session — it lives for one transaction. There is no "load it once and keep it around" benefit to weigh against the cost. Cross-request caching is a second-level cache or Redis, configured deliberately.

**`List<Posting>` on `JournalEntry`.** Three reasons, none of which is "it's queried often":

1. **Bounded.** Two postings, occasionally a handful. Never thousands.
2. **The invariant is defined over the collection.** Sum-to-zero is a property of a set of rows at commit time. Validating it requires holding every posting simultaneously.
3. **They are written as one act.** Nothing ever inserts a lone posting.

That is an **aggregate**: a root that owns its parts, where the whole is the unit of consistency. `merchant → account` is a lookup between independent things. Different relationship, different mapping.

```java
@OneToMany(mappedBy = "journalEntry", cascade = CascadeType.PERSIST)
private List<Posting> postings = new ArrayList<>();
```

`mappedBy` names the owning field on `Posting` and marks this side a mirror — without it JPA assumes two independent relationships and reaches for a join table. `cascade = PERSIST` makes `persist(entry)` write the postings too.

Not `final`: Hibernate replaces the field with its own `PersistentBag` on load. The getter returns `Collections.unmodifiableList` so callers can read but not bypass `addPosting`.

**Known gap:** `cascade` only reaches postings *in the collection*. `new Posting(entry, ...)` without `addPosting(p)` is valid Java, produces no error, and the row is never written. Guarded by having exactly one code path that builds postings — a factory method on `JournalEntry`, in milestone 6.

---

## `@ManyToOne` is `LAZY` everywhere

The JPA default is `EAGER`, which fetches the associated row on every load. Load 500 accounts, get 500 merchant fetches.

`LAZY` returns a proxy — a runtime-generated subclass that fetches on first method call. Cost: `LazyInitializationException` when the proxy is touched after the persistence context closed. That's the classic JPA failure and worth meeting rather than avoiding by making everything eager.

The proxy is also why entity no-arg constructors are `protected`, not `private`: the proxy is a subclass and every constructor must call one on its superclass. `private` isn't reachable. `public` would invite application code to build a half-empty entity the database then rejects.

---

## Narrow repository interfaces for the ledger tables

`Account` and `Merchant` extend `JpaRepository`. `JournalEntry` and `Posting` extend the bare `Repository` marker and declare only `save` and `findById`.

`JpaRepository` brings `delete`, `deleteById`, and `deleteAll` along with everything else. On the ledger tables that contradicts immutability — corrections are reversing entries, never deletions — and leaves the destructive methods one autocomplete away.

The marker does not make deletion impossible. Declaring `void deleteById(Long id)` would work: Spring Data matches methods by name and signature, not by which interface they came from, and routes anything it recognises to `SimpleJpaRepository`. What the marker does is make deletion **an explicit act someone has to write into the interface** rather than a default.

Cost: every method needed later must be declared by hand — `count()`, `findAll()`, and so on, one line each.

---

## The balance is a `@Query` returning `long`, with `COALESCE`

```java
@Query("select coalesce(sum(p.amount), 0) from Posting p where p.account.id = :accountId")
long balanceByAccountId(@Param("accountId") Long accountId);
```

**Why not a derived method name.** Spring Data builds a query by parsing the method *name* against a grammar covering filtering (`findBy`), sorting (`OrderBy`), limiting (`findFirst10`), existence (`existsBy`), and row counting (`countBy`). Column aggregation is not in that grammar — there is no name that means "sum the amount column." This is a vocabulary limit, not a performance judgement. `@Query` is the only option, not the faster one.

**Why not fetch and sum in Java.** `findByAccountId` plus a loop moves every posting row across the network, materialises each as an entity the persistence context then pins in memory, and discards everything but one field. `SUM` in SQL moves one row. Push the computation to where the data already lives.

**Why `long` and not `Optional<Long>`.** `SUM` over zero rows returns `NULL` per the SQL standard — "the sum of nothing" is not "zero." Unboxing that into a primitive throws NPE. `COALESCE` makes Postgres substitute 0 before the value leaves the database, so the primitive is safe. An account with no postings genuinely has a zero balance; that is a real answer, not missing data, and making callers unwrap an `Optional` to arrive at 0 is ceremony around a case that is not ambiguous.

Rejected: `Long` (every caller must remember a null check) and `Optional<Long>` (absence in the type, but unwrapped to 0 at every call site anyway).

Cost accepted: 0 is also returned for an account id that does not exist, so "no such account" and "empty account" are indistinguishable. The caller validates the id separately — a bad id is a 404, not a balance.

**JPQL, not native SQL.** Identical generated SQL and identical speed. What JPQL buys is that it names entities and fields (`Posting`, `p.amount`) rather than tables and columns, so it is validated against the entity model at startup and survives a column rename that the entity already maps. `@Query` strings are parsed when the proxies are built — a typo is a boot failure, not a runtime one.

---

## `mappedBy` names the inverse side, and the inverse side writes nothing

Seeding a posting that was added via `addPosting` but constructed with `null` as its entry produced:

```
ERROR: null value in column "journal_entry_id" of relation "posting" violates not-null constraint
```

Not an extra UPDATE. The database link is one thing — the `journal_entry_id` column on `posting`. Java represents it twice: `Posting.journalEntry` and `JournalEntry.postings`. `mappedBy = "journalEntry"` declares which of the two is in charge, and it is not the collection.

So the collection is a mirror. Hibernate reads it to decide **which objects to cascade**, never to decide **what value goes in the FK column**. That value comes from `Posting.journalEntry` and from nowhere else.

Adding to the collection and setting the parent are therefore two separate acts, and only the second one writes anything.

This widens the gap already recorded under *`JournalEntry` owns its postings*: it is not only that a posting never added is never written, it is that a posting added but not linked is written **wrong**. Same fix — one factory method on `JournalEntry` that constructs the posting and adds it, so the two cannot drift apart. Milestone 6.

---

## Repository implementations are proxies over `SimpleJpaRepository`

The interfaces have no bodies. At startup Spring Data scans for interfaces extending `Repository`, and for each one generates a proxy implementing it. Each call is routed to one of three places: `SimpleJpaRepository` (an ordinary class holding an `EntityManager` — it appears in stack traces), a query derived from the method name, or an `@Query`. A method matching none of the three fails the context boot.

Two consequences worth stating out loud:

**Repository mistakes are startup failures.** A typo in a derived name or in JPQL fails the boot, not the first request in production.

**`save()` is not `INSERT`.** `SimpleJpaRepository.save()` asks whether the entity is new — by default, whether the id is null — and calls `persist()` or `merge()` accordingly. This is the mechanism behind the `Long`-not-`long` id decision above.

`SimpleJpaRepository` is annotated `@Transactional(readOnly = true)` at class level, so a bare repository call outside any transaction opens and commits its own. Confirmed the hard way during seeding: with six `save()` calls and nothing wrapping them, the entry insert failed and the merchant and accounts stayed committed. That is the argument for milestone 6 in one observation.

---

## What `ddl-auto=validate` does not check

It caught a real type mismatch (`bpchar` vs `varchar`), which is the case for keeping it. But it compares **tables, columns, and JDBC type codes** only.

It does **not** verify: `NOT NULL`, defaults, `CHECK` constraints, `UNIQUE`, or FK actions. Every `nullable = false` in the entities is documentation Hibernate never confirmed — an entity can validate cleanly and still be wrong, failing at insert time instead of startup.

`@Column(unique = true)` is the sharpest case: it is DDL-generation metadata, inert under `validate`. Hibernate does not pre-check uniqueness. It sends the INSERT, Postgres rejects it against `journal_entry_external_reference_key`, and a `ConstraintViolationException` surfaces at **flush** — possibly well after the line that caused it.

Milestone 8 depends on exactly this. "Same `external_reference` twice → one entry" can be built by catching the violation, or by `SELECT`-then-insert. The second has a race between the check and the write; only the database constraint actually closes it.
