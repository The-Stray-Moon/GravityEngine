package cc.sighs.gravityengine.gravity.collision;

/**
 * Dedicated fail-closed result when a solver query leaves its captured
 * operation domain. The exception is an invariant violation: a legal route
 * segment must always be inside the conservative envelope captured before
 * solving. Solver code never expands the domain lazily.
 */
public final class CollisionSceneCoverageException extends RuntimeException {
    public CollisionSceneCoverageException(String message) {
        super(message);
    }
}
