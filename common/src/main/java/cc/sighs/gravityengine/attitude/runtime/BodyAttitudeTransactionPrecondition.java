package cc.sighs.gravityengine.attitude.runtime;

import java.util.Objects;

/** Expected actor snapshot; geometry and position are not attitude preconditions. */
public record BodyAttitudeTransactionPrecondition(BodyAttitudeComponent.Snapshot component) {
    public BodyAttitudeTransactionPrecondition { Objects.requireNonNull(component); }
}
