package cc.sighs.gravityengine.attitude;

/**
 * Immutable player state read once at the tick boundary.  Vanilla pose state
 * is read only to decide temporary Vanilla presentation ownership; it is never
 * a body-attitude mode.
 *
 * <p>Actual fluid locomotion and Vanilla swimming pose/flags are distinct
 * exclusions. Character SWIM_ACTION is a separate model-only intent and never
 * enters either field.</p>
 */
public record BodyAttitudePlayerState(
        boolean noPhysics,
        boolean spectator,
        boolean passenger,
        boolean sleeping,
        boolean deadOrDying,
        boolean actualFluidLocomotion,
        boolean swimmingPresentation,
        boolean autoSpinAttack,
        boolean climbing,
        boolean controlledFlight,
        boolean upsideDownPresentation,
        boolean otherVanillaPose,
        boolean fallFlying,
        boolean onGround,
        boolean environmentalGravity
) {}
