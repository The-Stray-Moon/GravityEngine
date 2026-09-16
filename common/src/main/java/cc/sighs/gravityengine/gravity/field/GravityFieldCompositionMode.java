package cc.sighs.gravityengine.gravity.field;

/**
 * Composition policy of one registered gravity-field instance.
 *
 * <p>This belongs to the registration layer rather than the mathematical
 * {@link GravityField} evaluator. A field evaluator only produces an
 * acceleration vector; the instance decides how that vector participates in
 * composition.</p>
 */
public enum GravityFieldCompositionMode {

    /**
     * Ordinary field. All active additive fields are vector-summed.
     */
    ADDITIVE,

    /**
     * Override field.
     *
     * <p>If at least one active OVERRIDE field exists at the query point,
     * ordinary ADDITIVE fields are excluded from the authoritative result.
     * Multiple active OVERRIDE fields are still vector-summed with each other,
     * so the result is deterministic and independent of field iteration order.</p>
     */
    OVERRIDE
}