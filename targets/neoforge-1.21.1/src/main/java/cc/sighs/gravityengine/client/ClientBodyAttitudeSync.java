package cc.sighs.gravityengine.client;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.attitude.AttitudeSpaceTransform;
import cc.sighs.gravityengine.attitude.BodyAttitudeState;
import cc.sighs.gravityengine.attitude.BodyRelativeViewState;
import cc.sighs.gravityengine.attitude.SemanticView;
import cc.sighs.gravityengine.attitude.runtime.*;
import cc.sighs.gravityengine.gravity.debug.PlayerViewDebugLog;
import cc.sighs.gravityengine.gravity.minecraft.GravityFrameAccess;
import cc.sighs.gravityengine.network.ClientboundBodyAttitudeStatePayload;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

/** Current body attitude receive/install boundary. Ordinary position corrections stay Vanilla-owned. */
public final class ClientBodyAttitudeSync {
    private static final int MAX_PENDING = 256;
    private static final long MAX_PENDING_AGE = 200L;
    private static final LinkedHashMap<PendingKey, Pending> PENDING = new LinkedHashMap<>();
    private static long connectionEpoch;
    private static long syncTick;
    private ClientBodyAttitudeSync() {}

    public static void handle(ClientboundBodyAttitudeStatePayload payload) {
        receive(payload, Minecraft.getInstance().level);
    }

    static void receive(ClientboundBodyAttitudeStatePayload payload, net.minecraft.world.level.Level level) {
        if (payload == null || !payload.hasUsableRepresentation()) return;
        if (PlayerViewDebugLog.shouldLog(payload.entityUuid(), level)) PlayerViewDebugLog.packet(payload.entityUuid(), level, "client-attitude-receive",
                "payload=%s connectionEpoch=%s syncTick=%s", payload, connectionEpoch, syncTick);
        if (level == null || !payload.dimensionId().equals(level.dimension().location())) {
            offerPending(payload, level); return;
        }
        Entity entity = level.getEntity(payload.entityId());
        if (!(entity instanceof Player player)) { offerPending(payload, level); return; }
        if (!player.getUUID().equals(payload.entityUuid())) return;
        if (!configReady(payload)) { offerPending(payload, level); return; }
        applyAttitude(player, payload);
    }

    private static boolean configReady(ClientboundBodyAttitudeStatePayload a) {
        return cc.sighs.gravityengine.attitude.runtime.BodyAttitudeRuntime.Config.clientFor(a.authoritativeConfigGeneration()).isPresent();
    }

    static boolean applyAttitude(Player player, ClientboundBodyAttitudeStatePayload a) {
        if (!a.hasUsableRepresentation() || !configReady(a)
                || a.entityId() != player.getId() || !a.entityUuid().equals(player.getUUID())
                || !a.dimensionId().equals(player.level().dimension().location())) return false;
        var component = BodyAttitudeRuntime.Access.component(player);
        synchronized (component) {
            var before = component.snapshot();
            if (player.isLocalPlayer() && before.authoritativeStreamOpen()
                    && before.authoritativeStreamEpoch() == a.streamEpoch()) {
                // Config reload/self snapshots cannot replace already-produced local state or input.
                return component.observeReplication(a.streamEpoch(), a.authoritativeRevision(),
                        a.authoritativeServerGameTick(), a.authoritativeConfigGeneration());
            }
            /*
             * The server packet supplies q + optional L_world.
             *
             * Effective inertia belongs to the already-validated
             * config generation, so the receiving client binds it
             * here instead of accepting a second payload copy as
             * state authority.
             */
            var receivingConfig =
                    BodyAttitudeRuntime.Config
                            .clientFor(
                                    a.authoritativeConfigGeneration()
                            )
                            .orElseThrow();

            BodyAttitudeState state =
                    a.dynamicStatePresent()

                            ? cc.sighs.gravityengine.attitude
                            .AttitudeDynamicHandoff
                            .installDynamic(
                                    a.worldFromBody(),
                                    a.angularMomentumWorld(),
                                    receivingConfig.simulation()
                                            .elytraEffectiveAngularInertia(),
                                    before.state().tick(),
                                    a.authoritativeRevision(),
                                    a.initialized()
                            )

                            : cc.sighs.gravityengine.attitude
                            .AttitudeDynamicHandoff
                            .installKinematic(
                                    a.worldFromBody(),
                                    before.state().tick(),
                                    a.authoritativeRevision(),
                                    a.initialized()
                            );
            var view = player.isLocalPlayer() && before.view().initialized()
                    ? before.view().bind(before.state().currentWorldFromBody()).relativeToBody(a.worldFromBody())
                    : a.initialized() ? BodyRelativeViewState.fromSemantic(new SemanticView(a.worldFromController()), a.worldFromBody(),
                            new AttitudeSpaceTransform.LocalLookAngles(0, 0))
                    : BodyRelativeViewState.uninitialized();
            // The receiving local lifecycle may render before its first control tick.
            // Capture Vanilla's special-mode intent here, rather than letting rendering
            // reinterpret a generic replicated-active profile from mutable entity state.
            var activeDecision = player.isLocalPlayer() && player.isFallFlying()
                    ? BodyAttitudeControlPolicyResolver.resolveActive(
                            cc.sighs.gravityengine.gravity.movement.CharacterAttitudeContract.ELYTRA_ALIGNED)
                    : BodyAttitudeDecision.replicatedActive();
            var update = new ReplicatedAttitudeState(state, view,
                    a.active() ? activeDecision
                            : BodyAttitudeDecision.suspended(a.suspensionReason()),
                    a.continuity(), a.ownership(), a.authoritativeServerGameTick(), a.authoritativeRevision(),
                    a.streamEpoch(), a.authoritativeConfigGeneration());
            if (!player.isLocalPlayer()) {
                if (!component.installReplicated(update).accepted()) return false;
                var control = (cc.sighs.gravityengine.gravity.minecraft.access.CharacterControlAccess) player;
                control.gravityengine$characterMode().installReplicated(a.swimActive());
                control.gravityengine$characterControl().clear();
                RemoteBodyAttitudeLerp.accept(player, a);
                return true;
            }
            var result = BodyAttitudeComponent.classifyReplicated(before, update);
            if (!result.accepted()) return false;

            // A new local lifecycle may release actor look into Vanilla carriers. Only this actual
            // Q/view/carrier handoff needs atomicity; ordinary remote installs have no transaction.
            float yaw = player.getYRot();
            float pitch = player.getXRot();
            float yawOld = player.yRotO;
            float pitchOld = player.xRotO;
            try {
                if (a.ownership() == BodyAttitudeOwnership.INACTIVE
                        && before.ownership() == BodyAttitudeOwnership.ACTIVE && before.view().initialized()
                        && (a.suspensionReason() == BodyAttitudeSuspensionReason.INACTIVE
                            || MinecraftBodyAttitudeSuspensionPolicy.lookPolicy(
                        a.suspensionReason())
                                == BodyAttitudeSuspensionLookPolicy.PRESERVE_WORLD_LOOK)) {
                    var rebase = AttitudeSpaceTransform.releaseLookRebase(
                            cc.sighs.gravityengine.gravity.policy.GravityInfluencePolicy.usesCustomPresentation(player)
                                    ? GravityFrameAccess.authoritativeFrame(player) : cc.sighs.gravityengine.gravity.GravityFrame.DEFAULT,
                            // The retiring local controller owns the current look.
                            // The server's independently timed scalar/quaternion
                            // sample cannot rewind it merely by releasing ownership.
                            before.state().currentWorldFromBody(), before.view(), yaw, pitch);
                    player.setYRot(rebase.sourceYaw());
                    player.setXRot(rebase.sourcePitch());

                    /*
                     * Same representation boundary as the local transaction path:
                     * do not interpolate the first gravity-owned render frame against stale
                     * attitude-era scalar history.
                     */
                    player.yRotO = rebase.sourceYaw();
                    player.xRotO = rebase.sourcePitch();
                }
                component.installReplicated(update);
            } catch (RuntimeException failure) {
                player.setYRot(yaw);
                player.setXRot(pitch);
                player.yRotO = yawOld;
                player.xRotO = pitchOld;
                return false;
            }
            cc.sighs.gravityengine.player.CharacterControlRuntime.clearMode(player);
            ClientBodyAttitudeControl.forgetSentState(player);
            return true;
        }
    }

    private static void offerPending(ClientboundBodyAttitudeStatePayload payload, net.minecraft.world.level.Level level) {
        if (PlayerViewDebugLog.shouldLog(payload.entityUuid(), level)) PlayerViewDebugLog.packet(payload.entityUuid(), level, "client-attitude-pending",
                "payload=%s connectionEpoch=%s syncTick=%s", payload, connectionEpoch, syncTick);
        PendingKey key = new PendingKey(payload.dimensionId(), payload.entityId(), payload.entityUuid(), connectionEpoch);
        Pending previous = PENDING.get(key);
        if (previous == null || payload.streamEpoch() > previous.payload().streamEpoch()
                || payload.streamEpoch() == previous.payload().streamEpoch()
                    && payload.authoritativeRevision() > previous.payload().authoritativeRevision())
            PENDING.put(key, new Pending(payload, syncTick));
        while (PENDING.size() > MAX_PENDING) PENDING.remove(PENDING.keySet().iterator().next());
    }

    public static void tick() {
        tick(Minecraft.getInstance().level);
    }

    static void tick(net.minecraft.world.level.Level level) {
        syncTick = Math.incrementExact(syncTick);
        for (var entry : List.copyOf(PENDING.entrySet())) {
            PendingKey key = entry.getKey();
            Pending pending = entry.getValue();
            if (key.connectionEpoch() != connectionEpoch || syncTick - pending.arrivalTick() > MAX_PENDING_AGE) {
                PENDING.remove(key); continue;
            }
            if (level == null || !key.dimension().equals(level.dimension().location())) continue;
            Entity entity = level.getEntity(key.entityId());
            if (entity != null && !entity.getUUID().equals(key.uuid())) { PENDING.remove(key); continue; }
            if (entity instanceof Player player && configReady(pending.payload())) {
                PENDING.remove(key);
                applyAttitude(player, pending.payload());
            }
        }
    }

    public static void onEntityJoin(Entity entity) {
        if (!(entity instanceof Player player)) return;
        PendingKey key = new PendingKey(entity.level().dimension().location(), entity.getId(), entity.getUUID(), connectionEpoch);
        Pending pending = PENDING.get(key);
        if (pending != null && configReady(pending.payload())) {
            PENDING.remove(key); applyAttitude(player, pending.payload());
        }
    }

    public static void remove(Player player) {
        PENDING.keySet().removeIf(key -> key.uuid().equals(player.getUUID()));
        ClientBodyAttitudeControl.clear(player);
    }
    public static void clearLevel() {
        PENDING.clear(); ClientBodyAttitudeControl.clearLevel();
    }
    public static void clearConnection() {
        clearLevel(); connectionEpoch = Math.incrementExact(connectionEpoch); syncTick = 0L;
        cc.sighs.gravityengine.attitude.runtime.BodyAttitudeRuntime.Config.clearClient();
    }
    static int pendingCount() { return PENDING.size(); }
    private record PendingKey(
            ResourceLocation dimension,
            int entityId,
            UUID uuid,
            long connectionEpoch
    ) {}

    private record Pending(
            ClientboundBodyAttitudeStatePayload payload,
            long arrivalTick
    ) {}

}
