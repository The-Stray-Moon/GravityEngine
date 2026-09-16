package cc.sighs.gravityengine.attitude.runtime;

/** Why vanilla presentation currently owns the player's global body pose. */
public enum BodyAttitudeSuspensionReason {
    NONE,
    NOT_EVALUATED,
    INACTIVE,
    NO_PHYSICS,
    SPECTATOR,
    PASSENGER,
    SLEEPING,
    DEAD_OR_DYING,
    SWIMMING_OR_FLUID_POSE,
    AUTO_SPIN_ATTACK,
    CLIMBING,
    CONTROLLED_FLIGHT,
    UPSIDE_DOWN_PRESENTATION,
    OTHER_VANILLA_POSE,
    LIFECYCLE_INVALIDATED
}
