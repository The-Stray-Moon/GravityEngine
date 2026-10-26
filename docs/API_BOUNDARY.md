# Consumer API boundary

This document defines the supported consumer contract for GravityEngine **0.0.2**.
The Minecraft integration baseline is **NeoForge 1.21.1 / 21.1.256, Java 21**;
the common API and kernel target **Java 17**. Other target directories are
scaffolds and do not imply equivalent API integration or runtime acceptance.

## Dependency and compatibility scope

GravityEngine is unreleased. Only the current API, configuration, attachment
schemas and network protocol are supported; there are no aliases, migration
readers or fallback decoders for earlier development versions. Consumers must
update with the engine. Unsupported attachment versions are rejected with
diagnostics and discarded without write-back; subsequent saves contain only
current authoritative state. Network peers must use the matching protocol.

Durable assignment revisions describe saved truth only. Loading starts a fresh
live synchronization sequence at zero, independently of the durable revision;
evidence-only updates advance only that live sequence. Native entity lifecycle
and replica identity continue to govern snapshot admission.

| Supported package | Responsibility |
| --- | --- |
| `cc.sighs.gravityengine.api` | Target facade, provider sessions, publication handles, entity adapters and immutable observations. Common observation values share this package. |
| `cc.sighs.gravityengine.api.field` | Platform-neutral field/query/contribution/coverage contracts, influence volumes and factories. |
| `cc.sighs.gravityengine.api.math` | Immutable engine vector values (`Vec3d`). |

Minecraft types in target signatures are legitimate dependencies. Common
signatures remain platform-neutral. Java visibility outside these packages does
not promise consumer compatibility. In particular, `gravity.*`, `math`, `mixin`,
`network`, `client`, JOML values and internal collision providers are not consumer
APIs. DIRECT assignment mutation, body-attitude writes, solver replacement,
attachment schemas and packet formats have no supported consumer entry point.
`GravityAuthority.DIRECT` is an observation, not permission to install it.

Build the primary target with Java 21 from the repository root:

```powershell
.\targets\neoforge-1.21.1\gradlew.bat -p targets/neoforge-1.21.1 build --console plain --no-daemon
```

In a matching NeoForge consumer, copy the resulting JAR into `libs/` and use:

```groovy
dependencies {
    implementation files('libs/GravityEngine-neoforge-1.21.1-0.0.2.jar')
}
```

The JAR embeds common; do not add a second common artifact. Declare the engine
as a required mod dependency when using its API. This file dependency example
does not imply that a remote Maven artifact exists. Sable and other optional
integrations are not required to compile the supported consumer examples.

## Entry points and execution context

The facade is
[`GravityEngineApi`](../targets/neoforge-1.21.1/src/main/java/cc/sighs/gravityengine/api/GravityEngineApi.java).
Except for initialization registration, target operations must run on the owning
game thread. A `Level` parameter does not make a server-only method client-safe.

| Operation | Contract |
| --- | --- |
| `registerFieldProvider(id, factory)` | Initialization before the first server Level runtime. Registers one stable producer domain and a factory for a fresh session per server Level. Duplicate IDs fail; late registration fails. |
| `publicationProvider(level, id, coverage)` | Publication-backed session helper constructed in the registered factory. The caller still owns query coverage and source lifetime. |
| `publish(level, provider, definition)` | Server Level thread, normal mutation phase or the owning provider's `onOpen`. Returns an accepted lease or stale rejection. |
| `samplePublications(level, provider, query)` | Provider-scoped raw contributions. Use in that provider's evaluation; it neither composes domains nor certifies coverage. |
| `sample(level, position[, velocity, gameTick, intervalTicks])` | Server Level thread. Evaluates every expected provider and returns immutable field composition plus coverage. |
| `entityGravity(entity)` | Owning entity thread, either side. Read-only optional observation; does not create sessions, query providers or commit physical state. |
| `registerEntityAdapter(type, callback)` | Initialization on both sides before capability resolution freezes the registry. One exact entity type per registration. |

Callbacks must bound their own work: engine work budgets cannot preempt arbitrary
consumer Java code. Pure common fields and values can be used independently of a
Level; they must not capture live Minecraft state.

## Provider lifecycle and source authority

Register providers during mod initialization, before any server Level runtime
can be acquired. Do not defer registration until a block entity loads. The frozen
provider set defines which domains must answer every query.

1. A factory receives a `ServerLevel` and returns a non-null, fresh
   `GravityFieldProvider`. It constructs only; publication and sampling are forbidden.
2. After all factories return, GE calls `onOpen()` in stable provider-ID order.
   A session may publish its own initial sources and close their handles, but may
   not publish for another provider or sample. Evaluation begins after all opens succeed.
3. `evaluate(query)` captures its source domain and returns contributions and
   coverage for that same query. It may sample its own publications, but cannot
   recursively compose gravity or mutate publications.
4. Level unload revokes publications and closes sessions. Failed initialization
   revokes initial publications and closes acquired sessions in reverse order.
   All acquired sessions receive a cleanup attempt. Resources a factory has not
   returned remain that factory's responsibility. Cleanup cannot publish or sample.

A provider owns discovery of its source domain, not GE entity recovery. For a
loaded-block-only domain, prove that all relevant loaded chunks have been indexed;
an arbitrary neighborhood scan is insufficient without a conservative influence
bound. If unloaded sources must keep acting, the consumer needs a persistent
source index with the required identities and parameters. Choose this gameplay
policy explicitly. Neither model needs a GE bootstrap-complete signal.

## Query coverage and composition

`GravityFieldProviderResult` defensively captures a list of `GravityContribution`
values and a `FieldCoverage`. `COMPLETE` means all contributions applicable to this
query in this provider's domain are included. `INCOMPLETE` means the provider
cannot prove completeness; it may still return positive contributions. A domain
known to be inapplicable returns `COMPLETE` with an empty list.

Aggregate coverage is complete only when every expected provider is complete.
A null/missing session or an unexpected callback failure is an error, not an empty
successful domain. A deliberately empty frozen provider set is complete; an empty
publication registry alone proves nothing. Coverage is per query and can change
without any publication revision changing.

| Aggregate result | Meaning for FIELD-owned entity reconciliation |
| --- | --- |
| `INCOMPLETE`, empty or non-empty | Evidence becomes `UNKNOWN`; retain the last authoritative assignment. Partial composition cannot replace it. |
| `COMPLETE`, contributions present | Evidence becomes `PRESENT`; commit the complete assignment, including a zero resultant. |
| `COMPLETE`, no contributions | Evidence becomes `ABSENT`; reconcile to the default FIELD assignment. |

`ComposedGravitySample` is a **field** result. Complete-empty sampling returns no
contributions and zero acceleration; it does not supply the entity's native/default
gravity. Use `entityGravity` to inspect assigned, effective and applied entity
values. On an incomplete sample, `fieldPresent()` describes partial evidence only.
Zero acceleration can also mean a present zero-gravity field or cancellation;
`down()` is empty for a zero resultant, independently of presence.

Composition rules are global across domains:

- Evaluate and validate active contributions before selecting the effective group.
  An active OVERRIDE does not hide ADDITIVE callback failures or missing coverage.
- If any OVERRIDE contribution exists, sum only OVERRIDE contributions; otherwise
  sum ADDITIVE contributions. Multiple OVERRIDE contributions add together.
- Accumulate in deterministic source-type string order, numeric X/Y/Z order, then
  contribution ID namespace/path order. Revision and registration order are not priority.
- Contribution and source-type strings use `[a-z0-9_.-]+:[a-z0-9/._-]+`.
  Contribution identities are Level-local and must be unique across providers;
  duplicate IDs in a query fail rather than being silently deduplicated.
- Non-finite output or composition overflow fails explicitly. The composed result's
  contribution list contains the selected group, not every evaluated contribution.

Positions use blocks, velocities blocks/game tick, accelerations blocks/game tick
squared. `gameTick` is non-negative integer sampling time; `intervalTicks` is a
finite non-negative duration, not seconds or a phase within that tick. A zero
interval is a valid instantaneous query. Identical inputs cannot identify different
substep phases. `sample(level, position)` and `GravityFieldQuery.at(position)` use
zero velocity, tick 0 and interval 0; supply the full query for physics sampling.

## Publications, revisions and immutable fields

A `GravityFieldDefinition` supplies instance ID, structural source order, pure
field, influence volume, composition mode and non-negative revision. The target
Level supplies the dimension. `GravityFieldDefinition.block` and
`GravityFieldIds.blockInstance` derive distinct IDs for different block positions;
`named` uses the supplied named source ID. Do not reuse one type ID for multiple
simultaneous instances or assume a provider ID namespaces publication IDs for you.

A higher revision replaces the current registration with that ID. An equal/lower
revision yields `REJECTED_STALE`, preserving the current registration. Validation
or callback failure also preserves the old registration. Keep the previous lease
until a new submission is accepted, then close the previous lease.
An ID already owned by another provider is rejected rather than replaced.

A `FieldPublication` owns the exact accepted instance, even if its ID and revision
are later reused. Closing an old handle cannot remove a replacement. Successful
close is idempotent; after unload it is harmless and never recreates a runtime.
Wrong-thread or forbidden-phase close on a live session fails and leaves the handle
retryable. A rejected handle owns nothing and is already closed.

`accepted()` and `status()` describe the submission outcome. `isClosed()` describes
local handle release, not whether the publication is still registered: replacement
does not automatically close old handles. None is a current-registration query.

Fields implement `GravityField.sample(GravityFieldQuery)` and return finite
world-space acceleration. Capture source parameters immutably; evaluators must
be pure and cannot read live worlds/entities or mutate registrations. Custom
`GravityInfluenceVolume` implementations must also remain stable: finite bounds
conservatively contain the exact `contains` domain, while empty `finiteBounds()`
means unbounded influence. Bounds select candidates; exact containment selects
activity. Bounds are captured for indexing at publication, so changed influence
requires a new publication revision rather than mutating a published object.

`GravityFields` supplies zero gravity, spherical mass and height fields, sphere
and infinite influences, and density/cutoff helpers. Follow each factory's numeric
preconditions in its Javadocs. GravityEngine supplies no universal gravitational
constant; the consumer owns its field parameters and cutoff policy. Custom field
and influence implementations require only supported common API types.

## Automatic block discovery

`GravityFieldProvider.blockDiscovery()` is optional and leaves `evaluate` as the
interface's single abstract method. It runs on the server Level thread once per
Level tick and must not sample, recursively discover or mutate publications.

For non-publication sources, return immutable conservative `GravityFieldBounds`
covering influence during that tick. The default `SAMPLING_ONLY` contributes no
automatic block-discovery hints. `UNBOUNDED` opts into budgeted loaded-world
traversal. Published influence bounds participate automatically; large finite
bounds remain finite even when the spatial index uses a fallback representation.

Hints select candidate work, never certify coverage. Exact block queries still
consult the expected providers. Changed/removed sources retain bounded cleanup of
previously affected positions; chunk unload retires related cursors. Continuous
sources are revisited without revision changes. No finite completion deadline is
promised under continuously unbounded incoming work.

## Entity observations and integration modes

`EntityGravitySnapshot` contains independent immutable observations:

| Value | Authority |
| --- | --- |
| `assignedDirection`, `assignedStrength`, `authority` | Latest authoritative assignment, including any retained FIELD seed. |
| `effectiveDirection`, `effectiveStrength`, `observationSource` | Coherent physical evaluation, or explicit assignment fallback. |
| `appliedDirection`, `appliedStrength`, `applicationEpoch` | Committed application used by body/movement integration. |
| `fieldPresence` | `UNKNOWN`, `PRESENT` or `ABSENT` evidence, independent of numeric strength. |
| `referenceFrame` | Optional environmental/control frame, not installed collision orientation or body attitude. |

`ACTIVE_OPERATION` uses the current operation's frozen evaluation. `CURRENT_TICK`
requires matching time, position, velocity and physical authority/application
context. Otherwise the read uses `ASSIGNMENT_FALLBACK`. The current implementation
rejects reuse of FIELD-registry-dependent tick evaluations outside their operation:
publication revision alone cannot prove unchanged provider coverage. The getter
does not resample to manufacture freshness. An empty snapshot means unavailable
GE entity state, not FIELD absence; it does not mean that an adapter mode is NATIVE.

Strengths in the snapshot must be finite and non-negative. Engine-produced
directions are unit world vectors even at zero strength; direct record construction
checks finiteness, not normalization. A blocked collision rotation can leave the
reference up and installed collision axis different. Consumers must not infer a
collider axis from effective gravity or the reference frame.

Entity adapters select existing integration contracts, not new movement solvers:

| Mode | Scope |
| --- | --- |
| `NATIVE` | Native motion, collision and presentation; fields remain sampleable. |
| `CHARACTER` | Ordinary living travel with supported current dimensions and locomotion states. |
| `BALLISTIC` | Supported projectile gravity seams; native AABB collision and presentation. |
| `PASSIVE_BALLISTIC` | Native item/orb/TNT/falling-block gravity with passive support integration. |
| `CONTROLLED_FLIGHT` | Flight ownership; environmental evidence does not enable character locomotion. |

An exact-type declaration precedes built-in classification, but cannot override
native exclusions, unsupported dimensions or missing gravity hooks. Adapter
callbacks run on the owning entity thread for each capability request, must be
pure/non-reentrant and return non-null. Their mutable decisions are not cached by
type. Exceptions propagate with type context. Unregistered custom `travel`
overrides conservatively use native behavior; an inherited ordinary travel path
can remain eligible. Audit the actual travel/tick implementation before opting in.

## Failures and consumer migration

Null required inputs fail; invalid numeric/value inputs fail explicitly. Duplicate
registration uses `IllegalArgumentException`; frozen/late registration and illegal
runtime phases use `IllegalStateException`. Stale publication is the documented
non-exceptional rejection. Unexpected provider runtime failures include provider,
Level and callback-phase context while retaining the cause. Cleanup failures are
suppressed behind the primary failure. Publication argument failures preserve their
exception type and attach context where the runtime boundary handles them. Invalid
output never becomes fabricated complete-empty success.

Consumers migrating from the earlier API must:

- Register provider domains and publish through their IDs; remove Level-global
  bootstrap/readiness assumptions and handle per-query coverage.
- Recompile snapshot constructor calls for the appended `assignedDirection`,
  `assignedStrength` and `observationSource` components. The old eight-component
  constructor is not retained. Reject negative snapshot strengths.
- Keep FIELD presence separate from strength, and environmental frames separate
  from collider orientation. Use the lease lifecycle when replacing sources.

Internal class renames and movement receipt changes are outside the consumer
compatibility boundary. This document does not promise binary compatibility for
internal packages or support for old save/network formats.

## Examples and verification boundary

The maintained compile fixtures use only supported GE imports plus platform types:

- [ExternalConsumerFixture](../verification/api-consumer/com/example/examplemod/gravity/ExternalConsumerFixture.java): initialization/open/close, publication replacement, full sampling, observation and adapter declarations.
- [ProviderFixture](../verification/api-consumer/com/example/examplemod/gravity/ProviderFixture.java): publication-backed coverage and a bounded non-publication source. Its probe controls are fixture scaffolding, not a production discovery policy.
- [CustomSpiFixture](../verification/api-consumer/com/example/examplemod/gravity/CustomSpiFixture.java): custom pure field and finite influence implementation.

`verifyApiConsumerImports` checks imports; `compileApiArtifact` compiles these
fixtures against the finished JAR and platform dependencies, excluding common
output directories and optional integrations. Both are included in the primary
`build`. They do not execute the examples or prove lifecycle/gameplay behavior.
Generate supported API Javadocs with the target-qualified task:

```powershell
.\targets\neoforge-1.21.1\gradlew.bat -p targets/neoforge-1.21.1 :javadoc --console plain --no-daemon
```

Behavioral acceptance uses repository scripts and AutoJS in a running Minecraft
instance; see [AGENTS.md](../AGENTS.md) and
[the driver](../scripts/verify-autojs.py). Build-only checks, server scenarios,
client behavior and optional-mod interactions require distinct evidence. A prior
PASS or a Gradle `test NO-SOURCE` result does not validate a new consumer integration.
