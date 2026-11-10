package cc.sighs.gravityengine.mixin;

import cc.sighs.gravityengine.gravity.integration.BallisticGravityIntegration;
import cc.sighs.gravityengine.gravity.model.GravityAccelerationMode;
import cc.sighs.gravityengine.gravity.model.GravityApplicationPlan;
import cc.sighs.gravityengine.gravity.policy.GravityInfluencePolicy;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * FishingHook airborne gravity bridge (21.1.249).
 *
 * <p>Vanilla {@code FishingHook.tick()} applies one explicit world-Y
 * airborne gravity increment
 * {@code getDeltaMovement().add(0.0, -0.03, 0.0)} whenever the hook is not
 * over water, and only then runs the per-tick drag
 * {@code scale(0.92)}.  When the hook has a committed ballistic field
 * application, that explicit gravity increment is the airborne gravity
 * semantic: it is replaced by the authoritative field acceleration through
 * {@link BallisticGravityIntegration}, keeping the Vanilla gravity-before-
 * drag ordering, water checks, state transitions and collision checks
 * untouched.  With no active field the original world-Y increment is
 * preserved byte-for-byte (default-gravity parity).</p>
 *
 * <p>Fluid-surface bobbing, {@code hookedIn} attachment, nibble/biting
 * motion and the per-tick drag are deliberately not touched here.</p>
 *
 * <p>Domain classification for the three independent fishing semantics:
 *
 * <ul>
 *   <li><b>hook airborne gravity</b> &mdash; reference/environment space
 *       (this mixin);</li>
 *   <li><b>hook attachment to an entity</b> &mdash; target body geometry
 *       (see {@code FishingHookAttachmentMixin});</li>
 *   <li><b>fluid surface / block-fluid geometry</b> &mdash; Vanilla
 *       world/block representation. Water is block geometry; its surface
 *       height stays world-Y unless a separate system redefines fluids, so
 *       BOBBING bobbing and open-water checks remain untouched.</li>
 * </ul>
 */
@Mixin(FishingHook.class)
public abstract class FishingHookGravityMixin {
    @WrapOperation(
            method = "tick",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/phys/Vec3;"
                            + "add(DDD)Lnet/minecraft/world/phys/Vec3;",
                    ordinal = 1
            ),
            require = 1
    )
    private Vec3 gravityengine$replaceAirborneGravity(
            Vec3 velocity,
            double x,
            double y,
            double z,
            Operation<Vec3> original
    ) {
        /*
         * 21.1.249 bytecode ordering inside FishingHook.tick: the BOBBING
         * biting add appears first, then the airborne gravity add
         * (0.0, -0.03, 0.0) guarded by the not-over-water fluid check.  The
         * ordinal is confirmed against the merged bytecode; the exact
         * addend check is a semantic guard so this handler can never
         * misinterpret the biting/fluid add as gravity even if a future
         * build reorders locals.
         */
        if (x != 0.0D || y != -0.03D || z != 0.0D) {
            return original.call(velocity, x, y, z);
        }
        FishingHook hook = (FishingHook) (Object) this;
        var plan = GravityInfluencePolicy.committedPlan(hook);
        if (plan.kind() != GravityApplicationPlan.Kind.BALLISTIC
                || plan.accelerationMode()
                == GravityAccelerationMode.NONE) {
            return original.call(velocity, x, y, z);
        }
        if (BallisticGravityIntegration.applyGravity(hook, 1.0D)) {
            // Field acceleration was applied; suppress the vanilla add so
            // gravity is never applied twice.  The tick's own scale(0.92)
            // drag still runs afterwards, preserving Vanilla ordering.
            return hook.getDeltaMovement();
        }
        return original.call(velocity, x, y, z);
    }
}
