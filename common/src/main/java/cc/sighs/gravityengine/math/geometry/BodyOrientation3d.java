package cc.sighs.gravityengine.math.geometry;

import org.joml.Matrix3d;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import java.util.Objects;

/** Lossless double-precision bridge between two immutable orientation representations. */
public final class BodyOrientation3d {
    private BodyOrientation3d() {}

    public static OrthonormalFrame3d frame(Quaterniondc value) {
        Quaterniond q = normalized(value);
        return new OrthonormalFrame3d(
                q.transform(new Vector3d(1, 0, 0)),
                q.transform(new Vector3d(0, 1, 0)),
                q.transform(new Vector3d(0, 0, 1)));
    }

    public static Quaterniond quaternion(OrthonormalFrame3d frame) {
        Objects.requireNonNull(frame, "frame");
        Matrix3d m = new Matrix3d();
        m.setColumn(0, frame.axisX(new Vector3d()));
        m.setColumn(1, frame.axisY(new Vector3d()));
        m.setColumn(2, frame.axisZ(new Vector3d()));
        return m.getNormalizedRotation(new Quaterniond()).normalize();
    }

    public static Quaterniond normalized(Quaterniondc value) {
        Objects.requireNonNull(value, "orientation");
        Quaterniond q = new Quaterniond(value);
        if (!Double.isFinite(q.x) || !Double.isFinite(q.y)
                || !Double.isFinite(q.z) || !Double.isFinite(q.w)
                || !Double.isFinite(q.lengthSquared()) || q.lengthSquared() < 1e-24) {
            throw new IllegalArgumentException("invalid body orientation");
        }
        return q.normalize();
    }

    public static boolean matches(OrthonormalFrame3d a, OrthonormalFrame3d b) {
        return a.axisX(new Vector3d()).distanceSquared(b.axisX(new Vector3d())) <= 1e-12
                && a.axisY(new Vector3d()).distanceSquared(b.axisY(new Vector3d())) <= 1e-12
                && a.axisZ(new Vector3d()).distanceSquared(b.axisZ(new Vector3d())) <= 1e-12;
    }

    /** Shortest sign-invariant angular distance, in radians, including tiny arcs. */
    public static double angularDistance(Quaterniondc first, Quaterniondc second) {
        Quaterniond delta = normalized(first).conjugate().mul(normalized(second)).normalize();
        return 2.0D * Math.atan2(Math.sqrt(delta.x * delta.x + delta.y * delta.y + delta.z * delta.z),
                Math.abs(delta.w));
    }
}
