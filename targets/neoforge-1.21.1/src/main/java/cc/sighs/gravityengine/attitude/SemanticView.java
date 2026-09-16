package cc.sighs.gravityengine.attitude;

import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;

/** Immutable absolute controller orientation. Scalar look is a bootstrap projection only. */
public record SemanticView(Quaterniond worldFromController) {
    public SemanticView { worldFromController = normalized(worldFromController); }

    /** Explicit ownership handoff from Vanilla/reference-local look. */
    public SemanticView(Quaterniond reference, AttitudeSpaceTransform.LocalLookAngles look) {
        this(new Quaterniond(reference).mul(AttitudeSpaceTransform.localViewRotation(look)));
    }
    @Override public Quaterniond worldFromController() { return new Quaterniond(worldFromController); }

    /** Pure pending endpoint shared by movement, gameplay, rendering and the normal tick commit.
     * Mouse axes are displacements; only held roll uses the supplied interval.
     * A zero interval leaves roll to a different owner (Elytra alignment). */
    public SemanticView previewController(BodyAttitudeInput input,
            BodyAttitudeConfigSnapshot config, double seconds) {
        if (!Double.isFinite(seconds) || seconds < 0) throw new IllegalArgumentException("invalid controller interval");
        return withInput(input).withRoll(input.rollAxis()
                * config.controllerRollRateRadiansPerSecond() * seconds);
    }

    /** Mouse displacement about local -Y/+X; previewController adds the same tick's +Z roll. */
    public SemanticView withInput(BodyAttitudeInput input) {
        return new SemanticView(new Quaterniond(worldFromController)
                .rotateY(-input.yawDeltaRadians()).rotateX(input.pitchDeltaRadians()));
    }
    public SemanticView withRoll(double radians) {
        if (!Double.isFinite(radians)) throw new IllegalArgumentException("roll must be finite");
        return new SemanticView(new Quaterniond(worldFromController).rotateZ(radians));
    }
    public Vec3 forward() { return AttitudeSpaceTransform.bodyForwardWorld(worldFromController); }
    public Vec3 up() { return AttitudeSpaceTransform.bodyUpWorld(worldFromController); }
    public Vec3 left() { return AttitudeSpaceTransform.bodyLeftWorld(worldFromController); }

    /** Scaling avoids overflow/underflow for finite nonzero representations. */
    public static Quaterniond normalized(Quaterniond q) {
        if (q == null || !q.isFinite()) throw new IllegalArgumentException("orientation must be finite");
        double scale = Math.max(Math.max(Math.abs(q.x), Math.abs(q.y)), Math.max(Math.abs(q.z), Math.abs(q.w)));
        if (scale == 0) throw new IllegalArgumentException("orientation must be nondegenerate");
        return new Quaterniond(q.x / scale, q.y / scale, q.z / scale, q.w / scale).normalize();
    }
}
