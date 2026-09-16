package cc.sighs.gravityengine.client;

import net.minecraft.client.model.geom.ModelPart;

/** Final head/hat transform after Vanilla limbs and swim animation have completed. */
public final class ControllerHeadPresentation {
    private ControllerHeadPresentation() {}
    public static void apply(BodyAttitudeRenderSnapshot snapshot, ModelPart head, ModelPart hat) {
        var rotation = snapshot.modelHeadRotation();
        head.setRotation((float)rotation.x, (float)rotation.y, (float)rotation.z);
        hat.copyFrom(head);
    }
}
