package cc.sighs.gravityengine.gravity.policy;

import cc.sighs.gravityengine.gravity.kinematic.geometry.CharacterDimensionPolicy;
import cc.sighs.gravityengine.gravity.minecraft.geometry.GravityEntityGeometry;
import cc.sighs.gravityengine.gravity.model.GravityEntityCapabilities;
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
import net.minecraft.world.entity.projectile.*;

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
        var explicit = EntityAdapterRegistry.resolve(entity);
        if (explicit != null) {
            return switch (explicit) {
                case NATIVE -> VANILLA_SPECIAL;
                case CONTROLLED_FLIGHT -> FLYING_BLOCKER;
                case CHARACTER -> entity instanceof LivingEntity && supportsCharacterDimensions(entity)
                        ? GravityEntityCapabilities.CHARACTER : VANILLA_SPECIAL;
                case BALLISTIC -> isBallistic(entity) ? BALLISTIC : VANILLA_SPECIAL;
                case PASSIVE_BALLISTIC -> isPassive(entity) ? PASSIVE_BALLISTIC : VANILLA_SPECIAL;
            };
        }
        // A custom travel implementation is not proof of character-kernel support.
        // Inherited ordinary travel remains available to ordinary modded entities.
        if (entity instanceof LivingEntity && CUSTOM_TRAVEL.get(entity.getClass())) return VANILLA_SPECIAL;

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
            // ArmorStand has a dedicated passive travel/interaction adapter. It shares
            // the continuous capsule installation contract, not player control input.
            if (living instanceof ArmorStand stand && stand.isMarker()) return VANILLA_SPECIAL;
            // Current installed dimensions include NeoForge Size overrides.
            // Unsupported ordinary shapes have no custom capability, so the
            // planner never enters an exact-body installation/retry loop.
            if (!supportsCharacterDimensions(entity)) return VANILLA_SPECIAL;
            return livingCapabilities(
                    isFlyingControlled(living),
                    isVanillaSpecialLiving(living)
            );
        }
        return VANILLA_SPECIAL;
    }

    private static final ClassValue<Boolean> CUSTOM_TRAVEL = new ClassValue<>() {
        @Override protected Boolean computeValue(Class<?> type) {
            try {
                return !type.getMethod("travel", net.minecraft.world.phys.Vec3.class)
                        .getDeclaringClass().getName().startsWith("net.minecraft.");
            } catch (NoSuchMethodException failure) {
                throw new IllegalStateException("LivingEntity travel contract missing", failure);
            }
        }
    };

    private static boolean supportsCharacterDimensions(Entity entity) {
        if (entity instanceof ArmorStand stand && stand.isMarker()) return false;
        var dimensions = GravityEntityGeometry.dimensions(entity);
        return CharacterDimensionPolicy.decide(dimensions.width(), dimensions.height())
                == CharacterDimensionPolicy.Decision.CAPSULE;
    }

    private static boolean isBallistic(Entity entity) {
        return entity instanceof ThrowableProjectile || entity instanceof AbstractArrow
                || entity instanceof LlamaSpit || entity instanceof FishingHook;
    }

    private static boolean isPassive(Entity entity) {
        return entity instanceof ItemEntity || entity instanceof ExperienceOrb
                || entity instanceof PrimedTnt || entity instanceof FallingBlockEntity;
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
     *   <li>{@link Shulker}: teleport/levitation movement.</li>
     *   <li>{@link EnderDragon}: phase-driven flight, no travel locomotion.</li>
     *   <li>{@link WitherBoss}: controlled hover flight via MoveControl.</li>
     * </ul>
     *
     * False positives here would hand a special entity to the character
     * controller, so the list stays conservative and explicit.
     */
    public static boolean isVanillaSpecialLiving(LivingEntity entity) {
        return entity instanceof Shulker
                || entity instanceof EnderDragon
                || entity instanceof WitherBoss;
    }
}
