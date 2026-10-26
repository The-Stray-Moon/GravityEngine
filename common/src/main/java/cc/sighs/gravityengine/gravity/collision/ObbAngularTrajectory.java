package cc.sighs.gravityengine.gravity.collision;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.kinematic.geometry.OrientedBox;
import cc.sighs.gravityengine.math.Quatd;
import cc.sighs.gravityengine.math.geometry.BodyOrientation3d;
import cc.sighs.gravityengine.math.geometry.OrthonormalFrame3d;

/** One shortest-arc rigid segment. A pivot is immutable, operation-local geometry;
 * reusing it across controller segments preserves one material anchor. */
public final class ObbAngularTrajectory {
    public record Pivot(Vec3d world, Vec3d body, OrthonormalFrame3d supportFrame) {
        public Pivot {
            java.util.Objects.requireNonNull(supportFrame, "supportFrame");
            if (!world.isFinite() || !body.isFinite()) throw new IllegalArgumentException("finite pivot required");
        }
        public static Pivot at(OrientedBox start, Vec3d world, OrthonormalFrame3d supportFrame) {
            return new Pivot(world, start.worldPointToLocal(world), supportFrame);
        }
    }

    private final OrientedBox start;
    private final Quatd from, to;
    private final double relativeSineHalf;
    private final double relativeAngle;
    private final Vec3d relativeAxis;
    private final Pivot pivot;

    public ObbAngularTrajectory(OrientedBox start, Quatd target, Pivot pivot) {
        this.start = start;
        this.from = BodyOrientation3d.quaternion(start.orientation());
        Quatd normalizedTarget = BodyOrientation3d.normalized(target);
        if (this.from.dot(normalizedTarget) < 0.0D) {
            normalizedTarget = new Quatd(
                    -normalizedTarget.x(),
                    -normalizedTarget.y(),
                    -normalizedTarget.z(),
                    -normalizedTarget.w()
            );
        }
        this.to = normalizedTarget;
        Quatd delta = this.from.conjugate().multiply(this.to).normalized();
        this.relativeSineHalf = Math.sqrt(
                delta.x() * delta.x()
                        + delta.y() * delta.y()
                        + delta.z() * delta.z()
        );
        this.relativeAngle = 2.0D * Math.atan2(
                this.relativeSineHalf,
                Math.max(0.0D, delta.w())
        );
        this.relativeAxis = this.relativeSineHalf < 1.0E-15D
                ? Vec3d.ZERO
                : new Vec3d(
                        delta.x(),
                        delta.y(),
                        delta.z()
                ).divide(this.relativeSineHalf);
        this.pivot = pivot;
        if (pivot != null && start.localPointToWorld(pivot.body())
                .distanceSquared(pivot.world()) > 1e-20) throw new IllegalArgumentException("pivot/start mismatch");
    }

    public OrientedBox start() { return start; }
    public Pivot pivot() { return pivot; }
    double relativeSineHalf() { return relativeSineHalf; }
    double relativeAngle() { return relativeAngle; }
    Vec3d relativeAxis() { return relativeAxis; }

    Quatd rotationAt(double fraction) {
        if (relativeSineHalf < 1.0E-15D) return from;
        Quatd rotation = Quatd.fromAxisAngle(
                relativeAxis,
                relativeAngle * fraction
        );
        return from.multiply(rotation).normalized();
    }

    public OrientedBox bodyAt(double fraction) {
        if (!(fraction >= 0 && fraction <= 1)) throw new IllegalArgumentException("fraction");
        if (fraction == 0) return start;
        Quatd q = rotationAt(fraction);
        // The same material point stays fixed: A = C + Q a, hence C = A - Q a.
        Vec3d center = pivot == null ? start.center()
                : pivot.world().subtract(q.transform(pivot.body()));
        return new OrientedBox(center, start.halfExtents(), BodyOrientation3d.frame(q));
    }

    public OrientedBox envelope(double lo, double hi) {
        return ObbRotationSweep.envelope(this, lo, hi);
    }
}
