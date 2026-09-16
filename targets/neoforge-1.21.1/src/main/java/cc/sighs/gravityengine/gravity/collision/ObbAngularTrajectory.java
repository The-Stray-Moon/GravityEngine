package cc.sighs.gravityengine.gravity.collision;

import cc.sighs.gravityengine.gravity.kinematic.geometry.OrientedBox;
import cc.sighs.gravityengine.math.geometry.BodyOrientation3d;
import cc.sighs.gravityengine.math.geometry.OrthonormalFrame3d;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;

/** One shortest-arc rigid segment. A pivot is immutable, operation-local geometry;
 * reusing it across controller segments preserves one material anchor. */
public final class ObbAngularTrajectory {
    public record Pivot(Vector3d world, Vector3d body, OrthonormalFrame3d supportFrame) {
        public Pivot {
            java.util.Objects.requireNonNull(supportFrame, "supportFrame");
            world = new Vector3d(world); body = new Vector3d(body);
            if (!world.isFinite() || !body.isFinite()) throw new IllegalArgumentException("finite pivot required");
        }
        @Override public Vector3d world() { return new Vector3d(world); }
        @Override public Vector3d body() { return new Vector3d(body); }
        public static Pivot at(OrientedBox start, Vector3d world, OrthonormalFrame3d supportFrame) {
            return new Pivot(world, start.worldPointToLocal(world, new Vector3d()), supportFrame);
        }
    }

    private final OrientedBox start;
    private final Quaterniond from, to;
    private final Pivot pivot;

    public ObbAngularTrajectory(OrientedBox start, Quaterniondc target, Pivot pivot) {
        this.start = start;
        this.from = BodyOrientation3d.quaternion(start.orientation());
        this.to = BodyOrientation3d.normalized(target);
        if (from.dot(to) < 0) to.set(-to.x, -to.y, -to.z, -to.w);
        this.pivot = pivot;
        if (pivot != null && start.localPointToWorld(pivot.body(), new Vector3d())
                .distanceSquared(pivot.world()) > 1e-20) throw new IllegalArgumentException("pivot/start mismatch");
    }

    public OrientedBox start() { return start; }
    public Pivot pivot() { return pivot; }
    Quaterniond from() { return new Quaterniond(from); }
    Quaterniond to() { return new Quaterniond(to); }

    public OrientedBox bodyAt(double fraction) {
        if (!(fraction >= 0 && fraction <= 1)) throw new IllegalArgumentException("fraction");
        if (fraction == 0) return start;
        Quaterniond q = ObbRotationSweep.interpolate(from, to, fraction);
        // The same material point stays fixed: A = C + Q a, hence C = A - Q a.
        Vector3d center = pivot == null ? start.center()
                : pivot.world().sub(q.transform(pivot.body()));
        return new OrientedBox(center, start.halfExtents(), BodyOrientation3d.frame(q));
    }

    public OrientedBox envelope(double lo, double hi) {
        return ObbRotationSweep.envelope(this, lo, hi);
    }
}
