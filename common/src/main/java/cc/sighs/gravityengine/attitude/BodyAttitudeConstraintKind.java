package cc.sighs.gravityengine.attitude;

/** Diagnostic constraint selected by the explicit character attitude contract. */
public enum BodyAttitudeConstraintKind {
    /** Ordinary reference-aligned character: no independent actor control. */
    REFERENCE_ALIGNED,
    /**
     * Explicit special free attitude (low-gravity swim): KINEMATIC ownership.
     * The semantic view is player-controlled and the body follows permitted
     * body-local joint overflow kinematically, so no angular momentum is
     * fabricated for it. The angular-dynamics stage applies no passive gravity
     * righting, angular inertia or deferred gravity-righting target, and
     * {@code SemanticView.previewController()} fixed-rate geometric roll
     * remains a controller/view behaviour of this path only.
     */
    FREE_ATTITUDE,
    /**
     * Elytra flight: DYNAMIC ownership. A flight-intent heading torque owns
     * the constrained forward direction, a sustained player roll torque owns
     * roll about the physical flight forward axis, optional generic game
     * angular damping is an independent contribution, and the common
     * {@code q + L_world + I_body} solver is the only integrator. The gravity
     * frame does not right the body and does not define a mandatory flight
     * plane, and this mode does not consume the SemanticView fixed-rate
     * controller roll.
     */
    ELYTRA_ALIGNED
}
