package cc.sighs.gravityengine.attitude;

import cc.sighs.gravityengine.api.math.Vec3d;

/** One quaternion controller request and its preceding orientation for local-joint continuity. */
public record BodyLookControlSample(BodyRelativeViewState nextViewState, SemanticView previousController) {
    public Vec3d requestedWorldForward() { return nextViewState.semantic(previousController.worldFromController()).forward(); }
    public Vec3d requestedWorldUp() { return nextViewState.semantic(previousController.worldFromController()).up(); }
    /** Vanilla/display projection only; never feeds controller integration. */
    public AttitudeSpaceTransform.LocalLookAngles requestedLocalLook() { return nextViewState.localLook(); }
}