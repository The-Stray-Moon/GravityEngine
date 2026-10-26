# AGENTS.md

## Purpose and scope

GravityEngine provides reusable gravity fields, movement/collision kernels, body-attitude control, angular dynamics and Minecraft integration. StarminerR is an external content consumer; its blocks, world generation and gameplay policy remain outside this engine. Preserve the `cc.sighs.gravityengine` / `gravityengine` identity.

This file defines long-term ownership and dependency contracts. Package layout, algorithms, body shapes, supported movement modes and adapter coverage may evolve while preserving these contracts. A current limitation is not a permanent prohibition; an existing implementation is not proof of correctness.

Use the current checkout, its version-matched Minecraft/loader contracts and reproducible evidence. Treat old plans, comments and previous acceptance reports as context. When implementation and a maintained contract disagree, explain the discrepancy and either repair the implementation or explicitly revise the contract and its tests. Do not silently turn a discovered bug into policy.

## Before changing code

1. Inspect applicable instructions, affected source, callers, tests and build configuration. Check `git status` when Git metadata exists; preserve unrelated edits. A source archive may omit Git metadata, documentation and scripts.
2. Identify the target/version and whether the change affects the common kernel, platform integration or supported consumer API.
3. For stateful work, identify the producer, authoritative owner, consumers, lifetime and invalidation events. State which values are durable, which are transient evidence, and what event makes absence authoritative after reload. Reuse an existing owner when it already expresses the required semantics.
4. Read the matching Minecraft/loader/optional-mod implementation or transformed bytecode before changing a Mixin seam. Verify descriptors, locals, slices and callback ordering for that version.
5. Make a coherent change with explicit failure behavior and focused validation. Avoid unrelated cleanup, forwarding layers and duplicate state created only to satisfy a layout preference.
6. If a deliberate contract correction supersedes an acceptance assertion, replace that assertion with a focused script scenario for the corrected behavior. Never weaken or remove a valid assertion merely because it fails.

## Required architecture contracts

The detailed architecture and delivery contracts below are part of these
instructions. They are kept here so a fresh checkout does not depend on ignored
local documents. Read the affected sections before changing a subsystem; do not
treat a current algorithm as a permanent API.

- Common production stays Java 17 and platform-neutral. Target code owns live
  worlds, lifecycle, Mixins, mutation and optional integrations.
- Public compatibility is limited to supported API packages and verified targets;
  Java public visibility alone grants no promise. Maintain API docs and fixtures.
- One mutable fact has one owner. Keep durable assignment, live FIELD evidence,
  evaluated gravity, committed application, geometry and transport distinct.
- FIELD completeness is per query across every expected provider. Incomplete
  evidence retains the authoritative assignment; registry emptiness is no proof.
- Position, collision orientation, attitude, semantic aim and presentation have
  independent authority. An operation owns its captured scene and work budget.
- Dynamic attitude has one momentum owner and one atomic integrator. Policies
  contribute torque; presentation cannot write physical state.
- Reuse native attachment and platform lifecycle semantics. Do not introduce
  duplicated readiness, persistence, clone or network-lifetime protocols.
- Expected coverage/budget failures reject or defer according to the operation.
  Unexpected failures roll back and propagate, preserving original causes.

## Verification and delivery

Follow the binding verification and delivery matrix below. Inspect target metadata
and use the matching wrapper/JDK. Compilation, transformed servers, client execution and
performance measurements prove different things; report PASS, FAIL, BLOCKED and
NOT RUN accurately. Never reuse stale PASS files.

Behavioral acceptance uses **repository scripts + AutoJS in a running Minecraft
instance**. Maintain scenarios under `scripts/autojs` and run them through
`scripts/verify-autojs.py`; extend this route for new behavior rather than
restoring JUnit/JVM test source sets or a test mod. API-only compilation fixtures
remain build checks, not gameplay acceptance. Gradle `test NO-SOURCE` is not PASS.

Inspect the installed AutoJS version and protocol before changing the driver.
Use `status` to establish the requested live side, then execute `eval_js` on its
owning game thread. Treat transport errors, timeout, script exceptions,
diagnostics and failed assertions explicitly; never retry a mutating evaluation
automatically. Do not substitute a mock server or pure JVM invocation for a live run.

Each run must retain a unique run ID, source/artifact identity, game/mod versions,
inputs, observations, assertions, cleanup outcome and fresh PASS/FAIL/BLOCKED result.
Use an isolated world or restore every fixture mutation. Reuse only an existing
user-accepted EULA file; never generate acceptance. A script-created player and
native listener prove transformed server behavior, not a real client connection.
Client-owned changes require client scenarios; multiplayer/latency and optional-mod
interactions require their respective live scenarios. Missing coverage is NOT RUN.

Test behavior and ownership, not class counts or layout. Do not weaken thresholds,
exclude failures or retain compatibility branches solely for obsolete assertions.

Use [docs/API_BOUNDARY.md](docs/API_BOUNDARY.md) for consumer migrations.
Retain fresh run evidence under the ignored build output; local historical
documents are context, not acceptance evidence for the current checkout.
Do not put task inventories or performance claims in this file. No automatic
push, release tag or remote publication follows from a refactor request.

## Dependency and API boundaries

| Area | Responsibility and permitted dependencies |
| --- | --- |
| `common/` production | Java 17 kernel, immutable domain values, pure mathematics and explicitly owned platform-neutral runtime/registry state. No Minecraft, loader, Mixin, Sable, client renderer or concrete packet types. |
| `common/.../api/field` and `api/math` | Supported platform-neutral consumer contracts. Signatures use Java 17 and supported API types; engine vector values use immutable `Vec3d`. |
| `targets/<loader>-<version>/` | Version-specific world capture, lifecycle, entity mutation, Mixins, networking, rendering and optional-mod adapters. May depend on common API **and common internals** as part of the same engine. |
| Target `cc.sighs.gravityengine.api` | Supported Minecraft-aware API for that target. Minecraft types such as `Level`, `Entity`, `BlockPos` and `ResourceLocation` are legitimate boundary types. Target-specific types must not leak into common. |
| External content mods | Consume supported API and normal platform APIs. Java `public` visibility in engine internals does not grant a compatibility promise. |

Common is not restricted to stateless functions: operation state, publication routing and lifecycle values can live there when their contracts are platform-neutral. Live world access and concrete platform mutation remain target-owned. Keep reusable algorithms in common and platform orchestration in targets; there is no line-count limit on an adapter.

Do not copy common implementations into targets, add loader/version switches to common, or make one target depend on another target's implementation. Test-only numerical oracles do not become production dependencies. Mutable platform/JOML values must be converted or defensively captured at ownership boundaries.

Current supported consumer operations include target `GravityEngineApi.publish`, `sample`, `entityGravity`, publication descriptors/leases, and common field/influence factories. DIRECT assignment mutation, attitude transactions, collision providers, movement internals and wire formats are not automatically public APIs. An interface named `ExternalRigidCollisionProvider` is still an internal engine extension point in this checkout.

Provider registration and query coverage are supported consumer contracts. The former `markFieldBootstrapComplete(Level)` API and Level-global readiness have no place in the target FIELD contract. Publication operations may remain as provider-owned source input, but an unscoped direct-publish path must not independently authorize reconciliation or infer completeness. Document signature/behavior migrations and update consumer fixtures and Javadocs together.

Public API signatures must not expose GE internal runtime, collision, attitude, math or protocol types. Implementations may delegate internally. API changes include observable validation, units, ordering, thread/side rules and lifecycle behavior as well as Java signatures. State the compatibility decision and update Javadocs, consumer fixtures and focused tests together. Maintain `docs/API_BOUNDARY.md` alongside the actual API declarations and package Javadocs.


## Architecture target: minimal state and native lifecycle

Prefer the smallest state model that can represent the real authorities. A new enum, boolean, revision, generation, lease or packet lifetime marker is justified only when it represents a fact that cannot be derived from an existing authoritative owner and has an independently testable lifetime or invalidation event.

- One fact has one mutable owner. Do not encode the same uncertainty or lifecycle in parallel enums, booleans and generations. Derived conditions such as "not applicable" should be derived from authority/capability rather than stored as another state when possible.
- Reuse the loader/Minecraft lifecycle when it already supplies the required persistence, copy, spawn, replacement or tracking semantics. Do not build a second lifecycle protocol around a removable queue or duplicated hook.
- Keep durable state, live world evidence, committed physical application and transport bookkeeping separate. Separation does not require duplicating the same fact in every layer.
- Prefer deleting accidental complexity over stabilizing it with more compatibility state. If removing a redundant layer changes tests that assert the redundant implementation rather than a required behavior, replace those tests with behavior/ownership tests.
- FIELD completeness belongs to each query result, never to Level-global readiness. GE owns persistence reconciliation; each registered provider reports only its own domain's contributions and coverage for that query. No publisher certifies another provider's completeness or signals that GE persistence recovery is finished.

For the primary NeoForge 1.21.1 target, use the platform contracts exposed by the pinned 21.1.256 source when they match GE semantics (the attachment implementation is unchanged from the original 21.1.249 review):

- Serializable entity data attachments are the canonical target-level mechanism for durable entity data. Do not inject `Entity.load` / `Entity.saveWithoutId` solely to duplicate attachment persistence.
- NeoForge serializes only attachment instances present on the holder. A GE persistence slot therefore must be materialized before a state that requires durable saving can be saved, or be created on the first durable mutation; its serializer may omit output for an intentionally empty/default record. Test this creation boundary explicitly.
- Player replacement already copies serializable attachments through NeoForge's clone path; death-copy behavior belongs to the attachment type's copy policy. Do not add a second GE clone copier for the same durable record.
- Only current attachment formats are supported: gravity format 5 and attitude format 6. Do not import pre-attachment root NBT or older attachment formats. Preserve unsupported-record diagnostics. The Entity.load tail still bootstraps an entity with no gravity slot; preserve NeoForge same-object attachment merge semantics.
- Entity pairing, player login, respawn and dimension change already provide ordered server lifecycle boundaries. Prefer fresh synchronization from those boundaries over buffering gravity snapshots across an entity lifetime that does not yet exist on the client.
- Native attachment persistence is not automatically live gravity/application synchronization. Keep explicit GE packets when their payload represents live assignment, committed body/application or presentation state rather than the durable persistence record.

The target gravity-state shape is semantic, not a frozen class layout:

```text
Durable gravity record
  gravity state
  assignment authority
  durable revision only if persistence conflict/migration semantics require it
  one FIELD continuity/provenance bit when required to distinguish a valid
  FIELD-derived seed from ordinary default gravity

Live gravity state
  assigned gravity state
  assignment authority
  FIELD evidence = UNKNOWN | PRESENT | ABSENT
  sync revision for new live truth, separate from durable revision when needed
  committed application / installed geometry owned separately

Operation/network state
  query coverage = COMPLETE | INCOMPLETE, ephemeral evaluation evidence
  transient and scoped to the operation or current live entity only
```

Do not add a parallel `FieldReconciliation` state machine, `NOT_APPLICABLE` reconciliation state, provisional-presence flag, per-entity readiness generation or synthetic network incarnation merely to restate the preceding facts. If later evidence demonstrates that one of those concepts is independently necessary, document the concrete failing lifecycle first and add the narrowest state that fixes it.

For FIELD assignment, persistence and application, preserve these five ownership boundaries. Names describe roles rather than mandatory package layout or five duplicate state containers:

| Owner | Sole responsibility | Persistence |
| --- | --- | --- |
| `GravityPersistenceSlot` | Attachment boundary for the last authoritative durable assignment tuple. | Durable tuple only. |
| `GravityEntityState` | Current assignment/authority and the single FIELD evidence tri-state. | Only its durable projection through the slot. |
| `GravityFieldProvider` | One producer domain's contributions and coverage for the current query. | No GE coverage/session persistence. |
| `GravityFieldRuntime` | Aggregate provider results and own deterministic composition. | No. |
| `CommittedGravityApplication` | Currently installed physical application, coordinated with the geometry owner. | No. |

The slot may serialize the entity state's authoritative durable projection; it must not introduce a second independently mutable assignment copy. Source discovery, durable assignment, live evidence and physical application have different lifetimes. A producer's own persistent source index, when its gameplay requires one, is external source authority rather than GE assignment persistence.

If both revisions are retained, their semantics are fixed: `durableRevision` advances only when the durable tuple (state, authority and required FIELD provenance) actually changes; `syncRevision` advances for each new live truth requiring client publication, including evidence-only changes. The counters are bookkeeping, not readiness evidence. Restoring revision 10 and resolving the same FIELD seed from `UNKNOWN` to `PRESENT` leaves durable revision 10 and advances sync revision. Repeating identical evidence changes neither. Existing names such as `assignmentRevision` and `assignmentSyncRevision` may implement these roles; do not add duplicate counters just to match names.

## FIELD providers and per-query coverage

The evaluation contract is `GravityFieldProviderResult(FieldCoverage coverage, List<GravityContribution> contributions)`, with `FieldCoverage = COMPLETE | INCOMPLETE`. These are semantic API shapes; use immutable supported API values and defensively capture the list. Public provider/query/result signatures must not expose internal `gravity.*` model types. Platform-neutral query, contribution and coverage values belong in common supported API; a Minecraft-aware provider interface belongs in the target API, with its registry and runtime remaining internal. Bind dimension/Level context through the target session or a supported target query boundary; do not add Minecraft types to common `GravityFieldQuery`.

- Register stable provider definitions/IDs and per-Level session factories during mod initialization, before Level runtimes are created. Freeze the expected provider set for those runtimes. First publication or BlockEntity load must not discover a previously unknown provider domain. Reject late/duplicate registration explicitly rather than silently changing the expected set.
- GE creates one session per expected provider per Level and destroys it on Level unload. Sessions own target-level source access/capture on the owning Level thread; common field evaluators remain pure. Contributions and coverage must describe the same captured query context. Providers must not mutate the provider registry during evaluation.
- `COMPLETE` guarantees that all contributions currently applicable to this query within that provider's declared domain are included. `INCOMPLETE` means the provider cannot prove that set is complete. Either result may contain contributions; `[A] + INCOMPLETE` is valid partial evidence. An inapplicable provider returns `COMPLETE + empty` based on its domain rules.
- `GravityFieldRuntime.evaluate()` is the sole aggregation/composition owner and may delegate pure arithmetic to common. Collect each expected provider's contributions once, preserve stable contribution identity/order across providers, and apply the existing OVERRIDE/ADDITIVE rules globally. Consumers must not precompose domains in a way that hides contributions needed by global composition.
- Aggregate coverage is `COMPLETE` only when every expected provider reports `COMPLETE` for the same query. A missing/unavailable expected session cannot be skipped as complete-empty. A deliberately empty, frozen provider set is complete; an empty publication registry alone proves nothing. Expected discovery failure yields incomplete evidence; invalid results and unexpected callback exceptions follow explicit boundary failure semantics, never fabricated complete-empty success.
- The aggregated field evaluation carries resolved gravity, contribution presence and coverage together. Presence on an incomplete evaluation describes only the partial sample; it cannot become authoritative entity `PRESENT`. API sampling must expose incomplete status rather than advertise partial composition as authoritative resolved gravity.
- Coverage is ephemeral and may change `COMPLETE -> INCOMPLETE -> COMPLETE` on successive queries. Do not persist it or introduce `bootstrapComplete`, `FieldDomainReadiness`, `evidenceGeneration`, `resetGeneration`, `trustedEvidenceGeneration` or per-entity coverage generations. A query cache must validate source/coverage context as well as query and assignment/application context; unchanged publication revision alone is insufficient.
- `LevelEvent.Load`, a chunk load event, registry emptiness/non-emptiness, registry revision, elapsed ticks and the first contribution are not completeness proofs. Provider-owned discovery of the actual query domain may use native lifecycle events as inputs; the event alone cannot certify the scan or other providers.

The authority rule for FIELD assignment is:

| Aggregate query coverage | Active contributions | Entity evidence and assignment |
| --- | --- | --- |
| `INCOMPLETE` | Empty or non-empty | `UNKNOWN`; retain last authoritative assignment and permitted application continuity. |
| `COMPLETE` | Non-empty, including a zero resultant | `PRESENT`; commit the complete composed assignment. |
| `COMPLETE` | Empty | `ABSENT`; commit default FIELD assignment. |

This rule applies after load and throughout runtime, including source creation/removal, movement between query domains, chunk unload/reload, dimension changes and source-index rebuilds. Previous `PRESENT` or `ABSENT` does not exempt a later incomplete query from becoming `UNKNOWN`. DIRECT authority remains independent of FIELD reconciliation.

StarminerR remains an external consumer. Its intended mapping is an overworld/dimension provider and a GravityCore provider backed by a source index. The dimension provider can return complete global contribution or complete-empty from dimension rules. GravityCore BlockEntity load/change/removal/unload updates the index; the provider answers contributions and coverage without knowing which entity's persistence GE is restoring.

GravityCore source lifetime is a content gameplay decision that must be explicit. Prefer loaded-core-only semantics unless unloaded cores are required to keep acting: unloaded chunks contribute no sources, and completeness requires completed discovery for every relevant loaded chunk that could influence the query. A bounded influence radius or another proven candidate-domain bound must justify that set; scanning an arbitrary neighborhood is not proof. Loading, unloading and index updates must agree with the declared source lifetime at the query snapshot. If unloaded cores must act, use a producer-owned persistent source index containing identities and field parameters; BlockEntity presence alone cannot provide that authority. Neither choice introduces a GE persistence-completion API.

## Gravity evaluation and application

- A field evaluates an immutable query into finite world-space acceleration. It must not read live worlds/entities or mutate registration state. Producers capture changing world data before publishing an evaluator.
- Keep field evaluation, influence geometry, publication identity/revision and composition separate. Influence bounds select candidates; the exact containment predicate decides activity. Influence geometry is not collision geometry.
- Preserve deterministic composition: active OVERRIDE contributions exclude ADDITIVE contributions; otherwise ADDITIVE contributions participate. Sum the selected group in stable order. Zero acceleration is a valid present contribution, not absence.
- Physics sampling supplies position, velocity, game tick and interval. Position-only sampling is a distinct API with its documented defaults. Reuse an evaluation only when its query and authority/application/provider-coverage/registry context remain valid.
- Assignment authority, evaluated acceleration, committed application and installed geometry have separate owners. Numeric equality with default gravity does not remove explicit authority. A pending assignment does not authorize an uncommitted body change.
- Publication lifetime is level-local and revision-aware. Closing an old lease must not remove a newer publication or recreate an unloaded runtime. Mutable world operations run on the owning level thread.

## Coordinate and authority contracts

Every physical operand must have a clear space, unit, sample time and owner.

| Value | Meaning |
| --- | --- |
| Entity position anchor `P` | Minecraft position/network bookkeeping owned by the platform commit path. |
| Exact collision body | Physical occupancy installed through the geometry owner; enclosing AABB is its conservative platform proxy. |
| Gravity reference frame | Environmental/control basis; its complete orientation can contain information beyond the collision up axis. |
| Body attitude | Independently owned actor attitude/control state; not an alias for gravity or collision orientation. |
| Semantic look | Gameplay aim used by targeting, attacks and propulsion; not an interpolated render sample. |
| Presentation | Derived/interpolated state for rendering and camera; cannot write physical authority. |

The current character convention is `C = P + worldUp * height / 2`; gravity feet derive from the exact body and its up direction. Do not substitute gravity feet or anatomical attachment points for `Entity.position()`. Changing this convention requires a coordinated migration of geometry, packets, sensors and presentation.

Root mapping, collision shape and collision orientation are independent contracts. The anchor-to-center mapping applies independently of shape; a capsule is not a workaround for position or orientation ownership. Exact collision queries reconstruct the installed body from native position, installed dimensions and the installed collision axis. They must not accept an environmental, attitude or presentation frame as an alternative geometry authority. Candidate construction takes an explicit proposed axis and remains separate from installed-body queries.

Ordinary translation, teleport repair and position correction rebuild the enclosing proxy using the installed axis; they do not select or rewrite that axis. Only an authorized geometry transaction, replica installation or rollback changes installed geometry. A locomotion frame derived around that axis is an operation operand, not another stored physical root or a source of collider orientation. Body attitude changes alone do not move the anchor or rotate the collider. Shape selection and collision algorithms remain independent of this ownership separation.

Live `deltaMovement` and movement requests remain world-space. Local scalar/vector carriers exist only within the operation that defines them. A frame change alone must not rotate stored physical momentum. Distinguish displacement, velocity, acceleration and per-second versus per-tick units; convert once at the adapter boundary.

Do not infer that every literal Y component means gravity. Establish whether it represents reference vertical, a contact normal, a fluid surface, plot coordinates or a world-grid position. Collision shape selection, gameplay aim and camera orientation must use their own authoritative samples.

## Body attitude, flight intent and angular dynamics

Environmental/reference evidence, actor intent, attitude control, angular dynamics and translation are different layers. Preserve this dependency direction:

```text
flight/environment evidence -> flight intent -> attitude policy -> torque contributions -> angular dynamics -> body attitude
                                                 |
                                                 +-------------------------------> diagnostics/presentation consumers

motion/translation model -------------------------------------------------------> world-space movement
```

Sharing an intent/attitude/dynamics mechanism does not require sharing a propulsion formula, AI, pathfinding or navigation policy. Elytra, future flying entities and a later aerodynamic model may reuse lower layers while keeping their motion models independent.

- A gravity reference frame is environmental/control evidence, not a body-attitude target. Gravity may affect translation and may be consumed by an explicitly named policy, but the mere existence, direction or strength of `down` must not right the body, complete a roll degree of freedom, or project flight intent onto a gravity tangent plane. Equality with `WORLD_DOWN` is not a capability switch: gravity-frame adaptation and enhanced flight-attitude control are separate decisions.
- Semantic/controller view is not physical angular state. A fixed-rate geometric controller/view roll such as `SemanticView.previewController()` is a kinematic view-path behavior; it must not be reinterpreted as physical roll torque merely because both use a roll input. A mode that deliberately passes a zero controller-roll interval leaves that input to another owner.
- The body attitude quaternion maps body-local coordinates to world coordinates. Model-specific flight axes are derived explicitly from that physical attitude through one axis-mapping authority; render interpolation, world axes and default humanoid assumptions are not physical flight axes.

For GE-owned dynamic attitude, use one angular state and one integrator. The canonical quantities are:

| Quantity | Contract |
| --- | --- |
| `q = worldFromBody` | Unit quaternion mapping body local -> world. |
| `L_world` | World-space angular momentum; authoritative dynamic rotational state. |
| `I_body` | Body-space rotational inertia. A strictly positive finite isotropic effective inertia is sufficient until a justified tensor model exists. |
| `tau_world` | World-space net torque accumulated for one solver step; ephemeral, never durable state. |
| `omega_world` | Derived from `q`, `L_world` and `I_body`; it is not a second authoritative state. |

The world inertia is `I_world = R(q) I_body R(q)^T`, and `omega_world = inverse(I_world) L_world`. Angular momentum evolves as `dL_world/dt = tau_world`. Angles are radians and integration time is seconds. Until a physical mass model exists, name effective inertia/torque units as rotational game units; do not call entity mass a moment of inertia or silently mix acceleration, torque, momentum and rate parameters.

A state representation may keep previous attitude for interpolation history, but interpolation history is not another physics state. If a mode is intentionally kinematic rather than dynamic, represent that ownership explicitly; do not fabricate angular momentum for it. Transitions between kinematic and dynamic ownership must define how angular momentum is initialized, preserved, consumed or reset.

- Dynamic `q` and `L_world` advance atomically through the common angular solver exactly once per fixed logical simulation step. No controller, view solver, renderer, packet handler or optional-mod adapter may perform a second hidden integration.
- Kinematic attitude changes are explicit mode-owned handoffs, not dynamic integration. A kinematic rewrite of `q` cannot claim to preserve momentum merely because an old `omega_world` vector was copied; if dynamic ownership continues, the change must reconcile `L_world` and inertia under an explicit physical or kinematic contract.
- Angular trajectories are immutable evidence/diagnostics for the step, not an independent continuity authority. Dynamic trajectory samples should carry or be reconstructible from the authoritative momentum state; any exposed angular velocity is derived.
- Normalize quaternions and reject non-finite torque, momentum, inertia and invalid time steps at the owning boundary. A numerical safety action that changes momentum is a declared protection event, not ordinary control and not a conservation claim.

Attitude policies contribute torque; they do not directly overwrite `q`, `L_world` or derived `omega_world`.

- Held roll input is a bounded dimensionless state. For physical roll control its contribution is `tau_roll_world = rollInput * rollTorque * forward_world`, where `forward_world` comes from the current physical body attitude and the explicit flight-axis mapping for that same solver sample. Releasing input removes this contribution only. Opposite input applies opposite torque; simultaneous opposite inputs produce zero net roll input.
- Heading control constrains the flight forward direction only. It must not construct a complete target orientation using world-up or gravity-up. Antiparallel direction ambiguity is resolved from retained/body-local evidence, never a hidden global vertical preference.
- Heading damping, player roll torque, global/game angular damping, explicit braking/stabilization and numerical protection are separate contributions. A heading controller must not cancel axial roll momentum by damping the complete angular-velocity vector. Zero input is not braking.
- Global angular damping is a configurable game-control model unless a real aerodynamic drag model owns that effect. A zero-damping configuration must exist for conservation tests. Do not apply equivalent damping in multiple controllers.
- Normal operation must not obtain terminal angular speed by hard-clamping the total rate or individual flight-axis rates each step. Use finite torque, explicit damping/braking or an explicitly named rate-limit controller when such gameplay behavior is required. Emergency finite/sanity bounds remain separate from normal control.
- A desired flight direction may use semantic look and an explicitly enabled velocity-alignment contribution, but velocity alignment is policy, not gravity-frame completion. Low speed, external impacts, sideslip and reverse flight must not silently redefine player intent. A future aerodynamic implementation should use relative air velocity where appropriate.

Elytra translation remains owned by its movement/aerodynamics path. Body attitude may affect or be affected by that path only through explicit contracts; rotating the rendered/model body does not by itself create lift, rotate the character collider or change position authority. Entering Elytra may use the Vanilla/displayed pose as a one-time bootstrap. It is not a per-step recovery target. Exiting Elytra has one mode-handoff owner that decides the retained/reset rotational state.

## Operation, geometry and position ownership

An outer movement/travel operation owns its captured evaluation, frame, time interval, collision scene, query scratch and work accounting. Subqueries and callbacks consume that operation's evidence. Identity preflight may widen a capture domain; it does not replace the captured scene as solver authority. A standalone operation may capture its own scene.

Nested calls must either have a defined nested owner that restores its caller or be deferred until a legal boundary. Do not globally ban legitimate nested Vanilla callbacks, and do not bypass an active owner to make a callback succeed. Operation-local results and landing receipts must not leak into another move.

A native player packet handler may retain a detached completion receipt for its exact root move after MOVE closes. The existing completion owner selects locomotion, final transport endpoint, support retention and endpoint certainty before producing it. Publish only after normal move return and normal close. The receipt and frozen target material belong to that handler invocation, not the entity or a game tick; nested moves cannot overwrite them. Rejection/teleport, independent position replacement, endpoint/body mismatch and exceptional completion make custom evidence unavailable. No collision scene or full move result survives through this handoff.

Packet consumption distinguishes native ownership (including noPhysics), unavailable custom evidence and solved facts. Native ownership keeps native operands. Unavailable custom evidence retains the native gameplay-ground compatibility operand but defers the derived fall callback and upward fall reset; it does not reinterpret world Y as environmental vertical. This conservative policy also defers path-derived fall effects when the accepted client endpoint differs from the server-solved endpoint. It never assigns old terminal support/material to the new endpoint or replays movement to recover evidence. A solved receipt supplies gameplay continuity separately from fall grounding: fall grounding is actual stable path landing or sustained terminal support. Terminal materials require terminal support and use provider-resolved Level/address/state; missing material means air. Fall consumes the receipt once; its material getters may read it throughout that callback.

Fall distance measures accepted locomotion projected onto the operation's captured environmental up, excluding recovery/depenetration. Packet jump intent, slow-fall, floating direction and movement statistics use environmental evidence; jump permission additionally requires current installed-body support departure. Collision residuals, support geometry, steps and collision vector carriers use the installed locomotion frame. Diagnostic frame lookup is not a gameplay fallback.


Geometry preparation returns a candidate and explicit legality/position authority. The target commits accepted changes to the exact body, proxy, dimensions, eye height, application and required bookkeeping consistently. A failed resize or rotation restores the corresponding previous state. Use the operation's scene for in-operation checks; do not introduce an unrelated world recapture or hidden second move.

Pose-fit, movement collision and packet occupancy answer different questions. Keep their tolerances and authority distinct. Do not make packet penetration tolerance a general permission for resize/rotation, or run recovery movement from a read-only occupancy query.

Retaining the currently installed pose and dimensions is not a new resize. Existing overlap must not force a standing player to crouch or swim merely to repair occupancy owned by movement. Proposed geometry changes still require their own legality check; pose retention does not authorize new or deeper penetration.

An externally owned position proposal must keep its captured anchor during preparation. Bounded support re-anchoring is allowed only when the invoking operation owns position. Teleports, respawn, dimension changes and accepted corrections invalidate stale movement evidence. A soft correction may preserve a one-use support hint, but it must be checked against the current body and scene before granting support or affecting geometry preparation.

Server connection tick restores its captured native position after player ticking. Frame preparation inside that boundary therefore has external position authority: it must not retain a rotation whose legality depends on a displacement that the enclosing native tick will discard. A blocked collider rotation retains the installed collision axis until a legal proposal is available; it does not overwrite independent body attitude.

A collider rotation proven legal at the current anchor may install without retaining old support. Bounded support preservation is an optional optimization, evaluated in the same scene only when position authority permits it. Failed or indeterminate optimization cannot revoke a completed direct legality proof. An illegal direct candidate still requires authorized, bounded, legal recovery; an indeterminate direct candidate retains the installed axis. Do not reset shared work budgets, round a nonzero adjustment into an unchanged anchor, or mask lost contact with retained on-ground state or extra friction. Revalidate support against the new installed body before granting traction or other contact-dependent behavior.

Routine network-player body preparation, including deferred dimensions/application and the completed connection-tick handoff, has external position authority. It must retain the accepted anchor and world-space momentum. A rotation requiring displacement to become geometrically legal retains the installed collider; a legal body-only change may commit at the unchanged anchor even if old support is lost. Explicit teleport, respawn, dimension-change and initial-load placement retain their native lifecycle owners. The server owns assignment and published body state; clients install atomic body transactions and reject stale/conflicting transactions. Movement never installs historical geometry or corrects a body-version mismatch.

For a server-installed custom player body, native pose selection and Size notifications propose dimensions; they do not independently install them. Defer refreshes reached outside the server body boundary, preserving the installed pose, dimensions, eye height, axis and proxy until that boundary validates the proposal. The pose associated with installed dimensions is geometry metadata: native DATA_POSE may already contain a different proposal, including updates that bypass setPose. One transient pending-notification flag is justified because a Size notification can occur without a pose change; it is consumed at installation and dies with its entity. Clients consume dimensions through the complete server body transaction, including native pose-data updates that arrive separately. Native-AABB entities retain their native size lifecycle. Rejection restores the previous geometry without replaying Size callbacks or undoing semantic death.

The complete environmental reference and an installed-axis locomotion frame are different operands. A blocked or bounded collider rotation must not rewrite environmental direction or tangent continuity. Capture reference evidence with the operation, derive its locomotion frame around the installed axis, and publish the original reference on completion. Reference consumers (including control, presentation and reference synchronization) read that evidence; collision/support consumers read the installed body and their operation frame. Reuse the existing completed-reference owner rather than storing a parallel physical frame.

Ordinary upright ground/air input uses the operation's captured environmental reference and semantic look, including walking, sprinting, crouching and ordinary airborne control. Upright body up follows reference up (opposite gravity down); a delayed collider cannot redefine the input plane. Explicit low-gravity swim retains semantic VIEW_3D control and free attitude; Elytra retains its independent flight contract. Installed-axis frames remain collision/support and local velocity-carrier operands. Never rotate existing world-space momentum merely because an input reference or installed axis changes. Support constraints may alter the final displacement; this is not permission to substitute a support-normal input basis.

Support lost through synchronization bookkeeping is not proof of physical separation. Pre-travel velocity cleanup and ground-friction selection must consume current verified contact evidence; cleanup must preserve the real support-normal velocity. Separation means relative velocity along the contact normal, not along gravity-up: uphill tangent movement is not a jump. Jump permission on a steep face still does not imply traction.

Packet jump inference must also consume current collision support evidence for the admitted installed body. A packet's on-ground flag changing to false and positive gravity-up displacement are not sufficient: native correction replies carry false even while supported. Require outward displacement beyond contact tolerance from every remaining supporting constraint before inferring a jump impulse. Keep this evidence immutable and invocation-local; missing or indeterminate contact cannot authorize an impulse. Packet displacement is not velocity and has no implicit one-tick interval. Dynamic support departure requires matched material displacement over a known interval; without that evidence, do not infer an impulse from the packet. Native jump input remains a separate path. Preserve native finite/bounds validation, the single movement call, correction/ACK bookkeeping and environmental fall operands; wrapped movement suppresses floating enforcement.

Clearing old physical continuity and installing new authoritative continuity are distinct steps. After a correction, publish the received complete physical frame only once the body is installed; retaining the up axis alone can lose the required tangent/reference orientation. Physical invalidation does not require a visual snap. The client presentation owner may converge from the currently displayed orientation to the new target while physical state and native position correction take effect immediately. `CompletedGravityFrame` supplies orientation/timing, not a second position-history path.

## Movement execution and collision ownership

Locomotion policy, installed geometry, collision ownership and operation lifetime are distinct concepts. Do not collapse them into one mode flag.

### Movement mode

`MovementMode` describes who owns locomotion/special movement semantics. It does not by itself select the physical collision solver.

In particular:

```text
MovementMode.NATIVE_FALLBACK != GravityCollisionRoute.VANILLA
```

`NATIVE_FALLBACK` may legitimately coexist with an installed exact body:

```text
movementMode   = NATIVE_FALLBACK
representation = EXACT_BODY
collisionRoute = EXACT_BODY
```

In that state Vanilla may generate/preprocess the movement request while GravityEngine remains responsible for clipping that request against the installed exact body.

### Installed representation

Installed geometry is a committed physical fact.

While an exact GE body is installed, pure native-AABB collision is not a legal substitute merely because a native handoff is desired or locomotion has entered a Vanilla-owned mode.

A transition toward native movement must retain exact collision until the geometry owner actually commits:

```text
EXACT_BODY -> NATIVE_AABB
```

Desired application, pending handoff, numeric default gravity and movement-mode fallback do not prove that native collision is installed.

### Outer movement transaction

The outer physical `Entity.move` integration boundary selects collision ownership once.

Before entering the Vanilla move body it must determine, from authoritative committed/installed state:

```text
locomotion mode
installed body representation
committed application requirements
external collision-provider ownership
selected collision route
whether a GravityOperation is required
```

If a GravityOperation is required, it freezes the operation's:

```text
GravityFrame
CollisionScene
collision route
movement evidence/provenance
query/work accounting
```

Only then may the Vanilla `Entity.move` body execute.

Pure Vanilla movement may bypass the operation only when committed application, installed geometry and external collision ownership all permit the native path. A movement-mode value alone is insufficient.

### Frozen route authority

Once a physical move has begun, its frozen `movementCollisionRoute` is the authoritative collision ownership for that move.

Inner `Entity.move` seams must not call a live policy selector to reconstruct ownership.

Do not combine these two operations behind one ambiguous helper:

```text
read current frozen route
select a candidate route for a future operation
```

Keep candidate selection at the outer boundary and frozen-route consumption inside the move.

A helper used by inner seams must never implement:

```text
if frozen route is absent:
    return GravityInfluencePolicy.collisionRoute(entity)
```

A null frozen movement route means that seam is not currently owned by a GE movement transaction. It must preserve Vanilla behavior. If GE collision was actually required, the defect is at the outer movement boundary and must be fixed there.

### Inner movement seams

Mixin/wrapper seams reached from the Vanilla `Entity.move` body consume frozen operation state only.

This includes current equivalents of:

```text
Entity.collide
Player.maybeBackOffFromEdge
walk-distance calculations
collision velocity response
block speed factors
support/on-position queries
landing state
step contact
fall/contact callbacks
collision flags
external collision-provider seams
```

If a seam needs a GravityFrame, CollisionScene or exact body, it must execute under an active operation that already owns the route.

Do not make an inner seam succeed by:

```text
sampling an ad-hoc frame
reusing presentation/reference geometry
recapturing the world
recomputing live collision policy
catching a missing-frame exception
fabricating an empty scene
```

`GravityOperationState.activeFrame()` remains fail-fast. Missing active-frame state at an engine-owned inner collision seam is an ownership bug, not a request for fallback sampling.

### No duplicate execution authority

An execution-plan value may be introduced to remove duplicated decisions, but it must be immutable and ephemeral or operation-owned.

Do not introduce a second durable owner that duplicates:

```text
committed application
installed body representation
BodyHandoffState
GravityOperationState
```

## Collision, support and momentum

- Broad-phase bounds select candidates. Exact bodies and finite features establish collision, occupancy and support. Do not authorize contact from an enclosing AABB alone.
- Solve against immutable captured geometry and motion at explicit times. Missing coverage, exhausted work and unknown results must stay distinguishable from a proven clear/unsupported result.
- Preserve the actual post-Vanilla-preprocessing movement request. Self-walk, external push, passive motion and support transport carry explicit provenance; unexplained motion must not silently become player intent.
- Ordinary collision clipping must not manufacture traversal permission. Step-up, snap and support transport require their own eligibility and evidence. Avoid apparent jitter fixes that bypass collision, fabricate on-ground state or damp all velocity.
- Path contact, landing contact, terminal support, resting evidence and persistent support identity are different facts. A transient impact or synthetic manifold is not automatically a reusable material anchor. Persist only a witness the relevant support resolver can validate.
- Reacquire moving support by provider/source/primitive identity and lifecycle/continuity epochs. Stale or replaced sources cannot bind a new body merely because numeric IDs match. Removal, re-registration and unload must invalidate routes correctly.
- Surface velocity is the velocity of the contacted material point, including angular motion. Support displacement and inherited release velocity are different quantities. Each physical contribution is applied once; provenance bookkeeping records it without producing a second impulse.
- Landing/bounce callbacks may already write a normal surface-velocity component. Reconcile remaining support momentum with that completed write before step damping. Keep receipts scoped to the exact move result, with nested-call and repeated-call safety.
- Persistent support is finalized after the operation's final physical velocity. Outward relative velocity can revoke retention without erasing the impact needed for callbacks. Closing the operation must not resurrect support from an earlier grounded result or add a second release impulse.
- Jump and movement-mode handoff use current validated support evidence. Missing evidence must not invent a release impulse; clearing support bookkeeping must not erase legitimate world momentum.
- Ground jump eligibility for an installed custom character body uses current finite lower-body contact, including steep faces below the walking/traction threshold. Validate the current body and scene before the native jump gate, including after body synchronization. Reject indeterminate queries and separating contacts; do not turn jump permission into general on-ground, traction, step, snap or persistent transport authority. Native jump input, cooldown, fluid routing and jump callbacks remain owned by Vanilla.

A touching classification is not permission to consume its remaining penetration band. A rigid sweep may preserve the exact shallow starting contact, but repeated sub-threshold entry must not accumulate into a deeper overlap. Conservative spatial or moving-plane proofs must cover the whole captured interval. Impact displacement may retain the existing CCD convergence gap to prevent rounded zero-gap endpoints from becoming spurious neighbouring voxel-side contacts. This positional separation does not add surface velocity, support credit or native reaction forces.

Body shapes, contact algorithms, step policies and numeric tolerances are implementation choices that may improve. Changes must preserve the preceding semantics and be checked on ordinary and tilted movement, corners, rotating/moving support and relevant state transitions.

Integration-state failures are not evidence that collision mathematics should change. Before modifying narrow phase, sweep/TOI, contact projection, step, recovery or support mathematics, first prove that the solver received a legal frozen operation, correct installed body, correct route and complete captured scene.

## Vanilla behavior and optional integrations

Vanilla retains gameplay decisions, callback ordering and eligibility unless the feature explicitly changes them. Adapt the spatial operands needed for gravity-aware behavior; avoid wholesale method replacement merely to rotate coordinates. When a GE capability is inactive, preserve its native path. Test both active and inactive behavior.

Geometry identity and block behavior have different lifetimes. Target `BlockContactResolver` validates the selected static/dynamic primitive and resolves invocation-local material/callback operands. Equal shape does not justify retaining stale `BlockState`; a material edit need not invalidate unrelated geometry. Dynamic lookup failure must not substitute unrelated parent-world terrain.

Context-sensitive shapes require the actual collision subject and correct coordinate conversion. Invocation-scoped target context must restore its caller and release entity references. A scene/provider/common value must not retain a live subject. Friction, speed/jump factors, fall response and step response must refer to the appropriate validated contact.

Sable and future platform adapters own version-specific discovery, coordinate/unit conversion and lifecycle hooks. Common consumes neutral rigid publications. There must be one owner for each collision/transport contribution; GE exact collision and an optional mod must not both solve and carry the same motion. The same rule applies to angular dynamics: when an external rigid system such as Sable owns pose, linear/angular state, mass properties, center of mass, inertia and integration, GE must not mirror a second authoritative `L_world` or integrate that body again. GE may submit force/torque through an explicit supported bridge, but the external rigid owner remains the solver and state authority.

Sable rigid collision already maps plot-space voxels through a captured SubLevel pose into GE's world-space rigid motion and oriented obstacles. Keep that transform tied to the operation's immutable publication; add a separate collision-frame value only if a concrete query cannot express its required space, sample time or authority with the existing pose/motion contracts. Sable's character solver and GE's exact-body solver have distinct ownership routes; do not infer that both solve one contact merely because both adapters are installed.

Sable's block physics properties own its rigid-body mass, inertia, volume, restitution and collider friction. GE's `BlockMovementMaterialSnapshot` is captured character-locomotion evidence (Vanilla friction, speed factor and fluid flags), not a competing rigid mass model. Do not unify values with different units or consumers under a new material authority. If a specific response needs shared data, name that operand, its owner and conversion at the target boundary.

The Sable gravity bridge may derive distributed force and tidal torque from Sable-owned mass points for supported ordinary rigid SubLevels. It must use complete spatial field samples with explicit world/plot coordinates, units and sample time, convert once to Sable's velocity or impulse boundary, and submit exactly once before Sable's solver step. No GE rigid angular state is retained. Own and attached native mass points must agree with the merged native mass and center before correction. Unavailable or inconsistent distributed evidence rejects the GE correction; it does not silently substitute a center-of-mass sample. Existing Sable publication hooks cover pose bounds, block-change geometry invalidation, SubLevel removal and Level unload; verify chunk-holder transitions and changed-shape behavior against the pinned Sable implementation before adding another invalidation protocol. Existing support transport is contact-validated movement evidence, not a persistent riding constraint. Sable owns SubLevel pose replication; GE owns its entity application packets. Establish a reproducible ordering or prediction failure before adding a combined wire frame or duplicating Sable's pose state.

Keep capture/discovery separate from identity resolution. Providers publish immutable exact geometry and motion, stream through the receiving collector, and respect its rejection. Geometric continuity, material changes and provider lifecycle are distinct. Keep registration epochs and identity allocation safe against reuse; avoid whole-source invalidation for unrelated edits when narrower evidence exists.

Optional dependencies must remain optional for class loading and runtime operation. Pin/validate the supported ABI and verify both installed and absent configurations. A prescribed rigid motion model, center-of-mass fallback or one-way character response does not imply arbitrary deformation or character-to-rigid reaction impulses.

Unsupported locomotion/geometry is explicit capability policy. A safe native handoff requires consistent installed geometry and cleared/reconciled transient state. If no legal handoff exists, preserve an explicit failure outcome; never convert unsupported geometry into a successful empty scene. Extending fluids, climbing, navigation, particles, vehicles, special entities or contraptions is allowed once its semantics, owner and validation are defined.

A request for native locomotion does not itself constitute a safe native collision handoff. If exact geometry remains installed, retain the exact collision owner until native geometry has actually committed.

Numerical small-velocity cleanup on verified moving support operates on the
actor velocity relative to its already-credited support momentum, then restores
that credit. Clearing a world component while retaining its support credit
manufactures relative momentum. Preserve the original real support-normal
scalar; a deadzone is not a new contact impulse. If current stable-support
proof is unavailable while support credit is still owned, defer that numerical
cleanup and preserve world velocity. Outward motion or carrier deceleration can
remove stable-ground eligibility before travel releases the credit; cleanup
must not erase world momentum while leaving its provenance intact.

Native force accumulation and GE field correction have distinct ownership. Within
one native physics substep, coupled GE mass, floating and balloon corrections
must share captured operands and accept or reject together. Late incomplete
coverage, invalidated mass/pose or an exhausted query budget discards all staged
GE corrections; it does not undo native queued forces. Query reuse ends with
that substep. Floating lift uses one sample at each native material cluster's
scale-weighted centroid; it is quadrature, not exact integration of arbitrary
nonlinear fields within a cluster. Different cluster samples contribute force
and torque separately. The self-lift cap uses lift-weighted gravity and center,
preserving the native uniform-field cap. Balloon sampling retains the native
pressure/gradient lever. Rapier water buoyancy remains native.

Create block edits rebuild Sable's existing native mass, bounds, lift-provider
and floating-cluster properties before publishing the changed block. Refresh the
native pipeline collider with those properties, including empty/rematerialized
contraptions. The rebuild is an edit-time native operation, not a per-character
mass scan. A failed build restores the old block and properties and propagates
the original failure; rollback failures are suppressed onto it. No second GE
mass or attachment lifetime owns this state.

Fluid absence requires complete captured volume evidence. An actor center or
foot point inside a water-occlusion region cannot erase separately wet body
volume. Fragmenting a fluid cell must not multiply its flow or material-motion
weight. Native gameplay still owns swimming, air, fire and fluid eligibility.

Native passenger removal and candidate dismount placement are separate effects.
A rejected final placement retains the previous anchor and restores any pose
chosen speculatively for that candidate. It must not remount an already removed
vehicle or replay native mount/unmount callbacks. The native removal remains
complete; rejecting displacement is not a claim that an arbitrary mounted
starting position was free of overlap. Removed-seat fallback uses the same
installed-body clearance rule and leaves recovery to the normal movement owner. Create's
client seat-mapping packet also produces a transient dismount-location tag.
Its deferred `ContraptionHandler.absMoveTo` commit must project the native plot
coordinate and validate the installed body. Create consumes that tag even when
GE rejects displacement; GE owns neither a duplicate pending location nor a
second passenger lifetime.

## Persistence and load reconciliation

Persisted continuity and live world evidence are separate authorities, but the representation must stay minimal.

- On NeoForge 1.21.1, durable gravity assignment data should use one serializable entity data attachment. If the supported contract is that gravity survives death respawn, the attachment owns that death-copy policy; non-death player replacement uses NeoForge's normal serializable-attachment copy path. Remove manual `Entity.load` / `saveWithoutId` persistence hooks and manual gravity `PlayerEvent.Clone` copying once the attachment path is authoritative. Do not enable attachment network sync unless the durable record itself is intentionally the wire contract.
- The attachment bridge must have an explicit creation rule: NeoForge persists serializable attachments that exist in the holder map, so a durable gravity mutation must ensure the persistence slot exists before the next save. An untouched/default entity may legitimately have no gravity persistence attachment/NBT.
- Persist only intentionally durable facts: the last valid gravity assignment seed, its authority, the minimum revision needed by the persistence contract, and one FIELD continuity/provenance bit if needed. Do not persist live `fieldPresent`, registry contents/readiness, publication leases, captured scenes, operation receipts, support handles or transient reconciliation evidence as destination-world truth.
- Persistence decoding is an explicit boundary. Distinguish absent data, valid current data, malformed data and unsupported versions even if the concrete result type uses different names. Older formats are explicitly unsupported. Malformed or unsupported payloads remain diagnosable; do not silently reinterpret them as successful default gravity.
- The pre-attachment `GravityEngineGravity` root tag is ignored. Current attachment serializers are the only persistence writers.
- Live FIELD evidence is a single tri-state fact: `UNKNOWN`, `PRESENT` or `ABSENT`. Do not mirror it with another reconciliation enum or extra booleans. When authority is not FIELD, FIELD evidence is not applicable by derivation rather than by another stored state.
- GE restores DIRECT authority normally from the attachment. A loaded FIELD assignment with continuity provenance restores its saved assignment and enters `UNKNOWN`; it is not proof of a matching field in the destination Level. FIELD state without a seed also needs complete query evidence before claiming destination-world absence. Callers never participate in attachment recovery or receive a persistence-completion callback.
- GE's reconciliation consumes the aggregated evaluation's coverage, presence and composition. **No incomplete evaluation may replace the last authoritative assignment or durable seed**, whether its sample is empty or non-empty and whether this is startup or ordinary runtime. A partial sample may omit a later ADDITIVE or OVERRIDE contribution. Set FIELD evidence to `UNKNOWN`, retain authoritative continuity and keep the durable revision unchanged. Evidence loss itself is new live truth and must be synchronized when the evidence changes.
- A `COMPLETE` non-empty result commits FIELD assignment with `PRESENT`; a `COMPLETE` empty result commits default FIELD assignment with `ABSENT`. Numeric equality, including zero acceleration or default gravity, never substitutes for contribution presence or coverage. Reconciliation is owned by GE and does not inspect chunk readiness, BlockEntity callbacks or producer bootstrap state.
- Incomplete coverage cannot authorize a sampled physical change, an absence-driven native geometry handoff or a save of partial composition. It does not prevent synchronization of `UNKNOWN`, truthful committed state, or independently authorized safe geometry/mode changes. While aggregate coverage remains incomplete, retain `UNKNOWN` and the last authoritative seed rather than inventing default/absence.
- Reuse `CommittedGravityApplication` for continuity: on FIELD seed load, attempt to install an application derived from the saved assignment through the existing safe geometry transition owner while evidence stays `UNKNOWN`. Do not add `provisionalApplicationState` or pretend the disk record contains committed geometry. If installation fails, retain the legal installed application (Vanilla on a fresh entity) and the unresolved seed; installation failure does not create `PRESENT` or erase the seed.
- `assignedFieldPresent()` remains false for `UNKNOWN`. `fieldReferenceInForce()` may nevertheless be true when the committed application actually uses FIELD continuity. Derive that distinction from the existing assignment evidence and application owners, not a provisional-presence flag. A pending assignment alone never authorizes physical geometry.
- Source rebuilds and runtime coverage loss use the same per-query rule as load recovery. There is no one-shot completion exemption for later publication/removal, and no producer reset/bootstrap signal. Atomic index updates may retain complete coverage; unfinished discovery returns `INCOMPLETE` for affected queries.
- Saving while FIELD evidence is `UNKNOWN` preserves the last authoritative durable tuple, including its provenance and revision. Evidence changes alone do not alter that tuple. Runtime uncertainty, as well as startup uncertainty, must never poison the next save.
- Level unload destroys provider sessions and all live field/runtime evidence without rewriting the durable seed. A new Level creates sessions from the already registered provider definitions; only their aggregate query coverage can establish live presence/absence.
- Persistence-format changes require an explicit compatibility decision, rejection tests for unsupported versions and a defined diagnostic/failure path for malformed or unsupported future versions.

## Networking, lifecycle and presentation

- Live world/provider capture runs after dispatch to the owning game thread and applicable native validation. Common does not schedule packets or hold platform transport objects.
- GE-wrapped player movement, including default gravity, uses client-owned endpoint acceptance. Within a player-specific exception-safe native-handler scope, bypass speed, moved-wrongly and new-endpoint-collision rejection and suppress floating enforcement. Keep the single native physical move and callbacks, finite-value checks, coordinate bounds, pending teleport/ACK, sleeping/passenger branches and accepted-position bookkeeping. Raw native packets outside the scope retain existing behavior; vehicle movement is separate. GE must not restore moved-wrongly checks bypassed by Sable.
- Current-server occupancy proves only occupancy against that captured server scene, not whether the client's original collision result was wrong. Unavailable evidence is neither collision proof nor proof of clearance. Client collision resolves immediate movement; server collision remains simulation/geometry evidence. Intentional tradeoff: GE-wrapped endpoints are not server-enforced against speed or penetration.
- Movement carries its captured prediction application epoch and dimension without changing the wire format. The epoch is diagnostic context only: unknown, expired, regressed or representation-mismatched epochs do not reject movement. No historical bodies, replay scenes, acknowledgements or replacement readiness protocol are retained. Native discontinuities still advance the application epoch; clientbound stale/conflicting body transaction checks remain authoritative.
- A logical old/new rigid occupancy comparison uses the same frozen publication set and shared budget. Do not independently capture the two sides or silently substitute live provider state between them.
- Assignment, installed application/body and observer/presentation updates are not interchangeable payloads. Preserve dimension/entity identity and the minimum monotonic revisions that correspond to real live authorities.
- Provider discovery, query coverage and persistence reconciliation are server-authoritative. Synchronize current server assignment, FIELD evidence (including `UNKNOWN`) and actual committed application/body with their owning revisions; an assigned seed is not an instruction to install an uncommitted body. Clients consume server truth and perform only explicitly owned prediction/presentation; they do not restore durable attachment seeds, infer coverage from client chunks or run FIELD reconciliation. The wire protocol does not need to transport provider coverage or discovery state.
- For ordinary tracked entities on NeoForge 1.21.1, send the fresh GE snapshot from the tracking lifecycle after native pairing/spawn has been queued. For the local player, resynchronize from login, respawn and dimension-change boundaries after the platform has installed/replaced the relevant player state. Keep these target-specific ordering assumptions covered by source/version checks and integration tests.
- Do not introduce a synthetic entity-incarnation epoch or lifetime packet merely to compensate for a client pending-snapshot queue. On the client, a gravity/body snapshot whose target entity/dimension/UUID is absent or mismatched is dropped, not carried across replacement. The server's next native lifecycle synchronization establishes the new live object's state.
- Pending network data, when unavoidable for a different protocol, has bounded size/lifetime and may not become an entity-lifetime authority. Gravity synchronization should not depend on render ticks. Disconnect, Level unload and native entity removal/replacement invalidate transient client state.
- A monotonically increasing revision is justified only for a real mutable authority whose stale duplicate/out-of-order update must be rejected. Durable assignment changes and publication of new live truth have distinct lifetimes: separate durable and sync revisions are valid under the semantics defined above. Do not reuse a durable revision to reject evidence-only updates, or add separate evidence/readiness/incarnation counters for the same live truth. Reuse the existing application epoch for committed body transactions; geometry-only or full-reference changes must also invalidate older body transactions. It is not a durable assignment revision.
- A player body transaction captures the actual installed representation (including dimensions and collision axis), committed application and complete environmental reference together. The client validates the complete transaction before installation at a legal operation boundary, and exposes it only after all physical facts are installed. A receiver must not defer an application because the same authoritative snapshot carries a different collider: install the changed facts in that transaction. An unchanged collider needs no reinstall. Remove the separate metadata-only versus representation-sync permissions and the retry/resync loop they required. A transaction is an operation, not another mutable copy of physical authority.
- Transfer collision orientation as the installed axis, separately from the complete reference frame. Do not synthesize missing reference orientation from a collider axis. Native position corrections remain the position authority and run against the installed body; neither reference nor attitude synchronization supplies a second position owner. Wire-format changes require a matching protocol version on client and server.
- Geometry changes outside the final connection-tick comparison still require publication. Compare against the last published physical facts, not only the immediately preceding local snapshot. Duplicate transactions are idempotent; stale transactions cannot change any installed fact, and conflicting content at an equal physical epoch is a boundary failure. Failed installation must not allow gameplay to continue with a partial commit.
- Rendering and diagnostics consume committed/completed evidence. Client visual interpolation never becomes collision, targeting, torque or packet authority and never advances angular physics. Environmental reference, installed collision geometry and body attitude retain their separate owners. Reuse the client presentation owner for interpolation history, including ordinary correction and gravity-presentation enable/disable transitions; do not add a second physical frame owner merely to rename the value type. Position remains native-owned and mouse input remains immediate. Entity replacement, dimension change and disconnect discard visual history.
- Camera handoff convergence interpolates the captured presentation offset, not the live controller input. Apply current mouse motion at full strength throughout activation, release and interrupted transitions. Camera owns eye-height smoothing and final orientation/basis installation; neither feeds back into actor attitude, semantic aim or collision geometry.
- A predicting player's native locomotion mode must be refreshed by the existing mode owner before opening travel and support preflight, including after passenger removal. Consuming server-owned application/body commits does not make that local mode stale by design. A nested move cannot repair a mode after its outer travel has already captured authority and support.
- Client-only code stays outside dedicated-server loading paths; disabled diagnostics must not create expensive captures or alter behavior.
- Interpolation of replicated moving obstacles is a separate physical/network contract, not camera smoothing. It is permitted only with explicit sample times, motion trajectories and matching movement validation. Sable-style delayed obstacle snapshots and support-local coordinates do not authorize delaying this engine's local-player collider. The scoped GE-wrapped movement bypass above applies with and without Sable. Network latency can still leave an in-flight move based on an older body after an atomic transaction; validate that scenario rather than claiming atomic installation removes propagation delay.
- If dynamic attitude continuity is intentionally durable, persist a generic angular-dynamics seed with the minimum authoritative state required to resume it; do not encode momentum as an Elytra-only special case.
- A correction or authority handoff that installs a dynamic body attitude must reconcile the corresponding angular momentum/inertia state atomically. Installing a new `q` while retaining unrelated stale angular state is invalid. If a protocol intentionally transfers pose without dynamics, the receiving boundary applies one explicit reset/reseed policy rather than manufacturing zero momentum in multiple places.
- Repeated Vanilla position corrections are evidence to investigate physical prediction/ownership disagreement. Preserve explicit native teleport/lifecycle handling and body synchronization, and evaluate physical correction counts independently from visual smoothness under the scoped acceptance policy above. Ordinary corrections may converge visually without postponing physical installation or acknowledgement; this is not evidence that the underlying disagreement is solved.

## Performance and failure semantics

Bound expensive candidate discovery, primitive enumeration, validation and narrow phase at their owning boundaries. A result-size cap does not bound work done before collection. Share accounting across phases of one logical query; do not reset the budget to continue an exhausted operation. Relevant unsupported geometry must remain discoverable.

Use deterministic ordering and purpose-specific finite tolerances. Preserve immutability and lifecycle checks when optimizing; do not use approximate geometry as a replacement for stable object identity. Measure the affected hot path and retain a reproducible regression case.

Catch only failures the boundary knows how to handle. Expected complexity/coverage failure may reject movement, a candidate or a packet according to that operation's contract; it must not become fabricated success. Unexpected callback/programming exceptions propagate after scope cleanup and rollback where applicable. Do not hide them with broad catch-and-continue logic or disable a solver to make verification pass.

Attitude preparation is not a committed simulation step. Publish STEPPED/SUSPENDED only after the corresponding candidate commits; stale preconditions report rejection. Unexpected commit failures restore actor/view/native carriers and propagate, preserving the original failure if rollback also fails. Input consumption and presentation handoffs must not treat a rejected candidate as an accepted step.

Fail ownership violations near their adapter boundary. Do not let an illegal route/frame/scene combination proceed deep enough to fail later in an unrelated movement seam.

## Build and live verification commands

The primary target is NeoForge 1.21.1 / 21.1.256 with Java 21; common production
remains Java 17. Inspect each target's `ci.properties`, toolchain and wrapper before
building other targets. Scaffold compilation does not certify integration parity.
Run target builds sequentially because they share `common/build`; do not rebuild
that output while a running game is consuming it.

From the repository root, with a matching Java 21 installation:

```powershell
.\targets\neoforge-1.21.1\gradlew.bat -p targets/neoforge-1.21.1 build --console plain --no-daemon
python scripts/verify-autojs.py --launch-server --eula-file <existing-accepted-eula.txt> --java-home <jdk-21-directory> --offline
```

Replace the angle-bracket placeholders with existing paths. The driver creates an
isolated world under `build/autojs-runs/<run-id>/game`, binds to loopback and stops
only its owned server. `--profile sable|create|aeronautics` chooses the pinned
optional stack; loading it alone does not certify interactions. To attach to an
existing instance, use `--endpoint http://127.0.0.1:25585/mcp` and `AUTOJS_TOKEN`
when needed. An attached instance is not shut down by the driver.

The packet scenario temporarily edits overworld blocks at X=37..48, Y=238..244,
Z=38..42, refuses block entities, restores block states and discards its own players.
Use an isolated world. The in-memory connection does not validate login, wire
serialization, real clients or latency. Retain `result.json`, RPC logs, source and
artifact hashes, worktree state and game logs. Driver exit codes are 0=PASS,
1=FAIL and 2=BLOCKED; unsupported scenarios stay NOT RUN.

Normal target `runClient` / `runServer` tasks accept `-PgeRunDirectory=<path>`.
The CI workflow and root aggregation select targets through `ci.properties`;
those builds prove compilation, not live acceptance. `:javadoc` in the primary
target generates supported API documentation. Build checks include
`verifyApiConsumerImports` and `compileApiArtifact`, compiling consumers against
the finished JAR without optional-mod dependencies.

Performance measurements must retain environment, raw results and comparable
warmup/JIT settings; the standalone `scripts/measure-hot-paths.ps1` probe is not a
whole-game profiler. Publishing uses `gradle/target-conventions/publish.gradle`,
explicit `-PpublishType=snapshot|release` and credentials. Review fresh verification
evidence and license/attribution before an authorized publication. A build does
not authorize a push, tag or release.

## Required change/evidence matrix and delivery

Use the affected target's real wrapper/JDK. The current root includes common and orchestrates independent target builds; its target `Exec` commands choose Windows `cmd` or POSIX `bash`. Inspect the actual wrapper and script paths before invoking them. Do not treat an aggregate build as cross-version runtime proof.

From repository root on Windows:

```powershell
# Common compilation using an available wrapper.
.\targets\neoforge-1.21.1\gradlew.bat -p common assemble --console plain --no-daemon

# Primary build and API-only consumer compilation.
.\targets\neoforge-1.21.1\gradlew.bat -p targets/neoforge-1.21.1 build --console plain --no-daemon
```

| Change | Evidence to obtain |
| --- | --- |
| Common mathematics, state or support lifecycle | Focused AutoJS behavioral scenarios; preserve Java 17/platform isolation. |
| Angular dynamics / flight attitude | Conservation and torque-response tests, substep consistency, ownership/single-integration checks, mode-handoff tests and affected target integration tests. Zero-damping tests must distinguish conserved `L_world` from derived `omega_world`. |
| Public API | Signature/consumer fixture checks, validation and lifecycle tests, synchronized contract documentation. |
| Target movement, geometry, callbacks or Mixins | Target compilation and fresh AutoJS-driven live scenarios; client smoke where behavior is client-owned. Until those scenarios run, report live coverage as NOT RUN. |
| Sable integration | Above plus pinned installed/absent runtime scenarios through AutoJS, including affected real interactions. |
| Networking/correction/presentation | Native spawn/tracking/login/respawn/dimension ordering; stale-revision rejection; missing/mismatched-target drop behavior; fresh lifecycle resynchronization; applicable client/server checks. Synthetic incarnation tests are required only if such a protocol is independently justified. |
| FIELD providers / query coverage | Registration before Level creation; duplicate/late registration rejection; all expected sessions consulted; missing-session failure; per-Level disposal; complete-empty/incomplete-empty/partial-positive results; global deterministic composition; zero resultant presence; query-domain discovery and cache invalidation when coverage changes without publication changes. |
| Persistence / load reconciliation | Native attachment creation/save/load and player-copy behavior; current-format roundtrip and unsupported-version rejection; publication-before-load and load-before-complete-query; partial-positive samples; complete PRESENT/ABSENT; runtime coverage loss/recovery; save while UNKNOWN; unchanged-seed durable/sync revision separation; safe continuity installation and rejection; Level unload/respawn replacement; self/observer synchronization; malformed/unsupported diagnostics. |
| Documentation only | Verify referenced declarations, paths, commands and diff; a game run is not required for prose alone. |

Removed test tasks are not replaced with build-only acceptance aliases. AutoJS
scenarios must exercise the invariants below; missing scenarios remain a coverage gap.

For FIELD persistence/load work, acceptance must include publication-before-load and load-before-publication orderings **and** a partial-composition case with at least two possible contributors/providers, including one reporting `INCOMPLETE` while another supplies a contribution. Neither incomplete-empty nor incomplete-positive results may replace the durable seed, advance its durable revision or poison a save. All-complete non-empty composition commits `PRESENT`; all-complete empty composition commits `ABSENT`. Include zero/cancelling contributions and a later OVERRIDE so numerical equality or an already non-empty sample cannot hide the bug. Provider-owned query coverage is the completeness oracle; a Level/chunk event, registry revision or fixed tick delay is not.

Also cover `PRESENT -> UNKNOWN` and `ABSENT -> UNKNOWN` on runtime coverage loss, repeated incomplete queries, recovery to each complete outcome, chunk unload/reload and source-index rebuild under the declared source-lifetime policy. A restored unchanged FIELD tuple at durable revision 10 must stay at 10 after `UNKNOWN -> PRESENT`, while its sync revision advances; changing durable provenance alone must count as a durable tuple change. Verify UNKNOWN continuity with both accepted and rejected geometry installation, and that clients accept evidence-only updates without running provider reconciliation. Preserve DIRECT authority throughout FIELD coverage changes.

Use tests that exercise behavior and ownership boundaries. Keep architecture/import checks for real dependency contracts, not arbitrary class placement.

Do not weaken thresholds, remove assertions, exclude failing cases or delete tests merely to obtain PASS.

When a deliberate contract correction supersedes a script assertion, replace it with focused coverage of the corrected behavior. Report uncovered requirements explicitly.

If an entire test file exists only to enforce the superseded contract, delete the file. If a file mixes obsolete and still-valid behavior, delete only the obsolete test cases and retain the valid ones.

Do not preserve an invalid contract through compatibility branches merely because an old test expects it. Do not leave obsolete tests ignored, disabled or excluded from the build.

For movement-ownership changes, test combinations of locomotion mode, installed representation, collision route and operation lifetime rather than testing each flag in isolation. At minimum cover the legal `NATIVE_FALLBACK + EXACT_BODY + engine collision` state and the `NATIVE_FALLBACK + NATIVE_AABB + pure Vanilla collision` state.

API compilation, transformed server scenarios and real client execution prove different things.

Unless the task explicitly requests a different delivery format, report what changed, why it preserves or deliberately revises a contract, the checks actually run and remaining blockers. Distinguish PASS, FAIL, BLOCKED and NOT RUN.

A task may explicitly suppress the narrative report without suppressing required implementation or validation work. If the task explicitly requests no report, perform the same implementation and verification work but obey its requested completion response.

An offline harness or different test dependency version is auxiliary evidence, and a partial source archive is not proof of a release artifact. Never carry a previous snapshot's test counts forward as current results.
