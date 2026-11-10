package cc.sighs.gravityengine.gravity.geometry;

import cc.sighs.gravityengine.gravity.GravityFrame;
import cc.sighs.gravityengine.gravity.GravityState;
import cc.sighs.gravityengine.api.math.Vec3d;

/**
 * Which collider a body actually has installed.
 *
 * <p>This value is derived only from installed geometry state. Application
 * semantics and movement/collision routing are independent facts and must not
 * participate in representation identity.</p>
 *
 * <p>A missing or default-equivalent installed axis means the platform AABB is
 * the physical collider. A rotated installed axis means GravityEngine exact
 * geometry is installed.</p>
 */
public enum BodyRepresentation {
    /** The platform AABB is the collider; an optional default axis is metadata. */
    NATIVE_AABB,
    /** An exact GravityEngine collision body is installed from a rotated axis. */
    EXACT_BODY;

    public static BodyRepresentation of(GravityFrame installedFrame) {
        return ofAxis(installedFrame == null ? null : installedFrame.up());
    }

    /** Installed geometry classification; no reference frame is needed. */
    public static BodyRepresentation ofAxis(Vec3d installedUp) {
        return installedUp != null
                && installedUp.distanceSquared(GravityState.DEFAULT_DOWN.negate()) > 1.0E-12D
                ? EXACT_BODY
                : NATIVE_AABB;
    }

    /** Geometry classification only; this does not choose movement ownership. */
    public static boolean requiresReferenceGeometry(GravityFrame frame) {
        return frame.down().distanceSquared(GravityState.DEFAULT_DOWN) > 1.0E-12D;
    }

    /** True when the installed collider is GravityEngine exact geometry. */
    public boolean isExact() {
        return this == EXACT_BODY;
    }
}
