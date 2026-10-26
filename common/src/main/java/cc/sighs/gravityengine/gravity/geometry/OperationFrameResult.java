package cc.sighs.gravityengine.gravity.geometry;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.GravityFrame;

import java.util.Objects;

/**
 * Result of one operation-frame preparation.
 *
 * <p>{@code preparationDisplacement} is a real support-preserving geometry
 * correction selected before collision; it is neither velocity nor collision
 * recovery.</p>
 */
public record OperationFrameResult(
        GravityFrame selectedFrame,
        GeometryTransitionStatus status,
        boolean geometryChanged,
        Vec3d preparationDisplacement,
        GeometryTransitionKind transitionKind
) {
    public OperationFrameResult {
        Objects.requireNonNull(selectedFrame, "selectedFrame");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(preparationDisplacement, "preparationDisplacement");
        Objects.requireNonNull(transitionKind, "transitionKind");
    }
}
