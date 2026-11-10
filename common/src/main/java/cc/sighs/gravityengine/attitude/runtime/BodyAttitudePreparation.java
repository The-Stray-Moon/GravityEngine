package cc.sighs.gravityengine.attitude.runtime;

import java.util.Objects;

/** Pure preparation result returned by the body-attitude service. */
public record BodyAttitudePreparation(
        BodyAttitudeService.UpdateOutcome outcome,
        BodyAttitudeLogicalCandidate candidate
) {
    public BodyAttitudePreparation {
        Objects.requireNonNull(outcome, "outcome");
        if (outcome.requiresCommit() != (candidate != null)) {
            throw new IllegalArgumentException(
                    "outcome/candidate commit contract mismatch");
        }
    }
}
