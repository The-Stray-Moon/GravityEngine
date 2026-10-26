package cc.sighs.gravityengine.mixin;

import cc.sighs.gravityengine.gravity.debug.PlayerViewDebugLog;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;

/** Optional observation of normal player tick and history writes. */
@Mixin(Player.class)
public abstract class PlayerViewTickDebugMixin {
    /** Vanilla baseTick and LivingEntity.tick directly maintain/wrap yRotO/xRotO.
     * Observe the completed normal player tick without replacing those writes. */
    @WrapMethod(method = "tick", require = 1)
    private void gravityengine$traceViewTick(Operation<Void> original) {
        try (var trace = PlayerViewDebugLog.begin((Player) (Object) this, "player-tick")) {
            original.call();
        }
    }
}
