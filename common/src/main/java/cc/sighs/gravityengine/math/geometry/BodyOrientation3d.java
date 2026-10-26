package cc.sighs.gravityengine.math.geometry;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.math.Quatd;
import java.util.Objects;

/** Lossless double-precision bridge between two immutable orientation representations. */
public final class BodyOrientation3d {
    private BodyOrientation3d() {}

    public static OrthonormalFrame3d frame(Quatd value) {
        Quatd q = normalized(value);
        return new OrthonormalFrame3d(
                q.transform(Vec3d.X),
                q.transform(Vec3d.Y),
                q.transform(Vec3d.Z));
    }

    public static Quatd quaternion(OrthonormalFrame3d frame) {
        Objects.requireNonNull(frame, "frame");
        Vec3d x = frame.axisX();
        Vec3d y = frame.axisY();
        Vec3d z = frame.axisZ();
        /*
         * Direct Shepperd-style rotation-matrix conversion. The branch on
         * the largest diagonal keeps the square root away from cancellation
         * for rotations near 180 degrees.
         */
        double trace = x.x() + y.y() + z.z();
        double qx;
        double qy;
        double qz;
        double qw;
        if (trace > 0.0D) {
            double s = Math.sqrt(trace + 1.0D) * 2.0D;
            qw = 0.25D * s;
            qx = (y.z() - z.y()) / s;
            qy = (z.x() - x.z()) / s;
            qz = (x.y() - y.x()) / s;
        } else if (x.x() > y.y() && x.x() > z.z()) {
            double s = Math.sqrt(1.0D + x.x() - y.y() - z.z()) * 2.0D;
            qw = (y.z() - z.y()) / s;
            qx = 0.25D * s;
            qy = (y.x() + x.y()) / s;
            qz = (z.x() + x.z()) / s;
        } else if (y.y() > z.z()) {
            double s = Math.sqrt(1.0D + y.y() - x.x() - z.z()) * 2.0D;
            qw = (z.x() - x.z()) / s;
            qx = (y.x() + x.y()) / s;
            qy = 0.25D * s;
            qz = (z.y() + y.z()) / s;
        } else {
            double s = Math.sqrt(1.0D + z.z() - x.x() - y.y()) * 2.0D;
            qw = (x.y() - y.x()) / s;
            qx = (z.x() + x.z()) / s;
            qy = (z.y() + y.z()) / s;
            qz = 0.25D * s;
        }
        return new Quatd(qx, qy, qz, qw).normalized();
    }

    public static Quatd normalized(Quatd value) {
        Objects.requireNonNull(value, "orientation");
        double lengthSquared = value.lengthSquared();
        if (!value.isFinite()
                || !Double.isFinite(lengthSquared)
                || lengthSquared < 1.0E-24D) {
            throw new IllegalArgumentException("invalid body orientation");
        }
        return value.normalized();
    }

    public static boolean matches(OrthonormalFrame3d a, OrthonormalFrame3d b) {
        return a.axisX().distanceSquared(b.axisX()) <= 1.0E-12D
                && a.axisY().distanceSquared(b.axisY()) <= 1.0E-12D
                && a.axisZ().distanceSquared(b.axisZ()) <= 1.0E-12D;
    }

    /** Shortest sign-invariant angular distance, in radians, including tiny arcs. */
    public static double angularDistance(Quatd first, Quatd second) {
        return Quatd.angularDistance(first, second);
    }
}
