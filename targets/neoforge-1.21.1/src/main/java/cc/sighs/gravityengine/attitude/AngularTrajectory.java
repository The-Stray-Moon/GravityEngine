package cc.sighs.gravityengine.attitude;

import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Immutable actor-controller angular samples. Dynamic segments integrate angular velocity;
 * kinematic segments preserve angular velocity while changing the view/body joint.
 * These samples are independent from character collision and position. */
public record AngularTrajectory(
        Vec3 initialAngularVelocityWorld,
        List<AngularSegment> segments
) {
    public enum Kind {
        DYNAMIC,
        KINEMATIC_GEOMETRIC
    }

    /** One angular segment with its actual integration duration and endpoint angular velocity. */
    public record AngularSegment(
            Quaterniond start,
            Quaterniond end,
            Kind kind,
            double durationSeconds,
            Vec3 endAngularVelocityWorld
    ) {
        public AngularSegment {
            Objects.requireNonNull(start, "start");
            Objects.requireNonNull(end, "end");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(endAngularVelocityWorld,
                    "endAngularVelocityWorld");
            start = normalizedCopy(start, "start");
            end = normalizedCopy(end, "end");
            if (!Double.isFinite(durationSeconds)
                    || durationSeconds < 0.0D) {
                throw new IllegalArgumentException(
                        "durationSeconds must be finite and non-negative");
            }
            if (kind == Kind.KINEMATIC_GEOMETRIC
                    && durationSeconds != 0.0D) {
                throw new IllegalArgumentException(
                        "kinematic geometric segments cannot carry an "
                                + "artificial integration duration");
            }
            requireFinite(endAngularVelocityWorld,
                    "endAngularVelocityWorld");
        }

        @Override
        public Quaterniond start() {
            return new Quaterniond(start);
        }

        @Override
        public Quaterniond end() {
            return new Quaterniond(end);
        }

        @Override
        public Vec3 endAngularVelocityWorld() {
            return copy(endAngularVelocityWorld);
        }
    }

    public AngularTrajectory {
        Objects.requireNonNull(initialAngularVelocityWorld,
                "initialAngularVelocityWorld");
        requireFinite(initialAngularVelocityWorld,
                "initialAngularVelocityWorld");
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
                    required.endAngularVelocityWorld()
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
        /*
         * Producer-owned segment metadata must stay internally consistent.
         * A KINEMATIC_GEOMETRIC segment is a zero-momentum orientation
         * transition, so its endpoint omega is exactly the omega entering the
         * segment. No collision response or Player translation is derived from this value.
         */
        Vec3 incomingOmega = initialAngularVelocityWorld;
        for (AngularSegment segment : copied) {
            if (segment.kind() == Kind.KINEMATIC_GEOMETRIC
                    && !segment.endAngularVelocityWorld()
                    .equals(incomingOmega)) {
                throw new IllegalArgumentException(
                        "KINEMATIC_GEOMETRIC segments must preserve the "
                                + "incoming angular velocity");
            }
            incomingOmega = segment.endAngularVelocityWorld();
        }
        initialAngularVelocityWorld = copy(initialAngularVelocityWorld);
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
                        segment.endAngularVelocityWorld()
                ))
                .toList();
    }

    public AngularSegment segment(int index) {
        AngularSegment segment = segments.get(index);
        return new AngularSegment(
                segment.start(),
                segment.end(),
                segment.kind(),
                segment.durationSeconds(),
                segment.endAngularVelocityWorld()
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

    public Vec3 omegaEnteringSegment(int index) {
        if (index < 0 || index >= segments.size()) {
            throw new IndexOutOfBoundsException(
                    "segment index out of range: " + index);
        }
        return index == 0
                ? copy(initialAngularVelocityWorld)
                : segments.get(index - 1).endAngularVelocityWorld();
    }

    public Vec3 finalAngularVelocityWorld() {
        return segments.get(segments.size() - 1).endAngularVelocityWorld();
    }

    public Quaterniond sample(int index) {
        if (index < 0 || index > segments.size()) {
            throw new IndexOutOfBoundsException(
                    "sample index out of range: " + index);
        }
        return index == 0
                ? segments.get(0).start()
                : segments.get(index - 1).end();
    }

    public List<Quaterniond> samples() {
        List<Quaterniond> samples = new ArrayList<>(segments.size() + 1);
        samples.add(segments.get(0).start());
        for (AngularSegment segment : segments) {
            samples.add(segment.end());
        }
        return List.copyOf(samples);
    }

    /**
     * Appends one zero-momentum geometric orientation correction. The incoming
     * omega is preserved exactly; no duration or angular velocity is
     * fabricated from the quaternion displacement.
     */
    public AngularTrajectory appendKinematic(
            Quaterniond target,
            double epsilon
    ) {
        Objects.requireNonNull(target, "target");
        Quaterniond normalized = new Quaterniond(target).normalize();
        Quaterniond last = sample(segments.size());
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
                finalAngularVelocityWorld()
        ));
        return new AngularTrajectory(
                initialAngularVelocityWorld,
                next
        );
    }

    /**
     * Prepends one zero-momentum geometric entry correction. Used only when an
     * bootstrap actor attitude differs from the displayed bootstrap
     * orientation; the existing incoming omega is preserved exactly.
     */
    public AngularTrajectory prependKinematic(
            Quaterniond start,
            double epsilon
    ) {
        Objects.requireNonNull(start, "start");
        Quaterniond normalized = new Quaterniond(start).normalize();
        Quaterniond first = sample(0);
        if (rotationallyEquivalent(normalized, first, epsilon)) {
            return this;
        }
        List<AngularSegment> next = new ArrayList<>(segments.size() + 1);
        next.add(new AngularSegment(
                normalized,
                first,
                Kind.KINEMATIC_GEOMETRIC,
                0.0D,
                initialAngularVelocityWorld
        ));
        for (int index = 0; index < segments.size(); index++) {
            next.add(segment(index));
        }
        return new AngularTrajectory(
                initialAngularVelocityWorld,
                next
        );
    }

    /** Defensive copy of this immutable value. */
    public AngularTrajectory copy() {
        return new AngularTrajectory(
                initialAngularVelocityWorld,
                segments
        );
    }

    /** Single integrated dynamic segment. */
    public static AngularTrajectory dynamic(
            Quaterniond start,
            Quaterniond end,
            double durationSeconds,
            Vec3 initialAngularVelocityWorld,
            Vec3 endAngularVelocityWorld
    ) {
        return new AngularTrajectory(
                initialAngularVelocityWorld,
                List.of(new AngularSegment(
                        start,
                        end,
                        Kind.DYNAMIC,
                        durationSeconds,
                        endAngularVelocityWorld
                ))
        );
    }

    /** Single zero-momentum geometric correction. */
    public static AngularTrajectory kinematic(
            Quaterniond start,
            Quaterniond end,
            Vec3 incomingAngularVelocityWorld
    ) {
        return new AngularTrajectory(
                incomingAngularVelocityWorld,
                List.of(new AngularSegment(
                        start,
                        end,
                        Kind.KINEMATIC_GEOMETRIC,
                        0.0D,
                        incomingAngularVelocityWorld
                ))
        );
    }

    /** One zero-length stationary segment with an explicit angular velocity. */
    public static AngularTrajectory stationary(
            Quaterniond orientation,
            Vec3 angularVelocityWorld
    ) {
        return kinematic(
                orientation,
                orientation,
                angularVelocityWorld
        );
    }

    private static boolean sameRotation(
            Quaterniond first,
            Quaterniond second
    ) {
        return rotationallyEquivalent(first, second, 1.0E-12D);
    }

    private static boolean rotationallyEquivalent(
            Quaterniond first,
            Quaterniond second,
            double epsilon
    ) {
        double dot = Math.abs(first.dot(second));
        return 1.0D - Math.min(1.0D, dot) <= epsilon;
    }

    private static Quaterniond normalizedCopy(
            Quaterniond value,
            String name
    ) {
        Objects.requireNonNull(value, name);
        if (!Double.isFinite(value.x)
                || !Double.isFinite(value.y)
                || !Double.isFinite(value.z)
                || !Double.isFinite(value.w)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
        double lengthSquared = value.lengthSquared();
        if (!Double.isFinite(lengthSquared)
                || lengthSquared <= 1.0E-24D) {
            throw new IllegalArgumentException(
                    name + " must be non-degenerate");
        }
        return new Quaterniond(value).normalize();
    }

    private static Vec3 copy(Vec3 value) {
        return new Vec3(value.x, value.y, value.z);
    }

    private static void requireFinite(Vec3 value, String name) {
        Objects.requireNonNull(value, name);
        if (!Double.isFinite(value.x)
                || !Double.isFinite(value.y)
                || !Double.isFinite(value.z)
                || !Double.isFinite(value.lengthSqr())) {
            throw new IllegalArgumentException(
                    name + " must be finite: " + value);
        }
    }
}
