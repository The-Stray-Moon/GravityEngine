package cc.sighs.gravityengine.math.geometry;

import cc.sighs.gravityengine.api.math.Vec3d;
import java.util.Objects;

/** Authoritative analytic continuous SAT for a translating OBB pair. */
public final class ObbSweep {
    private ObbSweep() {}

    public static ObbSweepResult sweep(
            Obb3d moving,
            Vec3d relativeDisplacement,
            Obb3d obstacle,
            ObbScratch scratch
    ) {
        Objects.requireNonNull(moving, "moving");
        Objects.requireNonNull(
                relativeDisplacement,
                "relativeDisplacement"
        );
        Objects.requireNonNull(obstacle, "obstacle");
        Objects.requireNonNull(scratch, "scratch");

        OrthonormalFrame3d.requireFinite(
                relativeDisplacement,
                "relativeDisplacement"
        );

        ObbSat.populateCandidateAxes(
                moving,
                obstacle,
                scratch
        );

        double minimum =
                Double.POSITIVE_INFINITY;

        int minimumIndex = -1;

        boolean initiallySeparated =
                false;

        for (int i = 0;
             i < ObbScratch.AXIS_COUNT;
             i++) {

            if (!scratch.normalizeAxis(i)) {
                scratch.penetrations[i] =
                        Double.NaN;

                continue;
            }

            double axisX = scratch.axisX(i);
            double axisY = scratch.axisY(i);
            double axisZ = scratch.axisZ(i);

            double penetration =
                    moving.radiusAlongUnitUnchecked(
                            axisX,
                            axisY,
                            axisZ
                    )
                            + obstacle.radiusAlongUnitUnchecked(
                            axisX,
                            axisY,
                            axisZ
                    )
                            - Math.abs(
                            ObbSat.centerDeltaAlong(
                                    moving,
                                    obstacle,
                                    axisX,
                                    axisY,
                                    axisZ
                            )
                    );

            if (!Double.isFinite(penetration)) {
                throw new IllegalArgumentException(
                        "sweep penetration must be finite");
            }

            scratch.penetrations[i] =
                    penetration;

            if (penetration
                    < -GeometryTolerance.TOUCHING) {
                initiallySeparated =
                        true;
            }

            if (minimumIndex < 0
                    || penetration
                    < minimum
                    - GeometryTolerance.COMPARISON) {

                minimum =
                        penetration;

                minimumIndex =
                        i;
            }
        }

        if (minimumIndex < 0) {
            throw new IllegalArgumentException(
                    "OBB pair produced no usable SAT axis");
        }

        ObbSweepResult.InitialState initialState;

        if (initiallySeparated) {
            initialState =
                    ObbSweepResult.InitialState.SEPARATED;
        } else if (minimum
                > GeometryTolerance.TOUCHING) {
            initialState =
                    ObbSweepResult.InitialState.OVERLAPPING;
        } else {
            initialState =
                    ObbSweepResult.InitialState.TOUCHING;
        }

        if (initialState
                == ObbSweepResult.InitialState.OVERLAPPING) {

            scratch.clearActive();

            addOrientedNormal(
                    moving,
                    obstacle,
                    minimumIndex,
                    scratch
            );

            return ObbSweepResult.initialOverlap(
                    minimum,
                    ActiveAxes.copyOf(scratch)
            );
        }

        if (initialState
                == ObbSweepResult.InitialState.TOUCHING) {

            double globalEntry =
                    Double.NEGATIVE_INFINITY;

            double globalExit =
                    Double.POSITIVE_INFINITY;

            boolean hasEnteringAxis =
                    false;

            for (int i = 0;
                 i < ObbScratch.AXIS_COUNT;
                 i++) {

                if (!Double.isFinite(
                        scratch.penetrations[i])
                        || scratch.penetrations[i]
                        > GeometryTolerance.TOUCHING) {

                    continue;
                }

                double axisX = scratch.axisX(i);
                double axisY = scratch.axisY(i);
                double axisZ = scratch.axisZ(i);

                double speed =
                        scratch.dotAxis(
                                i,
                                relativeDisplacement
                        );

                /*
                 * A touching axis with no relative
                 * motion remains a permanent
                 * separator.
                 */
                if (Math.abs(speed)
                        <= GeometryTolerance.PARALLEL) {

                    return ObbSweepResult.noHit(
                            initialState);
                }

                double aProjection =
                        projection(
                                moving,
                                axisX,
                                axisY,
                                axisZ
                        );

                double bProjection =
                        projection(
                                obstacle,
                                axisX,
                                axisY,
                                axisZ
                        );

                double aRadius =
                        moving.radiusAlongUnitUnchecked(
                                axisX,
                                axisY,
                                axisZ
                        );

                double bRadius =
                        obstacle.radiusAlongUnitUnchecked(
                                axisX,
                                axisY,
                                axisZ
                        );

                double aMin =
                        aProjection - aRadius;

                double aMax =
                        aProjection + aRadius;

                double bMin =
                        bProjection - bRadius;

                double bMax =
                        bProjection + bRadius;

                double entry;
                double exit;

                if (speed > 0.0D) {
                    entry =
                            (bMin - aMax)
                                    / speed;

                    exit =
                            (bMax - aMin)
                                    / speed;
                } else {
                    entry =
                            (bMax - aMin)
                                    / speed;

                    exit =
                            (bMin - aMax)
                                    / speed;
                }

                if (!Double.isFinite(entry)
                        || !Double.isFinite(exit)) {

                    throw new IllegalArgumentException(
                            "sweep event time must be finite");
                }

                if (entry > exit) {
                    double swap = entry;
                    entry = exit;
                    exit = swap;
                }

                scratch.entryTimes[i] =
                        entry;

                double centerDelta =
                        ObbSat.centerDeltaAlong(
                                moving,
                                obstacle,
                                axisX,
                                axisY,
                                axisZ
                        );

                double sign =
                        ObbSat.orientationSign(
                                axisX,
                                axisY,
                                axisZ,
                                centerDelta
                        );

                double outwardMotion =
                        speed * sign;

                if (outwardMotion
                        < -GeometryTolerance.ENTERING) {

                    hasEnteringAxis =
                            true;

                    globalEntry =
                            Math.max(
                                    globalEntry,
                                    entry
                            );

                    globalExit =
                            Math.min(
                                    globalExit,
                                    exit
                            );
                }
            }

            if (!hasEnteringAxis
                    || globalEntry - globalExit
                    > GeometryTolerance.TIME) {

                return ObbSweepResult.noHit(
                        initialState);
            }

            scratch.clearActive();

            for (int i = 0;
                 i < ObbScratch.AXIS_COUNT;
                 i++) {

                if (!Double.isFinite(
                        scratch.penetrations[i])
                        || scratch.penetrations[i]
                        > GeometryTolerance.TOUCHING) {

                    continue;
                }

                double entry =
                        scratch.entryTimes[i];

                if (!Double.isFinite(entry)
                        || Math.abs(
                        entry - globalEntry)
                        > GeometryTolerance.TIME) {

                    continue;
                }

                double axisX = scratch.axisX(i);
                double axisY = scratch.axisY(i);
                double axisZ = scratch.axisZ(i);

                double centerDelta =
                        ObbSat.centerDeltaAlong(
                                moving,
                                obstacle,
                                axisX,
                                axisY,
                                axisZ
                        );

                double sign =
                        ObbSat.orientationSign(
                                axisX,
                                axisY,
                                axisZ,
                                centerDelta
                        );

                double speed =
                        scratch.dotAxis(
                                i,
                                relativeDisplacement
                        );

                double outwardMotion =
                        speed * sign;

                if (outwardMotion
                        < -GeometryTolerance.ENTERING) {

                    scratch.addActive(
                            i,
                            axisX * sign,
                            axisY * sign,
                            axisZ * sign
                    );
                }
            }

            if (scratch.activeCount == 0) {
                return ObbSweepResult.noHit(
                        initialState);
            }

            return ObbSweepResult.hit(
                    initialState,
                    0.0D,
                    ActiveAxes.copyOf(scratch)
            );
        }

        double globalEntry =
                Double.NEGATIVE_INFINITY;

        double globalExit =
                Double.POSITIVE_INFINITY;

        boolean hasMovingAxis =
                false;

        for (int i = 0;
             i < ObbScratch.AXIS_COUNT;
             i++) {

            if (!Double.isFinite(
                    scratch.penetrations[i])) {

                scratch.entryTimes[i] =
                        Double.NaN;

                continue;
            }

            double axisX = scratch.axisX(i);
            double axisY = scratch.axisY(i);
            double axisZ = scratch.axisZ(i);

            double aProjection =
                    projection(
                            moving,
                            axisX,
                            axisY,
                            axisZ
                    );

            double bProjection =
                    projection(
                            obstacle,
                            axisX,
                            axisY,
                            axisZ
                    );

            double aRadius =
                    moving.radiusAlongUnitUnchecked(
                            axisX,
                            axisY,
                            axisZ
                    );

            double bRadius =
                    obstacle.radiusAlongUnitUnchecked(
                            axisX,
                            axisY,
                            axisZ
                    );

            double aMin =
                    aProjection - aRadius;

            double aMax =
                    aProjection + aRadius;

            double bMin =
                    bProjection - bRadius;

            double bMax =
                    bProjection + bRadius;

            double speed =
                    scratch.dotAxis(
                            i,
                            relativeDisplacement
                    );

            if (Math.abs(speed)
                    <= GeometryTolerance.PARALLEL) {

                scratch.entryTimes[i] =
                        Double.NaN;

                double gap =
                        Math.max(
                                aMin - bMax,
                                bMin - aMax
                        );

                if (gap
                        >= -GeometryTolerance.TOUCHING) {

                    return ObbSweepResult.noHit(
                            initialState);
                }

                continue;
            }

            hasMovingAxis =
                    true;

            double entry;
            double exit;

            if (speed > 0.0D) {
                entry =
                        (bMin - aMax)
                                / speed;

                exit =
                        (bMax - aMin)
                                / speed;
            } else {
                entry =
                        (bMax - aMin)
                                / speed;

                exit =
                        (bMin - aMax)
                                / speed;
            }

            if (!Double.isFinite(entry)
                    || !Double.isFinite(exit)) {

                throw new IllegalArgumentException(
                        "sweep event time must be finite");
            }

            if (entry > exit) {
                double swap = entry;
                entry = exit;
                exit = swap;
            }

            scratch.entryTimes[i] =
                    entry;

            globalEntry =
                    Math.max(
                            globalEntry,
                            entry
                    );

            globalExit =
                    Math.min(
                            globalExit,
                            exit
                    );

            if (globalEntry - globalExit
                    > GeometryTolerance.TIME) {

                return ObbSweepResult.noHit(
                        initialState);
            }
        }

        if (!hasMovingAxis
                || globalEntry
                < -GeometryTolerance.TIME
                || globalExit
                < -GeometryTolerance.TIME
                || globalEntry
                > 1.0D + GeometryTolerance.TIME
                || globalEntry - globalExit
                > GeometryTolerance.TIME) {

            return ObbSweepResult.noHit(
                    initialState);
        }

        scratch.clearActive();

        for (int i = 0;
             i < ObbScratch.AXIS_COUNT;
             i++) {

            double entry =
                    scratch.entryTimes[i];

            if (!Double.isFinite(entry)
                    || Math.abs(
                    entry - globalEntry)
                    > GeometryTolerance.TIME) {

                continue;
            }

            double axisX = scratch.axisX(i);
            double axisY = scratch.axisY(i);
            double axisZ = scratch.axisZ(i);

            double speed =
                    scratch.dotAxis(
                            i,
                            relativeDisplacement
                    );

            double sign =
                    speed > 0.0D
                            ? -1.0D
                            : 1.0D;

            scratch.addActive(
                    i,
                    axisX * sign,
                    axisY * sign,
                    axisZ * sign
            );
        }

        if (scratch.activeCount == 0) {
            throw new IllegalArgumentException(
                    "sweep hit produced no active SAT axis");
        }

        double time =
                Math.max(
                        0.0D,
                        Math.min(
                                1.0D,
                                globalEntry
                        )
                );

        return ObbSweepResult.hit(
                initialState,
                time,
                ActiveAxes.copyOf(scratch)
        );
    }

    private static double projection(
            Obb3d box,
            double axisX,
            double axisY,
            double axisZ
    ) {
        return box.centerX() * axisX
                + box.centerY() * axisY
                + box.centerZ() * axisZ;
    }

    private static void addOrientedNormal(
            Obb3d moving,
            Obb3d obstacle,
            int axisIndex,
            ObbScratch scratch
    ) {
        double axisX =
                scratch.axisX(axisIndex);

        double axisY =
                scratch.axisY(axisIndex);

        double axisZ =
                scratch.axisZ(axisIndex);

        double centerDelta =
                ObbSat.centerDeltaAlong(
                        moving,
                        obstacle,
                        axisX,
                        axisY,
                        axisZ
                );

        double sign =
                ObbSat.orientationSign(
                        axisX,
                        axisY,
                        axisZ,
                        centerDelta
                );

        scratch.addActive(
                axisIndex,
                axisX * sign,
                axisY * sign,
                axisZ * sign
        );
    }
}
