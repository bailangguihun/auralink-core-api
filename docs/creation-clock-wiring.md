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
