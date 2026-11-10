package cc.sighs.gravityengine.gravity.model;

/**
 * High-level classification of a gravity-sensitive operation.
 *
 * <p>Operation types identify scope intent. They may control diagnostics,
 * caching and allowed side effects later. Code must not branch arbitrarily
 * merely because a label exists. Movement and jump ownership now use
 * movement scope; render sampling remains outside operation scope.</p>
 */
public enum GravityOperationType {
    MOVE,
    TRAVEL,
    JUMP,
    QUERY,
    RENDER,

    /**
     * Non-translating lifecycle scope for server-player physical
     * BodyAttitude restore/bootstrap.
     */
    LOAD_RESTORE
}
