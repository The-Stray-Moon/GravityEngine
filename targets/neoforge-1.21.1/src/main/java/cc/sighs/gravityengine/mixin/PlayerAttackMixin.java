package cc.sighs.gravityengine.mixin;

import cc.sighs.gravityengine.gravity.integration.vanilla.VanillaActorBridge;
import cc.sighs.gravityengine.gravity.integration.vanilla.VanillaActorSnapshot;
import cc.sighs.gravityengine.gravity.integration.vanilla.VanillaMeleeBridge;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Player melee attack spatial operands.
 *
 * <p>21.1.249 {@code Player.attack} constructs knockback and push carriers
 * from raw {@code yRot}.  The three calls are the narrow producer seams that
 * still have attacker context; this mixin replaces only those directional
 * operands with one captured actor snapshot.  Vanilla retains attack damage,
 * knockback strength, critical/enchantment/cooldown policy, sweep eligibility
 * and callbacks.  The sweep candidate query is refined with exact-body
 * geometry before the Vanilla per-target loop so a phantom enclosing-AABB
 * candidate can never receive sweep knockback/damage side effects.
 * {@code LivingEntity.knockback} itself is never globally reinterpreted.</p>
 */
@Mixin(Player.class)
public abstract class PlayerAttackMixin {
    @Inject(method = "attack", at = @At("HEAD"))
    private void gravityengine$captureAttackActor(
            Entity target,
            CallbackInfo ci,
            @Share("gravityengineAttackActor")
            LocalRef<VanillaActorSnapshot> actorRef,
            @Share("gravityengineAttackTarget")
            LocalRef<Entity> targetRef
    ) {
        actorRef.set(VanillaActorBridge.capture(
                (Player) (Object) this));
        targetRef.set(target);
    }

    @WrapOperation(
            method = "attack",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/LivingEntity;"
                            + "knockback(DDD)V",
                    ordinal = 0
            ),
            require = 1
    )
    private void gravityengine$attackKnockbackDirection(
            LivingEntity target,
            double strength,
            double x,
            double z,
            Operation<Void> original,
            @Share("gravityengineAttackActor")
            LocalRef<VanillaActorSnapshot> actorRef
    ) {
        VanillaActorSnapshot actor = actorRef.get();
        if (!VanillaMeleeBridge.tryPlayerKnockback(target, actor, strength, x, z)) {
            original.call(target, strength, x, z);
        }
    }

    @WrapOperation(
            method = "attack",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/Entity;"
                            + "push(DDD)V"
            ),
            require = 1
    )
    private void gravityengine$attackPushDirection(
            Entity target,
            double x,
            double y,
            double z,
            Operation<Void> original,
            @Share("gravityengineAttackActor")
            LocalRef<VanillaActorSnapshot> actorRef
    ) {
        VanillaActorSnapshot actor = actorRef.get();
        if (!(actor.transformedLook() || actor.nonDefaultReferenceFrame())) {
            original.call(target, x, y, z);
            return;
        }
        double horizontal = Math.hypot(x, z);
        Vec3 push = VanillaMeleeBridge.meleeWorldDirection(actor)
                .scale(horizontal)
                .add(actor.referenceUp().scale(y));
        original.call(target, push.x, push.y, push.z);
    }

    @WrapOperation(
            method = "attack",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/LivingEntity;"
                            + "knockback(DDD)V",
                    ordinal = 1
            ),
            require = 1
    )
    private void gravityengine$sweepKnockbackDirection(
            LivingEntity target,
            double strength,
            double x,
            double z,
            Operation<Void> original,
            @Share("gravityengineAttackActor")
            LocalRef<VanillaActorSnapshot> actorRef
    ) {
        VanillaActorSnapshot actor = actorRef.get();
        if (!VanillaMeleeBridge.tryPlayerKnockback(target, actor, strength, x, z)) {
            original.call(target, strength, x, z);
        }
    }

    /**
     * Sweep candidate refinement seam.
     *
     * <p>Vanilla 21.1.249 computes {@code itemstack.getSweepHitBox(this,
     * target)} and then iterates
     * {@code level.getEntitiesOfClass(LivingEntity.class, sweepBox)}.  Each
     * loop body applies knockback, damage, enchantment effects and callbacks
     * in that order, so filtering at the {@code hurt(...)} call is too late
     * for phantom candidates (they would already have received knockback).
     * This wrap refines the broad-phase candidate list itself: a custom
     * exact-body candidate survives only when its oriented body actually
     * overlaps the same sweep volume; ordinary Vanilla AABB candidates are
     * untouched.  Vanilla owns the subsequent distance check, knockback
     * strength, damage computation, enchantments and event/callback order.
     */
    @WrapOperation(
            method = "attack",
            at = @At(
                    value = "INVOKE",
                    target =
                            "Lnet/minecraft/world/level/Level;"
                                    + "getEntitiesOfClass("
                                    + "Ljava/lang/Class;"
                                    + "Lnet/minecraft/world/phys/AABB;)"
                                    + "Ljava/util/List;"
            ),
            require = 1
    )
    private List<LivingEntity> gravityengine$sweepExactCandidates(
            Level level,
            Class<LivingEntity> entityClass,
            AABB sweepBox,
            Operation<List<LivingEntity>> original,
            @Share("gravityengineAttackTarget")
            LocalRef<Entity> targetRef
    ) {
        List<LivingEntity> candidates = original.call(
                level, entityClass, sweepBox);
        if (candidates.isEmpty()) {
            return candidates;
        }

        Entity mainTarget = targetRef.get();
        if (mainTarget == null) {
            return candidates;
        }

        List<LivingEntity> exact = null;
        for (LivingEntity candidate : candidates) {
            if (!VanillaMeleeBridge.sweepCandidatePasses(
                    candidate, sweepBox)) {
                if (exact == null) {
                    exact = new ArrayList<>(candidates.size());
                    for (LivingEntity kept : candidates) {
                        if (kept == candidate) {
                            break;
                        }
                        exact.add(kept);
                    }
                }
                continue;
            }
            if (exact != null) {
                exact.add(candidate);
            }
        }
        return exact == null ? candidates : exact;
    }
}
