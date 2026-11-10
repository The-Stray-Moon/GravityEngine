package cc.sighs.gravityengine.gravity.geometry;

import cc.sighs.gravityengine.gravity.collision.CollisionObstacle;

import java.util.List;
import java.util.Objects;

/**
 * One pose-fit evaluation: the status plus the exact obstacles that were
 * actually materialized for it.
 */
public record PoseFit(
        PoseFitStatus status,
        String reason,
        List<CollisionObstacle> obstacles
) {
    public PoseFit {
        Objects.requireNonNull(status, "status");
        obstacles = List.copyOf(obstacles);
        if (status == PoseFitStatus.INDETERMINATE) {
            Objects.requireNonNull(reason, "reason");
        }
    }

    public static PoseFit legal(List<CollisionObstacle> obstacles) {
        return new PoseFit(PoseFitStatus.LEGAL, null, obstacles);
    }

    public static PoseFit illegal(List<CollisionObstacle> obstacles) {
        return new PoseFit(PoseFitStatus.ILLEGAL, "POSE_ILLEGAL", obstacles);
    }

    public static PoseFit indeterminate(String reason) {
        return new PoseFit(PoseFitStatus.INDETERMINATE, reason, List.of());
    }

    public boolean legal() {
        return this.status == PoseFitStatus.LEGAL;
    }

    public boolean indeterminate() {
        return this.status == PoseFitStatus.INDETERMINATE;
    }
}
