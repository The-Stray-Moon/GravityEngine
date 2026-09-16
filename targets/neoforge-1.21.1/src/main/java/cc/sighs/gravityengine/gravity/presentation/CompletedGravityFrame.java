package cc.sighs.gravityengine.gravity.presentation;

import cc.sighs.gravityengine.gravity.GravityFrame;

import java.util.Objects;

/**
 * Immutable reference-frame publication of one fully closed outer gravity operation.
 * Position history and dimensions remain entity-owned; this publication supplies
 * environmental orientation/timing only and cannot retain a second translation path.
 */
public record CompletedGravityFrame(GravityFrame frame, long tick, long revision) {
    public CompletedGravityFrame {
        Objects.requireNonNull(frame, "frame");
        if (tick < 0L) {
            throw new IllegalArgumentException("tick must be non-negative");
        }
        if (revision <= 0L) {
            throw new IllegalArgumentException("revision must be positive");
        }
    }
}
