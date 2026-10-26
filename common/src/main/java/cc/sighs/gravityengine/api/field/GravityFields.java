package cc.sighs.gravityengine.api.field;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.field.*;

import java.util.Objects;

/**
 * Supported factories for the built-in loader-neutral field forms.
 *
 * <p>Every factory returns the existing engine implementation through the
 * {@link GravityField}/{@link GravityInfluenceVolume} SPI. No physics is
 * reimplemented here: the concrete evaluators remain the single authority for
 * their algorithms. Consumers never need to name the implementation classes,
 * so those classes stay free to change.</p>
 *
 * <p>These factories return stateless or immutable evaluators. An evaluator
 * may be shared between publications; the registration, not the evaluator,
 * owns influence, composition mode and revision.</p>
 */
public final class GravityFields {
    private GravityFields() {}

    /**
     * Zero acceleration, independent of the query.
     *
     * <p>Publish this with {@link GravityFieldCompositionMode#OVERRIDE} to
     * suppress ordinary gravity: presence belongs to the registered
     * instance, so the resultant is zero while the field remains an active
     * contribution.</p>
     */
    public static GravityField zeroGravity() {
        return ZeroGravityField.INSTANCE;
    }

    /**
     * Immutable spherical enclosed-mass field for one physical core.
     *
     * <p>Persistent physical authority stays with the owning source
     * ({@code mass}, {@code referenceDensity}, {@code surfaceRadius}); this
     * evaluator captures those values. Outside the surface acceleration
     * follows {@code G*M/r^2}; inside it follows the enclosed-mass profile of
     * the existing engine implementation.</p>
     *
     * <p>{@code gravityConstant} is explicit and producer-owned. GravityEngine
     * deliberately does not inject a canonical gravitational constant: pick
     * the value that matches your content scale. Use
     * {@link #sphericalCutoffRadius} to derive a matching finite influence
     * volume if you do not want {@link #infiniteInfluence()}.</p>
     *
     * @param center            world-space center of the mass, in blocks
     * @param mass              signed enclosed mass, in the producer's own unit
     * @param referenceDensity  positive density shape parameter; it is not
     *                          necessarily the sphere's mean density. Use
     *                          {@link #uniformDensity(double, double)} for a
     *                          uniform interior profile.
     * @param surfaceRadius     positive physical surface radius, in blocks
     * @param gravityConstant   non-negative producer-chosen gravitational constant
     */
    public static GravityField sphericalMass(
            Vec3d center,
            double mass,
            double referenceDensity,
            double surfaceRadius,
            double gravityConstant
    ) {
        return new SphericalMassGravityField(
                center,
                mass,
                referenceDensity,
                surfaceRadius,
                gravityConstant
        );
    }

    /**
     * Absolute world-space field aligned with world {@code -Y}.
     *
     * <p>Samples at or below {@code fullGravityY} return
     * {@code (0, -accelerationMagnitude, 0)}; samples in the open interval
     * between {@code fullGravityY} and {@code zeroGravityY} fade with a cubic
     * smoothstep; samples at or above {@code zeroGravityY} return zero while
     * remaining an active contribution.</p>
     *
     * <p>This is the evaluator used by the canonical Overworld base field and
     * is generally reusable for layered atmospheres.</p>
     */
    public static GravityField height(
            double fullGravityY,
            double zeroGravityY,
            double accelerationMagnitude
    ) {
        return new HeightGravityField(
                fullGravityY,
                zeroGravityY,
                accelerationMagnitude
        );
    }

    /**
     * Finite spherical influence volume with closed-surface containment.
     */
    public static GravityInfluenceVolume sphereInfluence(
            Vec3d center,
            double radius
    ) {
        Objects.requireNonNull(center, "center");
        return new SphereInfluenceVolume(center, radius);
    }

    /**
     * Infinite (uniform/global) influence volume.
     *
     * <p>The field participates at every position. Use it when the evaluator
     * itself decides where acceleration is meaningful, as the canonical
     * Overworld base field does.</p>
     */
    public static GravityInfluenceVolume infiniteInfluence() {
        return InfiniteInfluenceVolume.INSTANCE;
    }

    /**
     * Radius of the uniform-density sphere holding {@code absoluteMass} at
     * {@code referenceDensity}. Producer-side spherical geometry only.
     */
    public static double uniformDensityRadius(
            double absoluteMass,
            double referenceDensity
    ) {
        return SphericalGravityMath.uniformDensityRadius(
                absoluteMass,
                referenceDensity
        );
    }

    /**
     * Uniform density of a sphere holding {@code absoluteMass} at
     * {@code radius}. Producer-side spherical geometry only.
     */
    public static double uniformDensity(
            double absoluteMass,
            double radius
    ) {
        return SphericalGravityMath.uniformDensity(
                absoluteMass,
                radius
        );
    }

    /**
     * {@code G*M/r^2} at the physical surface. Producer-side derivation only;
     * this never samples the world.
     */
    public static double surfaceAccelerationMagnitude(
            double gravityConstant,
            double mass,
            double surfaceRadius
    ) {
        return SphericalGravityMath.surfaceAccelerationMagnitude(
                gravityConstant,
                mass,
                surfaceRadius
        );
    }

    /**
     * Exterior radius at which the spherical field drops to
     * {@code minimumFieldAcceleration}, never smaller than the physical
     * surface. Use it to size a {@link #sphereInfluence} volume that matches
     * the publisher's own field scale, including the same
     * {@code gravityConstant} that was passed to
     * {@link #sphericalMass}.
     */
    public static double sphericalCutoffRadius(
            double gravityConstant,
            double mass,
            double surfaceRadius,
            double minimumFieldAcceleration
    ) {
        return SphericalGravityMath.cutoffRadius(
                gravityConstant,
                mass,
                surfaceRadius,
                minimumFieldAcceleration
        );
    }
}
