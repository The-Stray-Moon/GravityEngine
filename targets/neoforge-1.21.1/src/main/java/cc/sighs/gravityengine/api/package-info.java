/**
 * Supported Minecraft-aware GravityEngine API for the NeoForge 1.21.1 target.
 *
 * <p>This package is part of GravityEngine's supported public API together
 * with the loader-neutral
 * {@code cc.sighs.gravityengine.api.field} and
 * {@code cc.sighs.gravityengine.api.math} packages in {@code common/}.
 * Consumers should depend only on these supported GravityEngine API
 * namespaces, plus normal Minecraft and NeoForge types.</p>
 *
 * <p>{@link cc.sighs.gravityengine.api.GravityAuthority},
 * {@link cc.sighs.gravityengine.api.GravityFrameView} and
 * {@link cc.sighs.gravityengine.api.EntityGravitySnapshot} are supplied by
 * {@code common/} under their original public names. Entity capture and facade availability remain target responsibilities.
 * Snapshot construction now requires assigned values and derived observation
 * provenance; see the coordinated migration in docs/API_BOUNDARY.md.</p>
 *
 * <p>GravityEngine-owned vectors in this target API are immutable
 * {@code cc.sighs.gravityengine.api.math.Vec3d} values. Minecraft
 * {@code Vec3} is reserved for actual Minecraft-facing call boundaries.</p>
 *
 * <p>Supported entry points:</p>
 *
 * <ul>
 *     <li>{@link cc.sighs.gravityengine.api.GravityEngineApi#registerFieldProvider} -
 *     register a domain before Level creation, with one session per Level.
 *     Factories construct only; initial publication belongs in the default onOpen callback.
 *     Each query returns contributions and COMPLETE/INCOMPLETE coverage;
 *     GE owns load and runtime reconciliation. Sessions may implement
 *     {@link cc.sighs.gravityengine.api.GravityFieldProvider#blockDiscovery()}
 *     for budgeted automatic block discovery without publications.</li>
 *     <li>{@link cc.sighs.gravityengine.api.GravityEngineApi#publicationProvider} and
 *     {@link cc.sighs.gravityengine.api.GravityEngineApi#registerEntityAdapter}:
 *     minimal publication sessions and finite entity integration declarations.</li>
 *     <li>{@link cc.sighs.gravityengine.api.GravityEngineApi#publish} -
 *     publish or replace a field in one {@code Level}.</li>
 *     <li>{@link cc.sighs.gravityengine.api.GravityEngineApi#sample} -
 *     composed sampling, position-only or full physics.</li>
 *     <li>{@link cc.sighs.gravityengine.api.GravityEngineApi#entityGravity} -
 *     read-only entity gravity snapshot without session creation, provider callbacks or resampling. Its reference frame is environmental
 *     evidence, not installed collision orientation; a blocked body rotation
 *     may leave their up axes different. Its
 *     {@link cc.sighs.gravityengine.api.EntityGravitySnapshot#fieldPresence()} reports
 *     {@code PRESENT}, {@code ABSENT} or {@code UNKNOWN}. Consumers must preserve
 *     that distinction; the former boolean constructor and accessor are removed.</li>
 *     <li>{@link cc.sighs.gravityengine.api.GravityFieldDefinition} -
 *     publication descriptor with {@code named} and {@code block}
 *     factories.</li>
 *     <li>{@link cc.sighs.gravityengine.api.GravityFieldIds} - deterministic
 *     per-instance publication identity for block-backed fields.</li>
 *     <li>{@link cc.sighs.gravityengine.api.FieldPublication} - publication-instance lease returned by publication.
 *     Live release requires the owning Level thread and a permitted mutation phase;
 *     rejected release remains retryable. isClosed reports local release completion.</li>
 *     <li>{@link cc.sighs.gravityengine.api.field.GravityFields} - built-in
 *     field and influence factories.</li>
 * </ul>
 *
 * <p>Deliberately not part of this API version: DIRECT assignment and its
 * release back to FIELD authority, body-attitude transactions, collision and
 * movement integration, networking, client and rendering, spatial-index or
 * runtime mutation. Those remain implementation details with unstable
 * contracts. See {@code docs/API_BOUNDARY.md}.</p>
 */
package cc.sighs.gravityengine.api;
