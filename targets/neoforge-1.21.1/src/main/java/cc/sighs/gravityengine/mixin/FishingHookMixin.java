package cc.sighs.gravityengine.mixin;

import cc.sighs.gravityengine.gravity.integration.vanilla.VanillaActorBridge;
import cc.sighs.gravityengine.gravity.integration.vanilla.VanillaActorSnapshot;
import cc.sighs.gravityengine.gravity.integration.vanilla.VanillaProjectileBridge;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

/**
 * FishingHook cast spatial bridge.
 *
 * <p>21.1.249 owner-constructor semantic seam (verified against the merged
 * NeoForge bytecode):
 *
 * <pre>
 * FishingHook(Player, Level, int, int)
 *     invokespecial FishingHook(EntityType, Level, int, int)  // delegated ctor
 *     setOwner(player)
 *     ... yaw/pitch trig locals ...
 *     moveTo(x, eyeY, z, yaw, pitch)                          // first position commit
 *     random.triangle x3                                      // exactly three draws
 *     setDeltaMovement(raw local launch vector)
 *     setYRot / setXRot / yRotO / xRotO
 *     return
 * </pre>
 *
 * <p>Constructor {@code HEAD} injection is never used.  The single immutable
 * {@link VanillaActorSnapshot} is captured at the {@code moveTo} invocation:
 * that instruction is the first stable seam after the delegated constructor
 * has returned, where the {@code Player} argument is still live.  The three
 * {@code RandomSource.triangle} draws are recorded without adding, removing
 * or reordering RNG calls.  At {@code RETURN} (the constructor's final
 * {@code return} bytecode, reached only after the whole constructor body)
 * the complete perturbed local launch vector is transformed once through the
 * captured semantic frame and rotation is derived from the world velocity.
 * The hook is never repositioned after {@code moveTo}.</p>
 */
@Mixin(FishingHook.class)
public abstract class FishingHookMixin {
    private static final String OWNER_CONSTRUCTOR =
            "<init>(Lnet/minecraft/world/entity/player/Player;"
                    + "Lnet/minecraft/world/level/Level;II)V";

    /**
     * Vanilla's first and only constructor-time position commit is
     * {@code moveTo(d0, eyeY, d2, yaw, pitch)}.  The actor is captured here
     * (not at constructor {@code HEAD}), and the moveTo argument tuple is
     * translated before the first position commit.
     */
    @ModifyArgs(
            method = OWNER_CONSTRUCTOR,
            at = @At(
                    value = "INVOKE",
                    target =
                            "Lnet/minecraft/world/entity/projectile/"
                                    + "FishingHook;moveTo(DDDFF)V"
            ),
            require = 1
    )
    private static void gravityengine$fishingSpawnOrigin(
            Args args,
            Player player,
            Level level,
            int luck,
            int lureSpeed,
            @Share("gravityengineFishingActor")
            LocalRef<VanillaActorSnapshot> actorRef,
            @Share("gravityengineFishingRandom")
            LocalRef<double[]> randomRef
    ) {
        VanillaActorSnapshot actor =
                VanillaActorBridge.capture(player);
        actorRef.set(actor);
        randomRef.set(new double[3]);

        boolean translateOrigin =
                actor.customBody()
                        || actor.transformedLook();

        if (!translateOrigin) {
            return;
        }

        // Vanilla's horizontal 0.3 offset is the pitch-zero casting heading.
        Vec3 origin = actor.eye().add(
                actor.zeroPitchHeading().scale(0.3D)
        );

        // moveTo(x, y, z, yaw, pitch)
        args.set(0, origin.x);
        args.set(1, origin.y);
        args.set(2, origin.z);
    }

    @WrapOperation(
            method = OWNER_CONSTRUCTOR,
            at = @At(
                    value = "INVOKE",
                    target =
                            "Lnet/minecraft/util/RandomSource;"
                                    + "triangle(DD)D",
                    ordinal = 0
            ),
            require = 1
    )
    private static double gravityengine$fishingRandomX(
            RandomSource random,
            double mean,
            double deviation,
            Operation<Double> original,
            @Share("gravityengineFishingRandom")
            LocalRef<double[]> randomRef
    ) {
        double value = original.call(random, mean, deviation);
        randomRef.get()[0] = value;
        return value;
    }

    @WrapOperation(
            method = OWNER_CONSTRUCTOR,
            at = @At(
                    value = "INVOKE",
                    target =
                            "Lnet/minecraft/util/RandomSource;"
                                    + "triangle(DD)D",
                    ordinal = 1
            ),
            require = 1
    )
    private static double gravityengine$fishingRandomY(
            RandomSource random,
            double mean,
            double deviation,
            Operation<Double> original,
            @Share("gravityengineFishingRandom")
            LocalRef<double[]> randomRef
    ) {
        double value = original.call(random, mean, deviation);
        randomRef.get()[1] = value;
        return value;
    }

    @WrapOperation(
            method = OWNER_CONSTRUCTOR,
            at = @At(
                    value = "INVOKE",
                    target =
                            "Lnet/minecraft/util/RandomSource;"
                                    + "triangle(DD)D",
                    ordinal = 2
            ),
            require = 1
    )
    private static double gravityengine$fishingRandomZ(
            RandomSource random,
            double mean,
            double deviation,
            Operation<Double> original,
            @Share("gravityengineFishingRandom")
            LocalRef<double[]> randomRef
    ) {
        double value = original.call(random, mean, deviation);
        randomRef.get()[2] = value;
        return value;
    }

    /**
     * Constructor completion.  {@code RETURN} targets the constructor's final
     * {@code return} bytecode, which is reached only after every constructor
     * body instruction has executed, so the injected instance receiver is a
     * fully initialized {@code FishingHook}.  {@code TAIL} is not used as the
     * constructor-completion locator for this seam.
     */
    @Inject(
            method = OWNER_CONSTRUCTOR,
            at = @At("RETURN")
    )
    private void gravityengine$correctFishingLaunch(
            Player player,
            Level level,
            int luck,
            int lureSpeed,
            CallbackInfo ci,
            @Share("gravityengineFishingActor")
            LocalRef<VanillaActorSnapshot> actorRef,
            @Share("gravityengineFishingRandom")
            LocalRef<double[]> randomRef
    ) {
        VanillaActorSnapshot actor = actorRef.get();
        if (actor == null
                || !actor.transformedLook()) {
            return;
        }

        double[] random = randomRef.get();

        Vec3 worldVelocity =
                VanillaProjectileBridge.fishingLaunch(actor, random[0], random[1], random[2]);

        FishingHook hook = (FishingHook) (Object) this;
        hook.setDeltaMovement(worldVelocity);

        hook.setYRot(
                (float) (Mth.atan2(
                        worldVelocity.x,
                        worldVelocity.z
                ) * 180.0F / (float) Math.PI)
        );

        hook.setXRot(
                (float) (Mth.atan2(
                        worldVelocity.y,
                        worldVelocity.horizontalDistance()
                ) * 180.0F / (float) Math.PI)
        );

        hook.yRotO = hook.getYRot();
        hook.xRotO = hook.getXRot();
    }
}
