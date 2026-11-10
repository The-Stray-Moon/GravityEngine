package cc.sighs.gravityengine.mixin;

import cc.sighs.gravityengine.gravity.debug.PlayerViewDebugLog;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.RelativeMovement;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Observes the existing teleport producer without changing packet state. */
@Mixin(net.minecraft.server.network.ServerGamePacketListenerImpl.class)
public abstract class ServerPlayerViewDebugMixin {
    @Shadow public net.minecraft.server.level.ServerPlayer player;
    @Shadow private int awaitingTeleport;
    /** The six-argument overload is the single Vanilla teleport packet producer:
     * commands, corrections and the five-argument overload all reach this seam. */
    @WrapMethod(method = "teleport(DDDFFLjava/util/Set;)V", require = 1)
    private void gravityengine$traceViewTeleport(double x, double y, double z, float yaw, float pitch,
            java.util.Set<RelativeMovement> relative, Operation<Void> original) {
        if (!PlayerViewDebugLog.shouldLog(player)) { original.call(x, y, z, yaw, pitch, relative); return; }
        try (var trace = PlayerViewDebugLog.begin(player, "server-teleport",
                "targetP=%s targetYaw=%s targetPitch=%s relative=%s wireYaw=%s wirePitch=%s",
                new Vec3(x, y, z), yaw, pitch, relative,
                yaw - (relative.contains(RelativeMovement.Y_ROT) ? player.getYRot() : 0),
                pitch - (relative.contains(RelativeMovement.X_ROT) ? player.getXRot() : 0))) {
            original.call(x, y, z, yaw, pitch, relative);
            PlayerViewDebugLog.event(player, "server-teleport-sent", "teleportId=%s relative=%s",
                    this.awaitingTeleport, relative);
        }
    }

}
