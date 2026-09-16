package cc.sighs.gravityengine.mixin;

import cc.sighs.gravityengine.gravity.GravityFrame;
import cc.sighs.gravityengine.gravity.integration.vanilla.VanillaActorBridge;
import cc.sighs.gravityengine.gravity.integration.vanilla.VanillaActorSnapshot;
import cc.sighs.gravityengine.gravity.integration.vanilla.VanillaProjectileBridge;

import cc.sighs.gravityengine.gravity.integration.vanilla.VanillaProjectileBridge.AimOperands;
import cc.sighs.gravityengine.gravity.minecraft.GravityFrameAccess;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.breeze.Breeze;
import net.minecraft.world.entity.monster.breeze.Shoot;
import net.minecraft.world.entity.projectile.windcharge.BreezeWindCharge;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static cc.sighs.gravityengine.gravity.integration.vanilla.VanillaProjectileBridge.bodyHeightPoint;
import static cc.sighs.gravityengine.gravity.integration.vanilla.VanillaProjectileBridge.referenceTangentCarrier;
import static cc.sighs.gravityengine.gravity.integration.vanilla.VanillaProjectileBridge.referenceVerticalComponent;
import static cc.sighs.gravityengine.gravity.integration.vanilla.VanillaProjectileBridge.worldShot;

/**
 * Version-matched Breeze shooting bridge.
 *
 * <p>Vanilla 21.1.249 first executes {@code lookAt(EYES, target.position())},
 * then tests {@code isFacingTarget}, then computes an aim vector from
 * Breeze {@code getY(0.5)} to target {@code getY(0.3/0.8)}. The projectile's
 * physical snout origin is a separate constructor concern.</p>
 *
 * <p>One environmental frame is captured at the lookAt seam and reused for
 * all translated operands in this tick invocation. The post-lookAt semantic
 * actor snapshot is captured once and reused by facing and shot construction.</p>
 */
@Mixin(Shoot.class)
public abstract class BreezeRangedMixin {
    private static final String METHOD =
            "tick(Lnet/minecraft/server/level/ServerLevel;"
                    + "Lnet/minecraft/world/entity/monster/breeze/Breeze;J)V";

    /**
     * First frame-sensitive seam in the Vanilla operation. Capture the
     * environmental frame once and translate only lookAt's target carrier.
     */
    @WrapOperation(
            method = METHOD,
            at = @At(
                    value = "INVOKE",
                    target =
                            "Lnet/minecraft/world/entity/monster/breeze/"
                                    + "Breeze;lookAt("
                                    + "Lnet/minecraft/commands/arguments/"
                                    + "EntityAnchorArgument$Anchor;"
                                    + "Lnet/minecraft/world/phys/Vec3;)V"
            ),
            require = 1
    )
    private void gravityengine$referenceLookAt(
            Breeze owner,
            EntityAnchorArgument.Anchor anchor,
            Vec3 point,
            Operation<Void> original,
            @Share("gravityengineBreezeFrame")
            LocalRef<GravityFrame> frameRef
    ) {
        GravityFrame frame =
                GravityFrameAccess
                        .authoritativeFrame(owner);

        frameRef.set(frame);

        original.call(
                owner,
                anchor,
                VanillaProjectileBridge.mobLookAtTargetCarrier(
                                owner,
                                frame,
                                anchor,
                                point
                        )
        );
    }

    /**
     * The Vanilla facing threshold is exactly {@code dot > 0.5}. Capture the
     * post-lookAt actor once against the already-owned frame.
     */
    @WrapOperation(
            method = METHOD,
            at = @At(
                    value = "INVOKE",
                    target =
                            "Lnet/minecraft/world/entity/monster/breeze/"
                                    + "Shoot;isFacingTarget("
                                    + "Lnet/minecraft/world/entity/monster/"
                                    + "breeze/Breeze;"
                                    + "Lnet/minecraft/world/entity/"
                                    + "LivingEntity;)Z"
            ),
            require = 1
    )
    private boolean gravityengine$facingGate(
            Breeze owner,
            LivingEntity target,
            Operation<Boolean> original,
            @Share("gravityengineBreezeFrame")
            LocalRef<GravityFrame> frameRef,
            @Share("gravityengineBreezeActor")
            LocalRef<VanillaActorSnapshot> actorRef
    ) {
        GravityFrame frame =
                frameRef.get();

        if (frame == null) {
            throw new IllegalStateException(
                    "Breeze ranged bridge reached facing gate "
                            + "without its operation GravityFrame"
            );
        }

        VanillaActorSnapshot actor =
                VanillaActorBridge.capture(
                        owner,
                        frame
                );

        actorRef.set(actor);

        if (!actor.transformedLook()) {
            return original.call(
                    owner,
                    target
            );
        }

        return VanillaProjectileBridge.facesTarget(
                        actor,
                        target.position(),
                        0.5D
                );
    }

    @Inject(
            method = METHOD,
            at = @At(
                    value = "INVOKE",
                    target =
                            "Lnet/minecraft/world/entity/"
                                    + "LivingEntity;getX()D",
                    ordinal = 0
            ),
            require = 1
    )
    private void gravityengine$captureAim(
            ServerLevel level,
            Breeze owner,
            long time,
            CallbackInfo ci,
            @Local LivingEntity target,
            @Share("gravityengineBreezeActor")
            LocalRef<VanillaActorSnapshot> actorRef,
            @Share("gravityengineAim")
            LocalRef<AimOperands> aimRef
    ) {
        VanillaActorSnapshot shooter =
                actorRef.get();

        if (shooter == null) {
            throw new IllegalStateException(
                    "Breeze ranged bridge reached aim construction "
                            + "without its post-lookAt actor snapshot"
            );
        }

        Vec3 targetPoint =
                VanillaProjectileBridge.bodyHeightPoint(
                        target,
                        target.isPassenger()
                                ? 0.8D
                                : 0.3D
                );

        // Vanilla aim source is Breeze getY(0.5), not projectile snout.
        Vec3 source =
                VanillaProjectileBridge.bodyHeightPoint(
                        shooter,
                        0.5D
                );

        Vec3 delta =
                targetPoint.subtract(source);

        aimRef.set(
                new VanillaProjectileBridge.AimOperands(
                        shooter.referenceFrame(),
                        VanillaProjectileBridge.referenceTangentCarrier(
                                shooter.referenceFrame(),
                                delta
                        ),
                        VanillaProjectileBridge.referenceVerticalComponent(
                                shooter.referenceFrame(),
                                delta
                        ),
                        shooter.customBody()
                                || cc.sighs.gravityengine.gravity.policy.GravityInfluencePolicy.usesCustomBody(target)
                                || shooter.nonDefaultReferenceFrame()
                )
        );
    }

    @ModifyVariable(
            method = METHOD,
            at = @At("STORE"),
            ordinal = 0,
            require = 1
    )
    private double gravityengine$aimX(
            double vanilla,
            @Share("gravityengineAim")
            LocalRef<AimOperands> ref
    ) {
        AimOperands operands = ref.get();
        return operands.translated()
                ? operands.tangentCarrier().x
                : vanilla;
    }

    @ModifyVariable(
            method = METHOD,
            at = @At("STORE"),
            ordinal = 1,
            require = 1
    )
    private double gravityengine$aimY(
            double vanilla,
            @Share("gravityengineAim")
            LocalRef<AimOperands> ref
    ) {
        AimOperands operands = ref.get();
        return operands.translated()
                ? operands.vertical()
                : vanilla;
    }

    @ModifyVariable(
            method = METHOD,
            at = @At("STORE"),
            ordinal = 2,
            require = 1
    )
    private double gravityengine$aimZ(
            double vanilla,
            @Share("gravityengineAim")
            LocalRef<AimOperands> ref
    ) {
        AimOperands operands = ref.get();
        return operands.translated()
                ? operands.tangentCarrier().z
                : vanilla;
    }

    @WrapOperation(
            method = METHOD,
            at = @At(
                    value = "INVOKE",
                    target =
                            "Lnet/minecraft/world/entity/projectile/"
                                    + "windcharge/BreezeWindCharge;"
                                    + "shoot(DDDFF)V"
            ),
            require = 1
    )
    private void gravityengine$worldShot(
            BreezeWindCharge projectile,
            double x,
            double y,
            double z,
            float velocity,
            float inaccuracy,
            Operation<Void> original,
            @Share("gravityengineAim")
            LocalRef<AimOperands> ref
    ) {
        AimOperands operands =
                ref.get();

        if (!operands.translated()) {
            original.call(
                    projectile,
                    x,
                    y,
                    z,
                    velocity,
                    inaccuracy
            );
            return;
        }

        Vec3 world =
                VanillaProjectileBridge.worldShot(
                        operands.frame(),
                        x,
                        y,
                        z
                );

        original.call(
                projectile,
                world.x,
                world.y,
                world.z,
                velocity,
                inaccuracy
        );
    }
}
