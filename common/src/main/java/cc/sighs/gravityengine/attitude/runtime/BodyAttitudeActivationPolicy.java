package cc.sighs.gravityengine.attitude.runtime;

import cc.sighs.gravityengine.gravity.movement.CharacterAttitudeContract;

/** Actor lifecycle is subordinate to the explicit step contract, never field or collision ownership. */
public final class BodyAttitudeActivationPolicy {
    private BodyAttitudeActivationPolicy() {}

    public static boolean requestsOwnership(CharacterAttitudeContract contract) {
        return contract != CharacterAttitudeContract.REFERENCE_ALIGNED;
    }

    public static BodyAttitudeOwnership resolve(BodyAttitudeDecision decision,
            CharacterAttitudeContract contract) {
        return decision.active() && requestsOwnership(contract)
                ? BodyAttitudeOwnership.ACTIVE : BodyAttitudeOwnership.INACTIVE;
    }
}