package cc.sighs.gravityengine.client;

import cc.sighs.gravityengine.gravity.debug.GravityDebugLog;
import cc.sighs.gravityengine.gravity.integration.geometry.GravityApplicationBarrier;
import cc.sighs.gravityengine.gravity.integration.geometry.NativeAabbApplicationCommit;
import cc.sighs.gravityengine.gravity.minecraft.access.GravityEntityAccess;
import cc.sighs.gravityengine.gravity.minecraft.geometry.GravityEntityGeometry;
import cc.sighs.gravityengine.network.ClientboundPlayerBodyCommitPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/** Runs on the payload registrar's MAIN thread. No client pose-fit/recovery search. */
public final class ClientPlayerBodyCommitHandler {
    private static final int MAX_PENDING = 256;
    private static final long MAX_AGE_TICKS = 600L;
    private record Key(ResourceLocation dimension, UUID uuid) {}
    private record Pending(ClientboundPlayerBodyCommitPayload payload, long receivedTick) {}
    private static final Map<Key, Pending> PENDING = new LinkedHashMap<>();
    private static final Map<Player, Long> APPLIED_EPOCH = new WeakHashMap<>();
    private static long tick;
    private static final Map<Player, Long> LAST_RESYNC_REQUEST = new WeakHashMap<>();

    private ClientPlayerBodyCommitHandler() {}

    public static void handle(ClientboundPlayerBodyCommitPayload payload) {
        Minecraft mc = Minecraft.getInstance();
        if (!mc.isSameThread()) throw new IllegalStateException("body commit requires the client thread");
        var assignment = payload.assignment();
        if (payload.nativeApplicationOnly()) {
            applyNativeApplication(payload);
            return;
        }
        if (payload.correction() != null) {
            Player player = mc.player;
            if (mc.level == null || player == null || mc.getConnection() == null
                    || !mc.level.dimension().location().equals(assignment.dimensionId())
                    || player.getId() != assignment.entityId()
                    || !player.getUUID().equals(assignment.entityUuid())) {
                // Never retain a self teleport for another world/incarnation and never ack
                // a body that could not be installed. This is a protocol/order violation.
                throw new IllegalStateException("self body commit does not match the current player/world");
            }
            try (var ignored = GravityApplicationBarrier.hold(player)) {
                install(player, payload);
                // Exactly one native handler call. Vanilla writes position/history/rotation,
                // emits its own teleport ACK and emits its normal PosRot confirmation.
                // The authoritative body is already installed before either packet is sent.
                mc.getConnection().handleMovePlayer(payload.correction());
                LAST_RESYNC_REQUEST.remove(player);
            }
            GravityDebugLog.log(player, "body-handoff-applied",
                    "epoch=%s teleportId=%s custom=%s position=%s",
                    payload.applicationEpoch(), payload.correction().getId(),
                    payload.application().plan().usesCustomBody(), GravityDebugLog.vec(player.position()));
            return;
        }
        if (!applyObserver(payload)) {
            var key = new Key(assignment.dimensionId(), assignment.entityUuid());
            var previous = PENDING.get(key);
            if (previous == null || payload.applicationEpoch() >= previous.payload().applicationEpoch()) {
                PENDING.put(key, new Pending(payload, tick));
            }
            while (PENDING.size() > MAX_PENDING) PENDING.remove(PENDING.keySet().iterator().next());
        }
    }

    private static void applyNativeApplication(ClientboundPlayerBodyCommitPayload payload) {
        var mc = Minecraft.getInstance();
        Player player = mc.player;
        var a = payload.assignment();
        if (mc.level == null || player == null || mc.getConnection() == null
                || !mc.level.dimension().location().equals(a.dimensionId())
                || player.getId() != a.entityId() || !player.getUUID().equals(a.entityUuid())) {
            // Never queue a self application for a different world/incarnation.
            return;
        }
        long appliedEpoch = APPLIED_EPOCH.getOrDefault(player, -1L);
        if (payload.applicationEpoch() <= appliedEpoch) return;
        if (cc.sighs.gravityengine.gravity.policy.GravityInfluencePolicy.collisionRoute(player)
                != cc.sighs.gravityengine.gravity.policy.GravityInfluencePolicy.CollisionRoute.VANILLA) {
            // Prediction may have entered an exact frame since the server snapshot.
            // Do not locally convert a capsule or discard authority indefinitely.
            long last = LAST_RESYNC_REQUEST.getOrDefault(player, Long.MIN_VALUE);
            if (last == Long.MIN_VALUE || tick - last >= 20) {
                LAST_RESYNC_REQUEST.put(player, tick);
                net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                        cc.sighs.gravityengine.network.ServerboundPlayerBodyResyncPayload.INSTANCE);
            }
            return;
        }
        // Pose in a metadata-only snapshot is not an instruction. Keep a
        // client-predicted crouch/swim pose and its current native dimensions.
        try (var ignored = GravityApplicationBarrier.hold(player)) {
            ClientGravitySyncService.applySnapshot(player, a);
            NativeAabbApplicationCommit.installCommitted(
                    player, payload.application(), payload.installedFrame());
            GravityEntityAccess.cast(player).gravityengine$gravityComponent().takePending();
            APPLIED_EPOCH.put(player, payload.applicationEpoch());
        }
        // No refreshDimensions, position/history/rotation write, velocity reset or ACK.
        GravityDebugLog.log(player, "native-application-applied",
                "epoch=%s plan=%s position=%s velocity=%s",
                payload.applicationEpoch(), payload.application().plan().kind(),
                GravityDebugLog.vec(player.position()), GravityDebugLog.vec(player.getDeltaMovement()));
    }

    private static boolean applyObserver(ClientboundPlayerBodyCommitPayload payload) {
        var mc = Minecraft.getInstance();
        var a = payload.assignment();
        if (mc.level == null || !mc.level.dimension().location().equals(a.dimensionId())) return false;
        Entity entity = mc.level.getEntity(a.entityId());
        if (entity == null) return false;
        if (!(entity instanceof Player player) || !player.getUUID().equals(a.entityUuid())) return true;
        // Observer data must NEVER install a local-player body without its matching teleport.
        if (player == mc.player) return true;
        if (payload.applicationEpoch() < APPLIED_EPOCH.getOrDefault(player, -1L)) return true;
        try (var ignored = GravityApplicationBarrier.hold(player)) {
            install(player, payload);
        }
        return true;
    }

    private static void install(Player player, ClientboundPlayerBodyCommitPayload payload) {
        var component = GravityEntityAccess.cast(player).gravityengine$gravityComponent();
        var runtime = component.runtime();
        if (runtime.isInMove()) throw new IllegalStateException("body commit during a live movement");
        // Desired assignment/suppression is separate from the committed application below.
        ClientGravitySyncService.applySnapshot(player, payload.assignment());
        Vec3 anchor = player.position();
        runtime.clearInfluenceTransientState();
        try (var ignored = runtime.openGeometryMutation()) {
            player.setPose(payload.pose());
            // A same-pose snapshot may arrive after another authoritative size update.
            player.refreshDimensions();
            component.commitApplication(payload.application());
            if (payload.installedFrame() != null) {
                GravityEntityGeometry.installFromPositionAnchor(player, payload.installedFrame(), anchor);
            } else {
                GravityEntityGeometry.commitVanillaBody(player, anchor,
                        GravityEntityGeometry.dimensions(player).makeBoundingBox(anchor));
            }
            player.fallDistance = 0.0F;
            player.setOnGround(false);
        }
        component.takePending();
        APPLIED_EPOCH.put(player, payload.applicationEpoch());
    }

    public static void retryOnTick() {
        tick++;
        Iterator<Pending> iterator = PENDING.values().iterator();
        while (iterator.hasNext()) {
            Pending pending = iterator.next();
            if (tick - pending.receivedTick() > MAX_AGE_TICKS || applyObserver(pending.payload())) {
                iterator.remove();
            }
        }
    }

    public static void onEntityJoin(Entity entity) {
        Pending pending = PENDING.remove(new Key(entity.level().dimension().location(), entity.getUUID()));
        if (pending != null) handle(pending.payload());
    }
    public static void onEntityRemoved(Entity entity) {
        PENDING.remove(new Key(entity.level().dimension().location(), entity.getUUID()));
        if (entity instanceof Player player) {
            APPLIED_EPOCH.remove(player);
            LAST_RESYNC_REQUEST.remove(player);
        }
    }
    public static void clearDimension(ResourceLocation dimension) {
        PENDING.keySet().removeIf(key -> key.dimension().equals(dimension));
        APPLIED_EPOCH.keySet().removeIf(player -> player.level().dimension().location().equals(dimension));
        LAST_RESYNC_REQUEST.keySet().removeIf(player -> player.level().dimension().location().equals(dimension));
    }
    public static void clearAll() { PENDING.clear(); APPLIED_EPOCH.clear(); LAST_RESYNC_REQUEST.clear(); tick = 0L; }
}
