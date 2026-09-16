package cc.sighs.gravityengine.gravity.movement;

import java.util.Objects;
import java.util.Optional;

/**
 * Immutable pre-step ground evidence.
 *
 * Empty terminal support means UNKNOWN, never unsupported.
 */
public record CharacterGroundFacts(
        boolean gameplayGroundedAtStepStart,
        Optional<Boolean> terminalSupportAtStepStart
) {
    public CharacterGroundFacts {
        Objects.requireNonNull(
                terminalSupportAtStepStart,
                "terminalSupportAtStepStart"
        );
    }

    public boolean terminalSupportKnownAtStepStart() {
        return terminalSupportAtStepStart.isPresent();
    }

    public boolean terminalSupportedAtStepStart() {
        return terminalSupportAtStepStart
                .orElse(false);
    }

    public boolean terminalExplicitlyUnsupportedAtStepStart() {
        return terminalSupportAtStepStart
                .map(supported -> !supported)
                .orElse(false);
    }
}
