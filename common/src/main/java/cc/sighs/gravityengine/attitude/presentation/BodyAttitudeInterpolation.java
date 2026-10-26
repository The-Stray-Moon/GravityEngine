package cc.sighs.gravityengine.attitude.presentation;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.math.Quatd;
import java.util.Objects;

/** Pure shortest-arc interpolation used by every body-attitude presentation path. */
public final class BodyAttitudeInterpolation {
    private static final double MIN_LENGTH_SQUARED = 1.0E-24D;

    private BodyAttitudeInterpolation() {}

    public static Quatd shortestArc(
            Quatd previous, Quatd current, double partialTick
    ) {
        Quatd a = normalizedOrIdentity(previous);
        Quatd b = normalizedOrFallback(current, a);
        double t = Double.isFinite(partialTick)
                ? Math.max(0.0D, Math.min(1.0D, partialTick)) : 0.0D;
        if (t == 0.0D) return a;
        if (t == 1.0D) return equivalentHemisphere(a, b);
        b = equivalentHemisphere(a, b);
        return normalizedOrFallback(a.slerp(b, t), a);
    }

    public static double angularDistance(Quatd a, Quatd b) {
        return Quatd.angularDistance(a, b);
    }

    /** Deterministic unit-sphere interpolation for semantic world aim. */
    public static Vec3d direction(
            Vec3d previous,
            Vec3d current,
            double progress
    ) {
        Vec3d a = unitOrFallback(
                previous,
                Vec3d.Z
        );
        Vec3d b = unitOrFallback(current, a);
        double t = Double.isFinite(progress)
                ? Math.max(0.0D, Math.min(1.0D, progress)) : 0.0D;
        if (t == 0.0D) return a;
        if (t == 1.0D) return b;
        double dot = Math.max(-1.0D, Math.min(1.0D, a.dot(b)));
        if (dot > 1.0D - 1.0E-8D) {
            return unitOrFallback(
                    a.multiply(1.0D - t)
                            .add(b.multiply(t)),
                    a
            );
        }
        if (dot < -1.0D + 1.0E-8D) {
            Vec3d seed = Math.abs(a.x()) <= Math.abs(a.y())
                    && Math.abs(a.x()) <= Math.abs(a.z())
                    ? Vec3d.X
                    : Math.abs(a.y()) <= Math.abs(a.z())
                    ? Vec3d.Y
                    : Vec3d.Z;
            Vec3d tangent = unitOrFallback(
                    seed.subtract(
                            a.multiply(seed.dot(a))
                    ),
                    Vec3d.X
            );
            double angle = Math.PI * t;
            return unitOrFallback(
                    a.multiply(Math.cos(angle))
                            .add(
                                    tangent.multiply(
                                            Math.sin(angle)
                                    )
                            ),
                    a
            );
        }
        double angle = Math.acos(dot);
        double denominator = Math.sin(angle);
        return unitOrFallback(
                a.multiply(
                        Math.sin((1.0D - t) * angle)
                                / denominator
                ).add(
                        b.multiply(
                                Math.sin(t * angle)
                                        / denominator
                        )
                ),
                a
        );
    }

    private static Quatd equivalentHemisphere(
            Quatd reference,
            Quatd value
    ) {
        return reference.dot(value) < 0.0D
                ? new Quatd(
                        -value.x(),
                        -value.y(),
                        -value.z(),
                        -value.w()
                )
                : new Quatd(value);
    }

    private static Quatd normalizedOrIdentity(Quatd value) {
        return normalizedOrFallback(value, Quatd.IDENTITY);
    }

    private static Quatd normalizedOrFallback(
            Quatd value,
            Quatd fallback
    ) {
        if (value == null
                || !value.isFinite()
                || !Double.isFinite(value.lengthSquared())
                || value.lengthSquared() <= MIN_LENGTH_SQUARED) {
            return Objects.requireNonNull(
                    fallback,
                    "fallback"
            ).normalized();
        }
        return new Quatd(value).normalized();
    }

    private static Vec3d unitOrFallback(
            Vec3d value,
            Vec3d fallback
    ) {
        if (value == null
                || !value.isFinite()
                || !Double.isFinite(value.lengthSquared())
                || value.lengthSquared() <= MIN_LENGTH_SQUARED) {
            return Objects.requireNonNull(
                    fallback,
                    "fallback"
            ).normalized();
        }
        return value.normalized();
    }
}
