package cc.sighs.gravityengine.attitude.runtime;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.attitude.*;
import cc.sighs.gravityengine.gravity.GravityFrame;
import cc.sighs.gravityengine.gravity.debug.PlayerViewDebugLog;
import cc.sighs.gravityengine.gravity.minecraft.GravityFrameAccess;
import cc.sighs.gravityengine.gravity.minecraft.math.MinecraftMathAdapter;
import cc.sighs.gravityengine.gravity.policy.GravityInfluencePolicy;
import net.minecraft.world.entity.player.Player;

import java.util.Objects;
import java.util.Optional;

/**
 * Body-attitude tick orchestration integration.
 *
 * <p>Owns the fixed-tick capture/resolve/commit sequence, the suspension
 * boundary and the receiving-load capture contract. All Minecraft reads are
 * delegated to {@link MinecraftBodyAttitudeSnapshotAdapter}; this class owns
 * ordering, transaction preconditions and outcome classification.</p>
 */
public final class BodyAttitudeTickIntegration {
    private BodyAttitudeTickIntegration() {}
    // ------------------------------------------------------------------
    // Fixed-tick capture / orchestration boundary
    // ------------------------------------------------------------------

    public sealed interface TickCapture {
        record Ready(BodyAttitudeTickInput input) implements TickCapture {}
        record Suspended(
                BodyAttitudeDecision decision,
                BodyAttitudeLookRebase lookRebase
        ) implements TickCapture {}
        record ConfigUnavailable() implements TickCapture {}
    }

    public static BodyAttitudeService.UpdateOutcome tickAt(
            Player player,
            BodyAttitudeInput input,
            long logicalStep,
            BodyAttitudeInput pendingLook
    ) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(input, "input");
        TickCapture capture = captureTick(player, input, logicalStep, pendingLook);
        if (PlayerViewDebugLog.shouldLog(player)) PlayerViewDebugLog.event(player, "attitude-control-capture",
                "logicalStep=%s input=%s pendingLook=%s capture=%s", logicalStep, input, pendingLook,
                capture.getClass().getSimpleName());
        BodyAttitudeComponent component =
                BodyAttitudeRuntime.Access.component(player);

        if (capture instanceof TickCapture.Suspended suspended) {
            return processSuspension(
                    player, component, suspended, logicalStep);
        }
        if (capture instanceof TickCapture.ConfigUnavailable) {
            return BodyAttitudeService.UpdateOutcome.CONFIG_UNAVAILABLE;
        }

        TickCapture.Ready ready = (TickCapture.Ready) capture;
        BodyAttitudeTickInput tickInput = ready.input();
        if (PlayerViewDebugLog.shouldLog(player)) PlayerViewDebugLog.event(player, "attitude-control-operands",
                "decision=%s ownership=%s frameUp=%s lookScalars=%s bootstrapLook=%s bootstrap=%s",
                tickInput.decision(), tickInput.ownership(), tickInput.frame().up(), tickInput.lookScalars(),
                MinecraftMathAdapter.toMinecraft(
                        tickInput.bootstrapLookForward()),
                tickInput.bootstrap());
        BodyAttitudeTransactionPrecondition precondition =
                new BodyAttitudeTransactionPrecondition(component.snapshot());
        BodyAttitudePreparation preparation =
                BodyAttitudeService.prepare(
                        component, tickInput, precondition);
        BodyAttitudeLogicalCandidate candidate =
                preparation.candidate();
        if (PlayerViewDebugLog.shouldLog(player)) PlayerViewDebugLog.event(player, "attitude-local-prepare",
                "outcome=%s kind=%s bootstrapped=%s requestedQ=%s requestedView=%s",
                preparation.outcome(), candidate == null ? "absent" : candidate.kind(),
                candidate != null && candidate.bootstrapped(),
                candidate == null ? "absent" : candidate.state().currentWorldFromBody(),
                candidate == null ? "absent" : candidate.view().localLook());
        if (candidate == null) {
            return preparation.outcome();
        }

        boolean committed = BodyAttitudeTransactionCoordinator.commitLogicalOnly(player, component, candidate);
        traceBootstrap(player, candidate, committed ? "COMMITTED" : "STALE");
        return committed ? preparation.outcome() : BodyAttitudeService.UpdateOutcome.REJECTED_COMMIT;
    }

    /** Retain the reactivation boundary that otherwise leaves an unchanged
     * inactive component and therefore sends no body-state update. */
    private static void traceBootstrap(Player player, BodyAttitudeLogicalCandidate candidate, String outcome) {
        if (!candidate.bootstrapped() || !cc.sighs.gravityengine.gravity.debug.GravityDebugLog.shouldLogSpatialState(player)) return;
        cc.sighs.gravityengine.gravity.debug.GravityDebugLog.spatialState(player, "BODY", "local-bootstrap",
                "outcome=%s positionAnchor=%s trajectoryStart=%s requestedOrientation=%s committedOwnership=%s",
                outcome, cc.sighs.gravityengine.gravity.debug.GravityDebugLog.exactVec(player.position()),
                cc.sighs.gravityengine.gravity.debug.GravityDebugLog.quaternion(candidate.trajectory().sample(0)),
                cc.sighs.gravityengine.gravity.debug.GravityDebugLog.quaternion(candidate.state().currentWorldFromBody()),
                BodyAttitudeRuntime.Access.component(player).ownership());
    }

    public static TickCapture captureTick(
            Player player, BodyAttitudeInput input,
            long logicalStep, BodyAttitudeInput pendingLook
    ) {
        return capture(player, input, logicalStep, pendingLook, false);
    }

    /**
     * Read the receiving load contract without consuming the previous lifetime's
     * swim selection, cached plan or pending input. Commit owns their reset.
     */
    static TickCapture captureForLoad(Player player, long logicalStep) {
        return capture(player, BodyAttitudeInput.NONE, logicalStep,
                BodyAttitudeInput.NONE, true);
    }

    private static TickCapture capture(
            Player player, BodyAttitudeInput input,
            long logicalStep, BodyAttitudeInput pendingLook,
            boolean loadBoundary
    ) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(input, "input");
        BodyAttitudeComponent component =
                BodyAttitudeRuntime.Access.component(player);
        BodyAttitudePlayerState playerState = MinecraftBodyAttitudeSnapshotAdapter.snapshot(player);

        Optional<BodyAttitudeDecision> suspension =
                BodyAttitudeControlPolicyResolver.findSuspension(playerState);
        if (suspension.isPresent()) {
            if (!loadBoundary) {
                cc.sighs.gravityengine.player.CharacterControlRuntime.clearMode(player);
            }
            boolean preserveWorldLook =
                    MinecraftBodyAttitudeSnapshotAdapter.hasContinuousActiveLook(component)
                            && MinecraftBodyAttitudeSuspensionPolicy.lookPolicy(
                            suspension.get().suspensionReason())
                            == BodyAttitudeSuspensionLookPolicy
                            .PRESERVE_WORLD_LOOK;
            GravityFrame frame = preserveWorldLook
                    && GravityInfluencePolicy.usesGravityLocalLook(player)
                    ? GravityFrameAccess.authoritativeFrame(player)
                    : GravityFrame.DEFAULT;
            BodyAttitudeLookRebase lookRebase =
                    lookRebaseForSuspension(
                            player, component, frame, preserveWorldLook);
            return new TickCapture.Suspended(
                    suspension.get(),
                    lookRebase);
        }

        BodyAttitudeConfigSnapshot simulationConfig;
        if (player.level().isClientSide()) {
            long requiredGeneration = component.snapshot()
                    .authoritativeConfigGeneration();
            var clientConfig = BodyAttitudeRuntime.Config.clientFor(
                    requiredGeneration);
            if (clientConfig.isEmpty()) {
                return new TickCapture.ConfigUnavailable();
            }
            simulationConfig = clientConfig.get().simulation();
        } else {
            simulationConfig = BodyAttitudeRuntime.Config.server().simulation();
        }

        GravityFrame frame = GravityFrameAccess.authoritativeFrame(player);
        var characterPlan = loadBoundary
                ? cc.sighs.gravityengine.gravity.movement.CharacterLocomotionControlPolicyResolver.resolve(
                        playerState, true, false,
                        cc.sighs.gravityengine.player.CharacterControlRuntime.groundFacts(
                                player, playerState.onGround()),
                        frame, simulationConfig)
                : cc.sighs.gravityengine.player.CharacterControlRuntime.resolve(
                        player, frame, simulationConfig, logicalStep);
        BodyAttitudeDecision decision = BodyAttitudeControlPolicyResolver.resolveActive( characterPlan.attitude());
        BodyAttitudeOwnership ownership =
                BodyAttitudeActivationPolicy.resolve(decision, characterPlan.attitude());
        Vec3d capturedLookForward = MinecraftBodyAttitudeSnapshotAdapter.worldLookForward(player, frame, pendingLook);
        BodyAttitudeTickInput tickInput = new BodyAttitudeTickInput(
                logicalStep,
                decision,
                ownership,
                frame,
                MinecraftMathAdapter.toVec3d(
                        player.getDeltaMovement()),
                MinecraftBodyAttitudeSnapshotAdapter.lookScalars(player),
                capturedLookForward,
                MinecraftBodyAttitudeSnapshotAdapter.bootstrap(player, decision, frame),
                input,
                simulationConfig,
                MinecraftBodyAttitudeSnapshotAdapter.maxHeadRotationRadians(player),  characterPlan.attitude(),  GravityInfluencePolicy.usesGravityLocalLook(player));
        return new TickCapture.Ready(tickInput);
    }

    private static BodyAttitudeLookRebase lookRebaseForSuspension(
            Player player,
            BodyAttitudeComponent component,
            GravityFrame frame,
            boolean preserveWorldLook
    ) {
        if (!preserveWorldLook
                || !component.state().initialized()
                || !component.view().initialized()) {
            return null;
        }
        AttitudeSpaceTransform.ReleaseLookRebase release =
                AttitudeSpaceTransform.releaseLookRebase(
                        frame,
                        component.state().currentWorldFromBody(),
                        component.view(),
                        player.getYRot(),
                        player.getXRot());
        return new BodyAttitudeLookRebase(
                release.sourceYaw(),
                release.sourcePitch());
    }

    private static BodyAttitudeService.UpdateOutcome processSuspension(
            Player player,
            BodyAttitudeComponent component,
            TickCapture.Suspended suspended,
            long logicalStep) {
        var precondition = new BodyAttitudeTransactionPrecondition(component.snapshot());
        boolean committed = BodyAttitudeTransactionCoordinator.commitLogicalOnly(player, component,
                suspensionLogicalCandidate(component, suspended, logicalStep, precondition));
        return committed ? BodyAttitudeService.UpdateOutcome.SUSPENDED
                : BodyAttitudeService.UpdateOutcome.REJECTED_COMMIT;
    }

    private static BodyAttitudeLogicalCandidate suspensionLogicalCandidate(
            BodyAttitudeComponent component,
            TickCapture.Suspended suspended,
            long logicalStep,
            BodyAttitudeTransactionPrecondition precondition
    ) {
        BodyAttitudeComponent.Snapshot before = component.snapshot();
        /* Suspension is a mode exit: consume GE-owned momentum exactly once. */
        BodyAttitudeState state = before.state().asKinematic();
        return new BodyAttitudeLogicalCandidate(
                state,
                before.view(),
                null,
                suspended.decision(),
                BodyAttitudeContinuity.INVALID,
                BodyAttitudeOwnership.INACTIVE,
                logicalStep,
                false,
                null,
                BodyAttitudeLogicalCandidate.Kind.SUSPENSION,
                precondition,
                suspended.lookRebase());
    }
}
