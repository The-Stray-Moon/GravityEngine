package cc.sighs.gravityengine.client;

/** Client Camera bridge for its authoritative smoothed visual eye height. */
public interface CameraEyeHeightAccess {
    float gravityengine$visualEyeHeight(float partialTick);
}
