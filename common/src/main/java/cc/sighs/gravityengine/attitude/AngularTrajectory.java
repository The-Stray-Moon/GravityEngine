package cc.sighs.gravityengine.attitude;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.math.Quatd;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Immutable step evidence for one logical attitude step.
 *
 * <p>Dynamic segments record the authoritative world-space angular momentum
 * samples {@code L_world} produced by {@link AngularDynamicsSolver}. Angular
 * velocity is no longer a second authority here: callers that need a
 * diagnostic rate derive it from {@code q + L + I} through
 * {@link #derivedAngularVelocity(int, EffectiveAngularInertia)}.</p>
 *
 * <p>Kinematic segments describe a mode-owned geometric orientation rewrite.
 * They carry no angular momentum at all, so they cannot claim momentum
 * preservation, and a trajectory that mixes dynamic and kinematic segments
 * must document an explicit handoff at its caller.</p>
 *
 * <p>A trajectory is evidence only. It never becomes the source of the next
 * physics state and never owns collision geometry or translation.</p>
 */
public record AngularTrajectory(
        Vec3d initialAngularMomentumWorld,
        List<AngularSegment> segments
) {
    public enum Kind {
        /** Integrated by the common angular-dynamics solver. */
        DYNAMIC,
        /** Mode-owned geometric orientation rewrite; no momentum is involved. */
        KINEMATIC_GEOMETRIC
    }

    /**
     * One angular segment with its integration duration and endpoint angular
     * momentum sample ({@code L_world}, {@code inertia-unit * rad / s}).
     */
    public record AngularSegment(
            Quatd start,
            Quatd end,
            Kind kind,
            double durationSeconds,
            Vec3d endAngularMomentumWorld
    ) {
        public AngularSegment {
            Objects.requireNonNull(start, "start");
            Objects.requireNonNull(end, "end");
            Objects.requireNonNull(kind, "kind");
            start = normalizedCopy(start, "start");
            end = normalizedCopy(end, "end");
            if (!Double.isFinite(durationSeconds) || durationSeconds < 0.0D) {
                throw new IllegalArgumentException(
                        "durationSeconds must be finite and non-negative");
            }
            if (kind == Kind.DYNAMIC) {
                if (endAngularMomentumWorld == null) {
                    throw new IllegalArgumentException(
                            "dynamic segments require an angular momentum sample");
                }
                requireFinite(endAngularMomentumWorld,
                        "endAngularMomentumWorld");
            } else {
                if (endAngularMomentumWorld != null) {
                    throw new IllegalArgumentException(
                            "kinematic geometric segments must not carry "
                                    + "angular momentum");
                }
                if (durationSeconds != 0.0D) {
                    throw new IllegalArgumentException(
                            "kinematic geometric segments cannot carry an "
                                    + "artificial integration duration");
                }
            }
        }

        /** Endpoint angular momentum sample, {@code null} for kinematic segments. */
        @Override
        public Vec3d endAngularMomentumWorld() {
            return endAngularMomentumWorld == null
                    ? null
                    : copy(endAngularMomentumWorld);
        }
    }

    public AngularTrajectory {
        Objects.requireNonNull(segments, "segments");
        if (segments.isEmpty()) {
            throw new IllegalArgumentException(
                    "trajectory requires at least one segment");
        }

        List<AngularSegment> copied = new ArrayList<>(segments.size());
        for (AngularSegment segment : segments) {
            AngularSegment required = Objects.requireNonNull(
                    segment, "segment");
            copied.add(new AngularSegment(
                    required.start(),
                    required.end(),
                    required.kind(),
                    required.durationSeconds(),
                    required.endAngularMomentumWorld()
            ));
        }
        for (int index = 1; index < copied.size(); index++) {
            AngularSegment previous = copied.get(index - 1);
            AngularSegment current = copied.get(index);
            if (!sameRotation(previous.end(), current.start())) {
                throw new IllegalArgumentException(
                        "trajectory segments must be contiguous at index "
                                + index);
            }
        }

        boolean anyDynamic = copied.stream()
                .anyMatch(segment -> segment.kind() == Kind.DYNAMIC);
        if (anyDynamic) {
            if (initialAngularMomentumWorld == null) {
                throw new IllegalArgumentException(
                        "a dynamic trajectory requires an initial angular "
                                + "momentum sample");
            }
            requireFinite(initialAngularMomentumWorld,
                    "initialAngularMomentumWorld");
        } else if (initialAngularMomentumWorld != null) {
            throw new IllegalArgumentException(
                    "a kinematic-only trajectory must not carry angular "
                            + "momentum");
        }

        initialAngularMomentumWorld = initialAngularMomentumWorld == null
                ? null
                : copy(initialAngularMomentumWorld);
        segments = List.copyOf(copied);
    }

    @Override
    public List<AngularSegment> segments() {
        return segments.stream()
                .map(segment -> new AngularSegment(
                        segment.start(),
                        segment.end(),
                        segment.kind(),
                        segment.durationSeconds(),
                        segment.endAngularMomentumWorld()
                ))
                .toList();
    }

    /** True when this evidence came from the dynamic solver. */
    public boolean dynamic() {
        return initialAngularMomentumWorld != null;
    }

    public AngularSegment segment(int index) {
        AngularSegment segment = segments.get(index);
        return new AngularSegment(
                segment.start(),
                segment.end(),
                segment.kind(),
                segment.durationSeconds(),
                segment.endAngularMomentumWorld()
        );
    }

    public int segmentCount() {
        return segments.size();
    }

    /** Number of integrated DYNAMIC segments. */
    public int dynamicSegmentCount() {
        int count = 0;
        for (AngularSegment segment : segments) {
            if (segment.kind() == Kind.DYNAMIC) {
                count++;
            }
        }
        return count;
    }

    public Quatd sample(int index) {
        if (index < 0 || index > segments.size()) {
            throw new IndexOutOfBoundsException(
                    "sample index out of range: " + index);
        }
        return index == 0
                ? segments.get(0).start()
                : segments.get(index - 1).end();
    }

    public List<Quatd> samples() {
        List<Quatd> samples = new ArrayList<>(segments.size() + 1);
        samples.add(segments.get(0).start());
        for (AngularSegment segment : segments) {
            samples.add(segment.end());
        }
        return List.copyOf(samples);
    }

    /**
     * Derived diagnostic {@code omega} at the end of one segment, computed
     * from the recorded {@code q + L + I} sample rather than stored separately.
     * Returns {@code Vec3d.ZERO} for kinematic segments.
     */
    public Vec3d derivedAngularVelocity(
            int segmentIndex,
            EffectiveAngularInertia inertia
    ) {
        Objects.requireNonNull(inertia, "inertia");
        AngularSegment segment = segments.get(segmentIndex);
        if (segment.kind() != Kind.DYNAMIC) {
            return Vec3d.ZERO;
        }
        return inertia.angularVelocityWorld(
                segment.end(), segment.endAngularMomentumWorld());
    }

    /**
     * Appends one kinematic geometric orientation correction.
     *
     * <p>Only a kinematic-only trajectory can be extended this way: a dynamic
     * body attitude is not rewritten by a view constraint, and the caller must
     * perform an explicit ownership handoff instead.</p>
     */
    public AngularTrajectory appendKinematic(
            Quatd target,
            double epsilon
    ) {
        Objects.requireNonNull(target, "target");
        if (initialAngularMomentumWorld != null) {
            throw new IllegalStateException(
                    "cannot append a kinematic rewrite to a dynamic trajectory");
        }
        Quatd normalized = target.normalized();
        Quatd last = sample(segments.size());
        if (rotationallyEquivalent(last, normalized, epsilon)) {
            return this;
        }
        List<AngularSegment> next = new ArrayList<>(segments.size() + 1);
        for (int index = 0; index < segments.size(); index++) {
            next.add(segment(index));
        }
        next.add(new AngularSegment(
                last,
                normalized,
                Kind.KINEMATIC_GEOMETRIC,
                0.0D,
                null
        ));
        return new AngularTrajectory(null, next);
    }

    /** Defensive copy of this immutable value. */
    public AngularTrajectory copy() {
        return new AngularTrajectory(
                initialAngularMomentumWorld,
                segments
        );
    }

    /** Single integrated dynamic segment. */
    public static AngularTrajectory dynamic(
            Quatd start,
            Quatd end,
            double durationSeconds,
            Vec3d initialAngularMomentumWorld,
            Vec3d endAngularMomentumWorld
    ) {
        return new AngularTrajectory(
                initialAngularMomentumWorld,
                List.of(new AngularSegment(
                        start,
                        end,
                        Kind.DYNAMIC,
                        durationSeconds,
                        endAngularMomentumWorld
                ))
        );
    }

    /** Single momentum-free geometric correction. */
    public static AngularTrajectory kinematic(
            Quatd start,
            Quatd end
    ) {
        return new AngularTrajectory(
                null,
                List.of(new AngularSegment(
                        start,
                        end,
                        Kind.KINEMATIC_GEOMETRIC,
                        0.0D,
                        null
                ))
        );
    }

    /**
     * Zero-length lifecycle install evidence: no integration happened, so the
     * trajectory claims no angular momentum. Durable momentum is carried by the
     * installed {@link BodyAttitudeState}, never by its trajectory.
     */
    public static AngularTrajectory install(Quatd orientation) {
        return kinematic(orientation, orientation);
    }

    private static boolean sameRotation(
            Quatd first,
            Quatd second
    ) {
        return rotationallyEquivalent(first, second, 1.0E-12D);
    }

    private static boolean rotationallyEquivalent(
            Quatd first,
            Quatd second,
            double epsilon
    ) {
        double dot = Math.abs(first.dot(second));
        return 1.0D - Math.min(1.0D, dot) <= epsilon;
    }

    private static Quatd normalizedCopy(
            Quatd value,
            String name
    ) {
        Objects.requireNonNull(value, name);
        if (!Double.isFinite(value.x())
                || !Double.isFinite(value.y())
                || !Double.isFinite(value.z())
                || !Double.isFinite(value.w())) {
            throw new IllegalArgumentException(name + " must be finite");
        }
        double lengthSquared = value.lengthSquared();
        if (!Double.isFinite(lengthSquared)
                || lengthSquared <= 1.0E-24D) {
            throw new IllegalArgumentException(
                    name + " must be non-degenerate");
        }
        return value.normalized();
    }

    private static Vec3d copy(Vec3d value) {
        return new Vec3d(value.x(), value.y(), value.z());
    }

    private static void requireFinite(Vec3d value, String name) {
        Objects.requireNonNull(value, name);
        if (!Double.isFinite(value.x())
                || !Double.isFinite(value.y())
                || !Double.isFinite(value.z())
                || !Double.isFinite(value.lengthSquared())) {
            throw new IllegalArgumentException(
                    name + " must be finite: " + value);
        }
    }
}
