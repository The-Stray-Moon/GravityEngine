package cc.sighs.gravityengine.gravity.collision;

/**
 * Signals that bounded collision work (recovery passes or voxel contact
 * reduction) exhausted its operation budget.
 *
 * <p>Ownership contract: this exception is thrown only by recovery/voxel work
 * reachable from geometry-transition paths. The owning boundary converts it
 * into the documented fail-closed outcome:</p>
 * <ul>
 *   <li>geometry transitions ({@code GravityGeometryTransitionService},
 *       {@code GravityEntityGeometry}) -> {@code Result.FAILED};</li>
 *   <li>application-time transitions are additionally rolled back by the
 *       target-side gravity-application coordinator.</li>
 * </ul>
 *
 * <p>Ordinary locomotion never throws it: its scene queries report budget
 * exhaustion through tracker booleans and stop at the last proven legal body.
 * It is distinct from an {@link IllegalStateException}, which represents a
 * genuine programmer/invariant bug and must never be swallowed.</p>
 */
public final class CollisionComplexityLimitException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    public CollisionComplexityLimitException(String message) {
        super(message);
    }
}
