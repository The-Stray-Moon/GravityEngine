package cc.sighs.gravityengine.gravity.movement;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Pure closest-space escape selection.
 *
 * <p>This policy deliberately knows nothing about Minecraft block cells,
 * world axes or a rotated local voxel grid. The integration layer evaluates
 * candidate legality and escape distance against actual world geometry, then
 * supplies those immutable candidates here.</p>
 */
public final class ClosestSpaceEscapePolicy {
    /** Vanilla-compatible velocity nudge magnitude. */
    public static final double ESCAPE_VELOCITY = 0.1D;

    private ClosestSpaceEscapePolicy() {}

    public enum LocalAxis {
        X,
        Z
    }

    /**
     * One legal gravity-tangent escape candidate.
     *
     * @param axis local tangent axis
     * @param sign -1 or +1 along that local axis
     * @param distance world-space distance until the sampled semantic point
     *                 first reaches non-suffocating space
     */
    public record Escape(
            LocalAxis axis,
            double sign,
            double distance
    ) {
        public Escape {
            Objects.requireNonNull(axis, "axis");

            if (sign != -1.0D && sign != 1.0D) {
                throw new IllegalArgumentException(
                        "escape sign must be -1 or +1: " + sign
                );
            }

            if (!Double.isFinite(distance) || distance < 0.0D) {
                throw new IllegalArgumentException(
                        "distance must be finite and non-negative: "
                                + distance
                );
            }
        }
    }

    /**
     * Selects the shortest legal candidate.
     *
     * <p>Equal-distance ties preserve caller order, giving the integration
     * layer one deterministic total tie-break without embedding geometry
     * assumptions here.</p>
     */
    public static Optional<Escape> resolveClosestEscape(
            List<Escape> candidates
    ) {
        Objects.requireNonNull(candidates, "candidates");

        Escape best = null;
        double bestDistance = Double.POSITIVE_INFINITY;

        for (Escape candidate : candidates) {
            Objects.requireNonNull(candidate, "candidate");

            if (candidate.distance() < bestDistance) {
                best = candidate;
                bestDistance = candidate.distance();
            }
        }

        return Optional.ofNullable(best);
    }
}