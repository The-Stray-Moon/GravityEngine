package cc.sighs.gravityengine.mixin;

import cc.sighs.gravityengine.client.GravityPresentationIntegration;
import cc.sighs.gravityengine.gravity.integration.vanilla.VanillaTargetingBridge;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.function.Predicate;

/**
 * Ordering contract with an optional third-party pick owner.
 *
 * <p>Sable 2.0.5 redirects {@code Entity.getEyePosition(F)} inside
 * {@code GameRenderer.pick} for SubLevel clipping. Mixin applies lower
 * priorities first, so a default-priority GravityEngine redirect would consume
 * that call site and fail Sable's mandatory injection. This mixin is therefore
 * applied after Sable's priority-1100 redirect and shares the eye-position call
 * through a chainable {@code @WrapOperation}, which returns the value Sable (or
 * Vanilla) produced whenever GravityEngine picking presentation is inactive.</p>
 *
 * <p>The block-pick and view-vector calls are not transformed by any mod in the
 * current compatibility environment and stay direct redirects.</p>
 */
@Mixin(value = GameRenderer.class, priority = 1200)
public abstract class GameRendererMixin {
    private static final String PICK_METHOD =
            "pick(Lnet/minecraft/world/entity/Entity;DDF)Lnet/minecraft/world/phys/HitResult;";

    // Fragile targets: these three calls form the block/entity ray inputs in GameRenderer#pick.
    @Redirect(
            method = PICK_METHOD,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;pick(DFZ)Lnet/minecraft/world/phys/HitResult;")
    )
    private HitResult gravityengine$pickBlock(
            Entity entity,
            double distance,
            float partialTick,
            boolean includeFluids
    ) {
        return GravityPresentationIntegration.pickBlock(entity, distance, partialTick, includeFluids);
    }

    @WrapOperation(
            method = PICK_METHOD,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;getEyePosition(F)Lnet/minecraft/world/phys/Vec3;"),
            require = 1
    )
    private Vec3 gravityengine$pickingEyePosition(
            Entity entity,
            float partialTick,
            Operation<Vec3> original
    ) {
        return GravityPresentationIntegration.pickingEyePosition(
                entity,
                partialTick,
                original.call(entity, partialTick)
        );
    }

    @Redirect(
            method = PICK_METHOD,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;getViewVector(F)Lnet/minecraft/world/phys/Vec3;")
    )
    private Vec3 gravityengine$pickingViewVector(Entity entity, float partialTick) {
        return GravityPresentationIntegration.viewVector(entity, partialTick);
    }

    /**
     * Client entity picking.
     *
     * <p>GameRenderer retains block/entity reach orchestration and
     * block-vs-entity precedence. The bridge reproduces the exact
     * version-matched {@code ProjectileUtil.getEntityHitResult(Entity,...)}
     * candidate policy, including pick radius, inside-origin behavior and
     * root-vehicle handling, while substituting exact inflated OBB geometry
     * only for custom-body candidates.</p>
     */
    @WrapOperation(
            method = PICK_METHOD,
            at = @At(
                    value = "INVOKE",
                    target =
                            "Lnet/minecraft/world/entity/projectile/"
                                    + "ProjectileUtil;getEntityHitResult("
                                    + "Lnet/minecraft/world/entity/Entity;"
                                    + "Lnet/minecraft/world/phys/Vec3;"
                                    + "Lnet/minecraft/world/phys/Vec3;"
                                    + "Lnet/minecraft/world/phys/AABB;"
                                    + "Ljava/util/function/Predicate;D)"
                                    + "Lnet/minecraft/world/phys/"
                                    + "EntityHitResult;"
            ),
            require = 1
    )
    private EntityHitResult gravityengine$exactClientEntityHit(
            Entity shooter,
            Vec3 start,
            Vec3 end,
            AABB broadPhase,
            Predicate<Entity> filter,
            double distance,
            Operation<EntityHitResult> original
    ) {
        return VanillaTargetingBridge.selectPickingEntityTarget(
                        shooter.level(),
                        shooter,
                        start,
                        end,
                        broadPhase,
                        filter,
                        distance
                );
    }
}
