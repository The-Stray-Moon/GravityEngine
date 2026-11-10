package cc.sighs.gravityengine.mixin.compat.sable;

import cc.sighs.gravityengine.gravity.integration.compat.sable.SableFloatingForces;
import dev.ryanhcode.sable.physics.floating_block.FloatingBlockData;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read-only access to native cluster quadrature data; Sable remains its owner. */
@Mixin(value=FloatingBlockData.class,remap=false)
public interface SableFloatingDataMixin extends SableFloatingForces.DataAccess {
    @Accessor("totalScale") double gravityengine$totalScale();
    @Accessor("weightedPosition") Vector3d gravityengine$weightedPosition();
}
