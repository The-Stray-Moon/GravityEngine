package cc.sighs.gravityengine.api;

import java.util.Objects;

/** Immediate result, not a live handle. Assignment and physical installation
 * are separate: DEFERRED/FAILED can retain an accepted durable assignment.
 * Observe entityGravity later to inspect the installed application. */
public record GravityMutationResult(Status status, boolean assignmentChanged) {
    public GravityMutationResult { Objects.requireNonNull(status, "status"); }
    public enum Status {
        /** No physical application change was necessary. Assignment may still change. */
        UNCHANGED,
        /** The requested application was committed; entity capability still governs integration. */
        COMMITTED,
        /** Assignment retained, application queued for an existing legal handoff. */
        DEFERRED,
        /** Expected geometry/coverage rejection; assignment retained and application remains pending. */
        FAILED,
        /** Release did not change DIRECT: the current FIELD query was incomplete. No release queued. */
        FIELD_INCOMPLETE
    }
}
