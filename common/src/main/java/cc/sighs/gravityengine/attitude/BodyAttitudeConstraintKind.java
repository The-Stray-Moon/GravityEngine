package cc.sighs.gravityengine.attitude;

/** Diagnostic constraint selected by the explicit character attitude contract. */
public enum BodyAttitudeConstraintKind {
    /** Ordinary reference-aligned character: no independent actor control. */
    REFERENCE_ALIGNED,
    /**
     * Explicit special free attitude (low-gravity swim): the semantic view is
     * player-controlled and the body follows permitted body-local joint
     * overflow kinematically. The angular-dynamics stage applies no passive
     * gravity righting, angular inertia or deferred gravity-righting target.
     */
    FREE_ATTITUDE,
    /** Elytra flight: passive look/velocity alignment owns pitch/yaw, roll stays user-owned. */
    ELYTRA_ALIGNED
}
