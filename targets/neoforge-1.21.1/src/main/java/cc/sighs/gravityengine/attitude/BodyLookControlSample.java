package cc.sighs.gravityengine.attitude;
import net.minecraft.world.phys.Vec3;
/** One quaternion controller request and its preceding orientation for local-joint continuity. */
public record BodyLookControlSample(BodyRelativeViewState nextViewState, SemanticView previousController) {
    public Vec3 requestedWorldForward() { return nextViewState.semantic(previousController.worldFromController()).forward(); }
    public Vec3 requestedWorldUp() { return nextViewState.semantic(previousController.worldFromController()).up(); }
    /** Vanilla/display projection only; never feeds controller integration. */
    public AttitudeSpaceTransform.LocalLookAngles requestedLocalLook() { return nextViewState.localLook(); }
}
