package cc.sighs.gravityengine.mixin;

import cc.sighs.gravityengine.gravity.minecraft.geometry.GravityEntityGeometry;
import cc.sighs.gravityengine.gravity.minecraft.math.MinecraftMathAdapter;
import cc.sighs.gravityengine.gravity.policy.GravityInfluencePolicy;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Unique;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import net.minecraft.network.syncher.EntityDataAccessor;
import org.spongepowered.asm.mixin.injection.At;

/** ArmorStand's native tick/travel remains the owner, with no active input. */
@Mixin(ArmorStand.class)
public abstract class ArmorStandGravityMixin implements cc.sighs.gravityengine.gravity.minecraft.access.GravityArmorStandAccess {
    @Shadow @Final private static EntityDataAccessor<Byte> DATA_CLIENT_FLAGS;
    /** Rollback receipt for the flag-driven dimensions callback, never physical orientation. */
    @Unique private byte gravityengine$acceptedDimensionFlags;
    @Unique private boolean gravityengine$restoringFlags;
    @Unique private boolean gravityengine$nativeNoPhysics;
    @Unique private boolean gravityengine$pendingDimensions;
    @Unique private boolean gravityengine$applyingFlags;

    @Override public boolean gravityengine$nativeNoPhysics() { return gravityengine$nativeNoPhysics; }
    @Override public boolean gravityengine$pendingDimensions() { return gravityengine$pendingDimensions; }
    @Override public void gravityengine$clearPendingDimensions() { gravityengine$pendingDimensions=false; }

    @WrapOperation(method="readAdditionalSaveData", at=@At(value="FIELD",
            target="Lnet/minecraft/world/entity/decoration/ArmorStand;noPhysics:Z",opcode=181),require=1)
    private void gravityengine$loadDerivedPhysics(ArmorStand stand, boolean derived, Operation<Void> original) {
        // Do not conflate Vanilla's derived flag with later external writes to Entity.noPhysics.
        gravityengine$nativeNoPhysics = stand.isMarker() || stand.isNoGravity();
    }

    @org.spongepowered.asm.mixin.injection.Inject(method="tick",at=@At("HEAD"),require=1)
    private void gravityengine$retryDimensions(org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        ArmorStand stand=(ArmorStand)(Object)this;
        if (stand.isRemoved()) { gravityengine$pendingDimensions=false; return; }
        if (stand.level().isClientSide() && gravityengine$pendingDimensions)
            stand.onSyncedDataUpdated(DATA_CLIENT_FLAGS);
    }

    // Synced data remains the authoritative target. Until legal installation, all geometry,
    // interaction and presentation consumers see the last committed representation.
    @org.spongepowered.asm.mixin.injection.Inject(method="isSmall",at=@At("HEAD"),cancellable=true,require=1)
    private void gravityengine$installedSmall(org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> ci) {
        if (gravityengine$pendingDimensions && !gravityengine$applyingFlags)
            ci.setReturnValue((gravityengine$acceptedDimensionFlags & 1)!=0);
    }
    @org.spongepowered.asm.mixin.injection.Inject(method="isMarker",at=@At("HEAD"),cancellable=true,require=1)
    private void gravityengine$installedMarker(org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> ci) {
        if (gravityengine$pendingDimensions && !gravityengine$applyingFlags)
            ci.setReturnValue((gravityengine$acceptedDimensionFlags & 16)!=0);
    }

    @WrapMethod(method = "onSyncedDataUpdated", require = 1)
    private void gravityengine$dimensionFlags(EntityDataAccessor<?> key, Operation<Void> original) {
        if (!DATA_CLIENT_FLAGS.equals(key)) { original.call(key); return; }
        if (gravityengine$restoringFlags) return;
        ArmorStand stand = (ArmorStand) (Object) this;
        byte incoming = stand.getEntityData().get(DATA_CLIENT_FLAGS);
        gravityengine$applyingFlags=true;
        try {
            boolean custom = GravityInfluencePolicy.usesCustomBody(stand);
            var before = GravityEntityGeometry.dimensions(stand);
            var expected = stand.getDimensions(stand.getPose());
            // Marker has no capsule. Use the existing legal native-body handoff first;
            // a blocked handoff rejects the flag transaction rather than leaving a phantom body.
            if (custom && stand.isMarker()) {
                if (stand.level().isClientSide()) {
                    cc.sighs.gravityengine.gravity.integration.GravityApplicationCoordinator
                            .updateReplicaBody(stand);
                } else {
                    cc.sighs.gravityengine.gravity.integration.GravityApplicationCoordinator
                            .updateBody(stand);
                }
                if (GravityInfluencePolicy.usesCustomBody(stand)) {
                    gravityengine$rejectOrDefer(stand, incoming);
                    return;
                }
            }
            try {
                original.call(key);
            } catch (RuntimeException | Error failure) {
                gravityengine$rejectOrDefer(stand, incoming);
                throw failure;
            }
            var installed = GravityEntityGeometry.dimensions(stand);
            if (custom && installed.equals(before) && !expected.equals(before)
                    && ((incoming ^ gravityengine$acceptedDimensionFlags) & 17) != 0) {
                gravityengine$rejectOrDefer(stand, incoming);
            } else {
                gravityengine$acceptedDimensionFlags = (byte) (incoming & 17);
                gravityengine$pendingDimensions=false;
            }
        } finally { gravityengine$applyingFlags=false; }
    }

    @Unique private void gravityengine$rejectOrDefer(ArmorStand stand, byte incoming) {
        if (stand.level().isClientSide()) {
            gravityengine$pendingDimensions=true;
            stand.blocksBuilding=(gravityengine$acceptedDimensionFlags & 16)==0;
        } else gravityengine$restoreFlags(stand,incoming);
    }

    @Unique
    private void gravityengine$restoreFlags(ArmorStand stand, byte incoming) {
        gravityengine$restoringFlags = true;
        try {
            stand.getEntityData().set(DATA_CLIENT_FLAGS,
                    (byte) ((incoming & ~17) | gravityengine$acceptedDimensionFlags));
            stand.blocksBuilding = !stand.isMarker();
        } finally { gravityengine$restoringFlags = false; }
    }

    /** Native hasPhysics gates both isEffectiveAi and travel, and initializes noPhysics on load.
     * NoGravity disables acceleration in LivingEntity.travel; it must not disable external motion.
     * Marker keeps its native exclusion. Explicit noPhysics still suppresses GE through the planner.
     */
    @ModifyExpressionValue(method = "hasPhysics", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/decoration/ArmorStand;isNoGravity()Z"), require = 1)
    private boolean gravityengine$noGravityIsNotImmobility(boolean original) {
        return original && !cc.sighs.gravityengine.gravity.integration.ArmorStandIntegration
                .ownsMotion((ArmorStand)(Object)this);
    }

    @WrapOperation(method = "travel", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/LivingEntity;travel(Lnet/minecraft/world/phys/Vec3;)V"), require = 1)
    private void gravityengine$passiveTravel(ArmorStand stand,
            Vec3 input, Operation<Void> original) {
        original.call(stand, cc.sighs.gravityengine.gravity.integration.ArmorStandIntegration.ownsMotion(stand) ? Vec3.ZERO : input);
    }

    /** interactAt supplies hit - Entity.position(), not a feet-relative vector.
     * Use installed height and committed up, never interpolated presentation.
     */
    @WrapOperation(method = "interactAt", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/decoration/ArmorStand;getClickedSlot(Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/entity/EquipmentSlot;"), require = 1)
    private EquipmentSlot gravityengine$localEquipmentHeight(ArmorStand stand, Vec3 hit,
            Operation<EquipmentSlot> original) {
        if (GravityInfluencePolicy.usesCustomBody(stand)) {
            double halfHeight = GravityEntityGeometry.dimensions(stand).height() * .5;
            Vec3 up = MinecraftMathAdapter.toMinecraft(GravityEntityGeometry.installedUp(stand));
            double height = hit.subtract(0, halfHeight, 0).dot(up) + halfHeight;
            hit = new Vec3(hit.x, height, hit.z);
        }
        return original.call(stand, hit);
    }
}
