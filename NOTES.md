# NOTES

Interview question bank. **Append only — this one is a history, unlike SCHEMA.md.**

Questions asked during the build, plus ones worth being able to answer even though they didn't come up. Grouped by milestone. Rehearse out loud; the written form is not the form you'll need them in.

Marked **[missed]** where the first answer was wrong or thin — those are the ones to drill.

---

## Java and JVM basics

**What is the JVM?**

Java Virtual Machine. `.java` compiles to bytecode (`.class`), which is not machine code — it's instructions for an imaginary machine. The JVM reads bytecode and executes it on the real hardware underneath.

Two consequences: the same `.class` runs anywhere a JVM exists, and the JVM owns memory. Objects live on the heap; a garbage collector reclaims unreferenced ones. There is no `free()`.

Why it matters here: "loading 2,000,000 rows into the JVM" is a real cost. Those become Java objects in a bounded heap, and while a transaction is open the persistence context holds references to all of them so the collector can't touch any.

**What is an NPE?**

`NullPointerException` — calling a method or reading a field on a null reference.

The trap is unboxing:

```java
Long boxed = null;
long primitive = boxed;   // NPE — Java inserts boxed.longValue()
```

A primitive can't hold null, so Java silently inserts the conversion call, and that call is on null.

**`interface` vs `class`.**

An interface declares method signatures with no bodies — a contract. A class provides implementations. A bodyless method in a class is a compile error (`missing method body, or declare abstract`); in an interface it's normal. A class `implements` an interface; an interface `extends` another interface.

**Constructor injection.**

Fields declared `private final`, assigned in a constructor that takes them as parameters. When a Spring bean has exactly one constructor, Spring reads the parameter types and passes in the matching beans. No `@Autowired` needed. `final` means the dependency can't be swapped after construction.

---

## Milestone 4 — entities

**`long` vs `Long` for a JPA id.**

`Long`. JPA decides new-vs-existing by asking whether the id is null. A primitive reports `0` before insert, indistinguishable from a real id of 0, so `save()` takes the merge path and issues a `SELECT` for a row that doesn't exist. Same problem in a `HashSet` — every unsaved instance hashes identically from `0`, so two distinct new objects deduplicate silently.

Cost: unboxing an unsaved id throws NPE. That's correct — loud at the point of the mistake.

For `amount`, the opposite: `long`. The column is `NOT NULL` with `CHECK (amount <> 0)`, a null amount is never valid, and unboxing null inside arithmetic throws far from the cause. The primitive makes the invalid state unrepresentable.

**`GenerationType.IDENTITY` vs `SEQUENCE`.**

IDENTITY matches `GENERATED ALWAYS AS IDENTITY`. The persistence context is keyed by `(class, id)`, and under IDENTITY the id only exists once the row is written — so `persist()` can't be deferred. It fires the INSERT immediately and reads the id back via JDBC generated keys.

Consequence: **insert batching is impossible.** `hibernate.jdbc.batch_size` does nothing. A sequence strategy would fetch ids up front and flush N inserts as one round trip.

Irrelevant at this volume; kept because it matches the DDL.

**Why `@Enumerated(EnumType.STRING)` and never the default.**

The default is `ORDINAL` — it stores the position in the declaration list. Inserting a constant in the middle of the enum silently reinterprets every existing row, and an `int` wouldn't match a `VARCHAR` column anyway. `STRING` stores the constant name, which is what the `CHECK` constraints expect.

**Why are entity no-arg constructors `protected`?**

`@ManyToOne(fetch = LAZY)` returns a proxy — a runtime-generated *subclass* of the entity. Every constructor must call one on its superclass, and `private` isn't reachable from a subclass. `public` would invite application code to build a half-empty entity the database then rejects.

**Why does the database own `created_at`?**

`@Column(insertable = false, updatable = false)` keeps the column out of every INSERT, so `DEFAULT now()` fires.

Three reasons over `@PrePersist`:
- **Clock authority.** Postgres has one clock; multiple app instances have several and they drift.
- **Two mechanisms, one column.** The V5 seed row came from the database default, as would anything written via psql. `@PrePersist` only covers rows going through this Hibernate session.
- **Transaction semantics.** Postgres `now()` is *transaction start time*, identical for every row in the transaction. `Instant.now()` advances between calls. For one entry plus its postings written as one act, identical timestamps are the more truthful record.

Cost accepted: after `persist()` the field is still null in memory until a `refresh()` or re-read.

**What does `ddl-auto=validate` actually check?**

Tables, columns, and JDBC type codes. **Not** nullability, defaults, `CHECK`, `UNIQUE`, or FK actions. Every `nullable = false` in the entities is documentation Hibernate never confirmed — an entity can validate cleanly and still be wrong, failing at insert time instead of startup.

It caught a real one: `account.currency` is `CHAR(3)`, mapped as a plain `String` Hibernate expected `varchar(255)` and refused to start. Fix was `@JdbcTypeCode(SqlTypes.CHAR)`, not a migration.

---

## Milestone 5 — repositories and the balance query

**Where does the implementation of `AccountRepository` come from, given the interface has no body?** **[missed]**

At startup Spring Data scans for interfaces extending `Repository` and generates a **proxy** implementing each one. Every call is routed to one of three places:

1. `SimpleJpaRepository` — a real, ordinary class holding an `EntityManager`. Everything inherited (`save`, `findById`, `findAll`, `count`, `delete`) lands here. It shows up in stack traces: `SimpleJpaRepository.save(SimpleJpaRepository.java:664)`.
2. A **derived query** — built by parsing the method name at startup.
3. An **`@Query`** — parsed and validated at startup.

A method matching none of the three fails the context boot.

Wrong answer to avoid: "it's inherited from `JpaRepository`." That explains where the *signatures* come from, not the *code*. Interfaces have no code.

**Why are repository mistakes startup failures rather than runtime ones?**

Because the proxies are built when the context boots, and building them means parsing every derived name and every `@Query`. A typo fails the boot, not the first production request.

**Is `save()` an INSERT?**

No. `SimpleJpaRepository.save()` asks whether the entity is new — by default, whether the id is null — and calls `persist()` if so, `merge()` otherwise. This is the mechanism behind choosing `Long` over `long` for ids.

**What do you lose by extending the bare `Repository` marker instead of `JpaRepository`?**

Everything not declared. `JpaRepository` gives `save`, `findById`, `findAll`, `count`, `existsById`, `delete`, `deleteById`, paging and sorting, all inherited. The marker gives nothing.

But it doesn't make anything *impossible*. Spring Data matches by name and signature, not by source interface — so `long count();` or even `void deleteById(Long id);` declared on the narrow interface works and routes to `SimpleJpaRepository`. The marker makes deletion **an explicit act someone has to write down**, not a default.

Cost: every method needed later is one hand-written line.

**Why can't a derived method name express `SUM`, when `findByAccountId` works fine?** **[missed]**

Spring Data builds a query by parsing the method *name* against a grammar, roughly:

```
[find|count|exists|delete] + By + <field> + [And|Or <field>] + [OrderBy <field>]
```

That covers filtering, sorting, limiting (`findFirst10`), existence, and row counting. **Column aggregation is not in the grammar.** There is no name that means "sum the amount column" — the parser has no production rule for it, so `sumAmountByAccountId` fails at startup.

This is a vocabulary limit, not a performance judgement. `@Query` is the only option, not the faster one.

`countBy...` works because a row count takes no column argument to reduce over.

**Wrong answer to avoid:** answering with the performance argument. That's the next question, and they're different.

**Why not `findByAccountId` and sum in a Java loop?**

Different question, performance answer.

With 2,000,000 postings on the account:
- Postgres produces 2,000,000 rows
- all of them cross the network
- Hibernate constructs 2,000,000 `Posting` objects, each with two lazy proxies
- the persistence context holds references to all of them, so none can be garbage collected while the transaction is open
- you add up one field and throw the rest away
- realistically: OutOfMemoryError

`SUM` in SQL: Postgres walks the index on `account_id`, aggregates in its own memory with native 64-bit arithmetic, and **one row** crosses the network. Eight bytes in the JVM.

Principle: push the computation to where the data lives.

**Is JPQL faster than SQL?**

No. It compiles to the same SQL. What JPQL buys is naming entities and fields (`Posting`, `p.amount`) instead of tables and columns, so it's checked against the entity model at startup and survives a column rename the entity already maps. The speed comes from `SUM` running in the database, which would be equally true of a native query.

**Does `p.account.id` in JPQL generate a join?**

No. The FK column is already on `posting`, so Hibernate reads it directly:

```sql
select coalesce(sum(p1_0.amount),0) from posting p1_0 where p1_0.account_id=?
```

`p.account.code` *would* force a join, because `code` only exists on the `account` table.

**`SUM` over zero rows returns null. Name all four ways to handle it.**

1. `long` bare — NPE on unboxing the first time you ask for a new account's balance.
2. `Long` — returns null; every caller must remember the check.
3. `Optional<Long>` — absence is in the type, callers can't forget; but you unwrap to 0 at every call site anyway.
4. `COALESCE(SUM(p.amount), 0)` returning `long` — the database substitutes 0, no null ever reaches Java. **Chosen.**

An account with no postings genuinely has a zero balance — a real answer, not missing data. Cost accepted: 0 is also returned for a nonexistent account id, so the caller must validate the id separately. A bad id is a 404, not a balance.

**What does `COALESCE` do?**

SQL function: returns the first argument that isn't null. `coalesce(null, 0)` → `0`. `coalesce(50000, 0)` → `50000`.

It matters because `SUM` over **zero rows** returns `NULL`, not `0` — SQL standard, and arguably right: "the sum of nothing" and "a sum that came to zero" are different statements.

**A posting was added via `addPosting` but constructed with a null entry. Why a not-null violation rather than an extra UPDATE?** **[missed]**

In the database the link is one thing: the `journal_entry_id` column on `posting`. Java represents it twice — `Posting.journalEntry` and `JournalEntry.postings`. `mappedBy = "journalEntry"` declares which of the two is in charge, and it isn't the collection.

The collection is a mirror. Hibernate reads it to decide **which objects to cascade**, never to decide **what goes in the FK column**. That value comes from `Posting.journalEntry` alone.

So the cascade saved the object, Hibernate looked at the owning field for the FK, found null, and wrote null. Postgres refused.

Rule of thumb: **the side holding the foreign key column owns the relationship; the other side is a mirror, and `mappedBy` marks the mirror.**

Consequence: adding to the collection and setting the parent are two separate acts, and only the second writes anything.

**Why is `cascade = PERSIST` on `JournalEntry.postings` but nothing similar on `Merchant`?**

`journal_entry → posting` is an **aggregate**: bounded (two postings, occasionally a handful), the sum-to-zero invariant is defined over the whole collection, and they're written as one act. Nothing ever inserts a lone posting.

`merchant → account` is a lookup between independent things. A mapped collection there is all-or-nothing — no `WHERE`, no `LIMIT`, no paging — so a merchant with 10,000 accounts loads all of them because something called `.size()`. A repository method (`findByMerchantId`) gives the same data with the query explicit at the call site.

**Six `save()` calls, the last one failed, and the merchant and accounts stayed in the database. Why?**

Nothing wrapped them. `SimpleJpaRepository` is `@Transactional` per method, so each `save()` opened and committed its own transaction. The earlier ones were already durable when the later one blew up.

Fix: `@Transactional` on one service method so all six inserts are one unit — all commit or none do. That's milestone 6.

**After `docker compose down -v`, why does the merchant identity counter restart at 1?** **[missed]**

`-v` deletes the Docker volume, which destroys the entire Postgres data directory — tables, rows, **and the identity sequences that track the next value.** `up -d` starts an empty database, Flyway replays all six migrations, `GENERATED ALWAYS AS IDENTITY` creates a fresh sequence starting at 1, V5's `PLATFORM` insert takes it, the next merchant takes 2.

Without `-v` the volume survives, the sequence survives with it, and Flyway finds all six migrations already applied.

Half-answers to avoid: explaining where the PLATFORM row comes from without explaining the sequence. The question is about the counter.

**Why does the seed fail on a second run without wiping?**

`account.code` and `journal_entry.external_reference` are UNIQUE. The second run tries the same values and Postgres rejects them. Correct behaviour — and the exact mechanism milestone 8's idempotency is built on.

---

## Schema and design (from DECISIONS.md — rehearse these too)

**Why are balances computed, not stored?**

A stored balance is a second source of truth that can drift from the postings that produced it. Computing means the postings are the only truth. Cost: the sum slows as postings accumulate, mitigated by the index on `account_id`. If it ever becomes real, the fix is periodic balance snapshots — sum only the postings after a known point — not a mutable running total.

**Why `BIGINT` minor units and not `DECIMAL` or `FLOAT`?**

`FLOAT` can't represent 0.10 exactly and the error accumulates until balances stop reconciling. Never an option for money.

`DECIMAL` would also be correct. Chose `BIGINT` for native 64-bit arithmetic (much faster to aggregate than software-implemented `NUMERIC`), because it maps to `long` rather than `BigDecimal` (avoiding the `equals()` vs `compareTo()` scale trap), and because it forces one explicit decision about scale.

Trade-off: currency exponents differ (EGP/USD = 2, JPY = 0, KWD = 3), so the display layer must know each currency's exponent.

**Why two timestamps?**

`occurred_at` is when the money moved; `created_at` is when we learned about it. A payment settles 23:47 Tuesday, the webhook arrives 02:15 Wednesday. With one timestamp, Tuesday's closing balance is permanently wrong and monthly reports land revenue in the wrong month. Accounting calls these value date and booking date.

`occurred_at` has no default deliberately — a default would paper over a caller that forgot to supply it, turning a loud failure into a wrong-but-plausible number.

**Why is sum-to-zero not a `CHECK` constraint?**

A `CHECK` is evaluated per-row and can only see the columns of the row being inserted. No subqueries, no aggregates, no sibling rows. It is structurally incapable of expressing "these N rows sum to zero."

Even if it could: inserting the debit leaves the sum at 500, and only the credit brings it to 0. The invariant is violated mid-transaction *by design*.

The invariant is a property of a **set of rows at commit time**, not of a row. Contrast `posting_amount_not_zero`, which *is* a `CHECK` because it needs one column of one row. Same mechanism, opposite outcome — the difference is entirely scope.

**Why `ON DELETE RESTRICT` and not `CASCADE` or the `NO ACTION` default?**

`CASCADE` would silently delete every posting under a journal entry — that's how financial records get lost to a stray `DELETE`. `NO ACTION` can be deferred to end of transaction, so a delete-both-then-commit sequence could succeed; `RESTRICT` refuses immediately and can never be deferred.

Layering gives conditional behaviour no single constraint expresses: `merchant ←RESTRICT— account ←— posting` means deleting a merchant requires deleting their accounts first, which is refused if those accounts have postings. The sequence succeeds exactly when the merchant has no financial history.

**Why index `account_id` and not `currency`?**

Selectivity, not just query frequency. `account_id`: millions of postings across thousands of accounts, so filtering narrows to a small slice. `currency`: three or four distinct values, so `WHERE currency = 'EGP'` might match 80% of the table — the index yields row locations, then Postgres does millions of random heap fetches, **slower than a sequential scan.** The planner ignores it and you've paid the write cost on every insert forever.

Rule: index columns that are both frequently filtered *and* selective.

`UNIQUE` creates an index automatically (enforcing uniqueness requires one). Foreign keys get nothing automatically — a FK is checked against the *target* table's PK index, so the referencing column is unindexed until you say otherwise.

**What happens if someone edits an applied migration?**

Hard startup failure. `flyway_schema_history` records version, description, and checksum, and all three are identity. Renaming `V1__Create_account.sql` to lowercase broke the build:

```
Migration description mismatch for migration version 1
-> Applied to database : Create account
-> Resolved locally    : create account
```

A migration that ran is a historical fact; a file that no longer matches it means the history table is lying. In production the tool is `flyway repair`; dropping the database is not an option there.

**Why is `merchant.status` `NOT NULL` with no default?**

`DEFAULT 'ACTIVE'` is convenient now and wrong later: the moment a real KYC step exists, a caller that forgets to set status silently marks an unverified merchant as active. That's a compliance failure, not a bug.

The test is who owns the value. `created_at DEFAULT now()` is safe because the database is the authority on when a row was written. Status is decided by business logic, so a database default would be the database overriding a decision that isn't its to make.

---

## Not yet answered — open for later milestones

- What does `@Transactional` actually do, and why does calling a `@Transactional` method from inside the same class not work?
- Which exceptions roll back by default and which silently commit?
- What does `LazyInitializationException` look like, and what are the three ways to avoid it?
- What does the N+1 problem look like in the SQL log, and why is LAZY `@ManyToOne` where it comes from?
- Why should a balance endpoint return a projection or DTO rather than an entity?
- Idempotency: catching the constraint violation vs `SELECT`-then-insert, and why only the former closes the race.
