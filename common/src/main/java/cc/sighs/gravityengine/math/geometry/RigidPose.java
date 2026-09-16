package cc.sighs.gravityengine.math.geometry;

import org.joml.Vector3d;
import org.joml.Vector3dc;
import java.util.Objects;

/** Immutable body-local to world transform; center is the publisher's COM reference. */
public record RigidPose(double x, double y, double z, OrthonormalFrame3d orientation) {
    public RigidPose {
        Objects.requireNonNull(orientation, "orientation");
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z))
            throw new IllegalArgumentException("finite rigid center required");
    }
    public RigidPose(Vector3dc center, OrthonormalFrame3d orientation) {
        this(center.x(), center.y(), center.z(), orientation);
    }
    public Vector3d center() { return new Vector3d(x, y, z); }
    public Vector3d transformPoint(Vector3dc local) {
        return BodyOrientation3d.quaternion(orientation).transform(new Vector3d(local)).add(center());
    }
}
