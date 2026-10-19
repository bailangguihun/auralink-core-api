# Daily Gallery ordering

The PostgreSQL Gallery supports explicit `sort=daily`. Existing `source`, `title`,
`author`, and `dynasty` sorts, the default API sort (`source`), daily recommendation,
favorites, and Assistant artwork queries retain their existing meaning.

## Request and response

Start a daily sequence with:

```text
GET /api/v1/paintings?sort=daily&direction=asc&page=0&size=24
```

The ordinary page response gains `orderDate`, an ISO `YYYY-MM-DD` string only for
daily sorting. Core computes the date using `auralink.paintings.daily-zone`
(default `Asia/Shanghai`), once per request. Clients must send the returned date
with subsequent pages:

```text
GET /api/v1/paintings?sort=daily&direction=asc&page=1&size=24&orderDate=2026-10-19
```

If `orderDate` is absent or differs from Core's current date, Core returns page 0
of today's sequence with today's `orderDate`. This includes valid future dates;
clients cannot select another day's sequence. Invalid date syntax/calendar dates
return HTTP 400 `INVALID_PAINTING_QUERY`. Clients render the response's actual
`page`, and use its `orderDate` for their next page. A midnight reset must replace
the displayed page rather than append to an old day's results. No extra fetch is
needed merely to copy response metadata into UI state.

Other sorts ignore `orderDate` and omit it from the response. Existing Java
constructors and service calls are retained as overloads for existing callers.
The legacy JPA/SQLite path retains its old sorts but explicitly rejects `daily`
with HTTP 400 `INVALID_SORT`: it has no PostgreSQL MD5 function. It does not
silently substitute source order or download every row to shuffle in Java.

## Query behavior and boundaries

PostgreSQL orders the complete matching, currently eligible result set by
`md5(date || ':' || public_id)` and then the stable public ID, before `LIMIT` and
`OFFSET`. `direction=desc` reverses hash order; the ID remains the collision
tie-breaker. MD5 here is only a deterministic distribution function, not a
security primitive. The date is bound as a query parameter. Existing current
revision joins, filters, visibility eligibility, and total counts are unchanged.
There is no schema migration and no data or media rewrite.

With unchanged catalog membership, repeat requests on the same date have stable
page boundaries, and the next date produces another deterministic ordering.
This is not a database snapshot: concurrent import, withdrawal, or revision
publication can still change membership between requests. As with the existing
offset pagination, strict snapshot consistency across such writes is not
promised. Random ordering also does not guarantee adjacent authors differ.

## Validation and release

`GalleryDailyOrderTest` covers timezone boundaries, anchored pagination, missing
and stale dates, validation, HTTP binding, backward-compatible wire shape/JPA
sorting, and the unchanged daily recommendation query path.
`GalleryDailyOrderPostgresTest` uses disposable schemas in the guarded loopback
test database to check whole-set ordering, page-size independence, stable/next-day
order, filters, canonical current membership, and old source ordering.

```bash
mvn -B -ntp -Dtest=GalleryDailyOrderTest,GalleryDailyOrderPostgresTest,R25ProductionContractTest,AssistantArtworkReadPostgresTest test
```

The PostgreSQL tests only run when the existing `ASSISTANT_TEST_JDBC_URL`,
`ASSISTANT_TEST_JDBC_USER`, and `ASSISTANT_TEST_JDBC_PASSWORD` fixture environment
is supplied. Its URL must point to
`jdbc:postgresql://127.0.0.1:<port>/assistant_foundation_test`. A skipped database
test is not a pass. Check a representative isolated catalog's query latency
before release; hash sorting is computed at read time and not claimed to use an
index.

Deploy Core before a Web client that defaults to `daily`; an older Core rejects
the new sort. Use the current documented release/controller process and bind
formal artifacts. This document does not grant production deployment or provider
call permission. No model calls are needed to verify this change.
