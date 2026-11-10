package cc.sighs.gravityengine.api;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.GravityFrame;
import java.util.Objects;

/**
 * Immutable read-only view of one environmental gravity/reference frame.
 * Collision orientation is independently owned and may have a different up axis.
 *
 * <p>Canonical local axes: {@code down = (0,-1,0)}, {@code up = (0,1,0)},
 * {@code left = (1,0,0)}, {@code forward = (0,0,1)}. The view is a snapshot:
 * it is never updated after the frame it was derived from is replaced.</p>
 */
public record GravityFrameView(
        Vec3d samplePoint,
        Vec3d down,
        Vec3d up,
        Vec3d left,
        Vec3d forward,
        double strength
) {
    public GravityFrameView {
        Objects.requireNonNull(samplePoint, "samplePoint");
        Objects.requireNonNull(down, "down");
        Objects.requireNonNull(up, "up");
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(forward, "forward");
        requireFinite(samplePoint, "samplePoint");
        requireFinite(down, "down");
        requireFinite(up, "up");
        requireFinite(left, "left");
        requireFinite(forward, "forward");
        if (!Double.isFinite(strength)) {
            throw new IllegalArgumentException(
                    "strength must be finite: " + strength
            );
        }
    }

    static GravityFrameView fromInternal(GravityFrame frame) {
        Objects.requireNonNull(frame, "frame");
        return new GravityFrameView(
                frame.samplePoint(),
                frame.down(),
                frame.up(),
                frame.left(),
                frame.forward(),
                frame.strength()
        );
    }

    private static void requireFinite(Vec3d value, String name) {
        if (!value.isFinite()) {
            throw new IllegalArgumentException(
                    name + " must be finite: " + value
            );
        }
    }
}
