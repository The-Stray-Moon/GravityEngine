package cc.sighs.gravityengine.gravity.field;

import cc.sighs.gravityengine.gravity.model.GravityFieldKey;

import java.util.Objects;

/**
 * Immutable registration of one gravity field.
 *
 * <p>Owns only generic field identity, structural accumulation order, the mathematical evaluator, spatial
 * influence volume, composition policy and a monotonic revision. It never
 * owns a gravity-core lifecycle, collision primitive, planet definition
 * or generation provenance: those belong to the producer that created the
 * instance.</p>
 *
 * <p>The mathematical {@link GravityField} remains concerned only with
 * evaluating acceleration. Cross-field composition semantics belong here at
 * the registration layer.</p>
 */
public record GravityFieldInstance(
        GravityFieldKey key,
        GravityFieldOrder order,
        GravityField field,
        GravityInfluenceVolume influence,
        GravityFieldCompositionMode compositionMode,
        long revision
) {
    public static final java.util.Comparator<GravityFieldInstance> ACCUMULATION_ORDER =
            java.util.Comparator.comparing(GravityFieldInstance::order)
                    .thenComparing(GravityFieldInstance::key);

    public GravityFieldInstance {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(order, "order");
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(influence, "influence");
        Objects.requireNonNull(compositionMode, "compositionMode");

        if (revision < 0L) {
            throw new IllegalArgumentException(
                    "revision must be non-negative: " + revision
            );
        }
    }

}
