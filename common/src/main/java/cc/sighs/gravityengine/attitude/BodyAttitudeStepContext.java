package cc.sighs.gravityengine.attitude;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.GravityFrame;
import java.util.Objects;

/** Immutable explicit input for an actor angular step; character heading and support remain external. */
public record BodyAttitudeStepContext(
        GravityFrame gravityFrame,
        BodyAttitudeControlProfile controlProfile,
        BodyAttitudeInput input,
        Vec3d lookForwardWorld,
        Vec3d velocityWorld,
        double dtSeconds
) {
    public BodyAttitudeStepContext {
        Objects.requireNonNull(gravityFrame, "gravityFrame");
        Objects.requireNonNull(controlProfile, "controlProfile");
        Objects.requireNonNull(input, "input");

        requireFinite(lookForwardWorld, "lookForwardWorld");
        requireFinite(velocityWorld, "velocityWorld");

        requireFinite(gravityFrame.samplePoint(), "gravityFrame.samplePoint");
        requireFinite(gravityFrame.down(), "gravityFrame.down");
        requireFinite(gravityFrame.up(), "gravityFrame.up");
        requireFinite(gravityFrame.left(), "gravityFrame.left");
        requireFinite(gravityFrame.forward(), "gravityFrame.forward");

        if (!Double.isFinite(gravityFrame.strength())) {
            throw new IllegalArgumentException("gravityFrame.strength must be finite");
        }

        if (!Double.isFinite(dtSeconds) || dtSeconds <= 0.0D) {
            throw new IllegalArgumentException("dtSeconds must be finite and positive");
        }
    }

    private static void requireFinite(Vec3d value, String name) {
        Objects.requireNonNull(value, name);
        if (!Double.isFinite(value.x()) || !Double.isFinite(value.y())
                || !Double.isFinite(value.z())) {
            throw new IllegalArgumentException(name + " must be finite: " + value);
        }
    }

}
