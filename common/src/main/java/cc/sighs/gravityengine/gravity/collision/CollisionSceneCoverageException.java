package cc.sighs.gravityengine.gravity.collision;

/**
 * Refuses a query for which collision coverage cannot be proved. Leaving the
 * captured domain or losing the query subject indicates an integration error;
 * unsupported geometry is an expected capability boundary. Both preserve the
 * last proven position. Solver code never expands the domain lazily.
 */
public final class CollisionSceneCoverageException
        extends RuntimeException {

    private static final long serialVersionUID = 1L;
    public enum Reason { CAPTURE_DOMAIN, UNAVAILABLE_BLOCK_DATA, UNSUPPORTED_GEOMETRY, MISSING_SUBJECT }
    private final Reason reason;

    public CollisionSceneCoverageException(
            String message
    ) {
        this(Reason.CAPTURE_DOMAIN, message);
    }

    public CollisionSceneCoverageException(Reason reason, String message) {
        super(message);
        this.reason = java.util.Objects.requireNonNull(reason);
    }

    public Reason reason() { return reason; }
}
