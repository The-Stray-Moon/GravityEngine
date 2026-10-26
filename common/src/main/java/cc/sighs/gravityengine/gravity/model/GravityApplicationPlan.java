package cc.sighs.gravityengine.gravity.model;

import java.util.Objects;

/** Legal environmental application plus acceleration authority. Independent
 * actor attitude remains separately owned. */
public record GravityApplicationPlan(Kind kind, GravityAccelerationMode accelerationMode) {
    /**
     * Ordinary server position-packet validation is not a gravity capability:
     * Vanilla owns packet acceptance, rejection and correction for every
     * entity kind, so the plan no longer carries a "player validation" kind.
     */
    public enum Kind { VANILLA, BALLISTIC, PASSIVE, CHARACTER }

    public GravityApplicationPlan {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(accelerationMode, "accelerationMode");
        if ((kind == Kind.VANILLA) != (accelerationMode == GravityAccelerationMode.NONE)) {
            throw new IllegalArgumentException(
                    "only Vanilla application has no acceleration authority");
        }
    }

    public static GravityApplicationPlan vanilla() {
        return new GravityApplicationPlan(Kind.VANILLA, GravityAccelerationMode.NONE);
    }

    public static GravityApplicationPlan ballistic(GravityAccelerationMode acceleration) {
        return new GravityApplicationPlan(Kind.BALLISTIC, acceleration);
    }

    public static GravityApplicationPlan passive(GravityAccelerationMode acceleration) {
        return new GravityApplicationPlan(Kind.PASSIVE, acceleration);
    }

    public static GravityApplicationPlan character(GravityAccelerationMode acceleration) {
        return new GravityApplicationPlan(Kind.CHARACTER, acceleration);
    }

    public boolean usesCustomBody() {
        return kind == Kind.CHARACTER;
    }

    public boolean usesCustomMoveSolver() {
        return usesCustomBody();
    }

    public boolean usesCustomCollision() {
        return kind == Kind.CHARACTER || kind == Kind.PASSIVE;
    }

    public boolean usesFieldAcceleration() {
        return accelerationMode == GravityAccelerationMode.FIELD;
    }

    public boolean needsGravityOperation() {
        return usesCustomCollision();
    }

    public boolean hasGravityOwnership() {
        return kind != Kind.VANILLA;
    }
}
