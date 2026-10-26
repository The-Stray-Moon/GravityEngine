package cc.sighs.gravityengine.attitude.runtime;

import cc.sighs.gravityengine.attitude.BodyAttitudeState;
import cc.sighs.gravityengine.attitude.BodyRelativeViewState;
import java.util.Objects;

/** Installed actor/view endpoint with server-owned replication metadata. It is not a physics proposal;
 * the owning client produces live state, while server lifecycle bootstrap may establish an initial state. */
public record ReplicatedAttitudeState(
        BodyAttitudeState state,
        BodyRelativeViewState view,
        BodyAttitudeDecision decision,
        BodyAttitudeContinuity continuity,
        BodyAttitudeOwnership ownership,
        long authoritativeServerGameTick,
        long authoritativeRevision,
        long streamEpoch,
        long configGeneration
) {
    public ReplicatedAttitudeState {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(view, "view");
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(continuity, "continuity");
        Objects.requireNonNull(ownership, "ownership");
        if (authoritativeServerGameTick < 0L
                || authoritativeRevision < 0L
                || streamEpoch <= BodyAttitudeComponent.NO_AUTHORITATIVE_STREAM_EPOCH) {
            throw new IllegalArgumentException(
                    "authoritative metadata must be non-negative and stream-positive"
            );
        }
        if (configGeneration
                <= BodyAttitudeComponent.NO_AUTHORITATIVE_CONFIG_GENERATION) {
            throw new IllegalArgumentException(
                    "authoritativeConfigGeneration must be positive"
            );
        }
        if (state.revision() != authoritativeRevision) {
            throw new IllegalArgumentException(
                    "state revision does not match authoritative revision"
            );
        }
    }
}
