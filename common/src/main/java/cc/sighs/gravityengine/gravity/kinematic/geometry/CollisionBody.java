package cc.sighs.gravityengine.gravity.kinematic.geometry;

import cc.sighs.gravityengine.math.geometry.Aabb3d;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * Immutable exact locomotion geometry. Minecraft's public entity AABB is only
 * the conservative broadphase proxy returned by {@link #enclosingAabb()}.
 *
 * <p>This type is part of the neutral kinematic kernel: it exposes only
 * generic geometry ({@link Aabb3d} and JOML vectors).  Minecraft conversion
 * happens in the collision adapter layer, never inside the kernel.</p>
 */
public sealed interface CollisionBody permits OrientedBox, CharacterCapsule {
    /** Fresh defensive world-space center copy. */
    Vector3d center();

    /** Immutable conservative world-axis-aligned enclosing bounds. */
    Aabb3d enclosingAabb();

    CollisionBody move(Vector3dc displacement);

    default Vector3d closestPointTo(Vector3dc point) {
        return switch(this) {
            case OrientedBox b -> b.closestPointTo(point);
            case CharacterCapsule c -> {
                Vector3d delta=new Vector3d(point).sub(c.center());
                Vector3d spine=c.center().fma(Math.clamp(delta.dot(c.axis()),
                        -c.halfSegmentLength(),c.halfSegmentLength()),c.axis());
                Vector3d offset=new Vector3d(point).sub(spine);
                if(offset.lengthSquared()>c.radius()*c.radius()) offset.normalize().mul(c.radius());
                yield spine.add(offset);
            }
        };
    }

    /** Query-only local-body deflation, distinct from proxy AABB deflation. */
    default CollisionBody deflated(double amount) {
        if(!Double.isFinite(amount)||amount<0) throw new IllegalArgumentException("invalid deflation");
        return switch(this) {
            case OrientedBox b -> new OrientedBox(b.center(),b.halfExtents().sub(amount,amount,amount).max(new Vector3d()),b.orientation());
            case CharacterCapsule c -> new CharacterCapsule(c.center(),c.axis(),Math.max(0,c.radius()-amount),c.halfSegmentLength());
        };
    }

    /**
     * Pure mathematical swept bounds. Geometry defines geometry; the
     * collision broadphase applies its own contact-slop inflation.
     */
    default Aabb3d rawSweptAabb(Vector3dc movement) {
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
        return center().sub(other.center()).lengthSquared() <= 1.0E-12D
                && enclosingAabb().equals(other.enclosingAabb());
    }
}
