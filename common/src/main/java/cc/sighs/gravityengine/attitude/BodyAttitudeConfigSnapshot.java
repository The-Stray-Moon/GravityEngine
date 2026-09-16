package cc.sighs.gravityengine.attitude;

/** Synchronized controller roll speed, eligibility and dedicated Elytra alignment settings.
 * Rate/acceleration constructor values are degrees/s and degrees/s^2; exposed values are radians.
 * Direction gains are s^-2, drag s^-1, and gravity scale is dimensionless. */
public final class BodyAttitudeConfigSnapshot {
    public static final BodyAttitudeConfigSnapshot DEFAULT = new BodyAttitudeConfigSnapshot( 0.35, 120, 120, 180, 360, 8, 2, 4, 6, 720, 300, 0.35, 0.1, 1, 1e-08, 1e-10, 0.05, 8);
    private final double lowGravitySwimThresholdRatio;
    private final double elytraMaxPitchRateRadiansPerSecond;
    private final double elytraMaxYawRateRadiansPerSecond;
    private final double controllerRollRateRadiansPerSecond;
    private final double elytraRollAngularAccelerationRadiansPerSecondSquared;
    private final double elytraGravityAlignmentGainPerSecondSquared;
    private final double elytraAngularDragPerSecond;
    private final double elytraMaxGravityScale;
    private final double elytraFlightAlignmentGainPerSecondSquared;
    private final double elytraMaxAngularAccelerationRadiansPerSecondSquared;
    private final double elytraMaxAngularSpeedRadiansPerSecond;
    private final double elytraVelocityAlignment;
    private final double elytraVelocityAlignStartSpeed;
    private final double elytraVelocityAlignFullSpeed;
    private final double quaternionEpsilon;
    private final double vectorEpsilon;
    private final double maxSubstepSeconds;
    private final int maxSubsteps;
    public BodyAttitudeConfigSnapshot(
            double lowGravitySwimThresholdRatio,
            double elytraMaxPitchRate,
            double elytraMaxYawRate,
            double controllerRollRate,
            double elytraRollAngularAcceleration,
            double elytraGravityAlignmentGainPerSecondSquared,
            double elytraAngularDragPerSecond,
            double elytraMaxGravityScale,
            double elytraFlightAlignmentGainPerSecondSquared,
            double elytraMaxAngularAcceleration,
            double elytraMaxAngularSpeed,
            double elytraVelocityAlignment,
            double elytraVelocityAlignStartSpeed,
            double elytraVelocityAlignFullSpeed,
            double quaternionEpsilon,
            double vectorEpsilon,
            double maxSubstepSeconds,
            int maxSubsteps) {
        if (!Double.isFinite(lowGravitySwimThresholdRatio)) throw new IllegalArgumentException("lowGravitySwimThresholdRatio must be finite");
        if (!Double.isFinite(elytraMaxPitchRate)) throw new IllegalArgumentException("elytraMaxPitchRate must be finite");
        if (!Double.isFinite(elytraMaxYawRate)) throw new IllegalArgumentException("elytraMaxYawRate must be finite");
        if (!Double.isFinite(controllerRollRate)) throw new IllegalArgumentException("controllerRollRate must be finite");
        if (!Double.isFinite(elytraRollAngularAcceleration)) throw new IllegalArgumentException("elytraRollAngularAcceleration must be finite");
        if (!Double.isFinite(elytraGravityAlignmentGainPerSecondSquared)) throw new IllegalArgumentException("elytraGravityAlignmentGainPerSecondSquared must be finite");
        if (!Double.isFinite(elytraAngularDragPerSecond)) throw new IllegalArgumentException("elytraAngularDragPerSecond must be finite");
        if (!Double.isFinite(elytraMaxAngularAcceleration)) throw new IllegalArgumentException("elytraMaxAngularAcceleration must be finite");
        if (!Double.isFinite(elytraMaxAngularSpeed)) throw new IllegalArgumentException("elytraMaxAngularSpeed must be finite");
        if (!Double.isFinite(elytraVelocityAlignment)) throw new IllegalArgumentException("elytraVelocityAlignment must be finite");
        if (!Double.isFinite(elytraVelocityAlignStartSpeed)) throw new IllegalArgumentException("elytraVelocityAlignStartSpeed must be finite");
        if (!Double.isFinite(elytraVelocityAlignFullSpeed)) throw new IllegalArgumentException("elytraVelocityAlignFullSpeed must be finite");
        if (!Double.isFinite(quaternionEpsilon)) throw new IllegalArgumentException("quaternionEpsilon must be finite");
        if (!Double.isFinite(vectorEpsilon)) throw new IllegalArgumentException("vectorEpsilon must be finite");
        if (!Double.isFinite(maxSubstepSeconds)) throw new IllegalArgumentException("maxSubstepSeconds must be finite");
        if (elytraVelocityAlignment < 0.0D
                || elytraVelocityAlignment > 1.0D) {
            throw new IllegalArgumentException(
                    "elytraVelocityAlignment must be in [0, 1], got "
                            + elytraVelocityAlignment
            );
        }

        if (elytraVelocityAlignStartSpeed < 0.0D) {
            throw new IllegalArgumentException(
                    "elytraVelocityAlignStartSpeed must be nonnegative, got "
                            + elytraVelocityAlignStartSpeed
            );
        }

        if (elytraVelocityAlignStartSpeed >= elytraVelocityAlignFullSpeed) {
            throw new IllegalArgumentException(
                    "elytraVelocityAlignStartSpeed must be strictly less than "
                            + "elytraVelocityAlignFullSpeed; start="
                            + elytraVelocityAlignStartSpeed
                            + ", full="
                            + elytraVelocityAlignFullSpeed
            );
        }
        if (maxSubsteps < 1) throw new IllegalArgumentException("invalid substep count");
        if (lowGravitySwimThresholdRatio <= 0) throw new IllegalArgumentException("lowGravitySwimThresholdRatio must be positive");
        this.lowGravitySwimThresholdRatio = lowGravitySwimThresholdRatio;
        if (elytraMaxPitchRate <= 0) throw new IllegalArgumentException("elytraMaxPitchRate must be positive");
        this.elytraMaxPitchRateRadiansPerSecond = Math.toRadians(elytraMaxPitchRate);
        if (elytraMaxYawRate <= 0) throw new IllegalArgumentException("elytraMaxYawRate must be positive");
        this.elytraMaxYawRateRadiansPerSecond = Math.toRadians(elytraMaxYawRate);
        if (controllerRollRate <= 0) throw new IllegalArgumentException("controllerRollRate must be positive");
        this.controllerRollRateRadiansPerSecond = Math.toRadians(controllerRollRate);
        if (elytraRollAngularAcceleration <= 0) throw new IllegalArgumentException("elytraRollAngularAcceleration must be positive");
        this.elytraRollAngularAccelerationRadiansPerSecondSquared = Math.toRadians(elytraRollAngularAcceleration);
        if (elytraGravityAlignmentGainPerSecondSquared < 0) throw new IllegalArgumentException("elytraGravityAlignmentGainPerSecondSquared must be nonnegative");
        this.elytraGravityAlignmentGainPerSecondSquared = elytraGravityAlignmentGainPerSecondSquared;
        if (elytraAngularDragPerSecond < 0) throw new IllegalArgumentException("elytraAngularDragPerSecond must be nonnegative");
        this.elytraAngularDragPerSecond = elytraAngularDragPerSecond;
        if (!Double.isFinite(elytraMaxGravityScale) || elytraMaxGravityScale < 0) throw new IllegalArgumentException("elytraMaxGravityScale must be finite and nonnegative");
        this.elytraMaxGravityScale = elytraMaxGravityScale;
        if (!Double.isFinite(elytraFlightAlignmentGainPerSecondSquared) || elytraFlightAlignmentGainPerSecondSquared < 0) throw new IllegalArgumentException("elytraFlightAlignmentGainPerSecondSquared must be finite and nonnegative");
        this.elytraFlightAlignmentGainPerSecondSquared = elytraFlightAlignmentGainPerSecondSquared;
        if (elytraMaxAngularAcceleration <= 0) throw new IllegalArgumentException("elytraMaxAngularAcceleration must be positive");
        this.elytraMaxAngularAccelerationRadiansPerSecondSquared = Math.toRadians(elytraMaxAngularAcceleration);
        if (elytraMaxAngularSpeed <= 0) throw new IllegalArgumentException("elytraMaxAngularSpeed must be positive");
        this.elytraMaxAngularSpeedRadiansPerSecond = Math.toRadians(elytraMaxAngularSpeed);
        this.elytraVelocityAlignment = elytraVelocityAlignment;
        this.elytraVelocityAlignStartSpeed = elytraVelocityAlignStartSpeed;
        if (elytraVelocityAlignFullSpeed <= 0) throw new IllegalArgumentException("elytraVelocityAlignFullSpeed must be positive");
        this.elytraVelocityAlignFullSpeed = elytraVelocityAlignFullSpeed;
        if (quaternionEpsilon <= 0) throw new IllegalArgumentException("quaternionEpsilon must be positive");
        this.quaternionEpsilon = quaternionEpsilon;
        if (vectorEpsilon <= 0) throw new IllegalArgumentException("vectorEpsilon must be positive");
        this.vectorEpsilon = vectorEpsilon;
        if (maxSubstepSeconds <= 0) throw new IllegalArgumentException("maxSubstepSeconds must be positive");
        this.maxSubstepSeconds = maxSubstepSeconds;
        this.maxSubsteps = maxSubsteps;
        double maximumSourceAcceleration = Math.PI * elytraGravityAlignmentGainPerSecondSquared * elytraMaxGravityScale
                + Math.PI * elytraFlightAlignmentGainPerSecondSquared
                + elytraAngularDragPerSecond * elytraMaxAngularSpeedRadiansPerSecond
                + elytraRollAngularAccelerationRadiansPerSecondSquared;
        if (!Double.isFinite(maximumSourceAcceleration))
            throw new IllegalArgumentException("Elytra acceleration sources must have a finite bound");
    }
    public double lowGravitySwimThresholdRatio() { return lowGravitySwimThresholdRatio; }
    public double elytraMaxPitchRateRadiansPerSecond() { return elytraMaxPitchRateRadiansPerSecond; }
    public double elytraMaxYawRateRadiansPerSecond() { return elytraMaxYawRateRadiansPerSecond; }
    public double controllerRollRateRadiansPerSecond() { return controllerRollRateRadiansPerSecond; }
    public double elytraRollAngularAccelerationRadiansPerSecondSquared() { return elytraRollAngularAccelerationRadiansPerSecondSquared; }
    public double elytraGravityAlignmentGainPerSecondSquared() { return elytraGravityAlignmentGainPerSecondSquared; }
    public double elytraAngularDragPerSecond() { return elytraAngularDragPerSecond; }
    public double elytraMaxGravityScale() { return elytraMaxGravityScale; }
    public double elytraFlightAlignmentGainPerSecondSquared() { return elytraFlightAlignmentGainPerSecondSquared; }
    public double elytraMaxAngularAccelerationRadiansPerSecondSquared() { return elytraMaxAngularAccelerationRadiansPerSecondSquared; }
    public double elytraMaxAngularSpeedRadiansPerSecond() { return elytraMaxAngularSpeedRadiansPerSecond; }
    public double elytraVelocityAlignment() { return elytraVelocityAlignment; }
    public double elytraVelocityAlignStartSpeed() { return elytraVelocityAlignStartSpeed; }
    public double elytraVelocityAlignFullSpeed() { return elytraVelocityAlignFullSpeed; }
    public double quaternionEpsilon() { return quaternionEpsilon; }
    public double vectorEpsilon() { return vectorEpsilon; }
    public double maxSubstepSeconds() { return maxSubstepSeconds; }
    public int maxSubsteps() { return maxSubsteps; }
    @Override public boolean equals(Object other) {
        if (!(other instanceof BodyAttitudeConfigSnapshot c)) return false;
        return maxSubsteps == c.maxSubsteps
                && Double.compare(lowGravitySwimThresholdRatio, c.lowGravitySwimThresholdRatio) == 0
                && Double.compare(elytraMaxPitchRateRadiansPerSecond, c.elytraMaxPitchRateRadiansPerSecond) == 0
                && Double.compare(elytraMaxYawRateRadiansPerSecond, c.elytraMaxYawRateRadiansPerSecond) == 0
                && Double.compare(controllerRollRateRadiansPerSecond, c.controllerRollRateRadiansPerSecond) == 0
                && Double.compare(elytraRollAngularAccelerationRadiansPerSecondSquared, c.elytraRollAngularAccelerationRadiansPerSecondSquared) == 0
                && Double.compare(elytraGravityAlignmentGainPerSecondSquared, c.elytraGravityAlignmentGainPerSecondSquared) == 0
                && Double.compare(elytraAngularDragPerSecond, c.elytraAngularDragPerSecond) == 0
                && Double.compare(elytraMaxGravityScale, c.elytraMaxGravityScale) == 0
                && Double.compare(elytraFlightAlignmentGainPerSecondSquared, c.elytraFlightAlignmentGainPerSecondSquared) == 0
                && Double.compare(elytraMaxAngularAccelerationRadiansPerSecondSquared, c.elytraMaxAngularAccelerationRadiansPerSecondSquared) == 0
                && Double.compare(elytraMaxAngularSpeedRadiansPerSecond, c.elytraMaxAngularSpeedRadiansPerSecond) == 0
                && Double.compare(elytraVelocityAlignment, c.elytraVelocityAlignment) == 0
                && Double.compare(elytraVelocityAlignStartSpeed, c.elytraVelocityAlignStartSpeed) == 0
                && Double.compare(elytraVelocityAlignFullSpeed, c.elytraVelocityAlignFullSpeed) == 0
                && Double.compare(quaternionEpsilon, c.quaternionEpsilon) == 0
                && Double.compare(vectorEpsilon, c.vectorEpsilon) == 0
                && Double.compare(maxSubstepSeconds, c.maxSubstepSeconds) == 0;
    }
    @Override public int hashCode() { return java.util.Objects.hash(
            lowGravitySwimThresholdRatio, elytraMaxPitchRateRadiansPerSecond, elytraMaxYawRateRadiansPerSecond, controllerRollRateRadiansPerSecond, elytraRollAngularAccelerationRadiansPerSecondSquared, elytraGravityAlignmentGainPerSecondSquared, elytraAngularDragPerSecond, elytraMaxGravityScale, elytraFlightAlignmentGainPerSecondSquared, elytraMaxAngularAccelerationRadiansPerSecondSquared, elytraMaxAngularSpeedRadiansPerSecond, elytraVelocityAlignment, elytraVelocityAlignStartSpeed, elytraVelocityAlignFullSpeed, quaternionEpsilon, vectorEpsilon, maxSubstepSeconds, maxSubsteps); }
}
