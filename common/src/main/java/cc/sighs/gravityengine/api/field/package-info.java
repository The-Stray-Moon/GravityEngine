/**
 * Supported loader-neutral gravity-field SPI (Java 17).
 *
 * <p>This package is part of GravityEngine's supported public API. It
 * contains the pure contracts an external content mod needs to define a
 * custom gravity field, plus the built-in field and influence factories:</p>
 *
 * <ul>
 *     <li>{@link cc.sighs.gravityengine.api.field.GravityField}</li>
 *     <li>{@link cc.sighs.gravityengine.api.field.GravityFieldQuery}</li>
 *     <li>{@link cc.sighs.gravityengine.api.field.GravityFieldSample}</li>
 *     <li>{@link cc.sighs.gravityengine.api.field.GravityInfluenceVolume}</li>
 *     <li>{@link cc.sighs.gravityengine.api.field.GravityFieldBounds}</li>
 *     <li>{@link cc.sighs.gravityengine.api.field.GravityFieldCompositionMode}</li>
 *     <li>{@link cc.sighs.gravityengine.api.field.GravityFields}</li>
 * </ul>
 *
 * <p>Everything in {@code cc.sighs.gravityengine.gravity.*},
 * {@code cc.sighs.gravityengine.attitude.*},
 * {@code cc.sighs.gravityengine.math.*} and
 * {@code cc.sighs.gravityengine.network.*} is an implementation detail even
 * where Java visibility is currently {@code public}. This package's
 * signatures reference no such type: a custom
 * {@link cc.sighs.gravityengine.api.field.GravityField} and a custom
 * {@link cc.sighs.gravityengine.api.field.GravityInfluenceVolume} can be
 * implemented with this package and {@code cc.sighs.gravityengine.api.math}
 * alone.</p>
 *
 * <p>Field evaluation is level-local: implement
 * {@link cc.sighs.gravityengine.api.field.GravityField} as a pure evaluator
 * that only reads its immutable query. It must not read or mutate Minecraft
 * state, and it runs on the thread that owns the level the field is published
 * to. The registration layer owns influence, composition, ordering, revision
 * and lifecycle. {@link cc.sighs.gravityengine.api.field.GravityFieldDiscovery}
 * describes conservative block wake-up domains independently of per-query coverage; see {@code docs/API_BOUNDARY.md}.</p>
 */
package cc.sighs.gravityengine.api.field;
