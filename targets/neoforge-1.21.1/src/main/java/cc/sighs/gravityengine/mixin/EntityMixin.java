package cc.sighs.gravityengine.mixin;

import cc.sighs.gravityengine.gravity.GravityFrame;
import cc.sighs.gravityengine.gravity.collision.*;
import cc.sighs.gravityengine.gravity.component.EntityGravityComponent;
import cc.sighs.gravityengine.gravity.debug.GravityDebugLog;
import cc.sighs.gravityengine.gravity.integration.ContactVelocityIntegration;
import cc.sighs.gravityengine.gravity.integration.EntityMovementIntegration;
import cc.sighs.gravityengine.gravity.integration.EntityPositionIntegration;
import cc.sighs.gravityengine.gravity.integration.vanilla.VanillaBodySensors;
import cc.sighs.gravityengine.gravity.minecraft.GravityFrameAccess;

import cc.sighs.gravityengine.gravity.integration.compat.sable.SableMovementCompatibility;
import cc.sighs.gravityengine.gravity.integration.GravityEntityTickLifecycle;
import cc.sighs.gravityengine.gravity.integration.LivingGravityIntegration;
import cc.sighs.gravityengine.gravity.minecraft.access.GravityEntityAccess;
import cc.sighs.gravityengine.gravity.minecraft.geometry.GravityEntityGeometry;
import cc.sighs.gravityengine.gravity.policy.GravityInfluencePolicy;
import cc.sighs.gravityengine.gravity.runtime.GravityRuntimeState;
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
import org.spongepowered.asm.mixin.injection.Slice;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
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
        if (EntityMovementIntegration.movementRoute(entity) == GravityInfluencePolicy.CollisionRoute.VANILLA)
            return original.call(vector);
        return EntityMovementIntegration.movementVerticalResponse(entity) ? 1 : 0;
    }
    @WrapOperation(method="move", at=@At(value="FIELD", target="Lnet/minecraft/world/phys/Vec3;y:D", ordinal=1),
            slice=@Slice(from=@At(value="INVOKE", target="Lnet/minecraft/world/level/block/state/BlockState;getBlock()Lnet/minecraft/world/level/block/Block;"),
                    to=@At(value="INVOKE", target="Lnet/minecraft/world/entity/Entity;getMovementEmission()Lnet/minecraft/world/entity/Entity$MovementEmission;")))
    private double gravityengine$verticalResponseResolved(Vec3 vector, Operation<Double> original) {
        return EntityMovementIntegration.movementRoute((Entity) (Object) this) == GravityInfluencePolicy.CollisionRoute.VANILLA
                ? original.call(vector) : 0;
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
        accepted.set(gravityengine$gravityComponent.runtime().discontinuityDestination() == null
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
                        .worldToLocal(velocity).y
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
    @Unique private net.minecraft.world.entity.Pose gravityengine$poseBeforeRefresh;

    @Override public net.minecraft.world.entity.EntityDimensions gravityengine$installedDimensions() { return dimensions; }

    /** setPose owns the semantic pose; retain it only across its synchronous
     * onSyncedDataUpdated -> refreshDimensions transaction for rollback. */
    @WrapMethod(method = "setPose")
    private void gravityengine$poseDimensionTransaction(net.minecraft.world.entity.Pose pose, Operation<Void> original) {
        var previous = this.gravityengine$poseBeforeRefresh;
        this.gravityengine$poseBeforeRefresh = ((Entity) (Object) this).getPose();
        try { original.call(pose); }
        finally { this.gravityengine$poseBeforeRefresh = previous; }
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
    @Override public GravityMoveResult gravityengine$getCurrentMoveResult() { return gravityengine$gravityComponent.runtime().currentMoveResult(); }
    @Override public void gravityengine$setCurrentMoveResult(GravityMoveResult r) { gravityengine$gravityComponent.runtime().setCurrentMoveResult(r); }

    @WrapMethod(method = "move") private void gravityengine$moveWithGravity(MoverType t, Vec3 m, Operation<Void> o) {
        EntityMovementIntegration.move((Entity)(Object)this, t, m, () -> o.call(t, m));
    }

    /** 21.1.249 push(Vec3)/push(Entity) both terminate at this explicit impulse producer. */
    @Inject(method = "push(DDD)V", at = @At("TAIL"), require = 1)
    private void gravityengine$captureExternalPush(double x, double y, double z, CallbackInfo ci) {
        Entity entity = (Entity) (Object) this;
        if (EntityMovementIntegration.movementRoute(entity) != GravityInfluencePolicy.CollisionRoute.VANILLA) {
            gravityengine$gravityComponent.runtime().recordExternalPush(entity.level().getGameTime(), new Vec3(x, y, z));
        }
    }

    /** Trace every notable world-domain Vec3 velocity write without changing it. */
    @Inject(
            method = "setDeltaMovement(Lnet/minecraft/world/phys/Vec3;)V",
            at = @At("HEAD"),
            require = 1
    )
    private void gravityengine$traceNotableVecVelocityWrite(
            Vec3 incoming,
            CallbackInfo ci
    ) {
        gravityengine$gravityComponent.runtime()
                .recordPostDiscontinuityVelocity(incoming);
        if (!GravityDebugLog.ENABLED) {
            return;
        }
        Entity entity = (Entity) (Object) this;
        if (EntityMovementIntegration.movementRoute(entity) == GravityInfluencePolicy.CollisionRoute.VANILLA) return;

        GravityFrame frame = GravityFrameAccess.reliableFrame(entity);
        if (frame == null) return;
        Vec3 before = entity.getDeltaMovement();
        if (!GravityDebugLog.isNotableVerticalChange(frame, before, incoming)) {
            return;
        }

        Vec3 beforeLocal = frame.worldToLocal(before);
        Vec3 incomingLocal = frame.worldToLocal(incoming);
        GravityRuntimeState runtime = gravityengine$gravityComponent.runtime();
        GravityMoveResult result = runtime.currentMoveResult();
        GravityDebugLog.log(
                entity,
                "velocity-set-vec-notable",
                "beforeWorld=%s beforeLocal=%s incomingWorld=%s incomingLocal=%s "
                        + "deltaLocal=%s onGround=%s horizontalCollision=%s "
                        + "verticalCollision=%s verticalCollisionBelow=%s "
                        + "runtimeInMove=%s runtimeApplyingGeometry=%s %s %s "
                        + "caller=%s",
                GravityDebugLog.vec(before),
                GravityDebugLog.vec(beforeLocal),
                GravityDebugLog.vec(incoming),
                GravityDebugLog.vec(incomingLocal),
                GravityDebugLog.vec(incomingLocal.subtract(beforeLocal)),
                entity.onGround(),
                entity.horizontalCollision,
                entity.verticalCollision,
                entity.verticalCollisionBelow,
                runtime.isInMove(),
                runtime.isApplyingGeometry(),
                GravityDebugLog.formatMoveResult(result),
                GravityDebugLog.verticalChangeFlags(beforeLocal, incomingLocal),
                GravityDebugLog.callerStack()
        );
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
     * GravityRuntimeState.
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

    @WrapMethod(method = "refreshDimensions")
    private void gravityengine$refreshDims(Operation<Void> original) {
        Entity entity = (Entity) (Object) this;

        if (!GravityInfluencePolicy.usesCustomBody(entity)) {
            original.call();
            return;
        }

        GravityRuntimeState runtime = gravityengine$gravityComponent.runtime();

        /*
         * An enclosing geometry transaction owns this dimensions mutation.
         *
         * Examples:
         * - BodyAttitude authoritative physical commit;
         * - BodyAttitude rollback;
         * - another atomic pose/body transaction.
         *
         * The owner is responsible for installing the final exact body after
         * Vanilla refreshDimensions has updated pose-derived dimensions.
         *
         * Do not invalidate movement continuity here and, critically, do not
         * rebuild an intermediate body using the old environmental/body state.
         */
        if (runtime.isApplyingGeometry()) {
            var previousDimensions = this.dimensions;
            try {
                original.call();
            } finally {
                if (!previousDimensions.equals(this.dimensions)) runtime.markBodyDimensionsChanged();
            }
            return;
        }

        /*
         * A dimensions change that appears unexpectedly inside a live movement
         * operation has no declared geometry owner.  Do not silently destroy the
         * live movement transaction.
         */
        if (runtime.isInMove()) {
            throw new IllegalStateException(
                    "unowned refreshDimensions inside an active gravity operation"
            );
        }

        /*
         * Standalone refreshDimensions is a real body-geometry discontinuity.
         * It owns exactly one movement-continuity invalidation.
         */
        Vec3 preservedPositionAnchor = entity.position();
        var oldDimensions = this.dimensions;
        float oldEyeHeight = this.eyeHeight;
        var frame = runtime.geometryReferenceFrame();
        var oldBody = GravityEntityGeometry.exactBody(entity, frame);
        AABB oldProxy = entity.getBoundingBox();

        /*
         * Vanilla is allowed to rebuild its temporary dimensions/AABB while the
         * geometry scope suppresses primitive position/body publications.
         */
        try (GravityRuntimeState.GeometryScope ignored =
                     runtime.openGeometryMutation()) {
            original.call();
            if (!cc.sighs.gravityengine.gravity.integration.geometry.GravityGeometryTransitionService
                    .dimensionsChangeLegal(entity, oldBody, preservedPositionAnchor, frame)) {
                if (this.gravityengine$poseBeforeRefresh != null) entity.setPose(this.gravityengine$poseBeforeRefresh);
                this.dimensions = oldDimensions;
                this.eyeHeight = oldEyeHeight;
                GravityEntityGeometry.restoreExactGeometry(
                        entity, preservedPositionAnchor, oldProxy, frame);
                return;
            }
        }

        if (!oldDimensions.equals(this.dimensions)) runtime.markBodyDimensionsChanged();
        EntityMovementIntegration.invalidateMovementContinuity(entity);

        /*
         * Vanilla has completely returned.  Restore the exact custom body once,
         * preserving the authoritative environmental frame and position anchor P.
         */
        EntityPositionIntegration.rebuildBodyAfterDimensionsChanged(
                entity,
                preservedPositionAnchor
        );
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

    /** 1.21.1 / NeoForge 21.1.249: one collide call after vanilla preprocessing. */
    @Inject(method = "collide", at = @At("HEAD"), cancellable = true, require = 1)
    private void gravityengine$collide(Vec3 m, CallbackInfoReturnable<Vec3> cir) {
        var e = (Entity)(Object)this;
        if (EntityMovementIntegration.movementRoute(e) != GravityInfluencePolicy.CollisionRoute.VANILLA) cir.setReturnValue(EntityMovementIntegration.collide(e, m));
        else if (GravityDebugLog.MOVEMENT_ENABLED) {
            cc.sighs.gravityengine.gravity.integration.diagnostics.MovementCollisionDiagnostics.vanillaInput(e, m);
        }
    }

    /** Observe the actual Vanilla collision return; no second solve or custom
     * interpretation of Vanilla's step-selection policy. */
    @Inject(method = "collide", at = @At("RETURN"), require = 1)
    private void gravityengine$observeVanillaCollision(Vec3 movement, CallbackInfoReturnable<Vec3> cir) {
        var entity = (Entity) (Object) this;
        if (GravityDebugLog.MOVEMENT_ENABLED && EntityMovementIntegration.movementRoute(entity) == GravityInfluencePolicy.CollisionRoute.VANILLA) {
            cc.sighs.gravityengine.gravity.integration.diagnostics.MovementCollisionDiagnostics.vanillaResult(entity, cir.getReturnValue());
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
                        EntityMovementIntegration.movementRoute((Entity) (Object) this)
                                != GravityInfluencePolicy.CollisionRoute.VANILLA
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
        if (!GravityInfluencePolicy.usesCustomBody(entity)) {
            original.call(entity, x, y, z);
            return;
        }
        GravityRuntimeState runtime = gravityengine$gravityComponent.runtime();
        GravityFrame frame = runtime.isInMove()
                ? runtime.activeFrame()
                : runtime.geometryReferenceFrame();
        if (frame == null) {
            throw new IllegalStateException(
                    "custom translation has no installed geometry frame"
            );
        }
        if (runtime.discontinuityDestination() != null) {
            return;
        }
        this.gravityengine$resolvedMovementWriteDepth++;
        try {
            original.call(entity, x, y, z);
        } finally {
            this.gravityengine$resolvedMovementWriteDepth--;
        }
        GravityEntityGeometry.reanchorAfterVanillaTranslation(entity, frame);
    }

    /** 1.21.1 / NeoForge 21.1.249 walk-distance uses committed displacement. */
    @Redirect(method = "move", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/phys/Vec3;horizontalDistance()D"), require = 1)
    private double gravityengine$walkDist(Vec3 m) {
        var e = (Entity)(Object)this;
        if (EntityMovementIntegration.movementRoute(e) == GravityInfluencePolicy.CollisionRoute.VANILLA) return m.horizontalDistance();
        var loc = gravityengine$gravityComponent.runtime()
                .activeFrame().worldToLocal(m);
        return Math.sqrt(loc.x * loc.x + loc.z * loc.z);
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
        GravityInfluencePolicy.CollisionRoute route =
                EntityMovementIntegration.movementRoute(entity);
        if (route == GravityInfluencePolicy.CollisionRoute.VANILLA) {
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
        if (EntityMovementIntegration.movementRoute(entity) == GravityInfluencePolicy.CollisionRoute.VANILLA) {
            return original.call(entity);
        }

        return gravityengine$authoritativeOnPos(entity);
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
        if (EntityMovementIntegration.movementRoute(entity) == GravityInfluencePolicy.CollisionRoute.VANILLA) {
            return original.call(velocity, x, y, z);
        }
        var runtime = gravityengine$gravityComponent.runtime();
        var frame = runtime.activeFrame();
        return frame.localToWorld(frame.worldToLocal(velocity).multiply(x, y, z));
    }

    @Unique
    private BlockPos gravityengine$authoritativeOnPos(Entity entity) {
        var state = EntityMovementIntegration.supportingCollisionState(entity);
        if (state != null && state.mainSupportingBlockPos().isPresent()) return state.mainSupportingBlockPos().get();
        if (this.mainSupportingBlockPos.isPresent()) return this.mainSupportingBlockPos.get();
        var frame = gravityengine$gravityComponent.runtime().activeFrame();
        return VanillaBodySensors.gravityRelativeOnPos(frame, GravityEntityGeometry.gravityFeet(entity, frame));
    }

    @Inject(method = "load", at = @At("RETURN")) private void gravityengine$readNbt(CompoundTag t, CallbackInfo ci) { gravityengine$gravityComponent.readFromNbt(t); }
    @Inject(method = "saveWithoutId", at = @At("RETURN")) private void gravityengine$writeNbt(CompoundTag t, CallbackInfoReturnable<CompoundTag> cir) { gravityengine$gravityComponent.writeToNbt(t); }
    
}
