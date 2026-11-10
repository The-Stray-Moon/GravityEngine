package cc.sighs.gravityengine.gravity.field;

import cc.sighs.gravityengine.api.field.GravityField;
import cc.sighs.gravityengine.api.field.GravityFieldCompositionMode;
import cc.sighs.gravityengine.api.field.GravityInfluenceVolume;
import cc.sighs.gravityengine.gravity.model.GravityFieldId;
import java.util.Comparator;
import java.util.Objects;

/**
 * Immutable registration of one gravity field.
 *
 * <p>Owns only generic field identity, structural accumulation order, the
 * mathematical evaluator, spatial influence volume, composition policy and a
 * monotonic revision. It never owns a gravity-core lifecycle, collision
 * primitive, planet definition or generation provenance: those belong to the
 * producer that created the instance. It never owns a world/loader handle:
 * world scope is the registry that contains it.</p>
 *
 * <p>The mathematical {@link GravityField} remains concerned only with
 * evaluating acceleration. Cross-field composition semantics belong to
 * {@link GravityFieldService} at the registration layer.</p>
 */
public record GravityFieldInstance(
        GravityFieldId id,
        GravityFieldOrder order,
        GravityField field,
        GravityInfluenceVolume influence,
        GravityFieldCompositionMode compositionMode,
        long revision
) {
    public static final Comparator<GravityFieldInstance> ACCUMULATION_ORDER =
            Comparator.comparing(GravityFieldInstance::order)
                    .thenComparing(GravityFieldInstance::id);

    public GravityFieldInstance {
        Objects.requireNonNull(id, "id");
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
