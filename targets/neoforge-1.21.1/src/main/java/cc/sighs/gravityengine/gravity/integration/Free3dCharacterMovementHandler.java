package cc.sighs.gravityengine.gravity.integration;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.attitude.runtime.BodyAttitudeOwnership;
import cc.sighs.gravityengine.gravity.minecraft.access.GravityLivingAccess;
import cc.sighs.gravityengine.gravity.minecraft.math.MinecraftMathAdapter;
import cc.sighs.gravityengine.gravity.movement.CharacterControlBasis;
import cc.sighs.gravityengine.gravity.movement.CharacterMovementBasis;
import cc.sighs.gravityengine.look.SemanticLookSnapshot;
import cc.sighs.gravityengine.math.Quatd;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * Full 3D character propulsion.
 *
 * <p>Control basis, persistent actor momentum, environmental acceleration,
 * contact response and drag remain independent semantic contributions.</p>
 */
public final class Free3dCharacterMovementHandler {
    private Free3dCharacterMovementHandler() {}

    public static void travel(GravityTravelContext context) {
        LivingEntity entity = context.entity();
        var frame = context.frame();
        CharacterControlBasis basisKind =
                context.characterPlan().movementBasis();

        Quatd acceptedBody =
                acceptedBodyIfRequired(entity, basisKind);
        SemanticLookSnapshot look =
                context.look();

        CharacterMovementBasis basis = CharacterMovementBasis.resolve(
                basisKind,
                frame,
                look.forward(),
                look.up(),
                look.zeroPitchForward(),
                acceptedBody
        );

        Vec3d propulsion = basis.propulsion(
                MinecraftMathAdapter.toVec3d(context.input()),
                GravityLivingAccess.cast(entity).gravityengine$getFlyingSpeed()
        );

        Vec3 request = MinecraftMathAdapter.toMinecraft(
                MinecraftMathAdapter.toVec3d(
                                entity.getDeltaMovement())
                        .add(propulsion));

        if (context.capturePlan() != null
                && !context.capturePlan().coversActorMovement(
                        MinecraftMathAdapter.toVec3d(request))) {
            entity.calculateEntityAnimation(false);
            return;
        }

        context.operationState().recordSelfWalk(
                entity.level().getGameTime(),
                propulsion
        );

        entity.setDeltaMovement(request);
        entity.move(MoverType.SELF, request);

        Vec3d accelerated = MinecraftMathAdapter.toVec3d(
                        entity.getDeltaMovement())
                .add(
                        entity.isNoGravity()
                                ? Vec3d.ZERO
                                : context.sample().accelerationVector()
                );

        Vec3 constrained = constrain(
                context,
                MinecraftMathAdapter.toMinecraft(accelerated));

        Vec3d local = frame.worldToLocal(
                MinecraftMathAdapter.toVec3d(constrained));
        Vec3 dragged =
                GravityLivingAccess.cast(entity).gravityengine$shouldDiscardFriction()
                        ? constrained
                        : MinecraftMathAdapter.toMinecraft(
                                frame.localToWorld(new Vec3d(
                                        local.x() * 0.91F,
                                        local.y() * 0.98F,
                                        local.z() * 0.91F
                                )));

        entity.setDeltaMovement(constrain(context, dragged));
        entity.calculateEntityAnimation(false);
    }

    private static Quatd acceptedBodyIfRequired(
            LivingEntity entity,
            CharacterControlBasis basis
    ) {
        if (basis != CharacterControlBasis.BODY_3D) {
            return null;
        }

        if (!(entity instanceof Player player)) {
            throw new IllegalStateException(
                    "BODY_3D character control requires a Player"
            );
        }

        var component = cc.sighs.gravityengine.attitude.runtime.BodyAttitudeRuntime.Access.peek(player);
        if (component == null) {
            throw new IllegalStateException(
                    "BODY_3D character control requires BodyAttitude ownership"
            );
        }

        var snapshot = component.snapshot();
        if (snapshot.ownership() == BodyAttitudeOwnership.INACTIVE
                || !snapshot.state().initialized()) {
            throw new IllegalStateException(
                    "BODY_3D character control requires a committed Qbody"
            );
        }

        return snapshot.state().currentWorldFromBody();
    }

    private static Vec3 constrain(
            GravityTravelContext context,
            Vec3 velocity
    ) {
        var result = context.operationState().currentMoveResult();

        if (!ContactVelocityIntegration
                .tangentVelocityResponseAllowed(result)) {
            return velocity;
        }

        var resolution =
                ContactVelocityIntegration.resolveContactVelocityResult(
                        context.entity(),
                        result,
                        velocity
                );

        ContactVelocityIntegration.logVelocityFallback(
                context.entity(), result, resolution, "free3d");
        return MinecraftMathAdapter.toMinecraft(resolution.velocity());
    }
}
