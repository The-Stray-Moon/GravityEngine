package cc.sighs.gravityengine.gravity.acceleration;

import cc.sighs.gravityengine.gravity.GravityState;
import cc.sighs.gravityengine.gravity.field.GravityFieldService;
import cc.sighs.gravityengine.gravity.minecraft.access.GravityEntityAccess;
import cc.sighs.gravityengine.gravity.model.GravityAccelerationMode;
import cc.sighs.gravityengine.gravity.model.GravityApplicationPlan;
import cc.sighs.gravityengine.gravity.model.GravitySample;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.Objects;

/**
 * Single authority for selecting NONE, FIELD or DIRECT acceleration.
 */
public final class GravityAccelerationResolver {
    private GravityAccelerationResolver() {}

    public record Resolution(
            Vec3 acceleration,
            boolean replacesVanilla,
            GravityAccelerationMode mode,
            GravitySample sample
    ) {
        public Resolution {
            Objects.requireNonNull(
                    acceleration,
                    "acceleration"
            );
            Objects.requireNonNull(
                    mode,
                    "mode"
            );
            Objects.requireNonNull(
                    sample,
                    "sample"
            );
        }
    }

    public static Resolution resolve(
            AccelerationQuery query,
            GravityApplicationPlan plan,
            Vec3 vanillaAcceleration
    ) {
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(plan, "plan");

        requireFinite(
                vanillaAcceleration,
                "vanillaAcceleration"
        );

        return switch (plan.accelerationMode()) {
            case NONE ->
                    preserveVanilla(
                            query.samplePoint(),
                            vanillaAcceleration
                    );

            case FIELD ->
                    resolveField(
                            sampleForPlan(
                                    query,
                                    plan
                            ),
                            vanillaAcceleration
                    );

            case DIRECT -> {
                GravityState state = appliedGravity(query.entity());

                yield new Resolution(
                        state.downAt(
                                query.samplePoint()
                        ).scale(
                                state.strength()
                        ),
                        true,
                        GravityAccelerationMode.DIRECT,
                        GravitySample.fromState(
                                state,
                                query.samplePoint()
                        )
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
            GravityApplicationPlan plan
    ) {
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(plan, "plan");

        return switch (plan.accelerationMode()) {
            case NONE ->
                    GravitySample.zero(
                            query.samplePoint()
                    );

            case FIELD ->
                    GravityFieldService.sample(
                            query.entity().level(),
                            query.samplePoint(),
                            query.velocity(),
                            query.gameTick(),
                            query.intervalTicks()
                    );

            case DIRECT ->
                    GravitySample.fromState(
                            appliedGravity(query.entity()),
                            query.samplePoint()
                    );
        };
    }

    /**
     * Canonical character-body operation evidence resolution.
     *
     * <p>This is the single environmental evidence boundary of an outer
     * character operation: FIELD authority performs exactly one composed
     * {@link GravityFieldService} query here, and DIRECT/NONE authority
     * resolves one immutable authoritative-state evidence snapshot without
     * querying the field service.  Acceleration and reference-orientation
     * semantics are derived from the returned evidence by pure
     * interpretation functions and must never trigger a second world
     * query.</p>
     *
     * <p>{@code accelerationMode = NONE} means vanilla or an external owner
     * integrates acceleration; it does NOT mean the environment has zero
     * gravity.  The evidence therefore keeps the applied reference state
     * while {@link #characterAcceleration} zeroes the acceleration sample so
     * gravity is never double-integrated.</p>
     */
    public static GravitySample sampleCharacterOperationEvidence(
            AccelerationQuery query,
            GravityApplicationPlan plan
    ) {
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(plan, "plan");

        GravityState applied = appliedGravity(query.entity());

        return switch (plan.accelerationMode()) {
            case NONE, DIRECT ->
                    GravitySample.fromState(
                            applied,
                            query.samplePoint()
                    );

            case FIELD ->
                    selectCharacterFieldSample(
                            GravityFieldService.sample(
                                    query.entity().level(),
                                    query.samplePoint(),
                                    query.velocity(),
                                    query.gameTick(),
                                    query.intervalTicks()
                            ),
                            applied,
                            query.samplePoint()
                    );
        };
    }

    /** Internal component read; never routes back through the public façade. */
    private static GravityState appliedGravity(Entity entity) {
        return GravityEntityAccess.cast(entity)
                .gravityengine$gravityComponent().appliedState();
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
            Vec3 samplePoint
    ) {
        Objects.requireNonNull(live, "live");
        Objects.requireNonNull(
                committedFieldState,
                "committedFieldState"
        );

        requireFinite(
                samplePoint,
                "samplePoint"
        );

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
            Vec3 vanillaAcceleration
    ) {
        Objects.requireNonNull(sample, "sample");

        requireFinite(
                vanillaAcceleration,
                "vanillaAcceleration"
        );

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

    private static AccelerationQuery oneTickQuery(
            Entity entity,
            Vec3 samplePoint
    ) {
        requireFinite(
                samplePoint,
                "samplePoint"
        );

        return new AccelerationQuery(
                entity,
                samplePoint,
                entity.getDeltaMovement(),
                entity.level().getGameTime(),
                1.0D
        );
    }

    private static Resolution preserveVanilla(
            Vec3 samplePoint,
            Vec3 vanillaAcceleration
    ) {
        return new Resolution(
                vanillaAcceleration,
                false,
                GravityAccelerationMode.NONE,
                GravitySample.zero(
                        samplePoint
                )
        );
    }

    private static void requireFinite(
            Vec3 value,
            String name
    ) {
        Objects.requireNonNull(value, name);

        if (!Double.isFinite(value.x)
                || !Double.isFinite(value.y)
                || !Double.isFinite(value.z)) {
            throw new IllegalArgumentException(
                    name
                            + " must be finite: "
                            + value
            );
        }
    }
}
