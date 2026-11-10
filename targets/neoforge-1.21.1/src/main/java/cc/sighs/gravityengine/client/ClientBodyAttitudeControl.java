package cc.sighs.gravityengine.client;

import cc.sighs.gravityengine.attitude.presentation.LocalControllerRoll;
import cc.sighs.gravityengine.attitude.presentation.BodyAttitudeRenderSnapshot;

import cc.sighs.gravityengine.attitude.BodyAttitudeInput;
import cc.sighs.gravityengine.attitude.runtime.BodyAttitudeRuntime.Access;
import cc.sighs.gravityengine.attitude.runtime.BodyAttitudeTickIntegration;
import cc.sighs.gravityengine.gravity.debug.PlayerViewDebugLog;
import cc.sighs.gravityengine.math.Quatd;
import cc.sighs.gravityengine.network.ServerboundBodyAttitudeStatePayload;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Consumer;

/** Pre-clamp mouse accumulation and held roll for the current normal LocalPlayer tick. */
public final class ClientBodyAttitudeControl {
    private static final Map<Player, Control> CONTROLS = new WeakHashMap<>();
    private ClientBodyAttitudeControl() {}
    // Raw binding level survives player replacement, so a held key cannot become a new press.
    private static boolean sprintIntentDown;

    public static void observeSprintBinding(net.minecraft.client.KeyMapping binding, boolean down) {
        var minecraft = net.minecraft.client.Minecraft.getInstance();
        if (minecraft.options != null && binding == minecraft.options.keySprint) {
            sprintIntentDown = down && binding.isConflictContextAndModifierActive();
            Control control = CONTROLS.get(minecraft.player);
            if (control != null) control.sprintPressed ^= control.sprint.observe(sprintIntentDown,
                    ClientBodyAttitudeKeys.acceptsInput());
        }
    }


    public static void accumulateRawLook(Player player, float yaw, float pitch) {
        if (player == null || !player.isLocalPlayer() || !Float.isFinite(yaw) || !Float.isFinite(pitch)) return;
        Control control = CONTROLS.computeIfAbsent(player, ignored -> new Control());
        control.yaw += yaw;
        control.pitch += pitch;
        if (PlayerViewDebugLog.shouldLog(player)) PlayerViewDebugLog.event(player, "input-accumulate",
                "rawYawDegrees=%s rawPitchDegrees=%s pendingYawDegrees=%s pendingPitchDegrees=%s current=%s",
                yaw, pitch, control.yaw, control.pitch, control.current);
    }

    /** Observe dedicated sprint intent after Vanilla input conditioning, before travel. */
    public static void beginTick(LocalPlayer player) {
        Control control = CONTROLS.computeIfAbsent(player, ignored -> new Control());
        control.current = new BodyAttitudeInput(Math.toRadians(boundedLookDelta(control.pitch)),
                Math.toRadians(boundedLookDelta(control.yaw)), ClientBodyAttitudeKeys.currentRollAxis());
        if (PlayerViewDebugLog.shouldLog(player)) PlayerViewDebugLog.event(player, "input-freeze",
                "pendingYawDegrees=%s pendingPitchDegrees=%s frozen=%s", control.yaw, control.pitch, control.current);
        control.yaw = control.pitch = 0;
        boolean available = ClientBodyAttitudeKeys.acceptsInput();
        // Observe once more to arm a newly created owner after release. Event capture
        // retains brief press/release taps until this tick; only parity is needed.
        boolean press = control.sprintPressed ^ control.sprint.observe(sprintIntentDown, available);
        control.sprintPressed = false;
        if (!available) press = false;
        cc.sighs.gravityengine.player.CharacterControlRuntime.captureSprintIntent(player, press);
    }

    /** Post-travel body control uses Vanilla's entity tick, with no independently allocated clock. */
    /**
     * Post-travel body control uses Vanilla's entity tick,
     * with no independently allocated clock.
     */
    public static void endTick(
            Player player
    ) {
        Control control =
                CONTROLS.get(player);

        if (control == null
                || control.current == null) {
            return;
        }

        var component =
                Access.component(player);

        var before =
                component.snapshot();

        var beforeRenderable =
                component.renderableSnapshot();

        var configOptional =
                cc.sighs.gravityengine.attitude.runtime
                        .BodyAttitudeRuntime.Config
                        .forPlayer(player);

        var config =
                configOptional.orElse(null);

        var presentationConfig =
                configOptional.orElse(
                        cc.sighs.gravityengine.attitude
                                .BodyAttitudeConfigSnapshot.DEFAULT
                );

        /*
         * Exact attitude presentation endpoint immediately before this tick's
         * ownership decision.
         */
        BodyAttitudeRenderSnapshot retiringPresentation =
                renderEndpoint(
                        player,
                        beforeRenderable,
                        control.current,
                        presentationConfig
                );

        control.rollPresentation =
                null;

        try (var trace =
                     PlayerViewDebugLog.begin(
                             player,
                             "input-control-step"
                     )) {

            var outcome = BodyAttitudeTickIntegration.tickAt(
                    player,
                    control.current,
                    player.tickCount,
                    control.current
            );

            if (outcome == cc.sighs.gravityengine.attitude.runtime.BodyAttitudeService.UpdateOutcome.REJECTED_COMMIT
                    || outcome == cc.sighs.gravityengine.attitude.runtime.BodyAttitudeService.UpdateOutcome.CONFIG_UNAVAILABLE) {
                // Look displacement has not committed. Keep it for the next tick/preview;
                // held roll is sampled afresh rather than accumulating torque intervals.
                control.yaw += Math.toDegrees(control.current.yawDeltaRadians());
                control.pitch += Math.toDegrees(control.current.pitchDeltaRadians());
                return;
            }

            var afterRenderable =
                    component.renderableSnapshot();

            /*
             * Exact endpoint of a newly active BodyAttitude state.
             *
             * control.current has already been consumed by tickAt(), therefore
             * never apply it again here.
             */
            BodyAttitudeRenderSnapshot activePresentation =
                    renderEndpoint(
                            player,
                            afterRenderable,
                            BodyAttitudeInput.NONE,
                            presentationConfig
                    );

            /*
             * ACTIVE -> INACTIVE
             */
            if (beforeRenderable != null
                    && afterRenderable == null
                    && retiringPresentation != null) {

                ClientBodyAttitudeHandoff
                        .retargetToGravity(
                                player,
                                retiringPresentation
                        );
            }

            /*
             * INACTIVE -> ACTIVE
             *
             * Do not cancel a previous release. Retarget from the currently
             * displayed bridge pose.
             */
            else if (beforeRenderable == null
                    && afterRenderable != null
                    && activePresentation != null) {

                ClientBodyAttitudeHandoff
                        .retargetToAttitude(
                                player,
                                activePresentation
                        );
            }

            control.rollPresentation =
                    LocalControllerRoll.committed(
                            before,
                            component.snapshot(),
                            control.current,
                            config
                    );

        } finally {
            control.current =
                    null;
        }
    }

    private static BodyAttitudeRenderSnapshot renderEndpoint(
            Player player,
            cc.sighs.gravityengine.attitude.runtime
                    .BodyAttitudeComponent.RenderableSnapshot renderable,
            BodyAttitudeInput input,
            cc.sighs.gravityengine.attitude.BodyAttitudeConfigSnapshot config
    ) {
        if (renderable == null
                || !renderable.view().initialized()) {
            return null;
        }

        BodyAttitudeRenderSnapshot pose =
                BodyRenderPoseResolver.local(
                        renderable,
                        input,
                        1.0F,
                        config,

                        renderable.decision()
                                .controllerRoll()
                                ? cc.sighs.gravityengine.attitude.runtime
                                  .BodyAttitudeService
                                  .GAME_TICK_SECONDS
                                : 0.0D,

                        /*
                         * At partialTick == 1 the accepted roll interval has reached
                         * its committed endpoint, so no presentation rollback remains.
                         */
                        0.0D
                );

        if (!player.isFallFlying()) {
            Quatd previousController =
                    renderable.view()
                            .semantic(
                                    renderable.state()
                                            .currentWorldFromBody()
                            )
                            .worldFromController();

            pose =
                    pose.boundedJoint(
                            previousController,

                            Math.toRadians(
                                    cc.sighs.gravityengine.gravity.minecraft.access
                                            .GravityLivingAccess
                                            .cast(player)
                                            .gravityengine$getMaxHeadRotationRelativeToBody()
                            )
                    );
        }

        return pose;
    }

    public static void sendCurrentState(Player player) {
        sendCurrentState(player, PacketDistributor::sendToServer);
    }

    static void sendCurrentState(Player player, Consumer<ServerboundBodyAttitudeStatePayload> send) {
        Control control = CONTROLS.get(player);
        if (control == null) return;
        var component = cc.sighs.gravityengine.attitude.runtime.BodyAttitudeRuntime.Access.component(player).snapshot();
        if (component.authoritativeStreamOpen() && component.authoritativeConfigGeneration() > 0) {
            var state = ServerboundBodyAttitudeStatePayload.from(player);
            if (!state.equals(control.sent)) {
                send.accept(state.forTransport(control.sequence = Math.incrementExact(control.sequence)));
                control.sent = state;
                if (PlayerViewDebugLog.shouldLog(player)) PlayerViewDebugLog.event(player, "client-attitude-send", "payload=%s", state);
            }
        }
    }

    static void forgetSentState(Player player) {
        Control control = CONTROLS.get(player);
        if (control != null) control.sent = null;
    }

    /** Explicit server look replaces earlier unconsumed look and its render
     * preview; it does not clear the locomotion mode or restart stream sequence. */
    public static void onExplicitLook(Player player) {
        Control control = CONTROLS.get(player);
        if (control != null) {
            control.yaw = control.pitch = 0;
            control.current = null;
            control.rollPresentation = null;
            control.sent = null;
        }
        ClientBodyAttitudeHandoff.remove(player);
    }

    public static BodyAttitudeInput pendingLook(Player player) {
        Control control = CONTROLS.get(player);
        if (control == null) return BodyAttitudeInput.NONE;
        BodyAttitudeInput current = control.current == null ? BodyAttitudeInput.NONE : control.current;
        return new BodyAttitudeInput(current.pitchDeltaRadians() + Math.toRadians(boundedLookDelta(control.pitch)),
                current.yawDeltaRadians() + Math.toRadians(boundedLookDelta(control.yaw)), current.rollAxis());
    }

    /** Read only the last accepted local roll interval; a lifecycle replacement cannot replay it. */
    static double committedRoll(Player player,
            cc.sighs.gravityengine.attitude.runtime.BodyAttitudeComponent.RenderableSnapshot snapshot) {
        Control control = CONTROLS.get(player);
        return control == null || control.rollPresentation == null ? 0
                : control.rollPresentation.radiansAt(snapshot, player.tickCount);
    }

    /** Preserve the existing single-tick mouse bound; this is local control, not a wire limit. */
    public static float boundedLookDelta(double degrees) {
        return Double.isFinite(degrees) ? (float) Math.clamp(degrees, -180.0D, 180.0D) : 0.0F;
    }

    public static void clear(Player player) {
        traceDiscard(player, CONTROLS.get(player));
        CONTROLS.remove(player);

        ClientBodyAttitudeHandoff.remove(player);

        cc.sighs.gravityengine.player.CharacterControlRuntime.clearMode(player);
    }
    public static void clearLevel() {
        if (cc.sighs.gravityengine.gravity.debug.BootDebugOptions.viewEnabled()) {
            CONTROLS.forEach(ClientBodyAttitudeControl::traceDiscard);
        }

        CONTROLS.keySet().forEach(
                cc.sighs.gravityengine.player.CharacterControlRuntime::clearMode
        );

        CONTROLS.clear();
        ClientBodyAttitudeHandoff.clear();
    }

    private static void traceDiscard(Player player, Control control) {
        if (PlayerViewDebugLog.shouldLog(player) && control != null) PlayerViewDebugLog.event(player, "input-discard",
                "pendingYawDegrees=%s pendingPitchDegrees=%s current=%s", control.yaw, control.pitch, control.current);
    }

    /** Runs even while player simulation is paused by a screen. */
    public static void observeCaptureAvailability() {
        if (!ClientBodyAttitudeKeys.acceptsInput()) CONTROLS.values().forEach(c -> {
            c.sprint.observe(sprintIntentDown, false);
            c.sprintPressed = false;
        });
    }

    private static final class Control {
        private final SprintIntentEdge sprint = new SprintIntentEdge();
        private boolean sprintPressed;
        private double yaw, pitch;
        private BodyAttitudeInput current;
        private LocalControllerRoll rollPresentation;
        private ServerboundBodyAttitudeStatePayload sent;
        private long sequence;
        private Control() { sprint.observe(false, false); }
    }
}
