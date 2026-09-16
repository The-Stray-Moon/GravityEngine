package cc.sighs.gravityengine.gravity.kinematic;

import net.minecraft.world.phys.Vec3;

import java.util.Objects;

/**
 * Immutable world-space evidence about newly introduced operation-local deltas.
 *
 * <p>This is not a decomposition of persistent actor velocity. The sum of
 * known deltas need not equal the geometric request or Entity.deltaMovement.
 * The passive contribution contains only explicitly identified acceleration or
 * geometric correction; an unexplained remainder is never inserted here.</p>
 *
 * <p>Minecraft displacement/velocity units remain blocks/tick.</p>
 */
public record OwnedMotion(
        Vec3 selfWalk,
        Vec3 externalPush,
        Vec3 passive,
        Vec3 supportMotion
) {
    private static final double AXIS_EPSILON_SQUARED =
            1.0E-24D;

    public static final OwnedMotion ZERO =
            new OwnedMotion(
                    Vec3.ZERO,
                    Vec3.ZERO,
                    Vec3.ZERO
            );

    public OwnedMotion {
        requireFinite(selfWalk);
        requireFinite(externalPush);
        requireFinite(passive);
        requireFinite(supportMotion);
    }

    public OwnedMotion(
            Vec3 selfWalk,
            Vec3 externalPush,
            Vec3 passive
    ) {
        this(
                selfWalk,
                externalPush,
                passive,
                Vec3.ZERO
        );
    }

    public Vec3 total() {
        return selfWalk
                .add(externalPush)
                .add(passive)
                .add(supportMotion);
    }

    public OwnedMotion multiply(Vec3 factors) {
        requireFinite(factors);

        return new OwnedMotion(
                selfWalk.multiply(factors),
                externalPush.multiply(factors),
                passive.multiply(factors),
                supportMotion.multiply(factors)
        );
    }

    public OwnedMotion scale(double factor) {
        if (!Double.isFinite(factor)) {
            throw new IllegalArgumentException(
                    "non-finite ownership scale: " + factor
            );
        }

        return new OwnedMotion(
                selfWalk.scale(factor),
                externalPush.scale(factor),
                passive.scale(factor),
                supportMotion.scale(factor)
        );
    }

    public OwnedMotion add(OwnedMotion other) {
        Objects.requireNonNull(other, "other");

        return new OwnedMotion(
                selfWalk.add(other.selfWalk),
                externalPush.add(other.externalPush),
                passive.add(other.passive),
                supportMotion.add(other.supportMotion)
        );
    }

    /** Owner-by-owner projection onto one world-space axis. */
    public OwnedMotion projectOnto(Vec3 axis) {
        Vec3 unit = normalizedAxis(axis);

        return new OwnedMotion(
                project(selfWalk, unit),
                project(externalPush, unit),
                project(passive, unit),
                project(supportMotion, unit)
        );
    }

    /** Owner-by-owner rejection from one world-space axis. */
    public OwnedMotion rejectFrom(Vec3 axis) {
        Vec3 unit = normalizedAxis(axis);

        return new OwnedMotion(
                reject(selfWalk, unit),
                reject(externalPush, unit),
                reject(passive, unit),
                reject(supportMotion, unit)
        );
    }

    private static Vec3 project(
            Vec3 value,
            Vec3 unit
    ) {
        return unit.scale(value.dot(unit));
    }

    private static Vec3 reject(
            Vec3 value,
            Vec3 unit
    ) {
        return value.subtract(project(value, unit));
    }

    private static Vec3 normalizedAxis(Vec3 axis) {
        requireFinite(axis);

        double lengthSquared = axis.lengthSqr();
        if (!(lengthSquared > AXIS_EPSILON_SQUARED)) {
            throw new IllegalArgumentException(
                    "ownership axis must be non-zero: " + axis
            );
        }

        return axis.scale(
                1.0D / Math.sqrt(lengthSquared)
        );
    }

    public static void requireFinite(Vec3 vector) {
        Objects.requireNonNull(vector, "motion");

        if (!Double.isFinite(vector.x)
                || !Double.isFinite(vector.y)
                || !Double.isFinite(vector.z)) {
            throw new IllegalArgumentException(
                    "non-finite motion: " + vector
            );
        }
    }
}
