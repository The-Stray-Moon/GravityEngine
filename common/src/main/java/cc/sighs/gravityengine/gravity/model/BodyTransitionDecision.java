package cc.sighs.gravityengine.gravity.model;

/**
 * One explicit outcome of an authoritative body/application transition.
 *
 * <p>Three independent concepts decide this value and are never substituted
 * for each other:</p>
 *
 * <ul>
 *   <li><b>authoritative application synchronization</b> - the committed
 *       application/assignment snapshot changed, the movement/collision routing
 *       changed, or a receiver asked for reconciliation;</li>
 *   <li><b>installed physical body / collider representation</b> - the body
 *       that is actually installed changed;</li>
 *   <li><b>player kinematic state</b> - the authoritative position anchor was
 *       relocated.</li>
 * </ul>
 *
 * <p>A synchronization request is not a physical event: it never authorizes a
 * position relocation or a momentum reset. An application or routing change is
 * not installed geometry either: only an observed installed-body change may
 * select {@link #GEOMETRY_REPRESENTATION_CHANGE}. Only
 * {@link #PHYSICAL_RELOCATION} may move the body, and even that outcome does
 * not, by itself, own the player's world-space velocity.</p>
 */
public enum BodyTransitionDecision {
    /** Nothing authoritative changed; no publication and no body work. */
    NONE,
    /**
     * Only the authoritative application/assignment snapshot or a receiver's
     * reconciliation request (including a routing ownership change) is pending.
     * The installed representation and the kinematic state stay untouched.
     */
    SYNC_ONLY,
    /**
     * The installed physics/collision body changed while the current
     * position anchor stays valid. Reconcile the representation; preserve
     * position and world-space velocity.
     */
    GEOMETRY_REPRESENTATION_CHANGE,
    /**
     * The authoritative position anchor actually moved (recovery, resize or
     * rotation correction). This is the only outcome allowed to relocate the
     * body through the platform correction path.
     */
    PHYSICAL_RELOCATION;

    /**
     * Single decision point. Precedence is physical relocation, then installed
     * geometry, then synchronization-only. Every input is an observed fact owned
     * by one state dimension, never a derived guess from position equality or
     * from an application/routing change.
     */
    public static BodyTransitionDecision decide(
            boolean physicalRelocationRequired,
            boolean installedGeometryChanged,
            boolean synchronizationRequired
    ) {
        if (physicalRelocationRequired) {
            return PHYSICAL_RELOCATION;
        }
        if (installedGeometryChanged) {
            return GEOMETRY_REPRESENTATION_CHANGE;
        }
        if (synchronizationRequired) {
            return SYNC_ONLY;
        }
        return NONE;
    }

    /** True when this outcome republishes authoritative state to the receiver. */
    public boolean publishesAuthoritativeState() {
        return this != NONE;
    }

    /** True when the body keeps its current position anchor. */
    public boolean preservesPositionAnchor() {
        return this != PHYSICAL_RELOCATION;
    }

    /** Only a relocation may write through the platform correction path. */
    public boolean relocatesPosition() {
        return this == PHYSICAL_RELOCATION;
    }
}
