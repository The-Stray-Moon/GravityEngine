package cc.sighs.gravityengine.attitude;

/**
 * Synchronized attitude-control, eligibility and dedicated Elytra dynamics
 * settings.
 *
 * <p><b>Dimension migration.</b> The previous snapshot mixed an angular
 * acceleration source, several rate caps and a gravity-restoring gain. It now
 * exposes real physical quantities so the generic solver never has to guess a
 * unit:</p>
 *
 * <table>
 *   <caption>Units</caption>
 *   <tr><td>{@code controllerRollRate}</td><td>degrees/s input, radians/s exposed
 *       (SemanticView / FREE_ATTITUDE geometric roll only)</td></tr>
 *   <tr><td>{@code elytraEffectiveAngularInertia}</td><td>rotational-inertia game units</td></tr>
 *   <tr><td>{@code elytraRollTorque}</td><td>torque game units (inertia-unit * rad/s^2)</td></tr>
 *   <tr><td>{@code elytraHeadingTorqueGain}</td><td>torque game units per radian of heading error</td></tr>
 *   <tr><td>{@code elytraHeadingDamping}</td><td>torque game units per rad/s of heading-only angular velocity</td></tr>
 *   <tr><td>{@code elytraAngularDamping}</td><td>1/s game angular damping coefficient</td></tr>
 * </table>
 *
 * <p>Initial tuning was migrated from the previous
 * {@code elytraRollAngularAccelerationDegPerSec2 = 360} and
 * {@code elytraFlightAlignmentGainPerSecondSquared = 6}. With the default
 * {@code I = 1 inertia-unit}, {@code tau = I * alpha} gives
 * {@code 360 deg/s^2 = 6.283185307179586 rad/s^2 -> tau_roll = 6.283185307179586}
 * and heading gain {@code 6.0}. The old whole-vector
 * {@code elytraAngularDragPerSecond = 2} becomes the generic angular damping
 * coefficient {@code tau = -k * L_world}.</p>
 *
 * <p>The removed {@code elytraGravityAlignmentGain}, {@code elytraMaxGravityScale}
 * and all Elytra rate caps are deliberately absent: gravity no longer restores
 * the body attitude, and terminal angular speed is produced only by finite
 * torque, damping or an explicit rate-limit controller.</p>
 */
public final class BodyAttitudeConfigSnapshot {
    /** Default GE dynamics profile; every dynamics value migrates by {@code tau = I * alpha}. */
    public static final BodyAttitudeConfigSnapshot DEFAULT =
            new BodyAttitudeConfigSnapshot(
                    0.35D,
                    180.0D,
                    1.0D,
                    6.283185307179586D,
                    6.0D,
                    0.0D,
                    2.0D,
                    0.35D,
                    0.1D,
                    1.0D,
                    1.0E-8D,
                    1.0E-10D,
                    0.05D,
                    8
            );

    private final double lowGravitySwimThresholdRatio;
    private final double controllerRollRateRadiansPerSecond;
    private final double elytraEffectiveAngularInertia;
    private final double elytraRollTorque;
    private final double elytraHeadingTorqueGain;
    private final double elytraHeadingDamping;
    private final double elytraAngularDamping;
    private final double elytraVelocityAlignment;
    private final double elytraVelocityAlignStartSpeed;
    private final double elytraVelocityAlignFullSpeed;
    private final double quaternionEpsilon;
    private final double vectorEpsilon;
    private final double maxSubstepSeconds;
    private final int maxSubsteps;

    public BodyAttitudeConfigSnapshot(
            double lowGravitySwimThresholdRatio,
            double controllerRollRateDegreesPerSecond,
            double elytraEffectiveAngularInertia,
            double elytraRollTorque,
            double elytraHeadingTorqueGain,
            double elytraHeadingDamping,
            double elytraAngularDamping,
            double elytraVelocityAlignment,
            double elytraVelocityAlignStartSpeed,
            double elytraVelocityAlignFullSpeed,
            double quaternionEpsilon,
            double vectorEpsilon,
            double maxSubstepSeconds,
            int maxSubsteps
    ) {
        if (!Double.isFinite(lowGravitySwimThresholdRatio)
                || lowGravitySwimThresholdRatio <= 0.0D) {
            throw new IllegalArgumentException(
                    "lowGravitySwimThresholdRatio must be finite and positive");
        }
        if (!Double.isFinite(controllerRollRateDegreesPerSecond)
                || controllerRollRateDegreesPerSecond <= 0.0D) {
            throw new IllegalArgumentException(
                    "controllerRollRate must be finite and positive");
        }
        if (!Double.isFinite(elytraEffectiveAngularInertia)
                || elytraEffectiveAngularInertia <= 0.0D) {
            throw new IllegalArgumentException(
                    "elytraEffectiveAngularInertia must be finite and "
                            + "strictly positive");
        }
        if (!Double.isFinite(elytraRollTorque) || elytraRollTorque < 0.0D) {
            throw new IllegalArgumentException(
                    "elytraRollTorque must be finite and non-negative");
        }
        if (!Double.isFinite(elytraHeadingTorqueGain)
                || elytraHeadingTorqueGain < 0.0D) {
            throw new IllegalArgumentException(
                    "elytraHeadingTorqueGain must be finite and non-negative");
        }
        if (!Double.isFinite(elytraHeadingDamping)
                || elytraHeadingDamping < 0.0D) {
            throw new IllegalArgumentException(
                    "elytraHeadingDamping must be finite and non-negative");
        }
        if (!Double.isFinite(elytraAngularDamping)
                || elytraAngularDamping < 0.0D) {
            throw new IllegalArgumentException(
                    "elytraAngularDamping must be finite and non-negative; "
                            + "zero is the required conservation configuration");
        }
        if (!Double.isFinite(elytraVelocityAlignment)
                || elytraVelocityAlignment < 0.0D
                || elytraVelocityAlignment > 1.0D) {
            throw new IllegalArgumentException(
                    "elytraVelocityAlignment must be in [0, 1], got "
                            + elytraVelocityAlignment);
        }
        if (!Double.isFinite(elytraVelocityAlignStartSpeed)
                || elytraVelocityAlignStartSpeed < 0.0D) {
            throw new IllegalArgumentException(
                    "elytraVelocityAlignStartSpeed must be nonnegative, got "
                            + elytraVelocityAlignStartSpeed);
        }
        if (!Double.isFinite(elytraVelocityAlignFullSpeed)
                || elytraVelocityAlignFullSpeed <= 0.0D) {
            throw new IllegalArgumentException(
                    "elytraVelocityAlignFullSpeed must be finite and positive");
        }
        if (elytraVelocityAlignStartSpeed >= elytraVelocityAlignFullSpeed) {
            throw new IllegalArgumentException(
                    "elytraVelocityAlignStartSpeed must be strictly less than "
                            + "elytraVelocityAlignFullSpeed; start="
                            + elytraVelocityAlignStartSpeed
                            + ", full="
                            + elytraVelocityAlignFullSpeed);
        }
        if (!Double.isFinite(quaternionEpsilon) || quaternionEpsilon <= 0.0D) {
            throw new IllegalArgumentException(
                    "quaternionEpsilon must be finite and positive");
        }
        if (!Double.isFinite(vectorEpsilon) || vectorEpsilon <= 0.0D) {
            throw new IllegalArgumentException(
                    "vectorEpsilon must be finite and positive");
        }
        if (!Double.isFinite(maxSubstepSeconds)
                || maxSubstepSeconds <= 0.0D) {
            throw new IllegalArgumentException(
                    "maxSubstepSeconds must be finite and positive");
        }
        if (maxSubsteps < 1) {
            throw new IllegalArgumentException("invalid substep count");
        }

        this.lowGravitySwimThresholdRatio = lowGravitySwimThresholdRatio;
        this.controllerRollRateRadiansPerSecond =
                Math.toRadians(controllerRollRateDegreesPerSecond);
        this.elytraEffectiveAngularInertia = elytraEffectiveAngularInertia;
        this.elytraRollTorque = elytraRollTorque;
        this.elytraHeadingTorqueGain = elytraHeadingTorqueGain;
        this.elytraHeadingDamping = elytraHeadingDamping;
        this.elytraAngularDamping = elytraAngularDamping;
        this.elytraVelocityAlignment = elytraVelocityAlignment;
        this.elytraVelocityAlignStartSpeed = elytraVelocityAlignStartSpeed;
        this.elytraVelocityAlignFullSpeed = elytraVelocityAlignFullSpeed;
        this.quaternionEpsilon = quaternionEpsilon;
        this.vectorEpsilon = vectorEpsilon;
        this.maxSubstepSeconds = maxSubstepSeconds;
        this.maxSubsteps = maxSubsteps;
    }

    public double lowGravitySwimThresholdRatio() {
        return lowGravitySwimThresholdRatio;
    }

    /** SemanticView / FREE_ATTITUDE geometric controller roll rate in rad/s. */
    public double controllerRollRateRadiansPerSecond() {
        return controllerRollRateRadiansPerSecond;
    }

    public EffectiveAngularInertia elytraEffectiveAngularInertia() {
        return EffectiveAngularInertia.of(elytraEffectiveAngularInertia);
    }

    /** Raw inertia value in rotational-inertia game units. */
    public double elytraEffectiveAngularInertiaValue() {
        return elytraEffectiveAngularInertia;
    }

    /** Sustained roll torque about the flight forward axis, torque game units. */
    public double elytraRollTorque() {
        return elytraRollTorque;
    }

    /** Heading proportional torque per radian of forward-direction error. */
    public double elytraHeadingTorqueGain() {
        return elytraHeadingTorqueGain;
    }

    /** Heading-only derivative damping per rad/s of heading angular velocity. */
    public double elytraHeadingDamping() {
        return elytraHeadingDamping;
    }

    /** Generic game angular damping in 1/s; zero disables it exactly. */
    public double elytraAngularDamping() {
        return elytraAngularDamping;
    }

    public double elytraVelocityAlignment() {
        return elytraVelocityAlignment;
    }

    public double elytraVelocityAlignStartSpeed() {
        return elytraVelocityAlignStartSpeed;
    }

    public double elytraVelocityAlignFullSpeed() {
        return elytraVelocityAlignFullSpeed;
    }

    public double quaternionEpsilon() {
        return quaternionEpsilon;
    }

    public double vectorEpsilon() {
        return vectorEpsilon;
    }

    public double maxSubstepSeconds() {
        return maxSubstepSeconds;
    }

    public int maxSubsteps() {
        return maxSubsteps;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof BodyAttitudeConfigSnapshot c)) {
            return false;
        }
        return maxSubsteps == c.maxSubsteps
                && Double.compare(lowGravitySwimThresholdRatio,
                        c.lowGravitySwimThresholdRatio) == 0
                && Double.compare(controllerRollRateRadiansPerSecond,
                        c.controllerRollRateRadiansPerSecond) == 0
                && Double.compare(elytraEffectiveAngularInertia,
                        c.elytraEffectiveAngularInertia) == 0
                && Double.compare(elytraRollTorque, c.elytraRollTorque) == 0
                && Double.compare(elytraHeadingTorqueGain,
                        c.elytraHeadingTorqueGain) == 0
                && Double.compare(elytraHeadingDamping,
                        c.elytraHeadingDamping) == 0
                && Double.compare(elytraAngularDamping,
                        c.elytraAngularDamping) == 0
                && Double.compare(elytraVelocityAlignment,
                        c.elytraVelocityAlignment) == 0
                && Double.compare(elytraVelocityAlignStartSpeed,
                        c.elytraVelocityAlignStartSpeed) == 0
                && Double.compare(elytraVelocityAlignFullSpeed,
                        c.elytraVelocityAlignFullSpeed) == 0
                && Double.compare(quaternionEpsilon, c.quaternionEpsilon) == 0
                && Double.compare(vectorEpsilon, c.vectorEpsilon) == 0
                && Double.compare(maxSubstepSeconds, c.maxSubstepSeconds) == 0;
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(
                lowGravitySwimThresholdRatio,
                controllerRollRateRadiansPerSecond,
                elytraEffectiveAngularInertia,
                elytraRollTorque,
                elytraHeadingTorqueGain,
                elytraHeadingDamping,
                elytraAngularDamping,
                elytraVelocityAlignment,
                elytraVelocityAlignStartSpeed,
                elytraVelocityAlignFullSpeed,
                quaternionEpsilon,
                vectorEpsilon,
                maxSubstepSeconds,
                maxSubsteps
        );
    }
}
