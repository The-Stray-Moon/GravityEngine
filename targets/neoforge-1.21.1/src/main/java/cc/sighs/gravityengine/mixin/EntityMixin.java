package cc.sighs.gravityengine.mixin;

import cc.sighs.gravityengine.gravity.integration.EntityMovementIntegration;

import cc.sighs.gravityengine.gravity.GravityFrame;
import cc.sighs.gravityengine.gravity.collision.GravityMoveResult;
import cc.sighs.gravityengine.gravity.component.EntityGravityComponent;
import cc.sighs.gravityengine.gravity.integration.*;
import cc.sighs.gravityengine.gravity.integration.compat.sable.SableMovementCompatibility;
import cc.sighs.gravityengine.gravity.integration.vanilla.VanillaBodySensors;
import cc.sighs.gravityengine.gravity.minecraft.GravityFrameAccess;
import cc.sighs.gravityengine.gravity.minecraft.access.GravityEntityAccess;
import cc.sighs.gravityengine.gravity.minecraft.geometry.GravityEntityGeometry;
import cc.sighs.gravityengine.gravity.minecraft.math.MinecraftMathAdapter;
import cc.sighs.gravityengine.gravity.model.GravityCollisionRoute;
import cc.sighs.gravityengine.gravity.policy.GravityInfluencePolicy;
import cc.sighs.gravityengine.gravity.runtime.GravityOperationState;
import cc.sighs.gravityengine.gravity.runtime.VanillaCollisionState;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Slice;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

/**
 * Ordering contract with an optional third-party {@code Entity.move} owner.
 *
 * <p>Verified against Sable 2.0.5 in the local compatibility environment:
 * Mixin applies mixins for the same target in ascending priority order, so
 * priority 1000 is applied before Sable's priority-1100 collision Redirect.
 * GravityEngine must not preempt that Redirect: Sable owns the replacement of the
 * {@code Entity.move -> Entity.collide} invocation, and GravityEngine owns the
 * {@code Entity.collide} method entry seam that Sable's Redirect calls into.
 * Raising this priority would apply GravityEngine after Sable's own
 * {@code setOnGroundWithMovement} wrap and would flip that chain, so Sable's
 * handler rather than GravityEngine's authoritative contact truth would decide the
 * flag Vanilla consumes.</p>
 *
 * <p>MixinExtras applies {@code @WrapMethod} after every other injector on the
 * same target method, so {@code gravityengine$moveWithGravity} always wraps the
 * fully transformed {@code Entity.move}, including Sable's SubLevel collision
 * Redirect. GravityEngine therefore keeps one outer movement scope without owning
 * the Vanilla collision call site.</p>
 *
 * <p>Every injection point in this class is deliberately anchored on a
 * Vanilla-only instruction rather than on an instruction an optional movement
 * mod may replace.</p>
 */
@Mixin(value = Entity.class, priority = 1000)
public abstract class EntityMixin implements GravityEntityAccess {
    /** 21.1.249: after material selection, pos.y != resolved.y is solely the
     * native vertical-response gate. Supply contact truth without changing the
     * displacement consumed by fall damage, emission or position commit.
     *
     * <p>The window starts at Vanilla's last pre-collision preprocessing call
     * and ends at the movement-emission query directly after the fall/step
     * callbacks. Both landmarks belong to Vanilla alone, so the window and the
     * two {@code Vec3.y} reads inside it are unaffected by an optional mod that
     * replaces the collision call or the fall response. */
    @WrapOperation(method="move", at=@At(value="FIELD", target="Lnet/minecraft/world/phys/Vec3;y:D", ordinal=0),
            slice=@Slice(from=@At(value="INVOKE", target="Lnet/minecraft/world/level/block/state/BlockState;getBlock()Lnet/minecraft/world/level/block/Block;"),
                    to=@At(value="INVOKE", target="Lnet/minecraft/world/entity/Entity;getMovementEmission()Lnet/minecraft/world/entity/Entity$MovementEmission;")))
    private double gravityengine$verticalResponseRequested(Vec3 vector, Operation<Double> original) {
        var entity = (Entity) (Object) this;
        if (!EntityMovementIntegration.engineOwnsActiveMovement(entity))
            return original.call(vector);
        return EntityMovementIntegration.movementVerticalResponse(entity) ? 1 : 0;
    }
    @WrapOperation(method="move", at=@At(value="FIELD", target="Lnet/minecraft/world/phys/Vec3;y:D", ordinal=1),
            slice=@Slice(from=@At(value="INVOKE", target="Lnet/minecraft/world/level/block/state/BlockState;getBlock()Lnet/minecraft/world/level/block/Block;"),
                    to=@At(value="INVOKE", target="Lnet/minecraft/world/entity/Entity;getMovementEmission()Lnet/minecraft/world/entity/Entity$MovementEmission;")))
    private double gravityengine$verticalResponseResolved(Vec3 vector, Operation<Double> original) {
        return !EntityMovementIntegration.engineOwnsActiveMovement((Entity) (Object) this)
                ? original.call(vector) : 0;
    }

    /**
     * Preserve native jump attributes, boost, threshold and event ordering.
     */
    @Inject(
            method = "getBlockJumpFactor",
            at = @At("HEAD"),
            cancellable = true,
            require = 1
    )
    private void gravityengine$contactJumpFactor(
            CallbackInfoReturnable<Float> cir
    ) {
        BlockContactResolver.jumpFactor((Entity) (Object) this)
                .ifPresent(cir::setReturnValue);
    }

    /** Native material response has consumed impact velocity; outward bounce
     * satisfies unilateral contacts and survives the complete world projection. */
    @Inject(method="move", at=@At(value="INVOKE", target="Lnet/minecraft/world/entity/Entity;getMovementEmission()Lnet/minecraft/world/entity/Entity$MovementEmission;"))
    private void gravityengine$contactAfterCallbacks(MoverType type, Vec3 movement, CallbackInfo ci) {
        ContactVelocityIntegration.commitMoveContactVelocity((Entity) (Object) this);
    }
    /** Retain native block enumeration and callback order; both callbacks for
     * one cell consume the same exact-body decision from this invocation. */
    @WrapOperation(method = "checkInsideBlocks", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/state/BlockState;entityInside(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/entity/Entity;)V"))
    private void gravityengine$exactInside(BlockState state, Level level, BlockPos pos, Entity entity,
            Operation<Void> original,
            @com.llamalad7.mixinextras.sugar.Share("insideBody") com.llamalad7.mixinextras.sugar.ref.LocalRef<cc.sighs.gravityengine.gravity.kinematic.geometry.CollisionBody> body,
            @com.llamalad7.mixinextras.sugar.Share("insideCell") com.llamalad7.mixinextras.sugar.ref.LocalBooleanRef accepted) {
        boolean custom = GravityInfluencePolicy.usesCustomBody(entity);
        if (custom && body.get() == null) body.set(cc.sighs.gravityengine.gravity.integration.vanilla.VanillaBodySensors.captureBody(entity));
        accepted.set(gravityengine$gravityComponent.operationState().discontinuityDestination() == null
                && (!custom || cc.sighs.gravityengine.gravity.integration.vanilla.VanillaBodySensors.occupiesCell(body.get(), pos)));
        if (accepted.get()) original.call(state, level, pos, entity);
    }

    @WrapOperation(method = "checkInsideBlocks", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;onInsideBlock(Lnet/minecraft/world/level/block/state/BlockState;)V"))
    private void gravityengine$exactInsideNotification(Entity entity, BlockState state, Operation<Void> original,
            @com.llamalad7.mixinextras.sugar.Share("insideCell") com.llamalad7.mixinextras.sugar.ref.LocalBooleanRef accepted) {
        if (accepted.get()) original.call(entity, state);
    }
    /** Vanilla retains slow-fall distance thresholds; the operand is environmental vertical speed. */
    @WrapOperation(method = "checkSlowFallDistance", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/phys/Vec3;y()D"), require = 1)
    private double gravityengine$slowFallVertical(Vec3 velocity, Operation<Double> original) {
        Entity entity = (Entity) (Object) this;
        return GravityInfluencePolicy.usesCustomLocomotion(entity)
                ? cc.sighs.gravityengine.gravity.minecraft.GravityFrameAccess.authoritativeFrame(entity)
                        .worldToLocal(
                                MinecraftMathAdapter.toVec3d(velocity))
                        .y()
                : original.call(velocity);
    }

    @Shadow protected abstract float getBlockSpeedFactor();
    @Shadow protected abstract void checkFallDamage(double y, boolean onGround, BlockState state, BlockPos pos);
    @Shadow public Optional<BlockPos> mainSupportingBlockPos;
    @Shadow private boolean onGround;
    @Shadow public boolean horizontalCollision;
    @Shadow public boolean verticalCollision;
    @Shadow public boolean verticalCollisionBelow;
    @Shadow public boolean minorHorizontalCollision;
    @Shadow private boolean onGroundNoBlocks;
    @Shadow private net.minecraft.world.entity.EntityDimensions dimensions;
    @Shadow private float eyeHeight;
    /** Pose belonging to the installed dimensions; native DATA_POSE is only a proposal. */
    @Unique private net.minecraft.world.entity.Pose gravityengine$installedDimensionPose = net.minecraft.world.entity.Pose.STANDING;
    /** Native Size notification awaiting the designated body boundary; never serialized. */
    @Unique private boolean gravityengine$dimensionsPending;

    @Override public net.minecraft.world.entity.EntityDimensions gravityengine$installedDimensions() { return dimensions; }
    @Override public net.minecraft.world.entity.Pose gravityengine$installedPose() { return gravityengine$installedDimensionPose; }

    @Override public void gravityengine$flushPlayerDimensions() {
        Entity entity = (Entity) (Object) this;
        if (entity.level().isClientSide()
                || !cc.sighs.gravityengine.gravity.integration.geometry.PlayerBodyHandoff.mayChangeBody(entity)) {
            throw new IllegalStateException("deferred dimensions require the server body boundary");
        }
        if (!gravityengine$dimensionsPending) return;
        gravityengine$dimensionsPending = false;
        entity.refreshDimensions();
    }

    @Override public void gravityengine$discardDimensionProposal() {
        Entity entity = (Entity) (Object) this;
        if (!cc.sighs.gravityengine.gravity.integration.geometry.BodyCommitTransaction.isActive(entity)) {
            throw new IllegalStateException("dimension proposal discard requires a body transaction");
        }
        boolean restoring = gravityengine$restoringDimensionsPose;
        gravityengine$restoringDimensionsPose = true;
        try { entity.setPose(gravityengine$installedDimensionPose); }
        finally {
            gravityengine$restoringDimensionsPose = restoring;
            gravityengine$dimensionsPending = false;
        }
    }

    @Unique private final EntityGravityComponent gravityengine$gravityComponent = new EntityGravityComponent((Entity) (Object) this);

    @Override public EntityGravityComponent gravityengine$gravityComponent() { return this.gravityengine$gravityComponent; }
    @Override public Optional<BlockPos> gravityengine$getVanillaSupportingBlock() { return this.mainSupportingBlockPos; }
    @Override public void gravityengine$setVanillaSupportingBlock(BlockPos supportingBlock) {
        this.mainSupportingBlockPos = Optional.ofNullable(supportingBlock);
        this.onGroundNoBlocks = this.onGround && supportingBlock == null;
    }
    @Override public VanillaCollisionState gravityengine$getVanillaCollisionState() {
        return new VanillaCollisionState(
                this.onGround,
                this.horizontalCollision,
                this.verticalCollision,
                this.verticalCollisionBelow,
                this.minorHorizontalCollision,
                this.mainSupportingBlockPos
        );
    }
    @Override public void gravityengine$restoreVanillaCollisionState(
            VanillaCollisionState state
    ) {
        /* Direct field writes only: setters would trigger checkSupportingBlock side effects. */
        this.onGround = state.onGround();
        this.horizontalCollision = state.horizontalCollision();
        this.verticalCollision = state.verticalCollision();
        this.verticalCollisionBelow = state.verticalCollisionBelow();
        this.minorHorizontalCollision = state.minorHorizontalCollision();
        this.mainSupportingBlockPos = state.mainSupportingBlockPos();
        this.onGroundNoBlocks = this.onGround && this.mainSupportingBlockPos.isEmpty();
    }
    @Override public GravityMoveResult gravityengine$getCurrentMoveResult() { return gravityengine$gravityComponent.operationState().currentMoveResult(); }
    @Override public void gravityengine$setCurrentMoveResult(GravityMoveResult r) { gravityengine$gravityComponent.operationState().setCurrentMoveResult(r); }

    @Shadow protected Vec3 stuckSpeedMultiplier;
    @WrapMethod(method = "move") private void gravityengine$moveWithGravity(MoverType t, Vec3 m, Operation<Void> o) {
        Vec3 stuck = stuckSpeedMultiplier;
        try { EntityMovementIntegration.move((Entity)(Object)this, t, m, () -> o.call(t, m)); }
        catch (FallingBlockTickIntegration.MoveUnavailable failure) {
            // A callback's nested move cannot authorize replay of its already-committed caller.
            if (failure.restored) throw (RuntimeException) failure.getCause();
            failure.restored = true;
            // 21.1.249: collide precedes every position/flag/callback commit. Restore
            // the consumed preprocessing operand and balance the native "move" profiler scope.
            stuckSpeedMultiplier = stuck;
            ((Entity)(Object)this).level().getProfiler().pop();
            throw failure;
        }
    }

    /** 21.1.249 push(Vec3)/push(Entity) both terminate at this explicit impulse producer. */
    @Inject(method = "push(DDD)V", at = @At("TAIL"), require = 1)
    private void gravityengine$captureExternalPush(double x, double y, double z, CallbackInfo ci) {
        Entity entity = (Entity) (Object) this;
        if (EntityMovementIntegration.committedCollisionRoute(entity) != GravityCollisionRoute.VANILLA) {
            gravityengine$gravityComponent.operationState().recordExternalPush(
                    entity.level().getGameTime(),
                    MinecraftMathAdapter.toVec3d(new Vec3(x, y, z)));
        }
    }

    /** Production discontinuity velocity ownership, independent of diagnostics. */
    @Inject(method = "setDeltaMovement(Lnet/minecraft/world/phys/Vec3;)V", at = @At("HEAD"), require = 1)
    private void gravityengine$recordDiscontinuityVelocity(Vec3 incoming, CallbackInfo ci) {
        gravityengine$gravityComponent.operationState().recordPostDiscontinuityVelocity(
                MinecraftMathAdapter.toVec3d(incoming));
    }

    /*
     * Bridge-local ownership for Vanilla composite position writes.
     *
     * Verified 1.21.1 / NeoForge 21.1.249 base Entity contract:
     *   setPos(DDD)V   = setPosRaw(DDD) + setBoundingBox(makeBoundingBox())
     *   moveTo(DDDFF)V = setPosRaw(DDD) + rotation + setOldPosAndRot()
     *                    + reapplyPosition() -> setPos(DDD)
     *
     * One logical externally imposed position replacement therefore owns
     * exactly one discontinuity boundary.  The outermost composite operation
     * publishes exactly once, only after Vanilla's final bounding-box
     * rebuild; every raw or nested composite write inside it is an
     * implementation detail and must not publish independently.  This depth
     * is Mixin-local locator state and deliberately never lives in
     * GravityOperationState.
     */
    @Unique private int gravityengine$positionWriteDepth;
    /**
     * Structural ownership for the movement's own resolved-position write.
     * This is Mixin-local because the write is identified by the owning
     * {@code Entity.move} seam, not by a runtime provenance stack.
     */
    @Unique private int gravityengine$resolvedMovementWriteDepth;

    @Unique
    private void gravityengine$enterCompositePositionWrite() {
        this.gravityengine$positionWriteDepth++;
    }

    @Unique
    private void gravityengine$leaveCompositePositionWrite() {
        if (this.gravityengine$positionWriteDepth <= 0) {
            throw new IllegalStateException(
                    "composite position-write scope underflow");
        }
        this.gravityengine$positionWriteDepth--;
    }

    @WrapMethod(method = "setPos(DDD)V")
    private void gravityengine$setPos(
            double x,
            double y,
            double z,
            Operation<Void> original
    ) {
        Entity entity = (Entity) (Object) this;
        boolean resolvedMovementTransport =
                this.gravityengine$resolvedMovementWriteDepth > 0;
        boolean outermost = this.gravityengine$positionWriteDepth == 0;
        Vec3 before = outermost ? entity.position() : null;
        this.gravityengine$enterCompositePositionWrite();
        try {
            original.call(x, y, z);
        } finally {
            this.gravityengine$leaveCompositePositionWrite();
        }
        if (outermost && !resolvedMovementTransport) {
            EntityPositionIntegration.onCompositePositionWriteCompleted(
                    entity, before, entity.position());
        }
    }

    /**
     * 1.21.1 / NeoForge 21.1.249 base {@code Entity.moveTo(DDDFF)V} semantic
     * boundary.  Vanilla performs a direct {@code setPosRaw}, then reaches
     * {@code setPos} through {@code reapplyPosition()}; both are nested
     * implementation details of this one logical position replacement, so
     * only the outermost {@code moveTo} completion publishes.
     */
    @WrapMethod(method = "moveTo(DDDFF)V")
    private void gravityengine$moveTo(
            double x,
            double y,
            double z,
            float yRot,
            float xRot,
            Operation<Void> original
    ) {
        Entity entity = (Entity) (Object) this;
        boolean resolvedMovementTransport =
                this.gravityengine$resolvedMovementWriteDepth > 0;
        boolean outermost = this.gravityengine$positionWriteDepth == 0;
        Vec3 before = outermost ? entity.position() : null;
        this.gravityengine$enterCompositePositionWrite();
        try {
            original.call(x, y, z, yRot, xRot);
        } finally {
            this.gravityengine$leaveCompositePositionWrite();
        }
        if (outermost && !resolvedMovementTransport) {
            EntityPositionIntegration.onCompositePositionWriteCompleted(
                    entity, before, entity.position());
        }
    }

    /**
     * {@code setPosRaw} is a primitive position-field write used by many
     * unrelated Vanilla systems; it is not intrinsically a teleport seam.
     * Inside an owning composite operation it is an implementation detail and
     * never publishes.  A truly standalone raw write is classified by whether
     * the position anchor actually changed.
     */
    @WrapMethod(method = "setPosRaw(DDD)V")
    private void gravityengine$setPosRaw(
            double x,
            double y,
            double z,
            Operation<Void> original
    ) {
        Entity entity = (Entity) (Object) this;
        if (this.gravityengine$resolvedMovementWriteDepth > 0) {
            original.call(x, y, z);
            return;
        }
        if (this.gravityengine$positionWriteDepth > 0) {
            original.call(x, y, z);
            return;
        }
        Vec3 before = entity.position();
        original.call(x, y, z);
        EntityPositionIntegration.onStandalonePositionWrite(
                entity, before, entity.position());
    }

    @Unique private boolean gravityengine$restoringDimensionsPose;

    @WrapMethod(method = "refreshDimensions")
    private void gravityengine$refreshDims(Operation<Void> original) {
        // Rollback changes synced pose without firing the Size event a second time.
        if (gravityengine$restoringDimensionsPose) return;
        Entity entity = (Entity) (Object) this;

        if (!GravityInfluencePolicy.usesCustomBody(entity)) {
            gravityengine$dimensionsPending = false;
            original.call();
            gravityengine$installedDimensionPose = entity.getPose();
            return;
        }

        GravityOperationState runtime = gravityengine$gravityComponent.operationState();

        if (entity instanceof net.minecraft.world.entity.player.Player
                && !runtime.isApplyingGeometry()
                && !cc.sighs.gravityengine.gravity.integration.geometry.PlayerBodyHandoff.mayChangeBody(entity)) {
            if (!entity.level().isClientSide()) gravityengine$dimensionsPending = true;
            return;
        }

        /*
         * An enclosing geometry transaction owns this dimensions mutation.
         *
         * Examples:
         * - Server body replica installation;
         * - Geometry rollback;
         * - another atomic pose/body transaction.
         *
         * The owner is responsible for installing the final exact body after
         * Vanilla refreshDimensions has updated pose-derived dimensions.
         *
         * Do not invalidate movement continuity here and, critically, do not
         * rebuild an intermediate body using the old environmental/body state.
         */
        if (runtime.isApplyingGeometry()) {
            gravityengine$dimensionsPending = false;
            var previousDimensions = this.dimensions;
            try {
                original.call();
                gravityengine$installedDimensionPose = entity.getPose();
            } finally {
                if (!previousDimensions.equals(this.dimensions)) runtime.markBodyDimensionsChanged();
            }
            return;
        }

        if (runtime.isInMove()) {
            gravityengine$refreshDimensionsInMove(entity, runtime, original);
            return;
        }

        /*
         * Standalone refreshDimensions is a real body-geometry discontinuity.
         * It owns exactly one movement-continuity invalidation.
         */
        Vec3 preservedPositionAnchor = entity.position();
        var oldDimensions = this.dimensions;
        float oldEyeHeight = this.eyeHeight;
        var installedUp = GravityEntityGeometry.installedUp(entity);
        var oldBody = GravityEntityGeometry.exactBody(entity);
        AABB oldProxy = entity.getBoundingBox();
        var oldPose = gravityengine$installedDimensionPose;

        /*
         * Vanilla is allowed to rebuild its temporary dimensions/AABB while the
         * geometry scope suppresses primitive position/body publications.
         */
        try (GravityOperationState.GeometryScope ignored =
                     runtime.openGeometryMutation()) {
            boolean committed = false;
            Throwable failure = null;
            try {
                original.call();
                if (!cc.sighs.gravityengine.gravity.integration.geometry.GravityGeometryTransitionService
                    .dimensionsChangeLegal(
                            entity,
                            oldBody,
                            MinecraftMathAdapter.toVec3d(
                                    preservedPositionAnchor),
                            installedUp)) {
                    return;
                }
                GravityEntityGeometry.commitCustomBody(entity, installedUp, preservedPositionAnchor,
                        GravityEntityGeometry.candidateBody(this.dimensions, preservedPositionAnchor, installedUp));
                gravityengine$installedDimensionPose = entity.getPose();
                committed = true;
            } catch (RuntimeException | Error thrown) {
                failure = thrown;
                throw thrown;
            } finally {
                if (!committed) {
                    try {
                        gravityengine$restoreRejectedDimensions(entity, oldPose, oldDimensions, oldEyeHeight,
                                preservedPositionAnchor, oldProxy, installedUp);
                    } catch (RuntimeException | Error rollbackFailure) {
                        if (failure != null) failure.addSuppressed(rollbackFailure);
                        else throw rollbackFailure;
                    }
                }
            }
        }

        if (!oldDimensions.equals(this.dimensions)) runtime.markBodyDimensionsChanged();
        EntityMovementIntegration.invalidateMovementContinuity(entity);
    }

    @Unique
    private void gravityengine$refreshDimensionsInMove(
            Entity entity, GravityOperationState runtime, Operation<Void> original) {
        var installedUp = GravityEntityGeometry.installedUp(entity);
        var position = entity.position();
        var oldDimensions = this.dimensions;
        float oldEyeHeight = this.eyeHeight;
        var oldProxy = entity.getBoundingBox();
        var oldBody = GravityEntityGeometry.exactBody(entity);
        var oldPose = this.gravityengine$installedDimensionPose;
        var operation = runtime.collisionOperation();
        // A completed move owns the endpoint. A pre-solve refresh owns time zero.
        boolean atEndpoint = runtime.currentMoveResult() != null
                || runtime.currentPassiveMoveResult() != null
                || runtime.discontinuityDestination() != null;
        boolean committed = false;
        Throwable failure = null;

        try (var ignored = runtime.openGeometryMutation()) {
            try {
                original.call();
                var decision = cc.sighs.gravityengine.gravity.kinematic.geometry
                        .CharacterDimensionPolicy.decide(dimensions.width(), dimensions.height());
                if (decision != cc.sighs.gravityengine.gravity.kinematic.geometry
                        .CharacterDimensionPolicy.Decision.CAPSULE) return;

                var candidate = GravityEntityGeometry.candidateBody(this.dimensions, position, installedUp);
                boolean legal = candidate.equals(oldBody) || entity.noPhysics;
                if (!legal && operation != null) {
                    try {
                        double time = atEndpoint ? operation.time().intervalTicks() : 0.0D;
                        legal = cc.sighs.gravityengine.gravity.collision.BodyCollisionDelta
                                .comparePoseAt(oldBody, candidate, operation.scene(), time,
                                        operation.geometryContext()).legal();
                    } catch (cc.sighs.gravityengine.gravity.collision.CollisionComplexityLimitException
                             | cc.sighs.gravityengine.gravity.collision.CollisionSceneCoverageException unavailable) {
                        // Unknown fit rejects this resize; it never means empty geometry.
                        legal = false;
                    }
                }
                if (!legal) return;

                // Network anchor remains authoritative; Vanilla's axis-Y size
                // fudge is not an independent translation of a rotated body.
                GravityEntityGeometry.commitCustomBody(entity, installedUp, position, candidate);
                gravityengine$installedDimensionPose = entity.getPose();
                committed = true;
                if (!oldDimensions.equals(this.dimensions)) runtime.markBodyDimensionsChanged();
            } catch (RuntimeException | Error thrown) {
                failure = thrown;
                throw thrown;
            } finally {
                try {
                    try {
                        if (!committed) {
                            gravityengine$restoreRejectedDimensions(entity, oldPose, oldDimensions,
                                    oldEyeHeight, position, oldProxy, installedUp);
                        }
                    } finally {
                        // Invalidate even if original/validation/commit/rollback failed.
                        // The old movement must not republish stale contact geometry.
                        try {
                            runtime.supersedeMovement(MinecraftMathAdapter.toVec3d(entity.position()));
                        } finally {
                            runtime.clearPersistentSupportState();
                        }
                    }
                } catch (RuntimeException | Error cleanupFailure) {
                    if (failure != null) failure.addSuppressed(cleanupFailure);
                    else throw cleanupFailure;
                }
            }
        }
    }

    @Unique
    private void gravityengine$restoreRejectedDimensions(
            Entity entity, net.minecraft.world.entity.Pose oldPose,
            net.minecraft.world.entity.EntityDimensions oldDimensions, float oldEyeHeight,
            Vec3 position, AABB oldProxy, cc.sighs.gravityengine.api.math.Vec3d installedUp) {
        boolean previous = this.gravityengine$restoringDimensionsPose;
        this.gravityengine$restoringDimensionsPose = true;
        this.dimensions = oldDimensions;
        this.eyeHeight = oldEyeHeight;
        try {
            // A rejected geometry request must never undo semantic death.
            // DYING retains the last valid installed dimensions if a Size hook
            // proposed an unsupported or obstructed death shape.
            boolean dying = entity.getPose() == net.minecraft.world.entity.Pose.DYING
                    || entity instanceof net.minecraft.world.entity.LivingEntity living
                    && living.isDeadOrDying();
            if (!dying && oldPose != null && entity.getPose() != oldPose) {
                entity.setPose(oldPose);
            }
        } finally {
            this.gravityengine$restoringDimensionsPose = previous;
            this.dimensions = oldDimensions;
            this.eyeHeight = oldEyeHeight;
            gravityengine$installedDimensionPose = oldPose;
            GravityEntityGeometry.restoreExactGeometry(entity, position, oldProxy, installedUp);
        }
    }

    @Inject(method = "getEyePosition()Lnet/minecraft/world/phys/Vec3;", at = @At("HEAD"), cancellable = true)
    private void gravityengine$eyePos(CallbackInfoReturnable<Vec3> cir) {
        if (GravityInfluencePolicy.usesCustomBody((Entity)(Object)this))
            cir.setReturnValue(GravityEntityGeometry.eyePosition((Entity)(Object)this));
    }

    @Inject(method = "getEyePosition(F)Lnet/minecraft/world/phys/Vec3;", at = @At("HEAD"), cancellable = true)
    private void gravityengine$eyePosPartial(float pt, CallbackInfoReturnable<Vec3> cir) {
        if (GravityInfluencePolicy.usesCustomBody((Entity)(Object)this))
            cir.setReturnValue(GravityEntityGeometry.eyePosition((Entity)(Object)this, pt));
    }

    @Inject(method = "isInWall", at = @At("HEAD"), cancellable = true)
    private void gravityengine$isInWall(CallbackInfoReturnable<Boolean> cir) {
        var e = (Entity)(Object)this; if (!GravityInfluencePolicy.usesCustomBody(e)) return;
        cir.setReturnValue(cc.sighs.gravityengine.gravity.integration.vanilla.VanillaBodySensors.isInWall(e));
    }

    /**
     * 1.21.1 / NeoForge 21.1.249:
     * one collide call after Vanilla preprocessing.
     *
     * <p>Entity.collide is an inner seam of the currently executing Entity.move.
     * It may enter GravityEngine only when that move owns a frozen collision
     * route. A null movement route means there is no GE movement transaction;
     * in particular this is the normal state of client NATIVE_FALLBACK.
     */
    @Inject(
            method = "collide",
            at = @At("HEAD"),
            cancellable = true,
            require = 1
    )
    private void gravityengine$collide(
            Vec3 movement,
            CallbackInfoReturnable<Vec3> cir
    ) {
        Entity entity = (Entity) (Object) this;

        /*
         * Do not use GravityInfluencePolicy here.
         *
         * Installed geometry may still be EXACT_BODY during a representation
         * handoff while this particular Entity.move is deliberately Vanilla.
         *
         * Only beginMovement(...) is allowed to grant GE ownership of this
         * Entity.collide invocation.
         */
        GravityCollisionRoute route =
                EntityMovementIntegration
                        .activeMovementCollisionRoute(entity);

        if (route == null || route == GravityCollisionRoute.VANILLA) {
            return;
        }

        try {
            cir.setReturnValue(
                    EntityMovementIntegration.collide(
                            entity,
                            movement
                    )
            );
        } catch (
                cc.sighs.gravityengine.gravity.collision.CollisionComplexityLimitException
                | cc.sighs.gravityengine.gravity.collision.CollisionSceneCoverageException failure
        ) {
            if (!(entity instanceof net.minecraft.world.entity.item.FallingBlockEntity)) {
                throw failure;
            }

            /*
             * Abort before the first physical commit. The falling-block tick can
             * retry its native movement with restored preprocessing rather than
             * committing a mixed GE/Vanilla movement.
             */
            throw new FallingBlockTickIntegration.MoveUnavailable(
                    failure
            );
        }
    }

    /** 1.21.1 / NeoForge 21.1.249 noPhysics branch: old position anchor plus raw request. */
    @WrapOperation(
            method = "move",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/Entity;setPos(DDD)V",
                    ordinal = 0
            ),
            require = 1
    )
    private void gravityengine$applyNoPhysicsTranslation(
            Entity entity,
            double x,
            double y,
            double z, Operation<Void> original
    ) {
        gravityengine$commitTranslation(entity, x, y, z, original);
    }

    /** Normal path: the target is old position anchor plus Entity.collide's result. */
    /**
     * 1.21.1 / NeoForge 21.1.249 ordinary Entity.move branch.
     *
     * <p>Invariant: every genuinely non-zero GravityEngine custom resolved
     * displacement must pass Vanilla's tiny-position-write optimization without
     * modifying the resolved Vec3 itself. The rule and its rationale live in
     * {@link cc.sighs.gravityengine.gravity.integration.vanilla.VanillaMovementCommit};
     * this seam only supplies the native predicate value and the current
     * collision route.
     *
     * <p>Locator (verified on the 21.1.249 Vanilla bytecode and on the
     * bytecode transformed by Sable 2.0.5): the predicate is identified by its
     * own semantics instead of by the collision call that precedes it. The
     * window opens at Vanilla's last pre-collision preprocessing call
     * ({@code maybeBackOffFromEdge}) and closes at Vanilla's own resolved
     * position write ({@code setPos(DDD)V}, second call site). In the
     * version-matched Vanilla body exactly one {@code Vec3.lengthSqr()}
     * invocation lies strictly inside that window - the resolved-displacement
     * commit predicate - so no bytecode ordinal is required. Neither landmark
     * is touched by Sable, whose priority-1100 Redirect replaces the
     * {@code Entity.move -> Entity.collide} invocation that this locator
     * previously used and that a lower-priority injector must not depend on.
     */
    @WrapOperation(
            method = "move",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/phys/Vec3;lengthSqr()D"
            ),
            slice = @Slice(
                    from = @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/world/entity/Entity;"
                                            + "maybeBackOffFromEdge("
                                            + "Lnet/minecraft/world/phys/Vec3;"
                                            + "Lnet/minecraft/world/entity/MoverType;)"
                                            + "Lnet/minecraft/world/phys/Vec3;"
                    ),
                    to = @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/world/entity/Entity;"
                                            + "setPos(DDD)V",
                            ordinal = 1
                    )
            ),
            require = 1
    )
    private double gravityengine$forceResolvedTranslationCommit(
            Vec3 resolved,
            Operation<Double> original
    ) {
        /*
         * The return value is consumed only by Vanilla's
         * "resolved.lengthSqr() > 1.0E-7D" predicate; the resolved Vec3 object
         * itself is never changed. The rule lives in VanillaMovementCommit so
         * the exact-zero no-op and the tiny non-zero commit stay testable
         * without a live entity.
         */
        return cc.sighs.gravityengine.gravity.integration.vanilla.VanillaMovementCommit
                .commitPredicateOperand(
                        resolved,
                        original.call(resolved),
                        EntityMovementIntegration.engineOwnsActiveMovement(
                                (Entity) (Object) this
                        )
                );
    }


    /**
     * Ordinary movement position publication.
     *
     * Once the native commit gate above accepts every non-zero custom resolved
     * translation, all position writes continue through the existing translation
     * transaction.  This is intentionally the only position commit path.
     */
    @WrapOperation(
            method = "move",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/Entity;setPos(DDD)V",
                    ordinal = 1
            ),
            require = 1
    )
    private void gravityengine$applyCollisionResolvedTranslation(
            Entity entity,
            double x,
            double y,
            double z,
            Operation<Void> original
    ) {
        /*
         * Assert that the ordinary custom-movement seam owns a completed
         * collision result before publishing its translation.
         */
        EntityMovementIntegration.movementCollisionState(entity);

        gravityengine$commitTranslation(entity, x, y, z, original);
    }

    @Unique
    private void gravityengine$commitTranslation(
            Entity entity,
            double x,
            double y,
            double z, Operation<Void> original
    ) {
        boolean customBody=GravityInfluencePolicy.usesCustomBody(entity);
        GravityOperationState runtime = gravityengine$gravityComponent.operationState();
        if(customBody) GravityEntityGeometry.installedUp(entity);
        if (runtime.discontinuityDestination() != null) {
            return;
        }
        // Entity.move owns this translation for native-shaped actors too.
        // Retained GE support does not turn accepted movement into a teleport.
        this.gravityengine$resolvedMovementWriteDepth++;
        try {
            original.call(entity, x, y, z);
        } finally {
            this.gravityengine$resolvedMovementWriteDepth--;
        }
        if(customBody) GravityEntityGeometry.reanchorAfterVanillaTranslation(entity);
    }

    /** 1.21.1 / NeoForge 21.1.249 walk-distance uses committed displacement. */
    @Redirect(method = "move", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/phys/Vec3;horizontalDistance()D"), require = 1)
    private double gravityengine$walkDist(Vec3 m) {
        var e = (Entity)(Object)this;
        if (!EntityMovementIntegration.engineOwnsActiveMovement(e)) return m.horizontalDistance();
        var loc = gravityengine$gravityComponent.operationState()
                .activeFrame().worldToLocal(
                        MinecraftMathAdapter.toVec3d(m));
        return Math.sqrt(loc.x() * loc.x() + loc.z() * loc.z());
    }

    @Inject(
            method = "checkSupportingBlock",
            at = @At("HEAD"),
            cancellable = true,
            require = 1
    )
    private void gravityengine$checkSupportingBlock(
            boolean onGround,
            Vec3 movement,
            CallbackInfo ci
    ) {
        var state = EntityMovementIntegration.supportingCollisionState((Entity) (Object) this);
        if (state == null) return;
        this.mainSupportingBlockPos = state.mainSupportingBlockPos();
        this.onGroundNoBlocks = state.onGround() && this.mainSupportingBlockPos.isEmpty();
        ci.cancel();
    }

    /** 21.1.249: the final collision-field assignment precedes minor-contact policy. */
    @Inject(method = "move", at = @At(value = "FIELD",
            target = "Lnet/minecraft/world/entity/Entity;verticalCollisionBelow:Z",
            opcode = 181, shift = At.Shift.AFTER), require = 1)
    private void gravityengine$collisionFlagsBeforeMinor(MoverType type, Vec3 movement, CallbackInfo ci) {
        EntityMovementIntegration.installMovementCollisionFlags((Entity) (Object) this);
    }

    /** Gameplay grounding is committed by Vanilla after its minor-contact policy. */
    @WrapOperation(
            method = "move",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/Entity;setOnGroundWithMovement(ZLnet/minecraft/world/phys/Vec3;)V"
            ),
            require = 1
    )
    private void gravityengine$setAuthoritativeGroundBeforeSideEffects(
            Entity entity,
            boolean vanillaGrounded,
            Vec3 movement,
            Operation<Void> original
    ) {
        var state = EntityMovementIntegration.movementCollisionState(entity);
        if (state != null) {
            /*
             * Sable's later-applied wrap writes its SubLevel/world-axis flags
             * immediately before this handler and then calls into it. Restore
             * the deliberate parent-world/SubLevel-compatibility projection
             * here so the final Entity fields consumed by Vanilla do not
             * depend on whichever wrapper happened to write last.
             */
            entity.horizontalCollision = state.horizontalCollision();
            entity.verticalCollision = state.verticalCollision();
            entity.verticalCollisionBelow = state.verticalCollisionBelow();
            entity.minorHorizontalCollision =
                    SableMovementCompatibility
                            .finalizedMinorHorizontalCollision(
                                    entity,
                                    state.minorHorizontalCollision()
                            );
            SableMovementCompatibility
                    .restoreTrackingAfterParentWorldWorldYChange(entity);
        }
        original.call(entity, state == null ? vanillaGrounded : state.onGround(), movement);
    }

    /**
     * 1.21.1 / NeoForge 21.1.249: Entity.move has exactly one DDD velocity
     * write, after collision flags/grounding and before block callbacks.
     *
     * <p>Custom movement preserves incoming world velocity here so native
     * material response can consume impact. Complete accepted contact
     * constraints apply at the join after fall/step callbacks.</p>
     */
    @WrapOperation(
            method = "move",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/Entity;setDeltaMovement(DDD)V"
            ),
            require = 1
    )
    private void gravityengine$collisionVelocityResponse(
            Entity entity,
            double x,
            double y,
            double z,
            Operation<Void> original
    ) {
        if (!EntityMovementIntegration.engineOwnsActiveMovement(entity)) {
            original.call(entity, x, y, z);
            return;
        }
        // Preserve incoming impact velocity until native bounce policy runs.
        // The complete world contact response commits at the callback join.

    }

    /*
     * Intentionally targets Entity.move()'s getOnPosLegacy call in
     * Minecraft 1.21.1 / NeoForge 21.1.249.
     *
     * The target method is deprecated, but the vanilla bytecode still calls
     * it. Do not replace this injection target until the vanilla call site
     * changes.
     */
    @WrapOperation(
            method = "move",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/Entity;getOnPosLegacy()Lnet/minecraft/core/BlockPos;"
            ),
            require = 1
    )
    private BlockPos gravityengine$gravityRelativeOnPos(
            Entity entity,
            Operation<BlockPos> original
    ) {
        GravityCollisionRoute route =
                EntityMovementIntegration.activeMovementCollisionRoute(entity);
        if (route == null || route == GravityCollisionRoute.VANILLA) {
            return original.call(entity);
        }

        if (route == GravityCollisionRoute.PASSIVE_AABB) {
            var landing = cc.sighs.gravityengine.gravity.integration.BlockContactResolver.landingBlock(entity);
            if (landing.isPresent()) return landing.get().position();
            var state = EntityMovementIntegration.supportingCollisionState(entity);
            if (state != null && state.mainSupportingBlockPos().isPresent()) return state.mainSupportingBlockPos().get();
            var frame = gravityengine$gravityComponent.operationState().activeFrame();
            return VanillaBodySensors.gravityRelativeOnPos(frame, GravityEntityGeometry.gravityFeet(entity, frame));
        }
        return cc.sighs.gravityengine.gravity.integration.BlockContactResolver.landingBlock(entity)
                .map(cc.sighs.gravityengine.gravity.integration.BlockContactResolver.Resolved::position)
                .orElse(BlockPos.ZERO);
    }

    /** Replace only the native behavior operand. Missing dynamic evidence must not read parent terrain. */
    @WrapOperation(method = "move", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;",
            ordinal = 0), require = 1)
    private BlockState gravityengine$landingState(Level level, BlockPos pos, Operation<BlockState> original) {
        var entity = (Entity)(Object)this;
        if (!EntityMovementIntegration.engineOwnsActiveMovement(entity)) return original.call(level, pos);
        return cc.sighs.gravityengine.gravity.integration.BlockContactResolver.landingBlock(entity)
                .map(cc.sighs.gravityengine.gravity.integration.BlockContactResolver.Resolved::state)
                .orElse(net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
    }

    @WrapOperation(method="checkFallDamage",at=@At(value="INVOKE",target=
            "Lnet/minecraft/world/level/block/Block;fallOn(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/entity/Entity;F)V"),require=1)
    private void gravityengine$landingWorld(net.minecraft.world.level.block.Block block,Level level,BlockState state,
            BlockPos position,Entity actor,float distance,Operation<Void> original) {
        var packetMaterial = cc.sighs.gravityengine.network.ServerPlayerMovementReceiver.fallMaterial(actor);
        if (packetMaterial.isPresent()) {
            var material = packetMaterial.get();
            original.call(material.state().getBlock(), material.level(), material.state(), material.position(), actor, distance);
            return;
        }
        var contact=EntityMovementIntegration.engineOwnsActiveMovement(actor)?BlockContactResolver.landingBlock(actor):java.util.Optional.<BlockContactResolver.Resolved>empty();
        if(contact.isPresent()) {
            var c=contact.get();original.call(c.state().getBlock(),c.level(),c.state(),c.position(),actor,distance);
        } else original.call(block,level,state,position,actor,distance);
    }

    @WrapOperation(method="move",at=@At(value="INVOKE",target=
            "Lnet/minecraft/world/level/block/Block;updateEntityAfterFallOn(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/world/entity/Entity;)V"),require=1)
    private void gravityengine$landingResponseWorld(net.minecraft.world.level.block.Block block,
            net.minecraft.world.level.BlockGetter level,Entity actor,Operation<Void> original) {
        var contact=EntityMovementIntegration.engineOwnsActiveMovement(actor)?BlockContactResolver.landingBlock(actor):java.util.Optional.<BlockContactResolver.Resolved>empty();
        if(contact.isPresent()) {
            var c=contact.get();original.call(c.state().getBlock(),c.level(),actor);
        } else original.call(block,level,actor);
    }

    @WrapOperation(method = "move", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/Block;stepOn(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/entity/Entity;)V"), require = 1)
    private void gravityengine$stepContact(net.minecraft.world.level.block.Block block, Level level, BlockPos pos,
            BlockState state, Entity entity, Operation<Void> original) {
        if (EntityMovementIntegration.activeMovementCollisionRoute(entity)
                != GravityCollisionRoute.EXACT_BODY) {
            original.call(block, level, pos, state, entity);
            return;
        }
        ContactVelocityIntegration.prepareStepVelocity(entity);
        cc.sighs.gravityengine.gravity.integration.BlockContactResolver.supportBlock(entity)
                .ifPresent(c -> original.call(c.state().getBlock(), c.level(), c.position(), c.state(), entity));
    }

    @Inject(method = "baseTick", at = @At("HEAD")) private void gravityengine$baseTick(CallbackInfo ci) { GravityEntityTickLifecycle.onBaseTick((Entity)(Object)this); }

    @Inject(method = "moveRelative", at = @At("HEAD"), cancellable = true)
    private void gravityengine$moveRelative(float s, Vec3 i, CallbackInfo ci) {
        var e = (Entity)(Object)this; var r = LivingGravityIntegration.moveRelative(e, s, i);
        if (r != null) { e.setDeltaMovement(r); ci.cancel(); }
    }

    @Redirect(method = "move", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;checkFallDamage(DZLnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;)V"), require = 1)
    private void gravityengine$fallDamage(Entity e, double v, boolean g, BlockState s, BlockPos p) {
        var fall = EntityMovementIntegration.fallMovement(e, v, g);
        if (fall != null) this.checkFallDamage(fall.vertical(), fall.landed(), s, p);
    }

    /** Native-selected speed factor damps the reference tangent exactly once. */
    @WrapOperation(method = "move", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/phys/Vec3;multiply(DDD)Lnet/minecraft/world/phys/Vec3;"), require = 1)
    private Vec3 gravityengine$blockSpeed(Vec3 velocity, double x, double y, double z, Operation<Vec3> original) {
        var entity = (Entity) (Object) this;
        if (!EntityMovementIntegration.engineOwnsActiveMovement(entity)) {
            return original.call(velocity, x, y, z);
        }
        var runtime = gravityengine$gravityComponent.operationState();
        var frame = runtime.activeFrame();
        return MinecraftMathAdapter.toMinecraft(
                frame.localToWorld(
                        frame.worldToLocal(
                                        MinecraftMathAdapter.toVec3d(
                                                velocity))
                                .multiply(x, y, z)));
    }

    /** NeoForge has already merged attachments; bootstrap only when no gravity slot exists. */
    @Inject(method = "load", at = @At("RETURN"))
    private void gravityengine$initializeGravityAfterLoad(CompoundTag t, CallbackInfo ci) {
        cc.sighs.gravityengine.gravity.persistence.GravityPersistence
                .initializeAfterLoad((Entity) (Object) this);
    }
}
