package cc.sighs.gravityengine.gravity.field;

import java.util.Objects;

/**
 * Pure zero-acceleration field evaluator.
 *
 * <p>This type carries no composition semantics. Composition is supplied by
 * the field registration that owns the instance.</p>
 *
 * <p>A zero vector is still a valid field sample. An override registration
 * can therefore suppress ordinary gravity through the normal
 * contribution-presence semantics without requiring concrete-type
 * knowledge.</p>
 */
public final class ZeroGravityField implements GravityField {
    public static final ZeroGravityField INSTANCE =
            new ZeroGravityField();

    private ZeroGravityField() {}

    @Override
    public GravityFieldSample sample(GravityFieldQuery query) {
        Objects.requireNonNull(query, "query");
        return GravityFieldSample.ZERO;
    }
}
