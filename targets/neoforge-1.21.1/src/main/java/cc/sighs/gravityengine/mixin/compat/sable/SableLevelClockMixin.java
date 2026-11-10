package cc.sighs.gravityengine.mixin.compat.sable;

import cc.sighs.gravityengine.gravity.integration.compat.sable.SableRigidCollisionProvider;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;

/** Sable 2.0.6 ticks its container at ServerLevel.tick HEAD, before Minecraft's
 * tickTime increment. Publish that completed interval under the entity phase's
 * clock only at the actual native transition; arbitrary old queries still fail. */
@Mixin(ServerLevel.class)
public abstract class SableLevelClockMixin {
    @WrapMethod(method="tickTime",require=1)
    private void gravityengine$nativeClockBoundary(Operation<Void> original) {
        var level=(ServerLevel)(Object)this;
        long before=level.getGameTime();
        original.call();
        SableRigidCollisionProvider.clockAdvanced(level,before,level.getGameTime());
    }
}
