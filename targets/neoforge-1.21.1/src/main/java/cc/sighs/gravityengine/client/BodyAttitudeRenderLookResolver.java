package cc.sighs.gravityengine.client;

import cc.sighs.gravityengine.attitude.AttitudeSpaceTransform;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.Objects;

/** Pure reference-space resolver shared by camera, picking and model adapters. */
public final class BodyAttitudeRenderLookResolver {
    private BodyAttitudeRenderLookResolver() {}

    /** Reads an already resolved pose; camera event modifiers remain separate. */
    public static RenderLookSample resolve(
            BodyAttitudeRenderSnapshot snapshot
    ) {
        Objects.requireNonNull(snapshot, "snapshot");
        return new RenderLookSample(new AttitudeSpaceTransform.LocalLookAngles(0, 0), snapshot.semanticWorldForward());
    }

    /**
     * Separately applies a NeoForge camera event modifier. The event angles are
     * absolute values initialized from Vanilla getViewYRot/getViewXRot values, so only
     * their difference from those defaults is a camera modifier.
     */
    public static CameraLookSample applyCameraModifier(
            RenderLookSample playerLook,
            float vanillaBaseYaw,
            float vanillaBasePitch,
            float eventYaw,
            float eventPitch
    ) {
        Objects.requireNonNull(playerLook, "playerLook");
        requireFinite(vanillaBaseYaw, "vanillaBaseYaw");
        requireFinite(vanillaBasePitch, "vanillaBasePitch");
        requireFinite(eventYaw, "eventYaw");
        requireFinite(eventPitch, "eventPitch");
        float modifierYaw = Mth.wrapDegrees(eventYaw - vanillaBaseYaw);
        float modifierPitch = eventPitch - vanillaBasePitch;
        AttitudeSpaceTransform.LocalLookAngles base =
                playerLook.localLook();
        AttitudeSpaceTransform.LocalLookAngles finalLook =
                new AttitudeSpaceTransform.LocalLookAngles(
                        Mth.wrapDegrees(base.yawDegrees() + modifierYaw),
                        base.pitchDegrees() + modifierPitch);
        return new CameraLookSample(
                playerLook, modifierYaw, modifierPitch, finalLook);
    }

    public record RenderLookSample(
            AttitudeSpaceTransform.LocalLookAngles localLook,
            Vec3 worldLook
    ) {
        public RenderLookSample {
            Objects.requireNonNull(localLook, "localLook");
            Objects.requireNonNull(worldLook, "worldLook");
        }
    }

    public record CameraLookSample(
            RenderLookSample playerLook,
            float modifierYaw,
            float modifierPitch,
            AttitudeSpaceTransform.LocalLookAngles finalLocalLook
    ) {
        public CameraLookSample {
            Objects.requireNonNull(playerLook, "playerLook");
            Objects.requireNonNull(finalLocalLook, "finalLocalLook");
        }
    }

    private static void requireFinite(float value, String name) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }
}
