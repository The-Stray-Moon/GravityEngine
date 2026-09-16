package cc.sighs.gravityengine.gravity.field;

import cc.sighs.gravityengine.gravity.model.GravityContribution;
import cc.sighs.gravityengine.gravity.model.GravitySample;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Authoritative composed-field sampler.
 *
 * <p>Field presence is contribution identity, never resultant magnitude.
 * Fields within the effective composition group are always vector-summed.
 * Ordinary ADDITIVE fields compose together; if one or more active OVERRIDE
 * instances exist, the OVERRIDE group becomes authoritative and ordinary
 * additive fields are excluded.</p>
 *
 * <p>No concrete field evaluator type receives special treatment here.</p>
 */
public final class GravityFieldService {
    private GravityFieldService() {}

    /**
     * Explicitly position-only query.
     *
     * <p>Use the full overload at any physics boundary.</p>
     */
    public static GravitySample sample(
            Level level,
            Vec3 samplePoint
    ) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(samplePoint, "samplePoint");

        return sample(
                level,
                samplePoint,
                Vec3.ZERO,
                0L,
                0.0D
        );
    }

    public static GravitySample sample(
            Level level,
            Vec3 samplePoint,
            Vec3 velocity,
            long gameTick,
            double intervalTicks
    ) {
        Objects.requireNonNull(level, "level");
        requireFinite(samplePoint, "samplePoint");
        requireFinite(velocity, "velocity");

        Vector3d queryPoint = new Vector3d(
                samplePoint.x,
                samplePoint.y,
                samplePoint.z
        );

        List<GravityFieldInstance> instances =
                GravityFieldRuntime.get(level)
                        .query(queryPoint);

        return compose(
                instances,
                samplePoint,
                velocity,
                gameTick,
                intervalTicks
        );
    }

    /**
     * Pure deterministic field-composition seam.
     *
     * <p>Composition occurs in two mutually exclusive groups:</p>
     *
     * <ul>
     *     <li>If no active OVERRIDE instance exists, all ADDITIVE instances
     *     are vector-summed.</li>
     *     <li>If any active OVERRIDE instance exists, only OVERRIDE instances
     *     are vector-summed and ADDITIVE instances do not participate.</li>
     * </ul>
     *
     * <p>There is deliberately no priority ordering between fields inside one
     * group. Multiple fields in the effective group always compose by vector
     * addition in structural source order, independently of caller iteration order.</p>
     *
     * <p>Zero acceleration remains a real contribution. Field presence is
     * contribution identity, not resultant magnitude.</p>
     */
    public static GravitySample compose(
            List<GravityFieldInstance> instances,
            Vec3 samplePoint,
            Vec3 velocity,
            long gameTick,
            double intervalTicks
    ) {
        Objects.requireNonNull(
                instances,
                "instances"
        );

        requireFinite(
                samplePoint,
                "samplePoint"
        );

        requireFinite(
                velocity,
                "velocity"
        );

        instances = instances.stream().sorted(GravityFieldInstance.ACCUMULATION_ORDER).toList();

        GravityFieldQuery query =
                new GravityFieldQuery(
                        new Vector3d(
                                samplePoint.x,
                                samplePoint.y,
                                samplePoint.z
                        ),
                        new Vector3d(
                                velocity.x,
                                velocity.y,
                                velocity.z
                        ),
                        gameTick,
                        intervalTicks
                );

        /*
         * Composition class is selected before evaluation.
         *
         * This avoids field-order-dependent semantics such as:
         *
         *     additive -> override -> additive
         *
         * where a sequential "reset accumulator" implementation would make the
         * final result depend on key/iteration order.
         */
        boolean hasOverride = false;

        for (GravityFieldInstance instance : instances) {
            Objects.requireNonNull(
                    instance,
                    "field instance"
            );

            if (instance.compositionMode()
                    == GravityFieldCompositionMode.OVERRIDE) {
                hasOverride = true;
                break;
            }
        }

        GravityFieldCompositionMode effectiveMode =
                hasOverride
                        ? GravityFieldCompositionMode.OVERRIDE
                        : GravityFieldCompositionMode.ADDITIVE;

        Vec3 totalAcceleration = Vec3.ZERO;

        List<GravityContribution> contributions =
                new ArrayList<>(instances.size());

        for (GravityFieldInstance instance : instances) {
            if (instance.compositionMode() != effectiveMode) {
                continue;
            }

            GravityFieldSample fieldSample =
                    Objects.requireNonNull(
                            instance.field().sample(query),
                            "field sample for " + instance.key()
                    );

            Vector3d acceleration =
                    fieldSample.acceleration();

            Vec3 contribution =
                    new Vec3(
                            acceleration.x,
                            acceleration.y,
                            acceleration.z
                    );

            totalAcceleration =
                    totalAcceleration.add(contribution);

            contributions.add(
                    new GravityContribution(
                            instance.key(),
                            contribution,
                            instance.revision()
                    )
            );
        }

        return new GravitySample(
                samplePoint,
                totalAcceleration,
                contributions
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