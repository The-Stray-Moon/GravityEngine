package cc.sighs.gravityengine.client;

import cc.sighs.gravityengine.attitude.presentation.BodyAttitudeRenderSnapshot;
import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.attitude.AttitudeSpaceTransform;
import cc.sighs.gravityengine.attitude.BodyViewConstraintSolver;
import cc.sighs.gravityengine.math.Quatd;

import net.minecraft.client.model.geom.ModelPart;

/** Final head/hat transform after Vanilla limbs and swim animation have completed. */
public final class ControllerHeadPresentation {
    private ControllerHeadPresentation() {}
    public static void apply(BodyAttitudeRenderSnapshot snapshot, ModelPart head, ModelPart hat) {
        var rotation = modelHeadRotation(snapshot);
        head.setRotation(
                (float) rotation.x(),
                (float) rotation.y(),
                (float) rotation.z()
        );
        hat.copyFrom(head);
    }

    /** Converts the resolved joint into the Minecraft model's local axis convention. */
    public static Vec3d modelHeadRotation(BodyAttitudeRenderSnapshot snapshot) {
        if (snapshot.attitudeContract().elytraAlignment()) {
            return new Vec3d(Math.toRadians(snapshot.viewLocalPitch()),
                    Math.toRadians(snapshot.viewLocalYaw()), 0);
        }
        Quatd joint = snapshot.headJoint();
        return BodyViewConstraintSolver.localJointAngles(
                new Quatd(joint.x(), -joint.y(), -joint.z(), joint.w()));
    }

    public static AttitudeSpaceTransform.LocalLookAngles modelLook(BodyAttitudeRenderSnapshot snapshot) {
        var angles = modelHeadRotation(snapshot);
        return new AttitudeSpaceTransform.LocalLookAngles((float) Math.toDegrees(angles.y()),
                (float) Math.toDegrees(angles.x()));
    }
}
