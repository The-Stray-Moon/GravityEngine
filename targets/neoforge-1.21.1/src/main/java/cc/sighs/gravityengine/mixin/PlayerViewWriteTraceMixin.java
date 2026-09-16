package cc.sighs.gravityengine.mixin;

import cc.sighs.gravityengine.gravity.debug.PlayerViewDebugLog;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;

/** 1.21.1 scalar setters plus the operations that also write old-rotation fields.
 * Both sides and all player ownership modes are observed; delegation is exact. */
@Mixin(Entity.class)
public abstract class PlayerViewWriteTraceMixin {
    @WrapMethod(method = "setYRot", require = 1)
    private void gravityengine$viewYaw(float value, Operation<Void> original) {
        Entity self = (Entity) (Object) this;
        if (!PlayerViewDebugLog.playerEnabled(self)) { original.call(value); return; }
        try (var trace = PlayerViewDebugLog.begin(self, "scalar-yaw", "requested=%s", value)) {
            original.call(value);
        }
    }

    @WrapMethod(method = "setXRot", require = 1)
    private void gravityengine$viewPitch(float value, Operation<Void> original) {
        Entity self = (Entity) (Object) this;
        if (!PlayerViewDebugLog.playerEnabled(self)) { original.call(value); return; }
        try (var trace = PlayerViewDebugLog.begin(self, "scalar-pitch", "requested=%s", value)) {
            original.call(value);
        }
    }

    @WrapMethod(method = "turn", require = 1)
    private void gravityengine$viewTurn(double yaw, double pitch, Operation<Void> original) {
        Entity self = (Entity) (Object) this;
        if (!PlayerViewDebugLog.playerEnabled(self)) { original.call(yaw, pitch); return; }
        try (var trace = PlayerViewDebugLog.begin(self, "vanilla-turn", "rawYaw=%s rawPitch=%s", yaw, pitch)) {
            original.call(yaw, pitch);
        }
    }

    @WrapMethod(method = "absRotateTo", require = 1)
    private void gravityengine$viewAbsolute(float yaw, float pitch, Operation<Void> original) {
        Entity self = (Entity) (Object) this;
        if (!PlayerViewDebugLog.playerEnabled(self)) { original.call(yaw, pitch); return; }
        try (var trace = PlayerViewDebugLog.begin(self, "vanilla-absolute-rotation", "yaw=%s pitch=%s", yaw, pitch)) {
            original.call(yaw, pitch);
        }
    }

    @WrapMethod(method = "setOldPosAndRot", require = 1)
    private void gravityengine$viewHistory(Operation<Void> original) {
        Entity self = (Entity) (Object) this;
        if (!PlayerViewDebugLog.playerEnabled(self)) { original.call(); return; }
        try (var trace = PlayerViewDebugLog.begin(self, "vanilla-history-reset", "")) {
            original.call();
        }
    }

    @WrapMethod(method = "lookAt", require = 1)
    private void gravityengine$viewLookAt(EntityAnchorArgument.Anchor anchor, Vec3 target, Operation<Void> original) {
        Entity self = (Entity) (Object) this;
        if (!PlayerViewDebugLog.playerEnabled(self)) { original.call(anchor, target); return; }
        try (var trace = PlayerViewDebugLog.begin(self, "vanilla-look-at", "anchor=%s target=%s", anchor, target)) {
            original.call(anchor, target);
        }
    }
}
