package cc.sighs.gravityengine.gravity.integration;

import cc.sighs.gravityengine.gravity.acceleration.GravityEvaluationContexts;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.GravityFrame;
import cc.sighs.gravityengine.gravity.acceleration.*;
import cc.sighs.gravityengine.gravity.minecraft.access.GravityEntityAccess;
import cc.sighs.gravityengine.gravity.minecraft.access.GravityFallingBlockAccess;
import cc.sighs.gravityengine.gravity.model.GravityAccelerationMode;
import cc.sighs.gravityengine.gravity.model.GravitySample;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.item.FallingBlockEntity;

/**
 * Server-authored FallingBlock ballistic authority.
 *
 * <p>Acceleration authority and collision-operation authority are independent:
 *
 * <ul>
 *     <li>{@code customAcceleration == true}: GE supplies gravity acceleration.</li>
 *     <li>{@code engineCollision == true}: GE owns the frozen collision operation.</li>
 * </ul>
 *
 * <p>This deliberately permits:
 *
 * <pre>
 * customAcceleration = false
 * engineCollision     = true
 * </pre>
 *
 * for native -Y gravity interacting with an external rigid collision provider.
 *
 * <p>The last synchronized snapshot remains authoritative until replaced.
 * Clients never reconstruct field authority from a local field registry.
 */
public record FallingBlockBallisticSnapshot(
        long sampleTick,
        Direction down,
        double magnitude,
        boolean customAcceleration,
        boolean engineCollision
) {
    public FallingBlockBallisticSnapshot {
        java.util.Objects.requireNonNull(down, "down");

        if (!Double.isFinite(magnitude) || magnitude < 0.0D) {
            throw new IllegalArgumentException(
                    "invalid falling-block acceleration magnitude"
            );
        }
    }

    /**
     * Pure Vanilla authority:
     *
     * <pre>
     * acceleration = vanilla -Y
     * collision    = vanilla
     * </pre>
     */
    public static FallingBlockBallisticSnapshot nativeGravity(long tick) {
        return nativeGravity(tick, false);
    }

    /**
     * Native acceleration with independently selected collision ownership.
     */
    public static FallingBlockBallisticSnapshot nativeGravity(
            long tick,
            boolean engineCollision
    ) {
        return new FallingBlockBallisticSnapshot(
                tick,
                Direction.DOWN,
                0.04D,
                false,
                engineCollision
        );
    }

    /**
     * Convert one authoritative evaluation into the six-axis FallingBlock
     * ballistic representation.
     *
     * @param engineCollision whether this tick owns a GE collision operation
     */
    public static FallingBlockBallisticSnapshot from(
            GravityEvaluationSnapshot evaluation,
            boolean engineCollision
    ) {
        var mode =
                evaluation
                        .committedApplication()
                        .plan()
                        .accelerationMode();

        if (mode == GravityAccelerationMode.NONE
                || mode == GravityAccelerationMode.FIELD
                && !evaluation.hasActiveField()
                && !(evaluation.fieldCoverage() == cc.sighs.gravityengine.api.field.FieldCoverage.INCOMPLETE
                && evaluation.authority().fieldPresent())) {
            return nativeGravity(
                    evaluation.gameTick(),
                    engineCollision
            );
        }

        Vec3d vector =
                evaluation.effectiveAcceleration();

        /*
         * Direction comes from the already-stabilized GravityFrame, not from the
         * raw resultant.
         *
         * GravityFrame already owns the attach/detach confidence policy and keeps
         * the previous completed direction through cancellation bands.
         */
        Direction down =
                FallingBlockStartIntegration
                        .dominantDown(
                                evaluation.frame().down()
                        );

        if (down == null) {
            throw new IllegalStateException(
                    "authoritative gravity frame has no discrete direction"
            );
        }

        return new FallingBlockBallisticSnapshot(
                evaluation.gameTick(),
                down,
                vector.length(),
                true,
                engineCollision
        );
    }

    /**
     * Acceleration used by the frozen FallingBlock operation.
     *
     * <p>This is needed even when {@code customAcceleration == false}, because
     * a GE collision operation still needs the same frame that Vanilla gravity
     * will produce before movement.
     */
    public Vec3d acceleration() {
        if (magnitude == 0.0D) {
            return Vec3d.ZERO;
        }

        var normal = down.getNormal();

        return new Vec3d(
                normal.getX(),
                normal.getY(),
                normal.getZ()
        ).multiply(magnitude);
    }

    /**
     * sampleTick is intentionally excluded: unchanged physical authority does
     * not require periodic SynchedEntityData traffic.
     */
    public boolean samePhysics(
            FallingBlockBallisticSnapshot other
    ) {
        return other != null
                && down == other.down
                && magnitude == other.magnitude
                && customAcceleration
                == other.customAcceleration
                && engineCollision
                == other.engineCollision;
    }

    public CompoundTag encode() {
        CompoundTag tag = new CompoundTag();

        tag.putLong("Tick", sampleTick);
        tag.putByte(
                "Down",
                (byte) down.get3DDataValue()
        );
        tag.putDouble("Magnitude", magnitude);

        tag.putBoolean(
                "Custom",
                customAcceleration
        );

        tag.putBoolean(
                "EngineCollision",
                engineCollision
        );

        return tag;
    }

    public static FallingBlockBallisticSnapshot decode(
            CompoundTag tag
    ) {
        if (tag.isEmpty()) {
            // Spawn received before first authoritative server sample.
            return null;
        }

        int axis = tag.getByte("Down");

        if (axis < 0
                || axis >= 6
                || !tag.contains("Down", CompoundTag.TAG_BYTE)
                || !tag.contains("Custom", CompoundTag.TAG_BYTE)
                || !tag.contains("EngineCollision", CompoundTag.TAG_BYTE)
                || !tag.contains("Magnitude", CompoundTag.TAG_DOUBLE)
                || !tag.contains("Tick", CompoundTag.TAG_LONG)) {
            throw new IllegalArgumentException(
                    "invalid falling-block ballistic snapshot"
            );
        }

        boolean customAcceleration =
                tag.getBoolean("Custom");

        boolean engineCollision = tag.getBoolean("EngineCollision");

        return new FallingBlockBallisticSnapshot(
                tag.getLong("Tick"),
                Direction.from3DDataValue(axis),
                tag.getDouble("Magnitude"),
                customAcceleration,
                engineCollision
        );
    }

    /**
     * Build the immutable operation evaluation.
     *
     * <p>Server evaluates the real field once. Client consumes only the
     * synchronized server result and never creates or queries a field registry.
     */
    public static GravityEvaluationSnapshot evaluate(
            FallingBlockEntity entity,
            AccelerationQuery query
    ) {
        var component =
                GravityEntityAccess.cast(entity)
                        .gravityengine$gravityComponent();

        var coverage = cc.sighs.gravityengine.api.field.FieldCoverage.COMPLETE;
        GravityEvaluationContext context;
        GravitySample evidence;
        FallingBlockBallisticSnapshot ballistic;

        if (entity.level().isClientSide) {
            ballistic =
                    ((GravityFallingBlockAccess) entity)
                            .gravityengine$ballisticSnapshot();

            if (ballistic == null) {
                throw new IllegalStateException(
                        "missing falling-block ballistic authority"
                );
            }

            if (!ballistic.engineCollision()) {
                throw new IllegalStateException(
                        "falling-block operation opened without collision authority"
                );
            }

            context =
                    GravityEvaluationContexts.captureRemoteResult(
                            component.state());

            evidence = new GravitySample(
                    query.samplePoint(),
                    ballistic.acceleration(),
                    java.util.List.of()
            );
        } else {
            var registry =
                    cc.sighs.gravityengine.gravity.field
                            .GravityFieldRuntime
                            .get(entity.level());

            GravityEvaluationSnapshot sampled =
                    GravityEvaluationService.evaluateForPlan(
                            GravityEvaluationContexts.capture(
                                    component.state(),
                                    registry
                            ),
                            registry,
                            query,
                            component.state()
                                    .appliedState()
                                    .down(),
                            component.operationState()
                                    .lastCompletedFrame()
                    );

            /*
             * This method is reached only while opening a GE FallingBlock
             * operation, therefore collision ownership for this evaluation is
             * true even when acceleration remains native -Y.
             */
            ballistic = from(sampled, true);

            coverage = sampled.fieldCoverage();
            context = sampled.context();
            evidence = sampled.evidence();
        }

        GravitySample acceleration =
                new GravitySample(
                        query.samplePoint(),
                        ballistic.acceleration(),
                        evidence.contributions()
                );

        GravityFrame frame =
                GravityFrame.fromEnvironmentalEvidence(
                        query.samplePoint(),
                        ballistic.acceleration(),
                        new Vec3d(
                                ballistic.down().getStepX(),
                                ballistic.down().getStepY(),
                                ballistic.down().getStepZ()
                        ),
                        null
                );

        return new GravityEvaluationSnapshot(
                context,
                new cc.sighs.gravityengine.api.field.GravityFieldQuery(
                        query.samplePoint(),
                        query.velocity(),
                        query.gameTick(),
                        query.intervalTicks()
                ),
                evidence,
                acceleration,
                frame,
                query.gameTick(),
                query.intervalTicks(), coverage
        );
    }
}