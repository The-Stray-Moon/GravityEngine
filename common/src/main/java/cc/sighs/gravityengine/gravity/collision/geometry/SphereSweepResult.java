package cc.sighs.gravityengine.gravity.collision.geometry;

import java.util.Objects;
import java.util.Optional;

/**
 * Detailed sphere/OBB sweep outcome.
 *
 * <p>Numerical uncertainty is never encoded as a miss; only a proved
 * separation or non-entering tangent may be a {@link Status#MISS}.</p>
 */
public record SphereSweepResult(
        Status status,
        SphereObbSweepHit hitOrNull,
        String diagnostic
) {
    public SphereSweepResult {
        Objects.requireNonNull(status, "status");
        diagnostic = diagnostic == null ? "" : diagnostic;
        if ((status == Status.HIT) != (hitOrNull != null)) {
            throw new IllegalArgumentException(
                    "only HIT may carry a sweep hit");
        }
    }

    public static SphereSweepResult hit(SphereObbSweepHit hit) {
        return new SphereSweepResult(
                Status.HIT, Objects.requireNonNull(hit, "hit"), "");
    }

    public static SphereSweepResult miss(String proof) {
        return new SphereSweepResult(Status.MISS, null, proof);
    }

    public static SphereSweepResult indeterminate(String diagnostic) {
        return new SphereSweepResult(
                Status.INDETERMINATE, null, diagnostic);
    }

    public Optional<SphereObbSweepHit> hit() {
        return Optional.ofNullable(hitOrNull);
    }

    public enum Status { HIT, MISS, INDETERMINATE }
}
