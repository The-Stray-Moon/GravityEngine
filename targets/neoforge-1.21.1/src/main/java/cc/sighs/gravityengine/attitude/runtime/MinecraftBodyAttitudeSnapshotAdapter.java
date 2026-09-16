package cc.sighs.gravityengine.attitude.runtime;

import cc.sighs.gravityengine.attitude.*;
import cc.sighs.gravityengine.gravity.GravityFrame;
import cc.sighs.gravityengine.gravity.look.GravityLocalLook;
import cc.sighs.gravityengine.gravity.minecraft.access.GravityLivingAccess;
import cc.sighs.gravityengine.gravity.policy.GravityInfluencePolicy;
import net.minecraft.ChatFormatting;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.PlayerModelPart;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Quaternionf;
import java.util.Objects;
import java.util.Optional;

/**
 * Minecraft-to-domain snapshot adapter for body-attitude capture.
 *
 * <p>Reads the live {@code Player} and produces immutable domain values: the
 * player-state snapshot, scalar look operands, semantic world look forward,
 * capture-time bootstrap attitude and the Vanilla-compatible Elytra baseline.
 * It owns no tick orchestration: deciding when to capture, when to suspend and
 * when to commit belongs to {@link BodyAttitudeTickIntegration}.</p>
 */
public final class MinecraftBodyAttitudeSnapshotAdapter {
    private MinecraftBodyAttitudeSnapshotAdapter() {}

    /**
     * Vanilla {@code LivingEntity#getMaxHeadRotationRelativeToBody()} in
     * radians.  BodyAttitude targets are players, whose default is 50 degrees.
     */
    static double maxHeadRotationRadians(Player player) {
        Objects.requireNonNull(player, "player");
        return Math.toRadians(
                GravityLivingAccess.cast(player)
                        .gravityengine$getMaxHeadRotationRelativeToBody());
    }


    public static BodyAttitudePlayerState snapshot(Player player) {
        Pose pose = player.getPose();
        boolean fallFlying = player.isFallFlying();
        /*
         * Semantic split: actual fluid locomotion is the physical
         * water/lava/fluid-type state; swimming presentation is the
         * isSwimming/visually-swimming/SWIMMING-pose state. Character
         * SWIM_ACTION owns free locomotion/descend input and model animation,
         * but never sets the real-fluid swimming flags.
         */
        boolean actualFluidLocomotion = player.isInWater()
                || player.isInLava()
                || player.isInFluidType();
        boolean swimmingPresentation = player.isSwimming()
                || player.isVisuallySwimming()
                || pose == Pose.SWIMMING;
        boolean otherVanillaPose = pose != Pose.STANDING
                && pose != Pose.CROUCHING
                && !(pose == Pose.FALL_FLYING && fallFlying)
                && pose != Pose.SWIMMING
                && pose != Pose.SLEEPING
                && pose != Pose.SPIN_ATTACK;
        return new BodyAttitudePlayerState(
                player.noPhysics,
                player.isSpectator(),
                player.isPassenger(),
                player.isSleeping() || pose == Pose.SLEEPING,
                !player.isAlive() || player.isDeadOrDying()
                        || player.deathTime > 0,
                actualFluidLocomotion,
                swimmingPresentation,
                player.isAutoSpinAttack() || pose == Pose.SPIN_ATTACK,
                player.onClimbable(),
                player.getAbilities().flying,
                hasUpsideDownPresentation(player),
                otherVanillaPose,
                fallFlying,
                player.onGround(),
                hasEnvironmentalGravity(player)
        );
    }

    /** Environmental evidence, independent of the installed collider and rendering. */
    public static boolean hasEnvironmentalGravity(Player player) {
        var gravity = cc.sighs.gravityengine.gravity.minecraft.access.GravityEntityAccess
                .cast(player).gravityengine$gravityComponent();
        if (gravity.effectiveSuppression()
                != cc.sighs.gravityengine.gravity.model.GravitySuppressionReason.NONE) {
            return false;
        }
        return GravityInfluencePolicy.usesCustomPresentation(player);
    }

    /** Vanilla scalars for explicit bootstrap/ownership handoff and Vanilla-facing output only. */
    static AttitudeSpaceTransform.LocalLookAngles lookScalars(Player player) {
        Objects.requireNonNull(player, "player");
        return new AttitudeSpaceTransform.LocalLookAngles(
                player.getYRot(), player.getXRot());
    }

    static Vec3 worldLookForward(Player player, GravityFrame frame, BodyAttitudeInput pendingLook) {
        BodyAttitudeComponent component = BodyAttitudeRuntime.Access.peek(player);
        if (hasContinuousActiveLook(component)) {
            BodyAttitudeComponent.Snapshot snapshot = component.snapshot();
            var config = BodyAttitudeRuntime.Config.forPlayer(player);
            return BodyLookResolver.resolve(snapshot.view(), pendingLook,
                    snapshot.state().currentWorldFromBody(), config.orElse(BodyAttitudeConfigSnapshot.DEFAULT),
                    config.isPresent() && snapshot.decision().controllerRoll()
                            ? BodyAttitudeRuntime.Service.GAME_TICK_SECONDS : 0).requestedWorldForward();
        }
        GravityFrame scalarFrame = GravityInfluencePolicy.usesGravityLocalLook(player)
                ? frame : GravityFrame.DEFAULT;
        return GravityLocalLook.toWorld(
                scalarFrame, player.getYRot(), player.getXRot()).forward();
    }

    static boolean hasContinuousActiveLook(BodyAttitudeComponent component) {
        if (component == null
                || component.ownership() == BodyAttitudeOwnership.INACTIVE) {
            return false;
        }
        BodyAttitudeComponent.Snapshot snapshot = component.snapshot();
        return snapshot.decision().active()
                && snapshot.continuity() == BodyAttitudeContinuity.CONTINUOUS
                && snapshot.state().initialized()
                && snapshot.view().initialized();
    }

    static Optional<BodyAttitudeBootstrap> bootstrap(
            Player player,
            BodyAttitudeDecision decision,
            GravityFrame frame
    ) {
        if (decision.profile().constraint()
                == BodyAttitudeConstraintKind.ELYTRA_ALIGNED) {
            return Optional.of(BodyAttitudeBootstrap.stationary(
                    vanillaElytraWorldFromBody(player, frame)));
        }
        Quaternionf displayedBaseline = GravityLocalLook.lookQuaternion(
                frame, player.yBodyRot, 0.0F, 0.0F);
        return Optional.of(BodyAttitudeBootstrap.stationary(
                new Quaterniond(
                        displayedBaseline.x,
                        displayedBaseline.y,
                        displayedBaseline.z,
                        displayedBaseline.w
                )
        ));
    }

    /**
     * Matching 1.21.1 PlayerRenderer global flight rotations with the model
     * baseline removed.
     *
     * <p>All Vanilla Elytra rotations are composed in gravity-local canonical
     * space first (Y = gravity up, X = gravity forward-pitch axis) and the
     * frame orientation is lifted once at the end, so the world-space
     * {@code frame.up()} is never encoded a second time as a right-hand
     * rotation axis after {@code frame.rotation()} is already present.</p>
     */
    private static Quaterniond vanillaElytraWorldFromBody(
            Player player, GravityFrame frame
    ) {
        float flight = (float) player.getFallFlyingTicks();
        float blend = Math.max(0.0F, Math.min(1.0F, flight * flight / 100.0F));

        /*
         * Vanilla 21.1.249 PlayerRenderer aligns the Elytra yaw with
         * sign(cross) * acos(cosine) around world +Y using the world-XZ
         * velocity/look pair.  Under custom gravity the equivalent is the
         * signed angle from the gravity-tangent look to the gravity-tangent
         * velocity around frame.up.  With the DEFAULT frame the helper must
         * reproduce the Vanilla value exactly; the parity is covered by tests.
         */
        Vec3 up = frame.up();
        Vec3 lookWorld = GravityLocalLook.toWorld(
                frame, player.getYRot(), player.getXRot()
        ).forward();
        Vec3 velocity = player.getDeltaMovement();
        Vec3 lookTangent = lookWorld.subtract(
                up.scale(lookWorld.dot(up)));
        Vec3 velocityTangent = velocity.subtract(
                up.scale(velocity.dot(up)));
        if (isUsableTangent(lookTangent)
                && isUsableTangent(velocityTangent)) {
            double angle = cc.sighs.gravityengine.attitude.AttitudeSpaceTransform
                    .signedAngleAround(up, lookTangent, velocityTangent);
            return composeElytraBootstrap(
                    GravityInfluencePolicy
                            .usesCustomPresentation(player)
                            ? frame.rotation()
                            : new Quaternionf(),
                    player.yBodyRot,
                    player.getXRot(),
                    blend,
                    angle
            );
        }
        return composeElytraBootstrap(
                GravityInfluencePolicy
                        .usesCustomPresentation(player)
                        ? frame.rotation()
                        : new Quaternionf(),
                player.yBodyRot,
                player.getXRot(),
                blend,
                null
        );
    }

    /**
     * Captures the currently displayed Vanilla-compatible Elytra humanoid/root
     * attitude with the renderer-only Y180 model baseline removed.
     *
     * <p>The returned quaternion is the same canonical actor/root Qbody used by
     * the rest of BodyAttitude state, replication and persistence. It is NOT the
     * aerodynamic Elytra flight frame.
     *
     * <p>Elytra dynamics derives its fixed flight frame from this Qbody through
     * {@link AttitudeSpaceTransform#elytraForwardWorld(Quaterniond)} and the
     * corresponding left/dorsal/belly helpers. Never change the stored Qbody
     * coordinate meaning merely because ELYTRA_ALIGNED is active.</p>
     */
    static Quaterniond composeElytraBootstrap(
            Quaternionf frameRotation,
            float yBodyRotDegrees,
            float xRotDegrees,
            float flightBlend,
            Double alignmentAngleRadians
    ) {
        Quaternionf local = new Quaternionf().rotationY(
                (float) Math.toRadians(180.0F - yBodyRotDegrees));
        local.mul(new Quaternionf().rotationX(
                (float) Math.toRadians(
                        flightBlend * (-90.0F - xRotDegrees))));
        if (alignmentAngleRadians != null) {
            local.mul(new Quaternionf().rotationY(
                    (float) (double) alignmentAngleRadians));
        }
        local.mul(new Quaternionf().rotationY((float) -Math.PI));
        Quaternionf world = new Quaternionf(
                Objects.requireNonNull(frameRotation, "frameRotation"))
                .mul(local);
        return new Quaterniond(world.x, world.y, world.z, world.w).normalize();
    }

    private static boolean isUsableTangent(Vec3 tangent) {
        return tangent.lengthSqr() > 1.0E-12D;
    }

    private static boolean hasUpsideDownPresentation(Player player) {
        String name = ChatFormatting.stripFormatting(
                player.getName().getString());
        return ("Dinnerbone".equals(name) || "Grumm".equals(name))
                && player.isModelPartShown(PlayerModelPart.CAPE);
    }
}