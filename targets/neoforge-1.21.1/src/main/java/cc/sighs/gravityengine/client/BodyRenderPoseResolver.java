package cc.sighs.gravityengine.client;

import cc.sighs.gravityengine.attitude.BodyAttitudeInput;
import cc.sighs.gravityengine.attitude.BodyLookResolver;
import cc.sighs.gravityengine.attitude.runtime.BodyAttitudeComponent;
import org.joml.Quaterniond;
import cc.sighs.gravityengine.gravity.debug.PlayerViewDebugLog;
import net.minecraft.world.entity.player.Player;
import javax.annotation.Nullable;

/** Pure render conversion. Mouse input belongs to the semantic controller frame. */
public final class BodyRenderPoseResolver {
    private BodyRenderPoseResolver() {}

    /**
     * A render read never allocates an owner, changes input or advances a timeline.
     */
    @Nullable
    public static BodyAttitudeRenderSnapshot snapshot(
            Player player,
            float partialTick
    ) {
        var component =
                cc.sighs.gravityengine.attitude.runtime
                        .BodyAttitudeRuntime.Access
                        .peek(player);

        var state =
                component == null
                        ? null
                        : component.renderableSnapshot();

        /*
         * First calculate the ordinary instantaneous target.
         *
         * If ownership is inactive this remains null, meaning the target is the
         * gravity-frame renderer.
         */
        BodyAttitudeRenderSnapshot pose =
                null;

        Quaterniond displayedPreviousController =
                null;

        if (state != null) {
            if (player.isLocalPlayer()) {
                var config =
                        cc.sighs.gravityengine.attitude.runtime
                                .BodyAttitudeRuntime.Config
                                .forPlayer(player);

                double roll =
                        ClientBodyAttitudeControl
                                .committedRoll(
                                        player,
                                        state
                                );

                pose =
                        !state.view().initialized()
                                ? null
                                : local(
                                state,

                                ClientBodyAttitudeControl
                                        .pendingLook(player),

                                partialTick,

                                config.orElse(
                                        cc.sighs.gravityengine.attitude
                                        .BodyAttitudeConfigSnapshot.DEFAULT
                                ),

                                config.isPresent()
                                ? cc.sighs.gravityengine.attitude.runtime
                                  .BodyAttitudeRuntime.Service
                                  .GAME_TICK_SECONDS
                                : 0.0D,

                                roll
                        );

                displayedPreviousController =
                        displayRoll(
                                state.view().semantic(
                                        state.state()
                                                .currentWorldFromBody()
                                ),
                                roll,
                                partialTick
                        ).worldFromController();

            } else {
                var remote =
                        RemoteBodyAttitudeLerp.get(
                                player
                        );

                pose =
                        remote == null
                                ? null
                                : remote.sample(partialTick);

                displayedPreviousController =
                        pose == null
                                ? null
                                : pose.cameraView()
                                .worldFromController();
            }

            if (pose != null
                    && pose.attitudeContract().viewBodyJointFollow()) {
                pose =
                        pose.boundedJoint(
                                displayedPreviousController,

                                Math.toRadians(
                                        cc.sighs.gravityengine.gravity.minecraft.access
                                                .GravityLivingAccess
                                                .cast(player)
                                                .gravityengine$getMaxHeadRotationRelativeToBody()
                                )
                        );
            }
        }

        /*
         * Critical rule:
         *
         * an unfinished handoff outranks the instantaneous logical ownership.
         *
         * ownership chooses the target, but cannot bypass the bridge.
         */
        if (player.isLocalPlayer()
                && ClientBodyAttitudeHandoff
                .active(player)) {

            BodyAttitudeRenderSnapshot bridged =
                    ClientBodyAttitudeHandoff.sample(
                            player,
                            partialTick,
                            pose
                    );

            if (bridged != null) {
                pose = bridged;
            }
        }

        if (PlayerViewDebugLog.ENABLED) {
            PlayerViewDebugLog.event(
                    player,
                    "presentation-sample",
                    "partialTick=%s local=%s qDisplay=%s worldForward=%s",
                    partialTick,
                    player.isLocalPlayer(),

                    pose == null
                            ? "absent"
                            : pose.worldFromBody(),

                    pose == null
                            ? "absent"
                            : pose.semanticWorldForward()
            );
        }

        return pose;
    }

    public static boolean hasAttitudePresentation(Player player) {
        if (player.isLocalPlayer()
                && ClientBodyAttitudeHandoff.active(player)) {
            return true;
        }

        var component =
                cc.sighs.gravityengine.attitude.runtime.BodyAttitudeRuntime.Access
                        .peek(player);

        if (component == null
                || component.renderableSnapshot() == null) {
            return false;
        }

        var remote = player.isLocalPlayer()
                ? null
                : RemoteBodyAttitudeLerp.get(player);

        return player.isLocalPlayer()
                || remote != null && remote.hasPresentation();
    }

    public static BodyAttitudeRenderSnapshot local(
            BodyAttitudeComponent.RenderableSnapshot snapshot,
            BodyAttitudeInput pending, float partialTick,
            cc.sighs.gravityengine.attitude.BodyAttitudeConfigSnapshot config, double seconds,
            double committedRollRadians) {
        Quaterniond renderBody = BodyAttitudeInterpolation.shortestArc(
                snapshot.state().previousWorldFromBody(), snapshot.state().currentWorldFromBody(), partialTick);
        var view = snapshot.view();
        var requested = BodyLookResolver.resolve(view, pending,
                snapshot.state().currentWorldFromBody(), config,
                snapshot.decision().controllerRoll() ? seconds : 0);
        // Recompute the same pending endpoint purely; render frequency never advances input.
        var cameraView = requested.nextViewState().semantic(snapshot.state().currentWorldFromBody());
        // Only the accepted tick's roll is interpolated. Keep the complete current aim and
        // immediate mouse preview, undoing the undisplayed roll about that same forward axis.
        // This is an interval sample, not held-key integration or a gameplay controller write.
        cameraView = displayRoll(cameraView, committedRollRadians, partialTick);
        return new BodyAttitudeRenderSnapshot(renderBody, requested.requestedWorldForward(),
                cameraView,
                view.localYaw(), view.localPitch(), snapshot.state().revision(),
                snapshot.lastLocalSimulationStep(), snapshot.lifecycleEpoch(),
                snapshot.authoritativeRevision(), snapshot.authoritativeServerGameTick(),
                snapshot.authoritativeStreamEpoch(), snapshot.authoritativeConfigGeneration(),
                snapshot.decision().profile().constraint() == cc.sighs.gravityengine.attitude.BodyAttitudeConstraintKind.ELYTRA_ALIGNED
                        ? cc.sighs.gravityengine.gravity.movement.CharacterAttitudeContract.ELYTRA_ALIGNED
                        : cc.sighs.gravityengine.gravity.movement.CharacterAttitudeContract.FREE_ATTITUDE);
    }

    private static cc.sighs.gravityengine.attitude.SemanticView displayRoll(
            cc.sighs.gravityengine.attitude.SemanticView endpoint, double radians, float partialTick) {
        double progress = Float.isFinite(partialTick) ? Math.clamp(partialTick, 0, 1) : 0;
        return endpoint.withRoll(-radians * (1 - progress));
    }
}
