package cc.sighs.gravityengine.gravity.kinematic.geometry;

import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.Objects;

/**
 * Immutable generic oriented box.
 *
 * <p>Owns only geometry data (center, half extents and one generic
 * {@link cc.sighs.gravityengine.math.geometry.OrthonormalFrame3d} orientation) and pure kinematic operations.
 * Minecraft conversion helpers live in the collision-facing
 * {@code MinecraftGeometryAdapter}; gravity frames are not part of this
 * type.  Local axis signs are generic geometry convention and carry no
 * gravity right/up/forward meaning.</p>
 *
 * <p>All vector components are defensively copied on construction and every
 * accessor returns a fresh mutable copy so callers may safely use JOML's
 * mutating vector API without corrupting this immutable body.</p>
 */
public record OrientedBox(
        Vector3d center,
        Vector3d halfExtents,
        cc.sighs.gravityengine.math.geometry.OrthonormalFrame3d orientation
) implements CollisionBody {

    public OrientedBox {
        Objects.requireNonNull(center, "center");
        Objects.requireNonNull(halfExtents, "halfExtents");
        Objects.requireNonNull(orientation, "orientation");
        requireFinite(center, "center");
        requireFinite(halfExtents, "halfExtents");
        if (halfExtents.x < 0.0D
                || halfExtents.y < 0.0D
                || halfExtents.z < 0.0D) {
            throw new IllegalArgumentException(
                    "halfExtents must be non-negative: " + halfExtents);
        }
        center = new Vector3d(center);
        halfExtents = new Vector3d(halfExtents);
    }

    public static OrientedBox fromDimensions(
            Vector3dc center,
            double width,
            double height,
            cc.sighs.gravityengine.math.geometry.OrthonormalFrame3d orientation
    ) {
        Objects.requireNonNull(orientation, "orientation");
        return new OrientedBox(
                new Vector3d(center),
                new Vector3d(width * 0.5D, height * 0.5D, width * 0.5D),
                orientation);
    }

    /** Neutral axis-aligned factory from immutable bounds. */
    public static OrientedBox axisAligned(cc.sighs.gravityengine.math.geometry.Aabb3d box) {
        Objects.requireNonNull(box, "box");
        return new OrientedBox(
                box.center(),
                box.halfExtents(new Vector3d()),
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

    /** Fresh defensive center copy. */
    @Override
    public Vector3d center() {
        return new Vector3d(center);
    }

    /** Fresh defensive half-extents copy. */
    public Vector3d halfExtents() {
        return new Vector3d(halfExtents);
    }

    /**
     * Converts a world-space POINT into box-local coordinates.
     *
     * <p>Translation by {@link #center()} is part of this operation. Do not use
     * this method for displacement, velocity, normal, axis, acceleration or any
     * other free vector.</p>
     */
    public Vector3d worldPointToLocal(
            Vector3dc worldPoint,
            Vector3d dest
    ) {
        Objects.requireNonNull(worldPoint, "worldPoint");
        Objects.requireNonNull(dest, "dest");

        dest.set(worldPoint)
                .sub(center.x, center.y, center.z);

        return orientation.worldToLocal(dest, dest);
    }

    /**
     * Converts a box-local POINT into world-space coordinates.
     *
     * <p>Translation by {@link #center()} is part of this operation.</p>
     */
    public Vector3d localPointToWorld(
            Vector3dc localPoint,
            Vector3d dest
    ) {
        Objects.requireNonNull(localPoint, "localPoint");
        Objects.requireNonNull(dest, "dest");

        orientation.localToWorld(localPoint, dest);

        return dest.add(
                center.x,
                center.y,
                center.z
        );
    }

    /**
     * Rotates a world-space FREE VECTOR into box-local coordinates.
     *
     * <p>No translation is applied. This is the correct operation for velocity,
     * displacement, normals, axes, accelerations and other directions.</p>
     */
    public Vector3d worldVectorToLocal(
            Vector3dc worldVector,
            Vector3d dest
    ) {
        Objects.requireNonNull(worldVector, "worldVector");
        Objects.requireNonNull(dest, "dest");

        return orientation.worldToLocal(
                worldVector,
                dest
        );
    }

    /**
     * Rotates a box-local FREE VECTOR into world space.
     *
     * <p>No translation is applied.</p>
     */
    public Vector3d localVectorToWorld(
            Vector3dc localVector,
            Vector3d dest
    ) {
        Objects.requireNonNull(localVector, "localVector");
        Objects.requireNonNull(dest, "dest");

        return orientation.localToWorld(
                localVector,
                dest
        );
    }

    public Vector3d axisX(Vector3d dest) {
        return orientation.axisX(dest);
    }

    public Vector3d axisY(Vector3d dest) {
        return orientation.axisY(dest);
    }

    public Vector3d axisZ(Vector3d dest) {
        return orientation.axisZ(dest);
    }

    /** Fresh copy of the local +X axis in world space. */
    public Vector3d axisX() {
        return orientation.axisX(new Vector3d());
    }

    public Vector3d axisY() {
        return orientation.axisY(new Vector3d());
    }

    public Vector3d axisZ() {
        return orientation.axisZ(new Vector3d());
    }

    /**
     * Closest point on this box's exact surface (or interior) to a world point.
     * Pure geometry: no Entity, Level, runtime, or ground semantics.
     */
    public Vector3d closestPointTo(Vector3dc worldPoint) {
        Objects.requireNonNull(worldPoint, "worldPoint");

        Vector3d local = worldPointToLocal(worldPoint, new Vector3d());
        local.x = clamp(local.x, -halfExtents.x, halfExtents.x);
        local.y = clamp(local.y, -halfExtents.y, halfExtents.y);
        local.z = clamp(local.z, -halfExtents.z, halfExtents.z);

        return localPointToWorld(local, new Vector3d());
    }

    private static double clamp(double value, double min, double max) {
        if (value < min) return min;
        if (value > max) return max;
        return value;
    }

    @Override
    public OrientedBox move(Vector3dc displacement) {
        Objects.requireNonNull(displacement, "displacement");
        return new OrientedBox(
                new Vector3d(center).add(displacement),
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
        cc.sighs.gravityengine.math.geometry.MutableAabb3d bounds = cc.sighs.gravityengine.math.geometry.ObbMath.enclosingAabb(
                toObb3d(), new cc.sighs.gravityengine.math.geometry.MutableAabb3d());
        return bounds.immutable();
    }

    /** World-space corner for signed corner coordinates in {-1, 1}. */
    public Vector3d corner(double xSign, double ySign, double zSign) {
        return toObb3d().corner(
                xSign, ySign, zSign, new Vector3d());
    }

    /** World-space corner written into a caller-owned destination. */
    public Vector3d corner(
            double xSign,
            double ySign,
            double zSign,
            Vector3d dest
    ) {
        return toObb3d().corner(xSign, ySign, zSign, dest);
    }

    private static void requireFinite(Vector3d vector, String name) {
        if (!Double.isFinite(vector.x)
                || !Double.isFinite(vector.y)
                || !Double.isFinite(vector.z)) {
            throw new IllegalArgumentException(
                    name + " must be finite: " + vector);
        }
    }
}
