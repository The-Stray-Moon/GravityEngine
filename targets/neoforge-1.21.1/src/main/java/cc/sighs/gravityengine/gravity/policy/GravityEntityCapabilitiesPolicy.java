package cc.sighs.gravityengine.gravity.policy;

import cc.sighs.gravityengine.gravity.model.*;
import cc.sighs.gravityengine.gravity.minecraft.geometry.CharacterDimensionPolicy;
import cc.sighs.gravityengine.gravity.minecraft.geometry.GravityEntityGeometry;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.FlyingMob;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ambient.Bat;
import net.minecraft.world.entity.animal.FlyingAnimal;
import net.minecraft.world.entity.animal.allay.Allay;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.monster.Shulker;
import net.minecraft.world.entity.monster.Vex;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.entity.projectile.LlamaSpit;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ThrowableProjectile;

/** Classifies entity-kind capability without consulting field presence. */
public final class GravityEntityCapabilitiesPolicy {
    private static final GravityEntityCapabilities VANILLA_SPECIAL =
            GravityEntityCapabilities.VANILLA_SPECIAL;
    private static final GravityEntityCapabilities BALLISTIC =
            GravityEntityCapabilities.BALLISTIC;
    private static final GravityEntityCapabilities PASSIVE_BALLISTIC =
            GravityEntityCapabilities.PASSIVE_BALLISTIC;
    private static final GravityEntityCapabilities FLYING_BLOCKER =
            GravityEntityCapabilities.FLYING_BLOCKER;

    private GravityEntityCapabilitiesPolicy() {}

    public static GravityEntityCapabilities capabilities(Entity entity) {
        if (entity instanceof ThrowableProjectile
                || entity instanceof AbstractArrow
                // 21.1.249 LlamaSpit.tick and FishingHook.tick both own a
                // simple focused gravity boundary (drag, gravity, position
                // commit in one tick). They opt into the same BALLISTIC
                // acceleration adapter as Throwable/Arrow; their Vanilla
                // AABB body, vanilla collision and vanilla tick remain
                // untouched. This is an explicit opt-in list, never a
                // blanket "all Projectile" classification: WindCharge,
                // FireworkRocket, ShulkerBullet, FishingHook's fluid bobbing
                // and every guided/tethered projectile family stay separate.
                || entity instanceof LlamaSpit
                || entity instanceof FishingHook) {
            return BALLISTIC;
        }
        // These vanilla ticks have a simple gravity boundary, but their
        // gravity-relative resting/placement semantics remain separate work.
        if (entity instanceof ItemEntity
                || entity instanceof ExperienceOrb
                || entity instanceof PrimedTnt
                || entity instanceof FallingBlockEntity) {
            return PASSIVE_BALLISTIC;
        }
        // Guided, propelled, tethered and other special projectile families
        // must opt in through a focused adapter rather than inheriting the
        // Throwable/Arrow contract accidentally.
        if (entity instanceof Projectile) {
            return VANILLA_SPECIAL;
        }
        if (entity instanceof LivingEntity living) {
            // Current installed dimensions include NeoForge Size overrides.
            // Unsupported ordinary shapes have no custom capability, so the
            // planner never enters an exact-body installation/retry loop.
            var dimensions = GravityEntityGeometry.dimensions(entity);
            if (CharacterDimensionPolicy.decide(dimensions.width(), dimensions.height())
                    != CharacterDimensionPolicy.Decision.CAPSULE) return VANILLA_SPECIAL;
            return livingCapabilities(
                    isFlyingControlled(living),
                    isVanillaSpecialLiving(living)
            );
        }
        return VANILLA_SPECIAL;
    }

    static GravityEntityCapabilities livingCapabilities(boolean flyingControlled) {
        return livingCapabilities(flyingControlled, false);
    }

    static GravityEntityCapabilities livingCapabilities(
            boolean flyingControlled,
            boolean specialMovement
    ) {
        if (specialMovement) {
            return VANILLA_SPECIAL;
        }
        if (flyingControlled) {
            return FLYING_BLOCKER;
        }
        return GravityEntityCapabilities.CHARACTER;
    }

    static GravityEntityCapabilities ballisticCapabilities() {
        return BALLISTIC;
    }

    static GravityEntityCapabilities passiveBallisticCapabilities() {
        return PASSIVE_BALLISTIC;
    }

    /**
     * 1.21.1 mapped hierarchy audit: Bee and Parrot implement FlyingAnimal;
     * Phantom and Ghast extend FlyingMob (a Mob-level public parent), so the
     * parent type is the authoritative semantic check. Allay, Vex and Bat use
     * independent hierarchies and therefore need explicit classification.
     */
    public static boolean isFlyingControlled(LivingEntity entity) {
        return entity instanceof FlyingAnimal
                || entity instanceof FlyingMob
                || entity instanceof Allay
                || entity instanceof Vex
                || entity instanceof Bat;
    }

    /**
     * Conservative VANILLA_SPECIAL classification for LivingEntities whose
     * movement is not ordinary ground/air locomotion and has no character
     * adapter yet:
     *
     * <ul>
     *   <li>{@link ArmorStand}: marker/decoration body; no normal travel.</li>
     *   <li>{@link Shulker}: teleport/levitation movement.</li>
     *   <li>{@link EnderDragon}: phase-driven flight, no travel locomotion.</li>
     *   <li>{@link WitherBoss}: controlled hover flight via MoveControl.</li>
     * </ul>
     *
     * False positives here would hand a special entity to the character
     * controller, so the list stays conservative and explicit.
     */
    public static boolean isVanillaSpecialLiving(LivingEntity entity) {
        return entity instanceof ArmorStand
                || entity instanceof Shulker
                || entity instanceof EnderDragon
                || entity instanceof WitherBoss;
    }
}
