package cc.sighs.gravityengine.mixin.compat.create;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import dev.ryanhcode.sable.api.block.BlockSubLevelLiftProvider;
import dev.ryanhcode.sable.api.physics.mass.MassTracker;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.physics.floating_block.FloatingClusterContainer;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;
import java.util.Map;

/** Sable 2.0.6 builds these properties only on the first Create tick. Reuse
 * that native builder on edits, before Create publishes its changed block. */
@Mixin(value=AbstractContraptionEntity.class,priority=900,remap=false)
public abstract class SableCreateBlockEditMixin {
    @Accessor("sable$initialized") protected abstract boolean gravityengine$initialized();
    @Accessor("sable$massTracker") protected abstract MassTracker gravityengine$mass();
    @Accessor("sable$massTracker") protected abstract void gravityengine$mass(MassTracker value);
    @Accessor("sable$localBounds") protected abstract BoundingBox3i gravityengine$bounds();
    @Accessor("sable$localBounds") protected abstract void gravityengine$bounds(BoundingBox3i value);
    @Accessor("sable$floatingClusterContainer") protected abstract FloatingClusterContainer gravityengine$floating();
    @Mutable @Accessor("sable$floatingClusterContainer") protected abstract void gravityengine$floating(FloatingClusterContainer value);
    @Accessor("sable$liftProviderContexts") protected abstract Object2ObjectMap<BlockPos,BlockSubLevelLiftProvider.LiftProviderContext> gravityengine$lift();
    @Mutable @Accessor("sable$liftProviderContexts") protected abstract void gravityengine$lift(Object2ObjectMap<BlockPos,BlockSubLevelLiftProvider.LiftProviderContext> value);
    @Invoker("sable$buildProperties") protected abstract void gravityengine$build();
    @Invoker("sable$addToPlot") protected abstract void gravityengine$addPlot();
    @Invoker("sable$removeFromPlot") protected abstract void gravityengine$removePlot();
    @Invoker("sable$addToPipeline") protected abstract void gravityengine$addPipeline(ServerLevel level);
    @Invoker("sable$removeFromPipeline") protected abstract void gravityengine$removePipeline(ServerLevel level);

    @WrapOperation(method="setBlock",at=@At(value="INVOKE",
            target="Ljava/util/Map;put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"),require=1)
    private Object gravityengine$rebuild(Map<Object,Object> blocks,Object position,Object block,Operation<Object> original) {
        var entity=(AbstractContraptionEntity)(Object)this;
        if (!gravityengine$initialized() || !(entity.level() instanceof ServerLevel level))
            return original.call(blocks,position,block);
        Object previous=original.call(blocks,position,block);
        var oldMass=gravityengine$mass();
        var oldBounds=gravityengine$bounds();
        var oldFloating=gravityengine$floating();
        var oldLift=gravityengine$lift();
        boolean replacing=false;
        try {
            gravityengine$mass(null);
            gravityengine$bounds(null);
            gravityengine$floating(new FloatingClusterContainer());
            gravityengine$lift(new Object2ObjectOpenHashMap<>());
            gravityengine$build();
            // Geometry and COM are native pipeline inputs captured at add.
            // Replacing only the mass tracker leaves the old collider alive.
            replacing=true;
            gravityengine$removePipeline(level);
            gravityengine$removePlot();
            if (gravityengine$mass()!=null) {
                gravityengine$addPlot();
                gravityengine$addPipeline(level);
            }
            return previous;
        } catch (RuntimeException | Error failure) {
            if (replacing) {
                try {gravityengine$removePipeline(level);gravityengine$removePlot();}
                catch (RuntimeException | Error rollback) {failure.addSuppressed(rollback);}
            }
            if (previous==null) blocks.remove(position); else blocks.put(position,previous);
            gravityengine$mass(oldMass);
            gravityengine$bounds(oldBounds);
            gravityengine$floating(oldFloating);
            gravityengine$lift(oldLift);
            if (replacing && oldMass!=null) {
                try {gravityengine$addPlot();gravityengine$addPipeline(level);}
                catch (RuntimeException | Error rollback) {failure.addSuppressed(rollback);}
            }
            throw failure;
        }
    }
}
