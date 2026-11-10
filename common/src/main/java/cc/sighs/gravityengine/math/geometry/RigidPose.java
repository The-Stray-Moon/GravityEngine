package cc.sighs.gravityengine.math.geometry;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.math.Quatd;
import java.util.Objects;

/** Immutable local-to-parent transform; center is the origin of the captured
 * local basis, which need not be the physical center of mass. */
public record RigidPose(double x, double y, double z, OrthonormalFrame3d orientation) {
    public RigidPose {
        Objects.requireNonNull(orientation, "orientation");
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z))
            throw new IllegalArgumentException("finite rigid center required");
    }
    public RigidPose(Vec3d center, OrthonormalFrame3d orientation) {
        this(center.x(), center.y(), center.z(), orientation);
    }
    public Vec3d center() { return new Vec3d(x, y, z); }
    public Vec3d transformPoint(Vec3d local) {
        Quatd rotation = BodyOrientation3d.quaternion(orientation);
        return rotation.transform(local).add(center());
    }
}
