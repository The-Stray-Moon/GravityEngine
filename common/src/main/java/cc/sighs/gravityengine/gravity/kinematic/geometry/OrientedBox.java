package cc.sighs.gravityengine.gravity.kinematic.geometry;

import cc.sighs.gravityengine.api.math.Vec3d;
import java.util.Objects;

/**
 * Immutable generic oriented box.
 *
 * <p>Owns only geometry data (center, half extents and one generic
 * {@link cc.sighs.gravityengine.math.geometry.OrthonormalFrame3d} orientation)
 * and pure kinematic operations. Minecraft collision conversion helpers live
 * in the collision-facing {@code MinecraftCollisionGeometryAdapter}; gravity
 * frames are not part of this type. Local axis signs are generic geometry
 * convention and carry no gravity right/up/forward meaning.</p>
 *
 * <p>All vector components are immutable GravityEngine values.</p>
 */
public record OrientedBox(
        Vec3d center,
        Vec3d halfExtents,
        cc.sighs.gravityengine.math.geometry.OrthonormalFrame3d orientation
) implements CollisionBody {

    public OrientedBox {
        Objects.requireNonNull(center, "center");
        Objects.requireNonNull(halfExtents, "halfExtents");
        Objects.requireNonNull(orientation, "orientation");
        requireFinite(center, "center");
        requireFinite(halfExtents, "halfExtents");
        if (halfExtents.x() < 0.0D
                || halfExtents.y() < 0.0D
                || halfExtents.z() < 0.0D) {
            throw new IllegalArgumentException(
                    "halfExtents must be non-negative: " + halfExtents);
        }
    }

    public OrientedBox(
            Vec3d center,
            double radius,
            cc.sighs.gravityengine.math.geometry.OrthonormalFrame3d orientation
    ) {
        this(
                center,
                new Vec3d(radius, radius, radius),
                orientation
        );
    }

    public static OrientedBox fromDimensions(
            Vec3d center,
            double width,
            double height,
            cc.sighs.gravityengine.math.geometry.OrthonormalFrame3d orientation
    ) {
        Objects.requireNonNull(orientation, "orientation");
        return new OrientedBox(
                center,
                new Vec3d(width * 0.5D, height * 0.5D, width * 0.5D),
                orientation);
    }

    /** Neutral axis-aligned factory from immutable bounds. */
    public static OrientedBox axisAligned(cc.sighs.gravityengine.math.geometry.Aabb3d box) {
        Objects.requireNonNull(box, "box");
        return new OrientedBox(
                box.center(),
                box.halfExtents(),
                cc.sighs.gravityengine.math.geometry.OrthonormalFrame3d.IDENTITY);
    }

    /**
     * Returns a new OBB whose local half-extents are uniformly expanded by
     * {@code amount}.
     *
     * <p>The center and orientation are unchanged and this instance is never
     * mutated. This is suitable for non-authoritative query tolerances such
     * as Vanilla entity pick radius; it must not be installed as the actor's
     * authoritative collision body.</p>
     */
    public OrientedBox inflated(double amount) {
        if (!Double.isFinite(amount)
                || amount < 0.0D) {
            throw new IllegalArgumentException(
                    "inflation must be finite and non-negative"
            );
        }

        return new OrientedBox(
                center,
                halfExtents().add(
                        amount,
                        amount,
                        amount
                ),
                orientation
        );
    }

    /**
     * Converts a world-space POINT into box-local coordinates.
     *
     * <p>Translation by {@link #center()} is part of this operation. Do not use
     * this method for displacement, velocity, normal, axis, acceleration or any
     * other free vector.</p>
     */
    public Vec3d worldPointToLocal(
            Vec3d worldPoint
    ) {
        Objects.requireNonNull(worldPoint, "worldPoint");
        return orientation.worldPointToLocal(worldPoint, center);
    }

    /**
     * Converts a box-local POINT into world-space coordinates.
     *
     * <p>Translation by {@link #center()} is part of this operation.</p>
     */
    public Vec3d localPointToWorld(
            Vec3d localPoint
    ) {
        Objects.requireNonNull(localPoint, "localPoint");
        return orientation.localPointToWorld(localPoint, center);
    }

    /**
     * Rotates a world-space FREE VECTOR into box-local coordinates.
     *
     * <p>No translation is applied. This is the correct operation for velocity,
     * displacement, normals, axes, accelerations and other directions.</p>
     */
    public Vec3d worldVectorToLocal(
            Vec3d worldVector
    ) {
        Objects.requireNonNull(worldVector, "worldVector");
        return orientation.worldToLocal(worldVector);
    }

    /**
     * Rotates a box-local FREE VECTOR into world space.
     *
     * <p>No translation is applied.</p>
     */
    public Vec3d localVectorToWorld(
            Vec3d localVector
    ) {
        Objects.requireNonNull(localVector, "localVector");
        return orientation.localToWorld(localVector);
    }

    public Vec3d axisX() {
        return orientation.axisX();
    }

    public Vec3d axisY() {
        return orientation.axisY();
    }

    public Vec3d axisZ() {
        return orientation.axisZ();
    }

    /**
     * Closest point on this box's exact surface (or interior) to a world point.
     * Pure geometry: no Entity, Level, runtime, or ground semantics.
     */
    public Vec3d closestPointTo(Vec3d worldPoint) {
        Objects.requireNonNull(worldPoint, "worldPoint");

        Vec3d local = worldPointToLocal(worldPoint);
        local = new Vec3d(
                clamp(local.x(), -halfExtents.x(), halfExtents.x()),
                clamp(local.y(), -halfExtents.y(), halfExtents.y()),
                clamp(local.z(), -halfExtents.z(), halfExtents.z())
        );

        return localPointToWorld(local);
    }

    private static double clamp(double value, double min, double max) {
        if (value < min) return min;
        if (value > max) return max;
        return value;
    }

    @Override
    public OrientedBox move(Vec3d displacement) {
        Objects.requireNonNull(displacement, "displacement");
        return new OrientedBox(
                center.add(displacement),
                halfExtents,
                orientation);
    }

    /** Projects this box into the neutral math kernel. */
    public cc.sighs.gravityengine.math.geometry.Obb3d toObb3d() {
        return new cc.sighs.gravityengine.math.geometry.Obb3d(
                center,
                halfExtents,
                orientation);
    }

    @Override
    public cc.sighs.gravityengine.math.geometry.Aabb3d enclosingAabb() {
        return cc.sighs.gravityengine.math.geometry.ObbMath.enclosingAabb(
                center,
                halfExtents,
                orientation
        );
    }

    /** World-space corner for signed corner coordinates in {-1, 1}. */
    public Vec3d corner(double xSign, double ySign, double zSign) {
        Objects.requireNonNull(center, "center");
        return orientation.localPointToWorld(
                halfExtents.x() * xSign,
                halfExtents.y() * ySign,
                halfExtents.z() * zSign,
                center
        );
    }

    private static void requireFinite(Vec3d vector, String name) {
        if (!vector.isFinite()) {
            throw new IllegalArgumentException(
                    name + " must be finite: " + vector);
        }
    }
}
