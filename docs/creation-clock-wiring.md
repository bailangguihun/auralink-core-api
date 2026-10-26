# Creation and Assistant clock wiring

When Assistant is enabled, `AssistantConfiguration` registers its qualified
`assistantClock`. Creation's fallback `creationClock` has a
`ConditionalOnMissingBean(Clock.class)` condition. That condition observes bean
definitions already registered; it does not remove a fallback when another
configuration subsequently contributes a Clock.

With Creation registered first, both definitions therefore exist. Unqualified
Creation constructor injection previously failed with
`No qualifying bean of type 'java.time.Clock' available: expected single matching
bean but found 2: creationClock,assistantClock`. This was observed in the formal
Core `3bb2a548d77e77dd3a034de5ee975b840d4b8d06` during production startup on
2026-10-26. The original publisher restored the previous application; no new
Core commit or Community installation was completed.

The default Creation bean is now `Primary` when it is registered. Its existing
missing-bean condition, UTC implementation, and custom-clock fallback remain
unchanged. Assistant's explicit `Qualifier("assistantClock")` continues to
select its own named bean. No lease/deadline policy, schema, provider, or
execution state is changed.

`CreationClockConfigurationTest` loads the actual two configurations and actual
`CreationResultPersistenceService` constructor through Spring, in both
registration orders, with Assistant absent, and with an existing custom Clock.
Storage beans are lazy and business dependencies are mocks: these checks open
no database and perform no provider/submission operation. Before the fix the
Creation-first case reproduces the exact constructor-parameter-9 failure. The
three involved baseline class files were byte-identical to the formal JAR.

The previous real-JAR Community interoperability fixture deliberately disabled
Assistant and Creation execution. Its passed auth/gallery/Community results
remain valid, but did not establish this combined production bean wiring.
The new test runs in ordinary Maven CI without an environment-gated skip. These
wiring tests are not a replacement for fresh formal artifact verification or
successful production startup.

Publication must follow the existing review/promotion/artifact process. The
installed finite control binding authorizes the previous exact candidate only;
do not patch its digest, overwrite its proof, or reuse the spent publication
authorization for this changed JAR. A reviewed transition from the actual
installed successor and a fresh concrete publication scope are prerequisites.
Community Host preparation still requires an actual compatible Core COMMITTED
postimage. There is no production retry or deployment in this change.

## Full application startup gate

`tests/run_full_application_startup.py` now starts the actual executable JAR
with its real eager Spring application context, native PostgreSQL, and the
production combination Assistant foundation/execution, Creation, providers,
workflows and managed VMM. It uses the real PostgresAssistantStore, recovery,
submission, persistence and lifecycle beans. There are no fixture beans,
Mockito substitutes or global lazy initialization. An external launcher only
sets fixture-local maintenance paths and asserts readiness; it is not packaged
into the application. Both normal scanning and explicit registration of the
actual CreationClockConfiguration before Application are checked. The latter
reproduces the historical two-Clock combination independently of ZIP ordering.

Run only in a new network namespace with UP loopback and no routes, as required
by the script. CI creates it with sudo/unshare and runs the fixture as the
unprivileged runner. Ambient credentials/proxies are not inherited. The empty
native database is explicitly migrated by the isolated migrator before Core
starts; Core uses a separate non-DDL runtime account. The source-only V001–V013
schema fixtures are hash inventoried; V014–V016 reuse this repository's reviewed
migrations. There is no production data or database rollback. The one synthetic
import-readiness record is explicitly named `full-startup-isolated`.

Provider endpoints terminate at an isolated refusal-only HTTP listener and no
external route exists. There is no submission or provider success simulation.
The gate requires actual HTTP health plus Assistant READY and an open Creation
recovery gate, unchanged table snapshots, clean JVM shutdown, closed listener,
zero runtime PostgreSQL sessions, and stopped fixture PostgreSQL. Raw logs and
result evidence survive failures. Readiness is not business/provider acceptance.

Both ordinary PR/main CI and the automatic formal JAR packaging workflow run
this gate on the exact JAR they built; the JAR manifest is created only after
it passes. Formal staging is pinned to Ubuntu24.04 for PostgreSQL16 availability.
This adds no publication trigger or enabled variable. The four focused Clock
wiring tests remain separate, faster constructor-order coverage.
