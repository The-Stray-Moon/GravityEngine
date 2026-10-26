package cc.sighs.gravityengine.gravity.geometry;

import cc.sighs.gravityengine.api.math.Vec3d;

import java.util.Objects;

/**
 * Loader-neutral outcome of one committed geometry transition.
 *
 * @param status           terminal outcome class
 * @param geometryChanged  whether the exact body representation was rebuilt
 * @param recoveryMovement positional recovery the commit had to apply
 */
public record GeometryTransitionResult(
        GeometryTransitionStatus status,
        boolean geometryChanged,
        Vec3d recoveryMovement
) {
    public static final GeometryTransitionResult UNCHANGED =
            new GeometryTransitionResult(
                    GeometryTransitionStatus.UNCHANGED,
                    false,
                    Vec3d.ZERO
            );

    public static final GeometryTransitionResult DEFERRED =
            new GeometryTransitionResult(
                    GeometryTransitionStatus.DEFERRED,
                    false,
                    Vec3d.ZERO
            );

    public GeometryTransitionResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(recoveryMovement, "recoveryMovement");
    }

    public static GeometryTransitionResult applied() {
        return new GeometryTransitionResult(
                GeometryTransitionStatus.APPLIED, true, Vec3d.ZERO);
    }

    public static GeometryTransitionResult recovered(Vec3d movement) {
        return new GeometryTransitionResult(
                GeometryTransitionStatus.RECOVERED, true, movement);
    }

    public static GeometryTransitionResult failed() {
        return new GeometryTransitionResult(
                GeometryTransitionStatus.FAILED, false, Vec3d.ZERO);
    }
}
