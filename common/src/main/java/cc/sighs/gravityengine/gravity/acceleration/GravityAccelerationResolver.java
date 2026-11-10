package cc.sighs.gravityengine.gravity.acceleration;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.GravityState;
import cc.sighs.gravityengine.gravity.field.GravityFieldRegistry;
import cc.sighs.gravityengine.gravity.field.GravityFieldService;
import cc.sighs.gravityengine.gravity.model.GravityAccelerationMode;
import cc.sighs.gravityengine.gravity.model.GravityApplicationPlan;
import cc.sighs.gravityengine.gravity.model.GravitySample;

import java.util.Objects;

/**
 * Single authority for selecting NONE, FIELD or DIRECT acceleration.
 *
 * <p>The world scope is supplied explicitly as the owning loader-neutral
 * {@link GravityFieldRegistry}. No platform entity or level reference reaches
 * this resolver, so the same selection and evidence logic runs for every
 * loader target.</p>
 */
public final class GravityAccelerationResolver {
    private GravityAccelerationResolver() {}

    public record Resolution(
            Vec3d acceleration,
            boolean replacesVanilla,
            GravityAccelerationMode mode,
            GravitySample sample
    ) {
        public Resolution {
            Objects.requireNonNull(acceleration, "acceleration");
            Objects.requireNonNull(mode, "mode");
            Objects.requireNonNull(sample, "sample");
        }
    }

    public static Resolution resolve(
            AccelerationQuery query,
            GravityApplicationPlan plan,
            Vec3d vanillaAcceleration,
            GravityFieldRegistry registry
    ) {
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(plan, "plan");

        requireFinite(vanillaAcceleration, "vanillaAcceleration");

        return switch (plan.accelerationMode()) {
            case NONE ->
                    preserveVanilla(
                            query.samplePoint(),
                            vanillaAcceleration
                    );

            case FIELD ->
                    resolveField(
                            sampleForPlan(query, plan, registry),
                            vanillaAcceleration
                    );

            case DIRECT -> {
                GravityState state = query.appliedGravity();

                yield new Resolution(
                        state.downAt(query.samplePoint())
                                .multiply(state.strength()),
                        true,
                        GravityAccelerationMode.DIRECT,
                        GravitySample.fromState(state, query.samplePoint())
                );
            }
        };
    }

    /**
     * Canonical physics-boundary sampling API.
     *
     * <p>FIELD consumes the complete AccelerationQuery. No velocity, clock or
     * interval information may be discarded at this boundary.</p>
     */
    public static GravitySample sampleForPlan(
            AccelerationQuery query,
            GravityApplicationPlan plan,
            GravityFieldRegistry registry
    ) {
        return GravityEvaluationService.fieldEvidence(
                query,
                plan,
                registry
        );
    }

    /**
     * Canonical character-body operation evidence resolution.
     *
     * <p>This is the single environmental evidence boundary of an outer
     * character operation: FIELD authority performs exactly one composed
     * {@link GravityFieldService} query here, and DIRECT/NONE authority
     * resolves one immutable authoritative-state evidence snapshot without
     * querying the field service. Acceleration and reference-orientation
     * semantics are derived from the returned evidence by pure
     * interpretation functions and must never trigger a second world
     * query.</p>
     *
     * <p>{@code accelerationMode = NONE} means vanilla or an external owner
     * integrates acceleration; it does NOT mean the environment has zero
     * gravity. The evidence therefore keeps the applied reference state
     * while {@link #characterAcceleration} zeroes the acceleration sample so
     * gravity is never double-integrated.</p>
     */
    public static GravitySample sampleCharacterOperationEvidence(
            AccelerationQuery query,
            GravityApplicationPlan plan,
            GravityFieldRegistry registry
    ) {
        return GravityEvaluationService.characterEvidence(
                query,
                plan,
                registry
        );
    }

    /**
     * Pure acceleration interpretation of one immutable evidence sample.
     *
     * <p>Under NONE authority the evidence retains the applied reference
     * state for orientation purposes while the acceleration contribution is
     * zero.  This function never samples the world.</p>
     */
    public static GravitySample characterAcceleration(
            GravitySample evidence,
            GravityApplicationPlan plan
    ) {
        Objects.requireNonNull(evidence, "evidence");
        Objects.requireNonNull(plan, "plan");
        if (plan.accelerationMode() == GravityAccelerationMode.NONE) {
            return GravitySample.zero(evidence.samplePoint());
        }
        return evidence;
    }

    /**
     * Field presence is contribution identity, not resultant magnitude.
     */
    public static GravitySample selectCharacterFieldSample(
            GravitySample live,
            GravityState committedFieldState,
            Vec3d samplePoint
    ) {
        Objects.requireNonNull(live, "live");
        Objects.requireNonNull(
                committedFieldState,
                "committedFieldState"
        );

        requireFinite(samplePoint, "samplePoint");

        if (live.hasActiveField()) {
            return live;
        }

        if (!committedFieldState.isDefault()) {
            return GravitySample.fromState(
                    committedFieldState,
                    samplePoint
            );
        }

        return live;
    }

    /**
     * Active cancellation to zero still replaces Vanilla gravity.
     */
    public static Resolution resolveField(
            GravitySample sample,
            Vec3d vanillaAcceleration
    ) {
        Objects.requireNonNull(sample, "sample");

        requireFinite(vanillaAcceleration, "vanillaAcceleration");

        if (!sample.hasActiveField()) {
            return preserveVanilla(
                    sample.samplePoint(),
                    vanillaAcceleration
            );
        }

        return new Resolution(
                sample.accelerationVector(),
                true,
                GravityAccelerationMode.FIELD,
                sample
        );
    }

    private static Resolution preserveVanilla(
            Vec3d samplePoint,
            Vec3d vanillaAcceleration
    ) {
        return new Resolution(
                vanillaAcceleration,
                false,
                GravityAccelerationMode.NONE,
                GravitySample.zero(samplePoint)
        );
    }

    private static void requireFinite(Vec3d value, String name) {
        Objects.requireNonNull(value, name);

        if (!value.isFinite()) {
            throw new IllegalArgumentException(
                    name + " must be finite: " + value
            );
        }
    }
}
