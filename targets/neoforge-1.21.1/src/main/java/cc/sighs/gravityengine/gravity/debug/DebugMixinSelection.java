package cc.sighs.gravityengine.gravity.debug;

import java.util.Set;

/** Explicit class names only; querying this table cannot load a client class. */
public final class DebugMixinSelection {
    private static final Set<String> VIEW = Set.of(
            "cc.sighs.gravityengine.mixin.PlayerViewWriteTraceMixin",
            "cc.sighs.gravityengine.mixin.PlayerViewTickDebugMixin",
            "cc.sighs.gravityengine.mixin.CameraViewDebugMixin",
            "cc.sighs.gravityengine.mixin.ClientPacketViewDebugMixin",
            "cc.sighs.gravityengine.mixin.ServerPlayerViewDebugMixin");
    private static final Set<String> GRAVITY = Set.of(
            "cc.sighs.gravityengine.mixin.ClientEntityLocalLookDebugMixin",
            "cc.sighs.gravityengine.mixin.CameraGravityDebugMixin",
            "cc.sighs.gravityengine.mixin.EntityVelocityDebugMixin",
            "cc.sighs.gravityengine.mixin.LocalPlayerMovementDebugMixin");

    private DebugMixinSelection() {}

    public static boolean shouldApply(String mixin, BootDebugOptions.Options options) {
        if (VIEW.contains(mixin)) return options.view().enabled();
        if (GRAVITY.contains(mixin)) return options.gravity().enabled();
        if (mixin.equals("cc.sighs.gravityengine.mixin.ClientPacketSpatialDebugMixin"))
            return options.spatialState();
        if (mixin.equals("cc.sighs.gravityengine.mixin.EntityMovementDebugMixin"))
            return options.movement().enabled();
        return true;
    }
}
