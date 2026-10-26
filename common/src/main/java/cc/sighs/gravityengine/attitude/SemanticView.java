package cc.sighs.gravityengine.attitude;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.math.Quatd;

/** Immutable absolute controller orientation. Scalar look is a bootstrap projection only. */
public record SemanticView(Quatd worldFromController) {
    public SemanticView { worldFromController = normalized(worldFromController); }

    /** Explicit ownership handoff from Vanilla/reference-local look. */
    public SemanticView(Quatd reference, AttitudeSpaceTransform.LocalLookAngles look) {
        this(reference.multiply(AttitudeSpaceTransform.localViewRotation(look)));
    }

    /**
     * Pure pending endpoint shared by movement, gameplay, rendering and the
     * normal tick commit.
     *
     * <p>Mouse axes are displacements; only held roll uses the supplied
     * interval, and that interval is a <b>geometric controller/view</b>
     * behaviour of the FREE_ATTITUDE body-view follow path. A zero interval
     * leaves roll to a different owner; ELYTRA_ALIGNED passes zero here
     * because its physical roll is a world-space torque contribution owned by
     * the angular-dynamics layer, not this fixed-rate view rotation.</p>
     */
    public SemanticView previewController(BodyAttitudeInput input,
            BodyAttitudeConfigSnapshot config, double seconds) {
        if (!Double.isFinite(seconds) || seconds < 0) throw new IllegalArgumentException("invalid controller interval");
        return withInput(input).withRoll(input.rollAxis()
                * config.controllerRollRateRadiansPerSecond() * seconds);
    }

    /** Mouse displacement about local -Y/+X; previewController adds the same tick's +Z roll. */
    public SemanticView withInput(BodyAttitudeInput input) {
        return new SemanticView(worldFromController
                .rotateY(-input.yawDeltaRadians()).rotateX(input.pitchDeltaRadians()));
    }
    public SemanticView withRoll(double radians) {
        if (!Double.isFinite(radians)) throw new IllegalArgumentException("roll must be finite");
        return new SemanticView(worldFromController.rotateZ(radians));
    }
    public Vec3d forward() { return AttitudeSpaceTransform.bodyForwardWorld(worldFromController); }
    public Vec3d up() { return AttitudeSpaceTransform.bodyUpWorld(worldFromController); }
    public Vec3d left() { return AttitudeSpaceTransform.bodyLeftWorld(worldFromController); }

    /** Scaling avoids overflow/underflow for finite nonzero representations. */
    public static Quatd normalized(Quatd q) {
        if (q == null || !q.isFinite()) throw new IllegalArgumentException("orientation must be finite");
        double scale = Math.max(Math.max(Math.abs(q.x()), Math.abs(q.y())), Math.max(Math.abs(q.z()), Math.abs(q.w())));
        if (scale == 0) throw new IllegalArgumentException("orientation must be nondegenerate");
        return new Quatd(q.x() / scale, q.y() / scale, q.z() / scale, q.w() / scale).normalized();
    }
}
