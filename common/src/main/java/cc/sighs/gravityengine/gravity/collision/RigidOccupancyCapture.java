package cc.sighs.gravityengine.gravity.collision;


import java.util.List;
import java.util.Objects;

/**
 * One packet-occupancy rigid capture plus its bounded-work outcome.
 *
 * <p>{@code budgetExhausted} is an explicit indeterminate state: the packet
 * validator could not prove the dynamic rigid geometry within the permitted
 * work budget, so the owning boundary must fail closed instead of accepting a
 * position it never verified.</p>
 */
public record RigidOccupancyCapture(
        List<DynamicCollisionObstacleSnapshot> obstacles,
        boolean budgetExhausted,
        CollisionWorkTracker tracker
) {
    public RigidOccupancyCapture {
        obstacles = List.copyOf(
                Objects.requireNonNull(obstacles, "obstacles")
        );
        Objects.requireNonNull(tracker, "tracker");
    }
}
