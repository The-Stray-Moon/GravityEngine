package cc.sighs.gravityengine.math.geometry;

import cc.sighs.gravityengine.api.math.Vec3d;
import java.util.Objects;

/** Authoritative static 15-axis OBB separating-axis implementation. */
public final class ObbSat {
    private ObbSat() {}

    public static ObbOverlapResult overlap(
            Obb3d a,
            Obb3d b,
            ObbScratch scratch
    ) {
        Objects.requireNonNull(a, "a");
        Objects.requireNonNull(b, "b");
        Objects.requireNonNull(scratch, "scratch");

        populateCandidateAxes(a, b, scratch);

        return classify(a, b, scratch);
    }

    public static ObbOverlapResult overlapDetached(
            Obb3d a,
            Obb3d b
    ) {
        return overlap(
                a,
                b,
                new ObbScratch()
        );
    }

    private static ObbOverlapResult classify(
            Obb3d a,
            Obb3d b,
            ObbScratch scratch
    ) {
        double minimum =
                Double.POSITIVE_INFINITY;

        int minimumIndex = -1;

        double minimumX = 0.0D;
        double minimumY = 0.0D;
        double minimumZ = 0.0D;

        for (int i = 0;
             i < ObbScratch.AXIS_COUNT;
             i++) {

            if (!scratch.normalizeAxis(i)) {
                continue;
            }

            double axisX = scratch.axisX(i);
            double axisY = scratch.axisY(i);
            double axisZ = scratch.axisZ(i);

            double centerDelta =
                    centerDeltaAlong(
                            a,
                            b,
                            axisX,
                            axisY,
                            axisZ
                    );

            double penetration =
                    a.radiusAlongUnitUnchecked(
                            axisX,
                            axisY,
                            axisZ
                    )
                            + b.radiusAlongUnitUnchecked(
                            axisX,
                            axisY,
                            axisZ
                    )
                            - Math.abs(centerDelta);

            if (!Double.isFinite(penetration)) {
                throw new IllegalArgumentException(
                        "SAT penetration must be finite");
            }

            scratch.penetrations[i] =
                    penetration;

            if (penetration
                    < -GeometryTolerance.TOUCHING) {
                return ObbOverlapResult.separated(i);
            }

            if (minimumIndex < 0
                    || penetration
                    < minimum - GeometryTolerance.COMPARISON) {

                minimum =
                        penetration;

                minimumIndex =
                        i;

                double sign =
                        orientationSign(
                                axisX,
                                axisY,
                                axisZ,
                                centerDelta
                        );

                minimumX =
                        axisX * sign;

                minimumY =
                        axisY * sign;

                minimumZ =
                        axisZ * sign;
            }
        }

        if (minimumIndex < 0) {
            throw new IllegalArgumentException(
                    "OBB pair produced no usable SAT axis");
        }

        if (minimum
                <= GeometryTolerance.TOUCHING) {
            return ObbOverlapResult.touching(
                    minimumX,
                    minimumY,
                    minimumZ,
                    minimumIndex
            );
        }

        return ObbOverlapResult.overlapping(
                minimum,
                minimumX,
                minimumY,
                minimumZ,
                minimumIndex
        );
    }

    public static ObbOverlapResult overlapAlong(
            Obb3d a,
            Obb3d b,
            Vec3d unitAxis
    ) {
        Objects.requireNonNull(a, "a");
        Objects.requireNonNull(b, "b");
        Objects.requireNonNull(
                unitAxis,
                "unitAxis"
        );

        OrthonormalFrame3d.requireFinite(
                unitAxis,
                "unitAxis"
        );

        double lengthSquared =
                unitAxis.lengthSquared();

        if (lengthSquared
                <= GeometryTolerance.DEGENERATE_AXIS_LENGTH_SQUARED) {
            throw new IllegalArgumentException(
                    "unitAxis must be non-degenerate");
        }

        if (Math.abs(lengthSquared - 1.0D)
                > GeometryTolerance.ORTHONORMAL) {
            throw new IllegalArgumentException(
                    "unitAxis must have unit length");
        }

        double axisX = unitAxis.x();
        double axisY = unitAxis.y();
        double axisZ = unitAxis.z();

        double centerDelta =
                centerDeltaAlong(
                        a,
                        b,
                        axisX,
                        axisY,
                        axisZ
                );

        double penetration =
                a.radiusAlongUnitUnchecked(
                        axisX,
                        axisY,
                        axisZ
                )
                        + b.radiusAlongUnitUnchecked(
                        axisX,
                        axisY,
                        axisZ
                )
                        - Math.abs(centerDelta);

        if (!Double.isFinite(penetration)) {
            throw new IllegalArgumentException(
                    "SAT penetration must be finite");
        }

        if (penetration
                < -GeometryTolerance.TOUCHING) {
            return ObbOverlapResult.separated(-1);
        }

        double sign =
                orientationSign(
                        axisX,
                        axisY,
                        axisZ,
                        centerDelta
                );

        double x = axisX * sign;
        double y = axisY * sign;
        double z = axisZ * sign;

        if (penetration
                <= GeometryTolerance.TOUCHING) {
            return ObbOverlapResult.touching(
                    x,
                    y,
                    z,
                    -1
            );
        }

        return ObbOverlapResult.overlapping(
                penetration,
                x,
                y,
                z,
                -1
        );
    }

    static void populateCandidateAxes(
            Obb3d a,
            Obb3d b,
            ObbScratch scratch
    ) {
        /*
         * 0..2 = A basis
         * 3..5 = B basis
         */
        a.frame().writeAxes(
                scratch.axes,
                0
        );

        b.frame().writeAxes(
                scratch.axes,
                9
        );

        scratch.crossAxes(6, 0, 3);
        scratch.crossAxes(7, 0, 4);
        scratch.crossAxes(8, 0, 5);

        scratch.crossAxes(9, 1, 3);
        scratch.crossAxes(10, 1, 4);
        scratch.crossAxes(11, 1, 5);

        scratch.crossAxes(12, 2, 3);
        scratch.crossAxes(13, 2, 4);
        scratch.crossAxes(14, 2, 5);
    }

    static double centerDeltaAlong(
            Obb3d a,
            Obb3d b,
            Vec3d axis
    ) {
        return centerDeltaAlong(
                a,
                b,
                axis.x(),
                axis.y(),
                axis.z()
        );
    }

    static double centerDeltaAlong(
            Obb3d a,
            Obb3d b,
            double axisX,
            double axisY,
            double axisZ
    ) {
        return (a.centerX() - b.centerX())
                * axisX
                + (a.centerY() - b.centerY())
                * axisY
                + (a.centerZ() - b.centerZ())
                * axisZ;
    }

    static double orientationSign(
            Vec3d axis,
            double centerDelta
    ) {
        return orientationSign(
                axis.x(),
                axis.y(),
                axis.z(),
                centerDelta
        );
    }

    static double orientationSign(
            double axisX,
            double axisY,
            double axisZ,
            double centerDelta
    ) {
        if (centerDelta
                > GeometryTolerance.COMPARISON) {
            return 1.0D;
        }

        if (centerDelta
                < -GeometryTolerance.COMPARISON) {
            return -1.0D;
        }

        if (axisX < 0.0D) {
            return 1.0D;
        }

        if (axisX > 0.0D) {
            return -1.0D;
        }

        if (axisY < 0.0D) {
            return 1.0D;
        }

        if (axisY > 0.0D) {
            return -1.0D;
        }

        return axisZ <= 0.0D
                ? 1.0D
                : -1.0D;
    }
}