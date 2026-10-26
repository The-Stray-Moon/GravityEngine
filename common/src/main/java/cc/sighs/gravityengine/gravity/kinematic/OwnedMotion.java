package cc.sighs.gravityengine.gravity.kinematic;

import cc.sighs.gravityengine.api.math.Vec3d;
import java.util.Objects;

/**
 * Immutable world-space evidence about newly introduced operation-local deltas.
 *
 * <p>This is not a decomposition of persistent actor velocity. The sum of
 * known deltas need not equal the geometric request or Entity.deltaMovement.
 * The passive contribution contains only explicitly identified acceleration or
 * geometric correction; an unexplained remainder is never inserted here.</p>
 *
 * <p>{@code supportMotion} is an operation-local displacement. It may carry
 * the exact pose-derived platform transport and/or explicitly inherited
 * release displacement; it is never a substitute for instantaneous contact
 * surface velocity.</p>
 *
 * <p>Minecraft displacement/velocity units remain blocks/tick.</p>
 */
public record OwnedMotion(
        Vec3d selfWalk,
        Vec3d externalPush,
        Vec3d passive,
        Vec3d supportMotion
) {
    private static final double AXIS_EPSILON_SQUARED =
            1.0E-24D;

    public static final OwnedMotion ZERO =
            new OwnedMotion(
                    Vec3d.ZERO,
                    Vec3d.ZERO,
                    Vec3d.ZERO
            );

    public OwnedMotion {
        requireFinite(selfWalk);
        requireFinite(externalPush);
        requireFinite(passive);
        requireFinite(supportMotion);
    }

    public OwnedMotion(
            Vec3d selfWalk,
            Vec3d externalPush,
            Vec3d passive
    ) {
        this(
                selfWalk,
                externalPush,
                passive,
                Vec3d.ZERO
        );
    }

    public Vec3d total() {
        return selfWalk
                .add(externalPush)
                .add(passive)
                .add(supportMotion);
    }

    public OwnedMotion multiply(Vec3d factors) {
        requireFinite(factors);

        return new OwnedMotion(
                selfWalk.multiply(factors.x(), factors.y(), factors.z()),
                externalPush.multiply(factors.x(), factors.y(), factors.z()),
                passive.multiply(factors.x(), factors.y(), factors.z()),
                supportMotion.multiply(
                        factors.x(),
                        factors.y(),
                        factors.z()
                )
        );
    }

    public OwnedMotion scale(double factor) {
        if (!Double.isFinite(factor)) {
            throw new IllegalArgumentException(
                    "non-finite ownership scale: " + factor
            );
        }

        return new OwnedMotion(
                selfWalk.multiply(factor),
                externalPush.multiply(factor),
                passive.multiply(factor),
                supportMotion.multiply(factor)
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
    public OwnedMotion projectOnto(Vec3d axis) {
        Vec3d unit = normalizedAxis(axis);

        return new OwnedMotion(
                project(selfWalk, unit),
                project(externalPush, unit),
                project(passive, unit),
                project(supportMotion, unit)
        );
    }

    /** Owner-by-owner rejection from one world-space axis. */
    public OwnedMotion rejectFrom(Vec3d axis) {
        Vec3d unit = normalizedAxis(axis);

        return new OwnedMotion(
                reject(selfWalk, unit),
                reject(externalPush, unit),
                reject(passive, unit),
                reject(supportMotion, unit)
        );
    }

    private static Vec3d project(
            Vec3d value,
            Vec3d unit
    ) {
        return unit.multiply(value.dot(unit));
    }

    private static Vec3d reject(
            Vec3d value,
            Vec3d unit
    ) {
        return value.subtract(project(value, unit));
    }

    private static Vec3d normalizedAxis(Vec3d axis) {
        requireFinite(axis);

        double lengthSquared = axis.lengthSquared();
        if (!(lengthSquared > AXIS_EPSILON_SQUARED)) {
            throw new IllegalArgumentException(
                    "ownership axis must be non-zero: " + axis
            );
        }

        return axis.multiply(
                1.0D / Math.sqrt(lengthSquared)
        );
    }

    public static void requireFinite(Vec3d vector) {
        Objects.requireNonNull(vector, "motion");

        if (!Double.isFinite(vector.x())
                || !Double.isFinite(vector.y())
                || !Double.isFinite(vector.z())) {
            throw new IllegalArgumentException(
                    "non-finite motion: " + vector
            );
        }
    }
}
