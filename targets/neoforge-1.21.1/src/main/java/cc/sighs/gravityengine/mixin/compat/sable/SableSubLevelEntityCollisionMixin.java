package cc.sighs.gravityengine.mixin.compat.sable;

import cc.sighs.gravityengine.gravity.integration.compat.sable.SablePlayerCollisionCompatibility;
import cc.sighs.gravityengine.gravity.integration.compat.sable.SableCollisionDiagnostics;
import com.llamalad7.mixinextras.sugar.Local;
import it.unimi.dsi.fastutil.objects.ObjectSet;
import com.llamalad7.mixinextras.expression.Definition;
import com.llamalad7.mixinextras.expression.Expression;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Slice;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

/**
 * Version-matched Sable 2.0.5 character-collision representation bridge.
 *
 * <p>Sable remains the SubLevel collision owner. This mixin only adapts the
 * character representation Sable sees while executing its own narrow phase.</p>
 */
@Pseudo
@Mixin(
        targets =
                "dev.ryanhcode.sable.sublevel.entity_collision."
                        + "SubLevelEntityCollision",
        priority = 1200,
        remap = false
)
public abstract class SableSubLevelEntityCollisionMixin {

    /**
     * Disable Sable's no-narrow-phase ServerPlayer shortcut only while a
     * GravityEngine custom character body is active.
     */
    @Definition(
            id = "ServerPlayer",
            type = ServerPlayer.class
    )
    @Expression("? instanceof ServerPlayer")
    @ModifyExpressionValue(
            method = "collide",
            at = @At("MIXINEXTRAS:EXPRESSION"),
            require = 1
    )
    private static boolean gravityengine$useRealServerPlayerCollision(
            boolean original,
            Entity entity
    ) {
        return original
                && !SablePlayerCollisionCompatibility
                .requiresFullServerPlayerCollision(entity);
    }

    /**
     * The custom ServerPlayer remains represented in parent-world coordinates.
     * Do not let Sable's ordinary non-player path kick it into plot space.
     */
    @WrapOperation(
            method = "collide",
            at = @At(
                    value = "INVOKE",
                    target =
                            "Ldev/ryanhcode/sable/api/entity/"
                                    + "EntitySubLevelUtil;"
                                    + "shouldKick("
                                    + "Lnet/minecraft/world/entity/Entity;"
                                    + ")Z"
            ),
            require = 1
    )
    private static boolean gravityengine$doNotKickCustomServerPlayer(
            Entity entity,
            Operation<Boolean> original
    ) {
        if (SablePlayerCollisionCompatibility
                .requiresFullServerPlayerCollision(entity)) {
            return false;
        }

        return original.call(entity);
    }

    /**
     * Supply only GravityEngine's physical gravity-up swing to Sable's collision
     * OBB. This override is collision-local and does not globally change
     * Sable rendering/riding orientation APIs.
     */
    @WrapOperation(
            method = "collide",
            at = @At(
                    value = "INVOKE",
                    target =
                            "Ldev/ryanhcode/sable/api/entity/"
                                    + "EntitySubLevelUtil;"
                                    + "getCustomEntityOrientation("
                                    + "Lnet/minecraft/world/entity/Entity;F"
                                    + ")Lorg/joml/Quaterniondc;"
            ),
            require = 1
    )
    private static Quaterniondc gravityengine$collisionOrientation(
            Entity entity,
            float partialTicks,
            Operation<Quaterniondc> original
    ) {
        Quaterniondc custom =
                SablePlayerCollisionCompatibility
                        .collisionOrientation(entity);

        return custom != null
                ? custom
                : original.call(
                entity,
                partialTicks
        );
    }

    /** Only the two initial fullContextBounds sweep operands. */
    @WrapOperation(
            method = "collide",
            at = {
                    @At(
                            value = "INVOKE",
                            target = "Lnet/minecraft/world/entity/Entity;getBoundingBox()Lnet/minecraft/world/phys/AABB;",
                            ordinal = 0
                    ),
                    @At(
                            value = "INVOKE",
                            target = "Lnet/minecraft/world/entity/Entity;getBoundingBox()Lnet/minecraft/world/phys/AABB;",
                            ordinal = 1
                    )
            },
            require = 2,
            allow = 2
    )
    private static AABB gravityengine$collisionBroadPhaseBounds(
            Entity entity,
            Operation<AABB> original
    ) {
        return SablePlayerCollisionCompatibility.collisionBroadPhaseBounds(
                entity, original.call(entity)
        );
    }

    /**
     * Third getter: initial OBB dimensions. Remaining four: orientation and
     * tracking height operands. None may use the proxy's world-Y height.
     */
    @WrapOperation(
            method = "collide",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/Entity;getBoundingBox()Lnet/minecraft/world/phys/AABB;"
            ),
            slice = @Slice(
                    from = @At(
                            value = "INVOKE",
                            target = "Lnet/minecraft/world/entity/Entity;getBoundingBox()Lnet/minecraft/world/phys/AABB;",
                            ordinal = 2
                    )
            ),
            require = 5,
            allow = 5
    )
    private static AABB gravityengine$collisionDimensionCarrier(
            Entity entity,
            Operation<AABB> original
    ) {
        return SablePlayerCollisionCompatibility.collisionDimensionCarrier(
                entity, original.call(entity)
        );
    }

    /** The scaffolding helper also consumes real half-height, not proxy Y. */
    @WrapOperation(
            method = "getSubLevelEntityCollisionShape",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/Entity;getBoundingBox()Lnet/minecraft/world/phys/AABB;"
            ),
            require = 1,
            allow = 1
    )
    private static AABB gravityengine$collisionShapeDimensionCarrier(
            Entity entity,
            Operation<AABB> original
    ) {
        return SablePlayerCollisionCompatibility.collisionDimensionCarrier(
                entity, original.call(entity)
        );
    }

    /**
     * Sable's transformEntityBoundsCenter() rotates its box center around an
     * eye-derived anchor:
     *
     * center += offset - Q * offset
     *
     * GravityEngine instead owns a fixed physical center
     * P + WORLD_UP * h/2 and rotates only the collision axis.
     *
     * Passing null only to this helper invocation suppresses the eye-anchor
     * translation. The local customEntityOrientation variable remains intact
     * and still rotates Sable's OBB/up axis afterwards.
     */
    @ModifyArgs(
            method = "collide",
            at = @At(
                    value = "INVOKE",
                    target =
                            "Ldev/ryanhcode/sable/sublevel/entity_collision/"
                                    + "SubLevelEntityCollision;"
                                    + "transformEntityBoundsCenter("
                                    + "Ldev/ryanhcode/sable/api/math/"
                                    + "LevelReusedVectors;"
                                    + "Lorg/joml/Quaterniondc;"
                                    + "Lnet/minecraft/world/entity/Entity;"
                                    + "Lorg/joml/Vector3d;"
                                    + ")V"
            ),
            require = 1
    )
    private static void gravityengine$keepAuthoritativeBodyCenter(
            Args args
    ) {
        Entity entity =
                args.get(2);

        if (SablePlayerCollisionCompatibility
                .usesCustomCollisionRepresentation(entity)) {
            /*
             * transformEntityBoundsCenter() immediately returns for null.
             * The carrier is already centered at GravityEngine's authoritative C.
             */
            args.set(1, null);
        }
    }

    /**
     * Sable calculates original and final tracking feet via
     * ActiveSableCompanion#getFeetPos(entity, 0, orientation).
     *
     * Its native custom-orientation implementation is eye-height anchored.
     * Replace the returned value only inside this collision method with
     * GravityEngine's gravity-feet definition.
     *
     * Sable 2.0.5 currently has exactly two calls in collide(): operation
     * start and inheritedMotion calculation.
     */
    @ModifyExpressionValue(
            method = "collide",
            at = @At(
                    value = "INVOKE",
                    target =
                            "Ldev/ryanhcode/sable/ActiveSableCompanion;"
                                    + "getFeetPos("
                                    + "Lnet/minecraft/world/entity/Entity;"
                                    + "F"
                                    + "Lorg/joml/Quaterniondc;"
                                    + ")Lorg/joml/Vector3d;"
            ),
            require = 2
    )
    private static Vector3d gravityengine$useGravityFeet(
            Vector3d original,
            Entity entity
    ) {
        return SablePlayerCollisionCompatibility
                .collisionFeet(
                        entity,
                        0.0F,
                        original
                );
    }

    /**
     * During tracking Sable temporarily writes:
     *
     * entityBoundsCenter - entityUp * height/2
     *
     * into Entity.position(). For GravityEngine that value is Fg, not P.
     *
     * Convert only the FIRST raw write in collide() back to GravityEngine's
     * authoritative Vanilla position anchor. The second raw write restores
     * originalEntityPosition and must remain untouched.
     */
    @WrapOperation(
            method = "collide",
            at = @At(
                    value = "INVOKE",
                    target =
                            "Ldev/ryanhcode/sable/mixinterface/"
                                    + "EntityExtension;"
                                    + "sable$setPosSuperRaw("
                                    + "Lnet/minecraft/world/phys/Vec3;"
                                    + ")V",
                    ordinal = 0
            ),
            require = 1
    )
    private static void gravityengine$preservePositionAnchorDuringTracking(
            @Coerce Object extension,
            Vec3 proposedGravityFeet,
            Operation<Void> original
    ) {
        Entity entity =
                (Entity) extension;

        Vec3 positionAnchor =
                SablePlayerCollisionCompatibility
                        .positionAnchorFromCollisionFeet(
                                entity,
                                proposedGravityFeet
                        );

        original.call(
                extension,
                positionAnchor
        );
    }

    @Inject(
            method = "collide",
            at = @At(value = "RETURN", ordinal = 0),
            require = 1,
            allow = 1
    )
    private static void gravityengine$traceServerShortcut(
            Entity entity, Vec3 motion, Vec3 velocityMotion,
            @Coerce Object sink, CallbackInfoReturnable<?> cir
    ) {
        SableCollisionDiagnostics.logReturn(
                entity, cir.getReturnValue(), "SERVER_PLAYER_SHORTCUT", -1
        );
    }

    @Inject(
            method = "collide",
            at = @At(value = "RETURN", ordinal = 1),
            require = 1,
            allow = 1
    )
    private static void gravityengine$traceNoCandidates(
            Entity entity, Vec3 motion, Vec3 velocityMotion,
            @Coerce Object sink, CallbackInfoReturnable<?> cir
    ) {
        SableCollisionDiagnostics.logReturn(
                entity, cir.getReturnValue(), "NO_CANDIDATES", 0
        );
    }

    @Inject(
            method = "collide",
            at = @At(value = "RETURN", ordinal = 2),
            require = 1,
            allow = 1
    )
    private static void gravityengine$traceCompletedSolver(
            Entity entity, Vec3 motion, Vec3 velocityMotion,
            @Coerce Object sink, CallbackInfoReturnable<?> cir,
            @Local ObjectSet<?> intersecting
    ) {
        SableCollisionDiagnostics.logReturn(
                entity, cir.getReturnValue(), "FULL_SOLVER", intersecting.size()
        );
    }
}
