package cc.sighs.gravityengine.gravity.acceleration;

import cc.sighs.gravityengine.api.field.GravityFieldQuery;
import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.GravityFrame;
import cc.sighs.gravityengine.gravity.model.CommittedGravityApplication;
import cc.sighs.gravityengine.gravity.model.GravitySample;
import java.util.Objects;

/**
 * One immutable physical gravity truth for one operation/tick.
 *
 * <p>The snapshot separates the three facts an operation needs:</p>
 *
 * <ul>
 *   <li>the complete {@link GravityEvaluationContext} (authority binding,
 *       committed application, application epoch and field-registry
 *       generation);</li>
 *   <li>the complete {@link GravityFieldQuery} inputs actually evaluated
 *       (position, velocity, game tick and interval);</li>
 *   <li>the physical result - composed evidence, the plan-gated effective
 *       acceleration and the completed {@link GravityFrame}.</li>
 * </ul>
 *
 * <p>Integration and trajectory consumers share the original operation snapshot.
 * A separately owned endpoint-support stage may evaluate a different query against
 * the same authority and registry generation. That evidence must remain separate:
 * it cannot replace the integration snapshot, add acceleration or rerun movement.</p>
 *
 * <p>The snapshot is runtime-only. It is never serialized, and it retains no
 * mutable scene or entity reference.</p>
 */
public record GravityEvaluationSnapshot(
        GravityEvaluationContext context,
        GravityFieldQuery query,
        GravitySample evidence,
        GravitySample acceleration,
        GravityFrame frame,
        long sampleTick,
        double sampleIntervalTicks,
        cc.sighs.gravityengine.api.field.FieldCoverage fieldCoverage
) {
    private static final double SAMPLE_POINT_EPSILON_SQUARED = 1.0E-24D;

    public GravityEvaluationSnapshot(GravityEvaluationContext context, GravityFieldQuery query,
            GravitySample evidence, GravitySample acceleration, GravityFrame frame,
            long sampleTick, double sampleIntervalTicks) {
        this(context, query, evidence, acceleration, frame, sampleTick, sampleIntervalTicks,
                cc.sighs.gravityengine.api.field.FieldCoverage.COMPLETE);
    }

    public GravityEvaluationSnapshot withFieldCoverage(cc.sighs.gravityengine.api.field.FieldCoverage coverage) {
        return new GravityEvaluationSnapshot(context, query, evidence, acceleration, frame,
                sampleTick, sampleIntervalTicks, coverage);
    }

    public GravityEvaluationSnapshot {
        Objects.requireNonNull(fieldCoverage, "fieldCoverage");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(evidence, "evidence");
        Objects.requireNonNull(acceleration, "acceleration");
        Objects.requireNonNull(frame, "frame");

        if (sampleTick != query.gameTick()) {
            throw new IllegalArgumentException(
                    "sampleTick must equal the evaluated query tick: "
                            + sampleTick + " vs " + query.gameTick()
            );
        }
        if (Double.compare(sampleIntervalTicks, query.intervalTicks()) != 0) {
            throw new IllegalArgumentException(
                    "sampleIntervalTicks must equal the evaluated query interval: "
                            + sampleIntervalTicks + " vs " + query.intervalTicks()
            );
        }
        requireSameSamplePoint(query.position(), evidence.samplePoint(), "evidence");
        requireSameSamplePoint(query.position(), acceleration.samplePoint(), "acceleration");
        requireSameSamplePoint(query.position(), frame.samplePoint(), "frame");
    }

    /** The complete query inputs this truth was evaluated with. */
    public Vec3d samplePoint() {
        return query.position();
    }

    public Vec3d velocity() {
        return query.velocity();
    }

    public long gameTick() {
        return sampleTick;
    }

    public double intervalTicks() {
        return sampleIntervalTicks;
    }

    /**
     * Assignment/authority binding revision carried by the context.
     *
     * <p>This is the entity-assignment generation, not the field registry
     * generation. It is exposed for callers that already reason in authority
     * terms and should not need to unpack the record.</p>
     */
    public long authorityRevision() {
        return authority().revision();
    }

    public GravityAuthorityState authority() {
        return context.authority();
    }

    public long applicationEpoch() {
        return context.applicationEpoch();
    }

    public CommittedGravityApplication committedApplication() {
        return context.committedApplication();
    }

    /**
     * Field-registry publication generation sampled with this evaluation.
     *
     * <p>DIRECT and NONE evaluations do not depend on this value and use
     * {@link #NO_FIELD_REGISTRY_REVISION}.</p>
     */
    public long fieldRegistryRevision() {
        return context.fieldRegistryRevision();
    }

    /** Registry revision is not relevant to an authority that bypasses fields. */
    public static final long NO_FIELD_REGISTRY_REVISION = 0L;

    /** Whether this snapshot's physical context still matches {@code other}. */
    public boolean matchesContext(GravityEvaluationContext other) {
        return context.samePhysicalContext(other);
    }

    /** Effective plan-gated acceleration in world space (blocks/tick^2). */
    public Vec3d effectiveAcceleration() {
        return acceleration.accelerationVector();
    }

    public boolean hasActiveField() {
        return evidence.hasActiveField();
    }

    public boolean matchesQuery(GravityFieldQuery other) {
        return other != null
                && query.gameTick() == other.gameTick()
                && Double.compare(query.intervalTicks(), other.intervalTicks()) == 0
                && query.position().equals(other.position())
                && query.velocity().equals(other.velocity());
    }

    public boolean matchesInputs(
            Vec3d position,
            Vec3d velocity,
            long gameTick,
            double intervalTicks
    ) {
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(velocity, "velocity");
        return sampleTick == gameTick
                && Double.compare(sampleIntervalTicks, intervalTicks) == 0
                && query.position().equals(position)
                && query.velocity().equals(velocity);
    }

    private static void requireSameSamplePoint(
            Vec3d expected,
            Vec3d actual,
            String name
    ) {
        if (actual == null
                || actual.subtract(expected).lengthSquared()
                > SAMPLE_POINT_EPSILON_SQUARED) {
            throw new IllegalArgumentException(
                    name + " sample point must match the evaluated query position: "
                            + actual + " vs " + expected
            );
        }
    }
}
