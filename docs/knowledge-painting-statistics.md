# Knowledge painting statistics contract (schemaVersion 1)

`GET /api/v1/knowledge/painting-statistics` uses the existing authenticated `/api/v1/**` security default. No query parameters are supported: any parameter returns HTTP 400 `BAD_REQUEST`. When catalog-read is disabled or PostgreSQL fails/times out, return HTTP 503 `CATALOG_READ_UNAVAILABLE` without SQL/connection details. A successful result has `Cache-Control: no-store`. Existing Gallery and media routes are unchanged.

## Population and visibility

`population = {key: "gallery-displayable", label: "Gallery 可展示画作", count: number}`.

The source join and eligibility predicate are shared with `PostgresCatalogReadStore.list`: membership in `catalog_batch_current_revisions`, joined source record and painting, the painting's own `current_batch_id/current_revision` matching that membership, non-null painting ID, `catalog_status=ACTIVE`, and `image_display_status=READY`. This is precisely the current unfiltered Gallery population. Historical revisions and another batch's membership cannot duplicate a stable painting. Missing/ambiguous media, incomplete/inactive paintings and revisions absent from the current-revision selector are excluded. No additional media-status or filesystem rule is invented: READY is the existing Gallery eligibility contract. No hard-coded count, database migration, source mutation or ingestion occurs.

One PostgreSQL statement reads every metric from one MVCC snapshot. The browser never loads painting details to aggregate. Aggregation, deduplication and stable value ranking run in PostgreSQL. The SQL returns every eligible group; the JDBC statement retains a 15-second timeout and fetches up to 100,001 rows to detect overflow beyond a 100,000-row snapshot bound. Overflow or incomplete groups fail with HTTP 503 instead of returning a partial success. The existing overview endpoint then keeps its original presentation limits: 50 labels per ordinary metric, 12 styles and 40 imagery terms (at most 302 items). `truncated` reports omitted overview groups; denominators always describe the complete population. Labels longer than 128 characters are omitted from scalar charts (eligible but unmatched); source values longer than 4096 characters are ineligible. No hidden values can enter labels, word clouds, scores or author counts.

## Complete metric pages

`GET /api/v1/knowledge/painting-statistics/{metric}` uses the same authenticated security policy and `Cache-Control: no-store`. Supported metrics are `geography`, `dynasty`, `category`, `style`, `imagery`, `sentiment` and `sentimentByDynasty`.

| Parameter | Contract |
| --- | --- |
| `page` | Zero-based nonnegative integer; default 0 |
| `size` | 1–200; default 100 |
| `q` | At most 200 characters; trimmed case-insensitive literal substring search over all names, including low-frequency groups |
| `sort` | `value` (default, descending with stable name tie-break), `name`, or `chronological` |
| `revision` | Optional 64-character lowercase SHA-256; identifies public aggregate contents |

The response is `{schemaVersion: 1, metric, items: [{name, value}], page, size, totalItems, totalPages, hasMore, revision}`. `totalItems` counts all matches before pagination. `totalPages = ceil(totalItems / size)`, including 0 for empty matches. An out-of-range page returns an empty `items` list while retaining the requested page. Search treats `%` and `_` literally. Chronological ordering recognizes the existing dynasty names/terminal 朝 or 代 suffix; unfamiliar source values remain unchanged and sort by name afterward. This is presentation ordering, not additional normalization or merging.

One public snapshot per catalog store is cached for 30 seconds, bounded by the row limit above. Concurrent page requests share its aggregate read; filtering and ordering run over that complete snapshot. Failed refreshes are not cached or replaced with expired results. A content-identical refresh preserves its revision. When a requested revision no longer matches, HTTP 409 `CONFLICT` instructs the client to restart from the first page, preventing concatenation of different aggregate revisions. Invalid metrics, unknown parameters, malformed revisions and invalid bounds return HTTP 400 before storage access. No query text is interpolated into SQL.

The legacy overview remains a fresh read for compatibility with its existing no-store contract and seeds the same complete snapshot, so its subsequent metric-page requests reuse that aggregation. The new metric-page cache contains only public aggregates, no identities, tokens or profiles, and is independent of browser session caches. The endpoint does not expand field visibility, infer missing values, mutate source data or invoke models.

Before normalization and aggregation, annotation rows must join their own revision/file field definition with `visibility=public` and `value_status=PRESENT`. `assertion_status=UNKNOWN` or `UNRESOLVED` is ineligible. `UNSPECIFIED`, `INFERRED` and `CONFIRMED` may contribute, but the results do not certify these assertions as true. There is no fallback to `catalog_paintings.author_name/category`, private identifiers, hidden annotations or unmapped field keys. This is intentionally fail-closed when field definitions are missing. Coverage combines missing, unsupported, conflicting and restricted cases without exposing a separate private-field count.

Only these exact schema mappings are used:

| Meaning | Public field keys |
| --- | --- |
| Author name | `core.author_name`, legacy alias `legacy.author_name` |
| Category | `core.category`, legacy alias `legacy.category` |
| Dynasty | `legacy.creation_dynasty_raw` |
| Birthplace | `legacy.author_birth_place` |
| Style | `legacy.style`, huaniao `annotation.style` |
| Imagery | `legacy.subject`, `legacy.painting_school`, `legacy.style`, `legacy.color`, `legacy.composition`, `legacy.artistic_conception`, `legacy.brushwork`, `legacy.ink_method`, huaniao `annotation.style`, `annotation.subject_secondary` |
| Music sentiment | `legacy.music_scene_description` |
| Dynasty sentiment | `legacy.artistic_conception` plus public dynasty |

The huaniao mapping has no dynasty, birthplace, artistic-conception or music-description field. Creation year is not converted into a dynasty. Huaniao public author/category/style/secondary-subject values can contribute independently; hidden notes and source metadata cannot.

## Normalization, coverage and metric definitions

Whitespace (including ideographic space) is trimmed and consecutive whitespace collapsed to one space. Author names are otherwise preserved, with no case-folding, punctuation removal, alias merging, or coauthor splitting. `authorNames.count` is the number of distinct normalized **name strings**, not verified people; `eligibleRecords` is the number of paintings with a usable public name. Homonyms can merge people and aliases can split one person. Conflicting public aliases for any scalar meaning make that scalar unavailable for that painting rather than choosing a lexicographic value. Identical aliases are deduplicated.

Literal `0` (either trimmed raw or normalized value) retains its source meaning of pending verification and never becomes a chart zero or category. Empty/null and these exact, case-insensitive whole-value markers are excluded: `未详, 不详, 未提供, 未知, 未注明, 不确定, 未明确提及, 未提及, 暂无, —, -, /, 无, 不明, 待核实, 待考, null, none, unknown, n/a`. Substrings are not broadly discarded: an otherwise valid phrase containing one of these characters is not automatically unknown. Stored values are never rewritten.

Each `metrics` entry has:

```text
{ status: "available" | "unavailable", reason: string | null,
  eligibleRecords: number, missingOrRestrictedRecords: number,
  unmatchedRecords: number, items: [{name: string, value: number}],
  truncated: boolean, unit: string, method: string }
```

`eligibleRecords` always counts paintings with usable required **public** fields, before geography/keyword matching. `missingOrRestrictedRecords = population.count - eligibleRecords`. `unmatchedRecords` counts eligible paintings with no emitted label (including unsupported locations, no keyword match and label-length limits). Multi-value metrics have one coverage sample per painting even if many tokens contribute. If no eligible samples exist, status is unavailable and reason names the required fields. An available metric may have an empty `items` list when no eligible values match; this is different from missing/restricted data. A zero-population response contains all seven unavailable metrics.

| Metric | Definition and units |
| --- | --- |
| `dynasty` | Painting counts. Exact existing `DynastyNormalizer` aliases: 唐/唐朝→唐代, 宋/宋朝→宋代, 元/元朝→元代, 明/明朝→明代, 清/清朝→清代, 民国时期/民国年间→民国. Other text, including 南宋, stays distinct; nothing is inferred from years. |
| `category` | Painting counts by the entire public category string. Composite categories are not split. This replaces the misleading “creation popularity” label. |
| `geography` | Counts distinct normalized `[author name, birthplace text]` pairs per matched province; not people and not paintings. Coverage counts paintings with both fields. Repeated pairs count once; differing birthplace descriptions remain distinct pairs. The legacy province/city dictionary is reused as code. Province names win over city tokens; otherwise one uniquely matched province is required. Ambiguous multi-province results are unmatched. Single-character abbreviations 京/津/沪/渝 are omitted to avoid accidental substring matches. Canonical province/autonomous-region names match the map. No coordinate/geocoding inference. |
| `style` | Split on comma, Chinese comma/list comma, semicolon, whitespace, slash, vertical bar, middle dot. Each painting contributes once per distinct usable token. Unit 幅次; total may exceed eligible paintings. Top 12. |
| `imagery` | Same separators, public fields listed above; only tokens 1–6 characters. Each painting contributes once per distinct token across all selected fields. Unit 幅次; top 40. This summarizes words in annotations, not semantic recognition. |
| `sentiment` | Legacy ten-group music-description keyword rules. Most matching distinct keywords wins; ties use original fixed rule order. No match is unmatched, not neutral. Unit paintings. Display as a **rule-based estimate**, never model output. |
| `sentimentByDynasty` | Legacy artistic-conception heuristic: 50 base + 10×keyword-weight sum + min(text length×0.5,15), clamped to 20–100; average by normalized dynasty, rounded to two decimals. Weights are positive 1, neutral 0.5, negative −0.8, excited 1.2; full vocabulary is in SQL. This includes the historical text-length bonus and is not an objective emotional scale. Coverage requires both public mood and dynasty. |

Results are ranked value descending with a deterministic bytewise label tie break. The UI reorders dynasty axes chronologically where known. Style and imagery intentionally deduplicate repeated words within one painting, preventing repeated text from inflating the counts. This is a documented metric change from the browser CSV implementation.

Geographic priority selection computes the minimum priority once per painting with a window aggregate, retaining **every** candidate at that priority. This replaces the correlated full candidate rescan that made high-coverage geography expensive; it does not pick an arbitrary province in a tie. Province precedence, ambiguous matches, distinct name/birthplace units and all other metrics remain unchanged. The query still uses one statement/MVCC snapshot and the existing timeout/output limits; no global or connection-level JIT setting, schema or stored data changes are required. Focused PostgreSQL fixtures compare every ordered aggregate row with the previous selection on 12 edge-case paintings and 390 paintings with repeated geographic combinations.

Eligible annotation rows are materialized before joining their source file's field definitions. This prevents the planner from looking up the same painting's annotations again for every field definition when population estimates are low. The same statuses, supported keys and complete source identity keys apply; public visibility is still enforced before normalization, coverage or aggregation. A separate 384-painting fixture uses shared source files, legacy/huaniao keys, restricted fields and production-shaped join indexes without collected planner statistics. It compares every ordered output row against both original joins, verifies hidden-value mutation invariance, and checks actual annotation scans do not multiply by the number of field definitions. These focused checks are not production API latency or release acceptance.

## Tests and release boundary

`PaintingStatisticsPostgresTest` runs against a dedicated loopback PostgreSQL database using synthetic tables in a newly created UUID-named schema. It uses only `ARTLIVE_TEST_JDBC_URL`, `ARTLIVE_TEST_DB_USER`, `ARTLIVE_TEST_DB_PASSWORD`, never production application properties. The JDBC URL must match `jdbc:postgresql://127.0.0.1:<port>/artlive_knowledge_fixture`; without this explicit opt-in these integration tests are skipped, not passed. The test removes only its own schema. It covers Gallery equality, canonical-revision deduplication, media/current-selector exclusions, exact huaniao field mapping, literal 0/unknown/pending states, hidden-field mutation invariance, scalar conflicts, coverage, top-k and empty data. MVC tests cover parameters, JSON failures, no-store and disabled configuration; real HTTP authentication is checked in the isolated runtime acceptance. The deterministic scale regression adds 10,000 synthetic paintings and 266,000 annotations in a private test schema: ten 1,000-record cohorts include legacy, huaniao-shaped, pending-zero, hidden, unknown/missing, partial and three Gallery-excluded groups. Twenty additional unsupported source fields per painting (half public, half hidden) exercise wider input schemas without changing any aggregate. It independently asserts the 7,000-record population, 50 normalized names, complete metric distributions and coverage, deduplication of 200 extra source memberships, bounded serialized output, deterministic repeated reads and invariance when hidden values change.

The test schema contains the minimal read tables and relevant join index shapes; it is not a full production schema/index or distribution benchmark. The scale test logs five sequential store-call timings and UTF-8 JSON byte size as observations, not production latency guarantees. For a minimal local PostgreSQL installation that lacks the optional LLVM runtime, explicitly setting `ARTLIVE_TEST_PG_DISABLE_JIT=true` turns off JIT on **that test's JDBC connections only**; it does not alter server settings or normal application connections. Report this setting with any timing result. `ARTLIVE_TEST_KEEP_SCALE_SCHEMA=true` retains only a successfully checked scale schema for a separately configured local HTTP validation; by default every test removes its own schema. Retained schemas contain synthetic data only and must never be used as a production data source.

No schema migration or new data is shipped. The endpoint is additive and old Web clients remain compatible. New Web handles an absent endpoint or disabled catalog read as an unavailable statistics view with retry; graph loading is independent. Publish compatible Core before Web only through the existing coordinated release process. The initial Core publisher's equivalent-repackaging-only rule will reject these business classes/resources; successful tests and artifact checks do not authorize production promotion. Do not weaken that rule. Rolling back these code changes leaves all stored catalog/huaniao data intact. Runtime graph/map snapshots are separately configured and are not PostgreSQL statistics inputs.

After the repository transfer, new transport manifests are created only for `bailangguihun/auralink-core-api`. Verification defaults to that repository and requires it to equal the independently expected repository; `--expected-repository Hang-666-star/auralink-core-api` may verify an unchanged historical record when that is its genuinely expected identity. It does not relabel the record or authorize a new build to claim the historical repository. Deployment-side verification must bind the same repository through the approved candidate policy before accepting new formal artifacts; the production approval and coordinated activation requirements are unchanged.
