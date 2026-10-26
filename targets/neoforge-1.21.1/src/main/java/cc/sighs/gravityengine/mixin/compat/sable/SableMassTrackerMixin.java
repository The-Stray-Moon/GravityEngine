package cc.sighs.gravityengine.mixin.compat.sable;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.integration.compat.sable.SableMassPoints;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.ryanhcode.sable.api.physics.mass.MassTracker;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Sable 2.0.6 build fma operand and addBlockMass RETURN, verified in release bytecode. */
@Mixin(value = MassTracker.class, remap = false)
public abstract class SableMassTrackerMixin implements SableMassPoints.Access {
    @Unique private static final ThreadLocal<SableMassPoints> gravityengine$building = new ThreadLocal<>();
    @Unique private SableMassPoints gravityengine$points = new SableMassPoints();
    public SableMassPoints gravityengine$massPoints() { return gravityengine$points; }
    public void gravityengine$massPoints(SableMassPoints points) { gravityengine$points = points; }

    @WrapMethod(method = "build")
    private static MassTracker gravityengine$captureBuild(BlockGetter blocks, BoundingBox3ic bounds,
                                                          Operation<MassTracker> original) {
        var outer = gravityengine$building.get();
        var capture = new SableMassPoints();
        gravityengine$building.set(capture);
        try {
            var tracker = original.call(blocks, bounds);
            ((SableMassPoints.Access)tracker).gravityengine$massPoints(capture);
            return tracker;
        } finally {
            if (outer == null) gravityengine$building.remove(); else gravityengine$building.set(outer);
        }
    }
    @Inject(method = "build", at = @At(value = "INVOKE",
            target = "Lorg/joml/Vector3d;fma(DLorg/joml/Vector3dc;)Lorg/joml/Vector3d;", ordinal = 0), require = 1)
    private static void gravityengine$capturePoint(BlockGetter blocks, BoundingBox3ic bounds,
            CallbackInfoReturnable<MassTracker> ci, @Local(index = 6) BlockPos.MutableBlockPos position,
            @Local(index = 7) Vector3d center, @Local(index = 13) double mass) {
        var capture = gravityengine$building.get();
        if (capture == null) throw new IllegalStateException("mass capture outside native build");
        capture.add(position, new Vec3d(center.x, center.y, center.z), mass);
    }
    @WrapMethod(method="addBlockMass")
    private void gravityengine$atomicAdd(BlockGetter blocks,BlockState state,BlockPos position,double mass,Vec3 inertia,
                                         Operation<Void> original) {
        gravityengine$points.beginEdit();boolean succeeded=false;
        try {original.call(blocks,state,position,mass,inertia);succeeded=true;}
        finally {gravityengine$points.endEdit(succeeded);}
    }
    @Inject(method = "addBlockMass", at = @At(value="INVOKE",target=
            "Ldev/ryanhcode/sable/api/physics/mass/MassTracker;addBlockInertia(Lorg/joml/Vector3d;DLorg/joml/Matrix3d;Lnet/minecraft/world/phys/Vec3;)Lorg/joml/Matrix3d;"), require = 1)
    private void gravityengine$massEdited(BlockGetter blocks, BlockState state, BlockPos position,
                                          double mass, Vec3 inertia, CallbackInfo ci,@Local(index=11) Vector3d center) {
        // Capture the actual native operand before native COM accumulation
        // multiplies this local in place. Never invoke a custom center provider twice.
        gravityengine$points.add(position, new Vec3d(center.x,center.y,center.z), mass);
    }
}
