package cc.sighs.gravityengine.gravity.field;

import cc.sighs.gravityengine.api.field.*;
import cc.sighs.gravityengine.api.math.Vec3d;

/** Immutable signed nearest-point acceleration to a closed axis-aligned source. Equal
 * bounds represent planes, lines or points. Influence is distance from the source,
 * independent of its dimensionality. Strength is blocks/tick squared, range blocks.
 * Inside/on the source the nearest-point displacement is zero, so acceleration is
 * zero while the contribution remains present. There is no distance attenuation. */
public record NearestSourceGravityField(Vec3d min, Vec3d max, double strength,
                                       double range) implements GravityField {
    public NearestSourceGravityField {
        if (min == null || max == null || !min.isFinite() || !max.isFinite()
                || min.x() > max.x() || min.y() > max.y() || min.z() > max.z()
                || !Double.isFinite(strength)
                || !Double.isFinite(range) || range <= 0)
            throw new IllegalArgumentException("Invalid nearest-source field");
    }
    public Vec3d displacement(Vec3d point) {
        return new Vec3d(Math.max(min.x(), Math.min(max.x(), point.x())) - point.x(),
                Math.max(min.y(), Math.min(max.y(), point.y())) - point.y(),
                Math.max(min.z(), Math.min(max.z(), point.z())) - point.z());
    }
    public boolean influences(Vec3d point) {
        return displacement(point).lengthSquared() <= range * range;
    }
    @Override public GravityFieldSample sample(GravityFieldQuery query) {
        Vec3d delta = displacement(query.position());
        double distance = delta.length();
        if (distance == 0 || distance > range || strength == 0) return GravityFieldSample.ZERO;
        return new GravityFieldSample(delta.multiply(strength / distance));
    }
}
