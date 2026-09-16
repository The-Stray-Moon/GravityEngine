package cc.sighs.gravityengine.mixin;

import cc.sighs.gravityengine.attitude.runtime.BodyAttitudeComponent;
import cc.sighs.gravityengine.gravity.debug.PlayerViewDebugLog;
import cc.sighs.gravityengine.gravity.integration.collision.GravityPlayerPoseFitQuery;
import cc.sighs.gravityengine.gravity.policy.GravityInfluencePolicy;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Player.class)
public abstract class PlayerMixin extends LivingEntity implements cc.sighs.gravityengine.attitude.runtime.BodyAttitudeRuntime.Access,
        cc.sighs.gravityengine.gravity.minecraft.access.CharacterControlAccess {
    protected PlayerMixin(EntityType<? extends LivingEntity> type, Level level) { super(type, level); }

    /** Vanilla baseTick and LivingEntity.tick directly maintain/wrap yRotO/xRotO.
     * Observe the completed normal player tick without replacing those writes. */
    @WrapMethod(method = "tick", require = 1)
    private void gravityengine$traceViewTick(Operation<Void> original) {
        try (var trace = PlayerViewDebugLog.begin((Player) (Object) this, "player-tick")) {
            original.call();
        }
    }
    @Unique
    private final cc.sighs.gravityengine.gravity.movement.CharacterControlStep gravityengine$controlStep =
            new cc.sighs.gravityengine.gravity.movement.CharacterControlStep();
    @Unique
    private final cc.sighs.gravityengine.gravity.movement.CharacterControlMode gravityengine$controlMode =
            new cc.sighs.gravityengine.gravity.movement.CharacterControlMode();
    @Override
    public cc.sighs.gravityengine.gravity.movement.CharacterControlMode gravityengine$characterMode() {
        return gravityengine$controlMode;
    }
    @Override
    public cc.sighs.gravityengine.gravity.movement.CharacterControlStep gravityengine$characterControl() {
        return gravityengine$controlStep;
    }

    @Unique
    private volatile BodyAttitudeComponent gravityengine$bodyAttitude;

    @Override
    public BodyAttitudeComponent gravityengine$bodyAttitude() {
        BodyAttitudeComponent component = this.gravityengine$bodyAttitude;
        if (component == null) {
            synchronized (this) {
                component = this.gravityengine$bodyAttitude;
                if (component == null) {
                    component = new BodyAttitudeComponent();
                    this.gravityengine$bodyAttitude = component;
                }
            }
        }
        return component;
    }

    @Override
    public BodyAttitudeComponent gravityengine$peekBodyAttitude() {
        return this.gravityengine$bodyAttitude;
    }

    /*
     * NeoForge/Vanilla owns the whole updatePlayerPose state machine
     * (forcedPose, SLEEPING, SWIMMING, SPIN_ATTACK, FALL_FLYING,
     * abilities.flying, spectator, passenger and the vanilla crouch/swim
     * fallback ordering).  This mixin only replaces the single pose-fit query
     * with the strict gravity-oriented query for custom-body players.
     */
    @WrapMethod(
            method = "canPlayerFitWithinBlocksAndEntitiesWhen"
                    + "(Lnet/minecraft/world/entity/Pose;)Z",
            require = 1
    )
    private boolean gravityengine$poseFit(
            Pose pose,
            Operation<Boolean> original
    ) {
        Player self = (Player) (Object) this;
        if (!GravityInfluencePolicy.usesCustomBody(self)) {
            return original.call(pose);
        }
        return GravityPlayerPoseFitQuery.canFit(self, pose);
    }

    @org.spongepowered.asm.mixin.Shadow
    protected abstract boolean isStayingOnGroundSurface();

    /** The native pre-collision displacement seam. Route ownership is frozen by
     * Entity.move; Vanilla-equivalent collision executes the original method. */
    @Inject(method = "maybeBackOffFromEdge", at = @At("HEAD"), cancellable = true, require = 1)
    private void gravityengine$customGravityOwnsSneakEdge(Vec3 movement, MoverType moverType,
            CallbackInfoReturnable<Vec3> cir) {
        Player self = (Player) (Object) this;
        var runtime = cc.sighs.gravityengine.gravity.minecraft.access.GravityEntityAccess.cast(self)
                .gravityengine$gravityComponent().runtime();
        var route = runtime.movementCollisionRoute();
        if (route == null) route = GravityInfluencePolicy.collisionRoute(self);
        if (route == GravityInfluencePolicy.CollisionRoute.VANILLA) return;
        cir.setReturnValue(cc.sighs.gravityengine.gravity.integration.LivingGravityIntegration.applyPlayerSneakEdge(
                self, movement, moverType, this.isStayingOnGroundSurface()));
    }
}
