package cc.sighs.gravityengine.mixin;

import cc.sighs.gravityengine.gravity.collision.*;
import cc.sighs.gravityengine.gravity.integration.MovementEnvironmentCapture;
import cc.sighs.gravityengine.gravity.minecraft.GravityFrameAccess;
import cc.sighs.gravityengine.gravity.minecraft.math.MinecraftMathAdapter;
import cc.sighs.gravityengine.gravity.policy.GravityInfluencePolicy;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import it.unimi.dsi.fastutil.objects.Object2DoubleMap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.fluids.FluidType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Captured physical fluid geometry feeds native fluid metadata. Native baseTick
 * still owns splash, extinguishing, drowning, damage, swimming and cooldowns. */
@Mixin(Entity.class)
public abstract class EntityFluidEnvironmentMixin {
    @Shadow protected Object2DoubleMap<FluidType> forgeFluidTypeHeight;
    @Shadow private FluidType forgeFluidTypeOnEyes;
    @Shadow protected boolean wasEyeInWater;
    @Shadow private java.util.Set<net.minecraft.tags.TagKey<net.minecraft.world.level.material.Fluid>> fluidOnEyes;

    @WrapMethod(method = "updateInWaterStateAndDoFluidPushing", require = 1)
    private boolean gravityengine$fluidMetadataScope(Operation<Boolean> original) {
        var entity=(Entity)(Object)this;
        if(!(entity instanceof LivingEntity actor) || actor.isPassenger() || actor.noPhysics
                || !GravityInfluencePolicy.usesCustomLocomotion(actor)) return original.call();
        MovementEnvironmentCapture.Snapshot snapshot;
        try { snapshot=MovementEnvironmentCapture.captureOrCurrent(actor); }
        catch(CollisionSceneCoverageException | CollisionComplexityLimitException unavailable) {
            cc.sighs.gravityengine.gravity.debug.CollisionCoverageDiagnostics.report(actor,unavailable);
            return actor.isInFluidType(); // Native maps have not been cleared; absence was not established.
        }
        try(var scope=MovementEnvironmentCapture.open(actor,snapshot)) { return original.call(); }
    }

    @WrapMethod(method = "updateFluidHeightAndDoFluidPushing()V", require = 1)
    private void gravityengine$physicalFluidFlow(Operation<Void> original) {
        var entity=(Entity)(Object)this;
        if(!(entity instanceof LivingEntity actor) || actor.isPassenger() || actor.noPhysics
                || !GravityInfluencePolicy.usesCustomLocomotion(actor)) { original.call();return; }
        MovementEnvironmentCapture.Snapshot snapshot;
        try { snapshot=MovementEnvironmentCapture.captureOrCurrent(actor); }
        catch(CollisionSceneCoverageException | CollisionComplexityLimitException unavailable) {
            // Native packet handling also calls this public method directly,
            // outside updateInWaterStateAndDoFluidPushing's capture scope.
            // Expected missing evidence defers this update without an impulse
            // or partial map write; it must not disconnect the connection.
            cc.sighs.gravityengine.gravity.debug.CollisionCoverageDiagnostics.report(actor,unavailable);
            return;
        }
        var update=MovementEnvironmentCapture.fluidUpdate(actor,snapshot);
        // No partial metadata or current impulse is committed before every region was captured.
        actor.setDeltaMovement(update.velocity());
        update.heights().forEach((type,height)->forgeFluidTypeHeight.put(type,height.doubleValue()));
    }

    @WrapMethod(method = "updateFluidOnEyes", require = 1)
    private void gravityengine$physicalEyeFluid(Operation<Void> original) {
        var entity=(Entity)(Object)this;
        if(!(entity instanceof LivingEntity actor) || actor.isPassenger() || actor.noPhysics
                || !GravityInfluencePolicy.usesCustomLocomotion(actor)) { original.call();return; }
        MovementEnvironmentCapture.Snapshot snapshot;
        try { snapshot=MovementEnvironmentCapture.captureOrCurrent(actor); }
        catch(CollisionSceneCoverageException | CollisionComplexityLimitException unavailable) {
            cc.sighs.gravityengine.gravity.debug.CollisionCoverageDiagnostics.report(actor,unavailable);return;
        }
        var eye=MinecraftMathAdapter.toVec3d(actor.getEyePosition());
        var selected=net.neoforged.neoforge.common.NeoForgeMod.EMPTY_TYPE.value();
        for(var region:snapshot.fluids()) {
            if(region.contains(eye)) {
                selected=region.state().getFluidType();break;
            }
        }
        wasEyeInWater=actor.isEyeInFluid(net.minecraft.tags.FluidTags.WATER);
        fluidOnEyes.clear();forgeFluidTypeOnEyes=selected;
    }
}
