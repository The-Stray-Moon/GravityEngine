package cc.sighs.gravityengine.api;

/** Finite target integration contracts. These do not change FIELD assignment or
 * permit body-attitude/solver replacement. Native transient exclusions always win.
 * Choosing a ballistic mode requires inheriting the matching native gravity seam;
 * custom ticks/travel remain the consumer's responsibility and require validation. */
public enum EntityGravityAdapter {
    /** Keep native motion, collision and presentation. Fields remain sampleable. */
    NATIVE,
    /** Ordinary LivingEntity travel; enables character body/contact/presentation
     * only for supported current dimensions and native locomotion states. */
    CHARACTER,
    /** Audited ThrowableProjectile/AbstractArrow/LlamaSpit/FishingHook gravity seam;
     * native AABB, collision and presentation remain owned by Minecraft. */
    BALLISTIC,
    /** Item/orb/TNT/falling-block native gravity with passive support integration. */
    PASSIVE_BALLISTIC,
    /** Controlled flight: environmental evidence does not enable character motion. */
    CONTROLLED_FLIGHT
}
