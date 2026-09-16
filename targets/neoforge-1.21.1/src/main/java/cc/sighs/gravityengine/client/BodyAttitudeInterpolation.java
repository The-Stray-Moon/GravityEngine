package cc.sighs.gravityengine.client;

import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;

import java.util.Objects;

/** Pure shortest-arc interpolation used by every body-attitude presentation path. */
public final class BodyAttitudeInterpolation {
    private static final double MIN_LENGTH_SQUARED = 1.0E-24D;

    private BodyAttitudeInterpolation() {}

    public static Quaterniond shortestArc(
            Quaterniond previous, Quaterniond current, double partialTick
    ) {
        Quaterniond a = normalizedOrIdentity(previous);
        Quaterniond b = normalizedOrFallback(current, a);
        double t = Double.isFinite(partialTick)
                ? Math.max(0.0D, Math.min(1.0D, partialTick)) : 0.0D;
        if (t == 0.0D) return a;
        if (t == 1.0D) return equivalentHemisphere(a, b);
        b = equivalentHemisphere(a, b);
        Quaterniond result = a.slerp(b, t, new Quaterniond());
        return normalizedOrFallback(result, a);
    }

    public static double angularDistance(Quaterniond a, Quaterniond b) {
        Quaterniond qa = normalizedOrIdentity(a);
        Quaterniond qb = normalizedOrFallback(b, qa);
        double dot = Math.abs(qa.dot(qb));
        return 2.0D * Math.acos(Math.max(-1.0D, Math.min(1.0D, dot)));
    }

    /** Deterministic unit-sphere interpolation for semantic world aim. */
    public static Vec3 direction(Vec3 previous, Vec3 current, double progress) {
        Vec3 a = unitOrFallback(previous, new Vec3(0.0D, 0.0D, 1.0D));
        Vec3 b = unitOrFallback(current, a);
        double t = Double.isFinite(progress)
                ? Math.max(0.0D, Math.min(1.0D, progress)) : 0.0D;
        if (t == 0.0D) return a;
        if (t == 1.0D) return b;
        double dot = Math.max(-1.0D, Math.min(1.0D, a.dot(b)));
        if (dot > 1.0D - 1.0E-8D) {
            return unitOrFallback(a.scale(1.0D - t).add(b.scale(t)), a);
        }
        if (dot < -1.0D + 1.0E-8D) {
            Vec3 seed = Math.abs(a.x) <= Math.abs(a.y)
                    && Math.abs(a.x) <= Math.abs(a.z)
                    ? new Vec3(1.0D, 0.0D, 0.0D)
                    : Math.abs(a.y) <= Math.abs(a.z)
                    ? new Vec3(0.0D, 1.0D, 0.0D)
                    : new Vec3(0.0D, 0.0D, 1.0D);
            Vec3 tangent = unitOrFallback(
                    seed.subtract(a.scale(seed.dot(a))),
                    new Vec3(1.0D, 0.0D, 0.0D));
            double angle = Math.PI * t;
            return unitOrFallback(
                    a.scale(Math.cos(angle)).add(tangent.scale(Math.sin(angle))),
                    a);
        }
        double angle = Math.acos(dot);
        double denominator = Math.sin(angle);
        return unitOrFallback(
                a.scale(Math.sin((1.0D - t) * angle) / denominator)
                        .add(b.scale(Math.sin(t * angle) / denominator)),
                a);
    }

    private static Quaterniond equivalentHemisphere(Quaterniond reference, Quaterniond value) {
        Quaterniond result = new Quaterniond(value);
        if (reference.dot(result) < 0.0D) result.set(-result.x, -result.y, -result.z, -result.w);
        return result;
    }

    private static Quaterniond normalizedOrIdentity(Quaterniond value) {
        return normalizedOrFallback(value, new Quaterniond());
    }

    private static Quaterniond normalizedOrFallback(Quaterniond value, Quaterniond fallback) {
        if (value == null || !finite(value.x) || !finite(value.y)
                || !finite(value.z) || !finite(value.w)
                || !finite(value.lengthSquared())
                || value.lengthSquared() <= MIN_LENGTH_SQUARED) {
            return new Quaterniond(Objects.requireNonNull(fallback, "fallback")).normalize();
        }
        return new Quaterniond(value).normalize();
    }

    private static Vec3 unitOrFallback(Vec3 value, Vec3 fallback) {
        if (value == null || !finite(value.x) || !finite(value.y)
                || !finite(value.z) || !finite(value.lengthSqr())
                || value.lengthSqr() <= MIN_LENGTH_SQUARED) {
            return Objects.requireNonNull(fallback, "fallback").normalize();
        }
        return value.normalize();
    }

    private static boolean finite(double value) { return Double.isFinite(value); }
}
