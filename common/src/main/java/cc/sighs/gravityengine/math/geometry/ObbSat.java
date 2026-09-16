package cc.sighs.gravityengine.math.geometry;

import org.joml.Vector3d;

import java.util.Objects;

/** Authoritative static 15-axis OBB separating-axis implementation. */
public final class ObbSat {
    private ObbSat() {}

    public static ObbOverlapResult overlap(Obb3d a, Obb3d b, ObbScratch scratch) {
        Objects.requireNonNull(a, "a");
        Objects.requireNonNull(b, "b");
        Objects.requireNonNull(scratch, "scratch");
        populateCandidateAxes(a, b, scratch);
        return classify(a, b, scratch);
    }

    /**
     * Scratch-free one-shot SAT overlap used by non-collision consumers whose
     * call is not inside a physics operation context.  It allocates only
     * invocation-local vectors and never stores mutable geometry state.
     */
    public static ObbOverlapResult overlapDetached(Obb3d a, Obb3d b) {
        return overlap(a, b, new ObbScratch());
    }

    private static ObbOverlapResult classify(
            Obb3d a,
            Obb3d b,
            ObbScratch scratch
    ) {
        double minimum = Double.POSITIVE_INFINITY;
        int minimumIndex = -1;
        double minimumX = 0.0D;
        double minimumY = 0.0D;
        double minimumZ = 0.0D;

        for (int i = 0; i < ObbScratch.AXIS_COUNT; i++) {
            Vector3d axis = scratch.axes[i];
            double lengthSquared = axis.lengthSquared();
            if (lengthSquared <= GeometryTolerance.DEGENERATE_AXIS_LENGTH_SQUARED) continue;
            axis.mul(1.0D / Math.sqrt(lengthSquared));
            double centerDelta = centerDeltaAlong(a, b, axis);
            double penetration = a.radiusAlongUnitUnchecked(axis)
                    + b.radiusAlongUnitUnchecked(axis)
                    - Math.abs(centerDelta);
            if (!Double.isFinite(penetration)) {
                throw new IllegalArgumentException("SAT penetration must be finite");
            }
            scratch.penetrations[i] = penetration;
            if (penetration < -GeometryTolerance.TOUCHING) {
                return ObbOverlapResult.separated(i);
            }
            if (minimumIndex < 0
                    || penetration < minimum - GeometryTolerance.COMPARISON) {
                minimum = penetration;
                minimumIndex = i;
                double sign = orientationSign(axis, centerDelta);
                minimumX = axis.x * sign;
                minimumY = axis.y * sign;
                minimumZ = axis.z * sign;
            }
        }

        if (minimumIndex < 0) {
            throw new IllegalArgumentException("OBB pair produced no usable SAT axis");
        }
        if (minimum <= GeometryTolerance.TOUCHING) {
            return ObbOverlapResult.touching(
                    minimumX, minimumY, minimumZ, minimumIndex
            );
        }
        return ObbOverlapResult.overlapping(
                minimum, minimumX, minimumY, minimumZ, minimumIndex
        );
    }

    /** Classifies one caller-supplied unit axis using the same interval mathematics. */
    public static ObbOverlapResult overlapAlong(Obb3d a, Obb3d b, Vector3d unitAxis) {
        Objects.requireNonNull(a, "a");
        Objects.requireNonNull(b, "b");
        Objects.requireNonNull(unitAxis, "unitAxis");
        OrthonormalFrame3d.requireFinite(unitAxis, "unitAxis");
        double lengthSquared = unitAxis.lengthSquared();
        if (lengthSquared <= GeometryTolerance.DEGENERATE_AXIS_LENGTH_SQUARED) {
            throw new IllegalArgumentException("unitAxis must be non-degenerate");
        }
        if (Math.abs(lengthSquared - 1.0D) > GeometryTolerance.ORTHONORMAL) {
            throw new IllegalArgumentException("unitAxis must have unit length");
        }
        double centerDelta = centerDeltaAlong(a, b, unitAxis);
        double penetration = a.radiusAlongUnitUnchecked(unitAxis)
                + b.radiusAlongUnitUnchecked(unitAxis)
                - Math.abs(centerDelta);
        if (!Double.isFinite(penetration)) {
            throw new IllegalArgumentException("SAT penetration must be finite");
        }
        if (penetration < -GeometryTolerance.TOUCHING) {
            return ObbOverlapResult.separated(-1);
        }
        double sign = orientationSign(unitAxis, centerDelta);
        double x = unitAxis.x * sign;
        double y = unitAxis.y * sign;
        double z = unitAxis.z * sign;
        if (penetration <= GeometryTolerance.TOUCHING) {
            return ObbOverlapResult.touching(x, y, z, -1);
        }
        return ObbOverlapResult.overlapping(penetration, x, y, z, -1);
    }

    static void populateCandidateAxes(Obb3d a, Obb3d b, ObbScratch scratch) {
        a.axisX(scratch.axes[0]);
        a.axisY(scratch.axes[1]);
        a.axisZ(scratch.axes[2]);
        b.axisX(scratch.axes[3]);
        b.axisY(scratch.axes[4]);
        b.axisZ(scratch.axes[5]);
        scratch.axes[0].cross(scratch.axes[3], scratch.axes[6]);
        scratch.axes[0].cross(scratch.axes[4], scratch.axes[7]);
        scratch.axes[0].cross(scratch.axes[5], scratch.axes[8]);
        scratch.axes[1].cross(scratch.axes[3], scratch.axes[9]);
        scratch.axes[1].cross(scratch.axes[4], scratch.axes[10]);
        scratch.axes[1].cross(scratch.axes[5], scratch.axes[11]);
        scratch.axes[2].cross(scratch.axes[3], scratch.axes[12]);
        scratch.axes[2].cross(scratch.axes[4], scratch.axes[13]);
        scratch.axes[2].cross(scratch.axes[5], scratch.axes[14]);
    }

    static double centerDeltaAlong(Obb3d a, Obb3d b, Vector3d axis) {
        return (a.centerX() - b.centerX()) * axis.x
                + (a.centerY() - b.centerY()) * axis.y
                + (a.centerZ() - b.centerZ()) * axis.z;
    }

    static double orientationSign(Vector3d axis, double centerDelta) {
        if (centerDelta > GeometryTolerance.COMPARISON) return 1.0D;
        if (centerDelta < -GeometryTolerance.COMPARISON) return -1.0D;
        if (axis.x < 0.0D) return 1.0D;
        if (axis.x > 0.0D) return -1.0D;
        if (axis.y < 0.0D) return 1.0D;
        if (axis.y > 0.0D) return -1.0D;
        return axis.z <= 0.0D ? 1.0D : -1.0D;
    }
}
