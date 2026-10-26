package cc.sighs.gravityengine.network;

import cc.sighs.gravityengine.gravity.GravityFrame;
import cc.sighs.gravityengine.gravity.model.GravityCollisionRoute;
import cc.sighs.gravityengine.gravity.debug.GravityDebugLog;
import cc.sighs.gravityengine.gravity.integration.BlockContactResolver;
import cc.sighs.gravityengine.gravity.kinematic.geometry.CollisionBody;
import cc.sighs.gravityengine.gravity.minecraft.GravityFrameAccess;
import cc.sighs.gravityengine.gravity.minecraft.access.GravityEntityAccess;
import cc.sighs.gravityengine.gravity.minecraft.geometry.GravityEntityGeometry;
import cc.sighs.gravityengine.gravity.runtime.CompletedMovementFacts;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import java.util.Optional;

/** Main-thread movement policy and detached facts for exactly one native handler.
 * Nothing here survives handler return, including exceptional return. */
public final class ServerPlayerMovementReceiver {
    public enum EndpointPolicy { NATIVE_CHECKS, CLIENT_ENDPOINT }
    public sealed interface Evidence permits NativeOwned, Unavailable, Solved {}
    public record NativeOwned() implements Evidence {}
    public record Unavailable(String reason) implements Evidence {}
    public record Material(net.minecraft.world.level.Level level, BlockPos position, BlockState state) {}
    public record Solved(CompletedMovementFacts facts, Optional<Material> material) implements Evidence {}

    private static final ThreadLocal<Invocation> ACTIVE = new ThreadLocal<>();
    private ServerPlayerMovementReceiver() {}

    public static Invocation current(ServerPlayer player) {
        var current = ACTIVE.get();
        return current != null && current.player == player && current.handling ? current : null;
    }

    public static EndpointPolicy endpointPolicy(ServerPlayer player) {
        var current = current(player);
        return current == null ? EndpointPolicy.NATIVE_CHECKS : current.policy;
    }

    public static Invocation openHandler(ServerPlayer player, ServerboundMovePlayerPacket packet) {
        var request = ACTIVE.get();
        var policy = request != null && !request.handling && request.player == player && request.packet == packet
                ? request.policy : EndpointPolicy.NATIVE_CHECKS;
        return new Invocation(player, packet, policy, true);
    }

    public static void receive(ServerPlayer player, ServerboundPlayerMovePayload payload) {
        if (player.isRemoved() || player.connection == null || player.connection.player != player
                || !payload.dimension().equals(player.level().dimension().location())) return;
        try (var request = new Invocation(player, payload.movement(), EndpointPolicy.CLIENT_ENDPOINT, false)) {
            if (GravityDebugLog.shouldLogMovement(player)) {
                GravityDebugLog.movement(player, "NETWORK", "client-endpoint",
                        "predictionEpoch=%s currentEpoch=%s bypass=speed,residual,endpoint,floating",
                        payload.bodyEpoch(), GravityEntityAccess.cast(player)
                                .gravityengine$gravityComponent().state().applicationEpoch());
            }
            player.connection.handleMovePlayer(payload.movement());
        }
    }

    /** Called once at the physical move entry armed by the exact packet callsite.
     * Recursive block moves cannot claim or overwrite that root invocation. */
    public static Invocation claimRootMove(Entity entity) {
        if (!(entity instanceof ServerPlayer player)) return null;
        var invocation = current(player);
        if (invocation == null || !invocation.rootArmed) return null;
        invocation.rootArmed = false;
        return invocation;
    }

    /** Position discontinuities and recursive physical moves cannot revive a receipt
     * merely by returning to its old coordinates later in the handler. */
    public static void invalidateFacts(Entity entity, String reason) {
        if (entity instanceof ServerPlayer player) {
            var invocation = current(player);
            if (invocation != null && invocation.rootCalled) {
                invocation.invalidReason = reason;
                if (!(invocation.evidence instanceof NativeOwned)) invocation.unavailable(reason);
            }
        }
    }

    public static Optional<Material> fallMaterial(Entity entity) {
        return entity instanceof ServerPlayer player && activeFall(player) instanceof Solved solved
                ? solved.material() : Optional.empty();
    }

    public static Evidence activeFall(ServerPlayer player) {
        var invocation = current(player);
        return invocation != null && invocation.fallActive ? invocation.resolution() : null;
    }

    public static final class Invocation implements AutoCloseable {
        private final Invocation parent;
        private final ServerPlayer player;
        private final ServerboundMovePlayerPacket packet;
        private final EndpointPolicy policy;
        private final boolean handling;
        private boolean rootArmed;
        private boolean rootCalled;
        private enum Outcome { PENDING, ACCEPTED, REJECTED }
        private Outcome outcome = Outcome.PENDING;
        private boolean fallClaimed;
        private boolean fallActive;
        private boolean closed;
        private Evidence evidence = new Unavailable("no root movement");
        private String invalidReason;
        private GravityFrame reference;
        private Vec3 endpoint;
        private CollisionBody body;
        private long bodyRevision;

        private Invocation(ServerPlayer player, ServerboundMovePlayerPacket packet,
                           EndpointPolicy policy, boolean handling) {
            this.parent = ACTIVE.get();
            this.player = player;
            this.packet = packet;
            this.policy = policy;
            this.handling = handling;
            ACTIVE.set(this);
        }

        public GravityFrame referenceFrame() {
            if (reference == null) reference = GravityFrameAccess.authoritativeFrame(player);
            return reference;
        }

        public void runRootMove(Runnable move) {
            if (rootCalled) throw new IllegalStateException("packet root move already called");
            rootCalled = true;
            rootArmed = true;
            try {
                move.run();
            } catch (RuntimeException | Error failure) {
                unavailable("root movement failed");
                throw failure;
            } finally {
                rootArmed = false;
            }
        }

        public void selectRoute(GravityCollisionRoute route) {
            evidence = player.noPhysics || route == GravityCollisionRoute.VANILLA
                    ? new NativeOwned() : new Unavailable("custom move has not completed");
        }

        /** Only called after both the physical move and its scope close succeeded. */
        public void complete(CompletedMovementFacts facts, Vec3 solvedEndpoint, long solvedBodyRevision) {
            if (evidence instanceof NativeOwned) return;
            if (invalidReason != null) { unavailable(invalidReason); return; }
            if (facts == null) { unavailable("no determinate completion"); return; }
            if (!player.position().equals(solvedEndpoint) || revision() != solvedBodyRevision) {
                unavailable("position or body changed during completion");
                return;
            }
            endpoint = solvedEndpoint;
            bodyRevision = solvedBodyRevision;
            body = GravityEntityGeometry.body(player);
            reference = facts.referenceFrame();
            // Resolve dynamic addresses through the existing provider adapter.
            // Freeze only the material values, never a scene or live provider object.
            var material = facts.terminalContact().flatMap(c -> BlockContactResolver.resolve(player, c))
                    .map(c -> new Material(c.level(), c.position().immutable(), c.state()));
            evidence = new Solved(facts, material);
        }

        public void unavailable(String reason) { evidence = new Unavailable(reason); }
        public void reject() {
            outcome = Outcome.REJECTED;
            if (!(evidence instanceof NativeOwned)) unavailable("packet rejected or corrected");
        }
        public void accept() {
            if (outcome != Outcome.REJECTED) outcome = Outcome.ACCEPTED;
        }
        private long revision() {
            return GravityEntityAccess.cast(player).gravityengine$gravityComponent().state().applicationEpoch();
        }

        public Evidence resolution() {
            // Native ownership (including noPhysics) keeps native fallback even
            // on the native corrected branch. Custom unknown evidence does not.
            if (evidence instanceof NativeOwned) return evidence;
            if (outcome != Outcome.ACCEPTED) return evidence instanceof Unavailable
                    ? evidence : new Unavailable("packet not accepted");
            if (evidence instanceof Solved && (!player.position().equals(endpoint)
                    || revision() != bodyRevision || !GravityEntityGeometry.body(player).equals(body))) {
                unavailable("accepted endpoint or body differs from solved endpoint");
            }
            return evidence;
        }

        /** Compatibility fallback for gameplay ground remains the native operand.
         * Missing custom fall evidence instead defers the derived fall update. */
        public boolean gameplayGrounded(boolean nativeGround) {
            return resolution() instanceof Solved solved ? solved.facts().gameplayGrounded() : nativeGround;
        }

        public void fall(Runnable callback) {
            if (fallClaimed) return;
            fallClaimed = true;
            fallActive = true;
            try { callback.run(); } finally { fallActive = false; }
        }

        @Override public void close() {
            if (closed || ACTIVE.get() != this) throw new IllegalStateException("packet scopes must close in LIFO order");
            closed = true;
            if (parent == null) ACTIVE.remove(); else ACTIVE.set(parent);
        }
    }
}
