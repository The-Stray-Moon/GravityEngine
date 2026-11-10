package cc.sighs.gravityengine.gravity.integration;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.minecraft.access.GravityEntityAccess;
import cc.sighs.gravityengine.gravity.minecraft.collision.MinecraftCollisionGeometryAdapter;
import cc.sighs.gravityengine.gravity.minecraft.math.MinecraftMathAdapter;
import cc.sighs.gravityengine.gravity.collision.CollisionCaptureDomain;
import cc.sighs.gravityengine.gravity.policy.GravityInfluencePolicy;
import net.minecraft.world.entity.item.FallingBlockEntity;
import cc.sighs.gravityengine.gravity.minecraft.access.GravityFallingBlockAccess;

/** One ballistic tick owns evaluation and collision scene. */
public final class FallingBlockTickIntegration {
    private FallingBlockTickIntegration() {}

    // Invocation scope restores its caller on nested ticks and releases entity references.
    private static final ThreadLocal<TickScope> CURRENT = new ThreadLocal<>();
    public static final class TickScope implements AutoCloseable {
        private final FallingBlockEntity entity;
        private final TickScope previous;
        private boolean unavailable;
        private FallingBlockLandingContext landing;
        private net.minecraft.world.phys.Vec3 beforeGravity;
        private Runnable nativeGravity;
        private TickScope(FallingBlockEntity entity) {
            this.entity = entity;
            previous = CURRENT.get();
            CURRENT.set(this);
        }
        @Override public void close() {
            try {
                var runtime = GravityEntityAccess.cast(entity).gravityengine$gravityComponent().operationState();
                if (!runtime.isInMove()) {
                    runtime.clearPersistentSupportState();
                    runtime.clearRestingContactSnapshot();
                    if (unavailable) EntityMovementIntegration.invalidateMovementContinuity(entity);
                }
            } finally {
                if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
            }
        }
    }
    public static TickScope beginTick(FallingBlockEntity entity) { return new TickScope(entity); }
    public static boolean isUnavailable(net.minecraft.world.entity.Entity entity) {
        var tick = CURRENT.get();
        return tick != null && tick.entity == entity && tick.unavailable;
    }
    public static void unavailable(net.minecraft.world.entity.Entity entity) {
        unavailable(entity, null);
    }

    public static void unavailable(
            net.minecraft.world.entity.Entity entity,
            RuntimeException failure
    ) {
        var tick = CURRENT.get();

        if (tick == null || tick.entity != entity) {
            throw new IllegalStateException(
                    "missing falling tick owner"
            );
        }

        tick.unavailable = true;

        if (failure != null) {
            cc.sighs.gravityengine.gravity.debug
                    .CollisionCoverageDiagnostics
                    .report(entity, failure);
        }

        if (!entity.level().isClientSide) {
            ((GravityFallingBlockAccess) entity)
                    .gravityengine$publishBallisticSnapshot(
                            FallingBlockBallisticSnapshot.nativeGravity(
                                    entity.level().getGameTime(),
                                    false
                            )
                    );
        }
    }
    public static FallingBlockLandingContext landing(FallingBlockEntity entity) {
        var tick = CURRENT.get();
        var runtime = GravityEntityAccess.cast(entity).gravityengine$gravityComponent().operationState();
        return tick == null || tick.entity != entity || tick.unavailable
                || runtime.discontinuityDestination() != null ? null : tick.landing;
    }

    public static GravityOperation open(
            FallingBlockEntity entity
    ) {
        boolean externalCollision;

        if (entity.level().isClientSide) {
            FallingBlockBallisticSnapshot snapshot =
                    ((GravityFallingBlockAccess) entity)
                            .gravityengine$ballisticSnapshot();

            /*
             * Collision-operation authority comes from the server snapshot.
             *
             * customAcceleration is deliberately NOT consulted here:
             *
             * native -Y acceleration + external rigid collision
             *
             * is a valid GE operation.
             */
            if (snapshot == null
                    || !snapshot.engineCollision()) {
                unavailable(entity);
                return null;
            }

            externalCollision = false;
        } else {
            externalCollision =
                    GravityInfluencePolicy
                            .hasExternalCollisionProviders(entity);
        }

        if (entity.level().isClientSide) {
            GravityApplicationCoordinator.updateReplicaBody(entity);
        } else {
            GravityApplicationCoordinator.updateBody(entity);
        }

        if (entity.isRemoved()
                || entity.getBlockState().isAir()
                || entity.noPhysics
                || entity.isPassenger()) {
            unavailable(entity);
            return null;
        }

        var bounds =
                MinecraftCollisionGeometryAdapter.toAabb3d(
                        entity.getBoundingBox()
                );

        Vec3d velocity =
                MinecraftMathAdapter.toVec3d(
                        entity.getDeltaMovement()
                );

        var samplePoint =
                FallingBlockSampling.entityCenter(entity);

        GravityOperation operation =
                GravityOperation.openFallingBlock(
                        entity,
                        samplePoint,
                        externalCollision,
                        (sample, frame) ->
                                CollisionCaptureDomain.forTranslation(
                                        bounds,
                                        velocity.add(
                                                entity.isNoGravity()
                                                        ? Vec3d.ZERO
                                                        : frame.down()
                                                        .multiply(
                                                                frame.strength()
                                                        )
                                        ),
                                        0
                                )
                );

        if (operation == null) {
            unavailable(entity);
            return null;
        }

        if (!entity.level().isClientSide) {
            /*
             * Successful open means GE collision-operation authority exists for
             * this tick, independently of who supplies acceleration.
             */
            ((GravityFallingBlockAccess) entity)
                    .gravityengine$publishBallisticSnapshot(
                            FallingBlockBallisticSnapshot.from(
                                    operation.gravitySnapshot(),
                                    true
                            )
                    );
        }

        return operation;
    }

    /** Only thrown before Entity.move commits position, flags or callbacks. */
    public static final class MoveUnavailable extends RuntimeException {
        public boolean restored;
        public MoveUnavailable(RuntimeException cause) { super(cause); }
    }
    public static void retryNativeMove(FallingBlockEntity entity, MoveUnavailable failure) {
        var tick = CURRENT.get();
        if (tick == null || tick.entity != entity || tick.nativeGravity == null) throw failure;
        unavailable(entity, failure);
        entity.setDeltaMovement(tick.beforeGravity);
        tick.nativeGravity.run();
    }
    public static boolean applyGravity(
            FallingBlockEntity entity,
            Runnable nativeGravity
    ) {
        TickScope tick = CURRENT.get();

        if (tick == null || tick.entity != entity) {
            throw new IllegalStateException(
                    "missing falling-block tick owner"
            );
        }

        tick.beforeGravity = entity.getDeltaMovement();
        tick.nativeGravity = nativeGravity;

        FallingBlockBallisticSnapshot snapshot =
                ((GravityFallingBlockAccess) entity)
                        .gravityengine$ballisticSnapshot();

        /*
         * Client spawn can precede the first SynchedEntityData update.
         *
         * Do not inject Vanilla -Y during this tiny window because the server may
         * shortly tell us that the FallingBlock belongs to another gravity axis.
         */
        if (entity.level().isClientSide
                && snapshot == null) {
            return true;
        }

        /*
         * GE explicitly gave up this operation.
         *
         * Caller executes the untouched Vanilla applyGravity().
         */
        if (isUnavailable(entity)) {
            return false;
        }

        /*
         * Collision ownership does not imply acceleration ownership.
         *
         * Important case:
         *
         *     customAcceleration = false
         *     engineCollision    = true
         *
         * Keep the active frozen GE collision scene, but execute Vanilla's exact
         * gravity method instead of reproducing -0.04 here.
         */
        if (snapshot != null
                && !snapshot.customAcceleration()) {
            return false;
        }

        if (!entity.isNoGravity()) {
            var evaluation =
                    GravityEntityAccess.cast(entity)
                            .gravityengine$gravityComponent()
                            .operationState()
                            .activeOperationEvaluation()
                            .orElseThrow();

            entity.setDeltaMovement(
                    entity.getDeltaMovement().add(
                            MinecraftMathAdapter.toMinecraft(
                                    evaluation.effectiveAcceleration()
                            )
                    )
            );
        }

        return true;
    }

    /**
     * Snapshot the completed physical landing before Vanilla's installation
     * transaction.
     *
     * <p>The optional second field sample is a discrete Entity -> Block
     * conversion validation. It does not modify or re-open the movement
     * operation's frozen gravity evaluation.
     */
    public static void afterMove(
            FallingBlockEntity entity
    ) {
        TickScope tick = CURRENT.get();

        if (tick == null
                || tick.entity != entity
                || tick.unavailable) {
            return;
        }

        FallingBlockLandingContext landing =
                FallingBlockPlacementIntegration
                        .fromMove(entity);

        /*
         * Raw collision geometry never authorizes block conversion by itself.
         *
         * Client:
         *     may predict physical collision,
         *     never authorizes Entity -> Block.
         *
         * Server:
         *     static installation is accepted only if the resulting block would
         *     NOT immediately satisfy the exact Block -> Entity start predicate.
         */
        if (landing != null
                && landing.installable()) {
            boolean conversionAllowed = false;

            if (!entity.level().isClientSide) {
                var level =
                        (net.minecraft.server.level.ServerLevel)
                                entity.level();

                conversionAllowed =
                        !FallingBlockStartIntegration
                                .wouldStartFalling(
                                        level,
                                        landing.placementPos()
                                );
            }

            landing =
                    landing.withBlockConversionAllowed(
                            conversionAllowed
                    );
        }

        tick.landing = landing;
    }
}
