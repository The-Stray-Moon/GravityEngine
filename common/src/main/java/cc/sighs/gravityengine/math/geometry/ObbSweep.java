package cc.sighs.gravityengine.math.geometry;

import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.Objects;

/** Authoritative analytic continuous SAT for a translating OBB pair. */
public final class ObbSweep {
    private ObbSweep() {}

    public static ObbSweepResult sweep(
            Obb3d moving,
            Vector3dc relativeDisplacement,
            Obb3d obstacle,
            ObbScratch scratch
    ) {
        Objects.requireNonNull(moving, "moving");
        Objects.requireNonNull(relativeDisplacement, "relativeDisplacement");
        Objects.requireNonNull(obstacle, "obstacle");
        Objects.requireNonNull(scratch, "scratch");
        OrthonormalFrame3d.requireFinite(relativeDisplacement, "relativeDisplacement");
        ObbSat.populateCandidateAxes(moving, obstacle, scratch);

        double minimum = Double.POSITIVE_INFINITY;
        int minimumIndex = -1;
        boolean initiallySeparated = false;

        for (int i = 0; i < ObbScratch.AXIS_COUNT; i++) {
            Vector3d axis = scratch.axes[i];
            double lengthSquared = axis.lengthSquared();
            if (lengthSquared <= GeometryTolerance.DEGENERATE_AXIS_LENGTH_SQUARED) {
                scratch.penetrations[i] = Double.NaN;
                continue;
            }
            axis.mul(1.0D / Math.sqrt(lengthSquared));
            double penetration = moving.radiusAlongUnitUnchecked(axis)
                    + obstacle.radiusAlongUnitUnchecked(axis)
                    - Math.abs(ObbSat.centerDeltaAlong(moving, obstacle, axis));
            if (!Double.isFinite(penetration)) {
                throw new IllegalArgumentException("sweep penetration must be finite");
            }
            scratch.penetrations[i] = penetration;
            if (penetration < -GeometryTolerance.TOUCHING) initiallySeparated = true;
            if (minimumIndex < 0
                    || penetration < minimum - GeometryTolerance.COMPARISON) {
                minimum = penetration;
                minimumIndex = i;
            }
        }
        if (minimumIndex < 0) {
            throw new IllegalArgumentException("OBB pair produced no usable SAT axis");
        }

        ObbSweepResult.InitialState initialState;
        if (initiallySeparated) {
            initialState = ObbSweepResult.InitialState.SEPARATED;
        } else if (minimum > GeometryTolerance.TOUCHING) {
            initialState = ObbSweepResult.InitialState.OVERLAPPING;
        } else {
            initialState = ObbSweepResult.InitialState.TOUCHING;
        }

        if (initialState == ObbSweepResult.InitialState.OVERLAPPING) {
            scratch.clearActive();
            addOrientedNormal(moving, obstacle, minimumIndex, scratch);
            return ObbSweepResult.initialOverlap(minimum, ActiveAxes.copyOf(scratch));
        }

        if (initialState == ObbSweepResult.InitialState.TOUCHING) {
            // Validate that motion will actually produce overlap before collecting normals
            double globalEntry = Double.NEGATIVE_INFINITY;
            double globalExit = Double.POSITIVE_INFINITY;
            boolean hasEnteringAxis = false;

            for (int i = 0; i < ObbScratch.AXIS_COUNT; i++) {
                if (!Double.isFinite(scratch.penetrations[i])
                        || scratch.penetrations[i] > GeometryTolerance.TOUCHING) continue;

                Vector3d axis = scratch.axes[i];
                double speed = relativeDisplacement.dot(axis);

                // Parallel axis check: touching axes with no relative motion are
                // permanent separators - they can never produce positive overlap
                if (Math.abs(speed) <= GeometryTolerance.PARALLEL) {
                    return ObbSweepResult.noHit(initialState);
                }

                // Calculate entry/exit times for this touching axis
                double aProjection = moving.centerX() * axis.x
                        + moving.centerY() * axis.y
                        + moving.centerZ() * axis.z;
                double bProjection = obstacle.centerX() * axis.x
                        + obstacle.centerY() * axis.y
                        + obstacle.centerZ() * axis.z;
                double aRadius = moving.radiusAlongUnitUnchecked(axis);
                double bRadius = obstacle.radiusAlongUnitUnchecked(axis);
                double aMin = aProjection - aRadius;
                double aMax = aProjection + aRadius;
                double bMin = bProjection - bRadius;
                double bMax = bProjection + bRadius;

                double entry;
                double exit;
                if (speed > 0.0D) {
                    entry = (bMin - aMax) / speed;
                    exit = (bMax - aMin) / speed;
                } else {
                    entry = (bMax - aMin) / speed;
                    exit = (bMin - aMax) / speed;
                }
                if (!Double.isFinite(entry) || !Double.isFinite(exit)) {
                    throw new IllegalArgumentException("sweep event time must be finite");
                }
                if (entry > exit) {
                    double swap = entry;
                    entry = exit;
                    exit = swap;
                }

                scratch.entryTimes[i] = entry;

                // Check if this axis is entering (not exiting)
                double centerDelta = ObbSat.centerDeltaAlong(moving, obstacle, axis);
                double sign = ObbSat.orientationSign(axis, centerDelta);
                double outwardMotion = speed * sign;
                if (outwardMotion < -GeometryTolerance.ENTERING) {
                    hasEnteringAxis = true;
                    globalEntry = Math.max(globalEntry, entry);
                    globalExit = Math.min(globalExit, exit);
                }
            }

            // No entering axes or intervals don't overlap
            if (!hasEnteringAxis || globalEntry - globalExit > GeometryTolerance.TIME) {
                return ObbSweepResult.noHit(initialState);
            }

            // Collect normals only for axes actually entering at the global entry time
            scratch.clearActive();
            for (int i = 0; i < ObbScratch.AXIS_COUNT; i++) {
                if (!Double.isFinite(scratch.penetrations[i])
                        || scratch.penetrations[i] > GeometryTolerance.TOUCHING) continue;

                double entry = scratch.entryTimes[i];
                if (!Double.isFinite(entry)
                        || Math.abs(entry - globalEntry) > GeometryTolerance.TIME) continue;

                Vector3d axis = scratch.axes[i];
                double centerDelta = ObbSat.centerDeltaAlong(moving, obstacle, axis);
                double sign = ObbSat.orientationSign(axis, centerDelta);
                double speed = relativeDisplacement.dot(axis);
                double outwardMotion = speed * sign;

                if (outwardMotion < -GeometryTolerance.ENTERING) {
                    scratch.addActive(i, axis.x * sign, axis.y * sign, axis.z * sign);
                }
            }

            if (scratch.activeCount == 0) return ObbSweepResult.noHit(initialState);
            return ObbSweepResult.hit(initialState, 0.0D, ActiveAxes.copyOf(scratch));
        }

        double globalEntry = Double.NEGATIVE_INFINITY;
        double globalExit = Double.POSITIVE_INFINITY;
        boolean hasMovingAxis = false;
        for (int i = 0; i < ObbScratch.AXIS_COUNT; i++) {
            Vector3d axis = scratch.axes[i];
            if (!Double.isFinite(scratch.penetrations[i])) {
                scratch.entryTimes[i] = Double.NaN;
                continue;
            }
            double aProjection = moving.centerX() * axis.x
                    + moving.centerY() * axis.y
                    + moving.centerZ() * axis.z;
            double bProjection = obstacle.centerX() * axis.x
                    + obstacle.centerY() * axis.y
                    + obstacle.centerZ() * axis.z;
            double aRadius = moving.radiusAlongUnitUnchecked(axis);
            double bRadius = obstacle.radiusAlongUnitUnchecked(axis);
            double aMin = aProjection - aRadius;
            double aMax = aProjection + aRadius;
            double bMin = bProjection - bRadius;
            double bMax = bProjection + bRadius;
            double speed = relativeDisplacement.dot(axis);

            if (Math.abs(speed) <= GeometryTolerance.PARALLEL) {
                scratch.entryTimes[i] = Double.NaN;
                double gap = Math.max(aMin - bMax, bMin - aMax);
                // A parallel axis that is separated OR exactly touching can
                // never produce positive volume overlap, so it is a permanent
                // separator: the pair may slide along the touching surface but
                // must not report a phantom side hit (flat voxel seams).
                if (gap >= -GeometryTolerance.TOUCHING) {
                    return ObbSweepResult.noHit(initialState);
                }
                continue;
            }

            hasMovingAxis = true;
            double entry;
            double exit;
            if (speed > 0.0D) {
                entry = (bMin - aMax) / speed;
                exit = (bMax - aMin) / speed;
            } else {
                entry = (bMax - aMin) / speed;
                exit = (bMin - aMax) / speed;
            }
            if (!Double.isFinite(entry) || !Double.isFinite(exit)) {
                throw new IllegalArgumentException("sweep event time must be finite");
            }
            if (entry > exit) {
                double swap = entry;
                entry = exit;
                exit = swap;
            }
            scratch.entryTimes[i] = entry;
            globalEntry = Math.max(globalEntry, entry);
            globalExit = Math.min(globalExit, exit);
            if (globalEntry - globalExit > GeometryTolerance.TIME) {
                return ObbSweepResult.noHit(initialState);
            }
        }

        if (!hasMovingAxis
                || globalEntry < -GeometryTolerance.TIME
                || globalExit < -GeometryTolerance.TIME
                || globalEntry > 1.0D + GeometryTolerance.TIME
                || globalEntry - globalExit > GeometryTolerance.TIME) {
            return ObbSweepResult.noHit(initialState);
        }

        scratch.clearActive();
        for (int i = 0; i < ObbScratch.AXIS_COUNT; i++) {
            double entry = scratch.entryTimes[i];
            if (!Double.isFinite(entry)
                    || Math.abs(entry - globalEntry) > GeometryTolerance.TIME) continue;
            Vector3d axis = scratch.axes[i];
            double speed = relativeDisplacement.dot(axis);
            double sign = speed > 0.0D ? -1.0D : 1.0D;
            scratch.addActive(i, axis.x * sign, axis.y * sign, axis.z * sign);
        }
        if (scratch.activeCount == 0) {
            throw new IllegalArgumentException("sweep hit produced no active SAT axis");
        }
        double time = Math.max(0.0D, Math.min(1.0D, globalEntry));
        return ObbSweepResult.hit(initialState, time, ActiveAxes.copyOf(scratch));
    }

    private static void addOrientedNormal(
            Obb3d moving,
            Obb3d obstacle,
            int axisIndex,
            ObbScratch scratch
    ) {
        Vector3d axis = scratch.axes[axisIndex];
        double centerDelta = ObbSat.centerDeltaAlong(moving, obstacle, axis);
        double sign = ObbSat.orientationSign(axis, centerDelta);
        scratch.addActive(axisIndex, axis.x * sign, axis.y * sign, axis.z * sign);
    }
}
