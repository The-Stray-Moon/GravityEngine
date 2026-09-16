package cc.sighs.gravityengine.gravity.movement;

import java.util.Objects;

/** Immutable step policy; never synchronized or persisted. */
public record CharacterControlPlan(CharacterLocomotionTechnique locomotion,
        CharacterControlBasis movementBasis,
        CharacterAttitudeContract attitude, CharacterPresentationIntent presentation) {
    public boolean ownsDescendInput() {
        return locomotion == CharacterLocomotionTechnique.FREE_3D
                && movementBasis == CharacterControlBasis.VIEW_3D
                && attitude == CharacterAttitudeContract.FREE_ATTITUDE
                && presentation == CharacterPresentationIntent.SWIM_ACTION;
    }

    public CharacterControlPlan {
        Objects.requireNonNull(locomotion); Objects.requireNonNull(movementBasis);
        Objects.requireNonNull(attitude);
        Objects.requireNonNull(presentation);
    }
}
