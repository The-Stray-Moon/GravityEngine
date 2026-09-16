package cc.sighs.gravityengine.gravity.movement;

import cc.sighs.gravityengine.gravity.GravityFrame;
import cc.sighs.gravityengine.gravity.collision.*;
import cc.sighs.gravityengine.gravity.kinematic.geometry.CollisionBody;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

import java.util.Objects;

/**
 * Sneak-edge support constraint for gravity-oriented movement.
 *
 * <p>This is a movement-intent constraint, not a second movement solver: it
 * clips only the gravity-tangent components of an already integrated
 * displacement candidate before the formal collision solve.  It is the
 * gravity-local equivalent of Vanilla's {@code Player.maybeBackOffFromEdge}
 * back-off loop (per-axis and diagonal, {@value #EDGE_BACKOFF_STEP_BLOCKS}
 * blocks per step) and deliberately:
 *
 * <ul>
 *   <li>never cancels travel, writes a velocity-like vector, or clears the
 *       gravity-normal component (fall/jump/support-breaking motion);</li>
 *   <li>never reconstructs a body center from world +Y; callers supply the
 *       exact collision body the solver owns;</li>
 *   <li>queries support through an injected {@link SupportProbe} backed by
 *       the borrowed immutable {@link CollisionScene}, so the back-off policy
 *       stays free of live {@code Level}/{@code Player} reads.</li>
 * </ul>
 */
public final class SneakEdgePreventionService {
    /** Vanilla-compatible tangent back-off increment in blocks. */
    public static final double EDGE_BACKOFF_STEP_BLOCKS = 0.05D;

    private SneakEdgePreventionService() {}

    /**
     * Pure gravity-local tangent back-off.
     *
     * @param frame       immutable operation frame (gravity-local basis)
     * @param body        the exact body currently owned by the collision
     *                    solver, at its current body center
     * @param displacement world-space displacement candidate already produced
     *                    by velocity integration
     * @param probe       support predicate: true when the exact body placed
     *                    at the candidate tangent position still has support
     *                    within the step-down allowance
     * @return possibly tangent-reduced displacement; the gravity-normal
     *         (frame-up) component is never changed
     */
    public static Vec3 constrainTangentMovement(
            GravityFrame frame,
            CollisionBody body,
            Vec3 displacement,
            SupportProbe probe
    ) {
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(displacement, "displacement");
        Objects.requireNonNull(probe, "probe");
        if (!Double.isFinite(displacement.x)
                || !Double.isFinite(displacement.y)
                || !Double.isFinite(displacement.z)) {
            throw new IllegalArgumentException(
                    "displacement must be finite: " + displacement);
        }

        Vec3 local = frame.worldToLocal(displacement);
        double vertical = local.y;
        /*
         * Movement that leaves support along frame-up (jump, knockback,
         * external launch) is explicitly owned and never edge-constrained.
         */
        if (vertical > 0.0D) {
            return displacement;
        }
        double dx = local.x;
        double dz = local.z;
        if (dx == 0.0D && dz == 0.0D) {
            return displacement;
        }

        double stepX = Math.copySign(EDGE_BACKOFF_STEP_BLOCKS, dx);
        double stepZ = Math.copySign(EDGE_BACKOFF_STEP_BLOCKS, dz);

        while (dx != 0.0D && fallsAt(frame, body, dx, 0.0D, probe)) {
            dx = backOff(dx, stepX);
        }
        while (dz != 0.0D && fallsAt(frame, body, 0.0D, dz, probe)) {
            dz = backOff(dz, stepZ);
        }
        while (dx != 0.0D && dz != 0.0D
                && fallsAt(frame, body, dx, dz, probe)) {
            dx = backOff(dx, stepX);
            dz = backOff(dz, stepZ);
        }

        if (dx == local.x && dz == local.z) {
            return displacement;
        }
        return frame.localToWorld(new Vec3(dx, vertical, dz));
    }

    private static boolean fallsAt(
            GravityFrame frame,
            CollisionBody body,
            double tangentX,
            double tangentZ,
            SupportProbe probe
    ) {
        Vec3 tangent = frame.localToWorld(
                new Vec3(tangentX, 0.0D, tangentZ));
        CollisionBody candidate = body.move(new Vector3d(
                tangent.x, tangent.y, tangent.z));
        return !probe.hasSupport(candidate);
    }

    private static double backOff(double component, double step) {
        if (Math.abs(component) <= EDGE_BACKOFF_STEP_BLOCKS) return 0.0D;
        double reduced = component - step;
        // Finite values beyond double's decrement resolution cannot progress.
        // Fail closed there; every Vanilla coordinate-range displacement strictly
        // decreases, so no iteration budget truncates a supported candidate.
        return reduced == component ? 0.0D : reduced;
    }

    /** Reuses the discrete feet query over the borrowed scene. The depth is
     * only a candidate-search allowance, never a body translation or response. */
    public static SupportProbe sceneProbe(
            CollisionScene scene,
            GravityFrame frame,
            double maxDownDistance
    ) {
        Objects.requireNonNull(scene, "scene");
        Objects.requireNonNull(frame, "frame");
        if (!Double.isFinite(maxDownDistance) || maxDownDistance < 0.0D) {
            throw new IllegalArgumentException(
                    "maxDownDistance must be finite and non-negative");
        }
        return candidate -> {
            try {
                var query = cc.sighs.gravityengine.gravity.collision.FeetSupportQuery.query(candidate, frame,
                        scene, 0, maxDownDistance, null, new ObbQueryContext());
                return !query.indeterminate() && query.selected() != null;
            } catch (CollisionComplexityLimitException | CollisionSceneCoverageException queryFailure) {
                return false;
            }
        };
    }

    @FunctionalInterface
    public interface SupportProbe {
        /**
         * True when the exact body placed at the candidate tangent position
         * (no vertical displacement applied) still has support within the
         * movement's step-down allowance.
         */
        boolean hasSupport(CollisionBody candidateBody);
    }
}
