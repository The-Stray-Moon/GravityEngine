package cc.sighs.gravityengine.gravity.kinematic.geometry;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.math.ScalarMath;
import cc.sighs.gravityengine.math.geometry.Aabb3d;

/**
 * Immutable exact locomotion geometry. Minecraft's public entity AABB is only
 * the conservative broadphase proxy returned by {@link #enclosingAabb()}.
 *
 * <p>This type is part of the neutral kinematic kernel: it exposes only
 * generic geometry ({@link Aabb3d} and {@link Vec3d}). Minecraft conversion
 * happens in the collision adapter layer, never inside the kernel.</p>
 */
public sealed interface CollisionBody permits OrientedBox, CharacterCapsule {
    Vec3d center();

    /** Immutable conservative world-axis-aligned enclosing bounds. */
    Aabb3d enclosingAabb();

    CollisionBody move(Vec3d displacement);

    default Vec3d closestPointTo(Vec3d point) {
        if (this instanceof OrientedBox b) {
            return b.closestPointTo(point);
        }
        if (this instanceof CharacterCapsule c) {
            Vec3d delta = point.subtract(c.center());
            Vec3d spine = c.center().fma(
                    ScalarMath.clamp(
                            delta.dot(c.axis()),
                            -c.halfSegmentLength(),
                            c.halfSegmentLength()
                    ),
                    c.axis()
            );
            Vec3d offset = point.subtract(spine);
            if (offset.lengthSquared() > c.radius() * c.radius()) {
                offset = offset.normalized().multiply(c.radius());
            }
            return spine.add(offset);
        }
        throw new IllegalStateException(
                "Unhandled collision body type: " + getClass().getName()
        );
    }

    /** Query-only local-body deflation, distinct from proxy AABB deflation. */
    default CollisionBody deflated(double amount) {
        if(!Double.isFinite(amount)||amount<0) throw new IllegalArgumentException("invalid deflation");
        if (this instanceof OrientedBox b) {
            return new OrientedBox(
                    b.center(),
                    b.halfExtents()
                            .subtract(amount, amount, amount)
                            .componentMax(Vec3d.ZERO),
                    b.orientation());
        }
        if (this instanceof CharacterCapsule c) {
            return new CharacterCapsule(c.center(),c.axis(),Math.max(0,c.radius()-amount),c.halfSegmentLength());
        }
        throw new IllegalStateException(
                "Unhandled collision body type: " + getClass().getName()
        );
    }

    /**
     * Pure mathematical swept bounds. Geometry defines geometry; the
     * collision broadphase applies its own contact-slop inflation.
     */
    default Aabb3d rawSweptAabb(Vec3d movement) {
        return enclosingAabb().expandTowards(movement);
    }

    /**
     * Exact-position equality for the same physical body. Two independent
     * constructions from the same entity/frame should be geometrically equal;
     * this deliberately does not use reference identity so a reusable seed can
     * be matched against a freshly recomputed body.
     */
    default boolean geometricallyEquals(CollisionBody other) {
        if (other == null) return false;
        if (this == other) return true;
        return center().subtract(other.center()).lengthSquared() <= 1.0E-12D
                && enclosingAabb().equals(other.enclosingAabb());
    }
}
