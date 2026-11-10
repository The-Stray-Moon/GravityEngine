package cc.sighs.gravityengine.mixin.compat.sable;

import cc.sighs.gravityengine.gravity.integration.compat.sable.SableMassPoints;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;

/** The 2.0.6 native add-new/remove-old transaction must not publish intermediate mass evidence. */
@Mixin(value=SubLevelPhysicsSystem.class,remap=false)
public abstract class SableMassEditMixin {
    @WrapMethod(method="updateMassDataFromBlockChange")
    private void gravityengine$massTransaction(SubLevel body, BlockPos position, BlockState oldState,
                                               BlockState newState, boolean notify, Operation<Void> original) {
        var points=body instanceof ServerSubLevel server && server.getSelfMassTracker()!=null
                ? ((SableMassPoints.Access)server.getSelfMassTracker()).gravityengine$massPoints():null;
        if(points==null) {original.call(body,position,oldState,newState,notify);return;}
        points.beginEdit(); boolean succeeded=false;
        try { original.call(body,position,oldState,newState,notify); succeeded=true; }
        finally { points.endEdit(succeeded); }
    }
}
