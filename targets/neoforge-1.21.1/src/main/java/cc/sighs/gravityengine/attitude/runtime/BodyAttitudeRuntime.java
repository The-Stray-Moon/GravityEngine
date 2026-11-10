package cc.sighs.gravityengine.attitude.runtime;

import cc.sighs.gravityengine.GravityEngineAttachments;
import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.attitude.*;
import cc.sighs.gravityengine.attitude.persistence.BodyAttitudePersistenceSlot;
import cc.sighs.gravityengine.attitude.persistence.BodyAttitudePersistentSeed;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

import javax.annotation.Nullable;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Cohesive body-attitude runtime boundary.
 *
 * <p>Configuration generations, persistence restore and entity access seams
 * share one runtime namespace. The pure logical preparation service itself is
 * the loader-neutral
 * {@link cc.sighs.gravityengine.attitude.runtime.BodyAttitudeService}, and the
 * transaction, collision and ownership lifecycle owners remain separate
 * classes.</p>
 */
public final class BodyAttitudeRuntime {
    private BodyAttitudeRuntime() {}


/** Atomic server configuration generation and client control mirror. */
public static final class Config {
    private static final AtomicLong NEXT_GENERATION = new AtomicLong(1L);
    private static final AtomicReference<Generation> SERVER = new AtomicReference<>(
            new Generation(1L, BodyAttitudeConfigSnapshot.DEFAULT));
    private static final AtomicReference<ClientGeneration> CLIENT =
            new AtomicReference<>();

    private Config() {}

    public static Generation server() { return SERVER.get(); }

    /** Configuration paired with this actor's installed stream, without allocating state. */
    public static Optional<BodyAttitudeConfigSnapshot> forPlayer(Player player) {
        if (!player.level().isClientSide()) return Optional.of(server().simulation());
        var component = Access.peek(player);
        return clientFor(component == null ? NO_GENERATION : component.snapshot().authoritativeConfigGeneration())
                .map(ClientGeneration::simulation);
    }

    public static Generation reloadServer(
            BodyAttitudeConfigSnapshot simulation
    ) {
        Objects.requireNonNull(simulation, "simulation");
        long generation = NEXT_GENERATION.updateAndGet(previous -> {
            if (previous == Long.MAX_VALUE) {
                throw new IllegalStateException("body attitude config generation overflow");
            }
            return previous + 1L;
        });
        Generation next = new Generation(generation, simulation);
        SERVER.set(next);
        return next;
    }

    public static Optional<ClientGeneration> client() {
        return Optional.ofNullable(CLIENT.get());
    }

    /** Returns client configuration only when it matches state authority. */
    public static Optional<ClientGeneration> clientFor(
            long requiredGeneration
    ) {
        if (requiredGeneration < NO_GENERATION) {
            throw new IllegalArgumentException("requiredGeneration must be non-negative");
        }
        ClientGeneration current = CLIENT.get();
        if (current == null) return Optional.empty();
        if (requiredGeneration != NO_GENERATION
                && current.generation() != requiredGeneration) {
            return Optional.empty();
        }
        return Optional.of(current);
    }

    public static InstallResult installClient(
            long generation, cc.sighs.gravityengine.attitude.BodyAttitudeConfigSnapshot simulation
    ) {
        Objects.requireNonNull(simulation, "simulation");
        if (generation <= 0L) {
            throw new IllegalArgumentException("generation must be positive");
        }
        while (true) {
            ClientGeneration current = CLIENT.get();
            if (current != null && generation < current.generation()) {
                return InstallResult.STALE;
            }
            if (current != null && generation == current.generation()) {
                /*
                 * The same generation must map to exactly one serialized
                 * simulation value. Record equality is deliberate: no epsilon
                 * may mask a real protocol conflict.
                 */
                return current.simulation().equals(simulation)
                        ? InstallResult.DUPLICATE
                        : InstallResult.CONFLICT;
            }
            ClientGeneration next = new ClientGeneration(generation, simulation);
            if (CLIENT.compareAndSet(current, next)) return InstallResult.ACCEPTED;
        }
    }

    public static void clearClient() { CLIENT.set(null); }

    public static final long NO_GENERATION = 0L;

    public record Generation(
            long generation,
            BodyAttitudeConfigSnapshot simulation
    ) {
        public Generation {
            if (generation <= 0L) throw new IllegalArgumentException("generation must be positive");
            Objects.requireNonNull(simulation, "simulation");
        }
    }

    public record ClientGeneration(
            long generation,
            BodyAttitudeConfigSnapshot simulation
    ) {}

    public enum InstallResult { ACCEPTED, STALE, DUPLICATE, CONFLICT }
}

/** Actor-only persistence restore/bootstrap. Configuration absence preserves the seed for retry;
 * reference-aligned or suspended policy discards it. Active contracts install actor/view continuity
 * without collision queries, position changes or support-pivot placement. */
public static final class Persistence {
    public enum Result {
        RESTORED,
        BOOTSTRAPPED,
        NOT_REQUIRED,
        FAILED
    }

    private Persistence() {}

    public static Result restoreOrBootstrap(
            ServerPlayer player
    ) {
        Objects.requireNonNull(player, "player");

        BodyAttitudePersistenceSlot slot =
                player.getData(
                        GravityEngineAttachments
                                .BODY_ATTITUDE_PERSISTENCE
                );

        return restoreOrBootstrap(player, slot);
    }

    /**
     * Package-private load/restore seam shared by the public server entry.
     *
     * <p>Tests inject an explicitly staged persistence slot on a player-shaped
     * entity so the exact restore/bootstrap transaction can be exercised
     * without a full server connection bootstrap.</p>
     */
    static Result restoreOrBootstrap(Player player, BodyAttitudePersistenceSlot slot) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(slot, "slot");
        BodyAttitudeComponent component = Access.component(player);
        long logicalStep = Math.max(0L, player.tickCount);

        synchronized (component) {
            var precondition = new BodyAttitudeTransactionPrecondition(component.snapshot());
            var capture = BodyAttitudeTickIntegration.captureForLoad(player, logicalStep);
            if (capture instanceof BodyAttitudeTickIntegration.TickCapture.ConfigUnavailable) {
                return Result.FAILED;
            }

            /*
             * Loading is a lifecycle commit, not another ordinary tick. It must
             * release old ownership even if this actor already ran this tick.
             * No old mode/input/cache is changed until the actor commit succeeds.
             */
            BodyAttitudeLogicalCandidate release = null;
            if (capture instanceof BodyAttitudeTickIntegration.TickCapture.Suspended suspended) {
                var before = precondition.component();
                release = new BodyAttitudeLogicalCandidate(
                        before.state().asKinematic(), before.view(), null, suspended.decision(),
                        BodyAttitudeContinuity.INVALID, BodyAttitudeOwnership.INACTIVE,
                        BodyAttitudeComponent.NO_LOCAL_SIMULATION_STEP, false, null,
                        BodyAttitudeLogicalCandidate.Kind.SUSPENSION,
                        precondition, suspended.lookRebase());
            } else {
                var ready = (BodyAttitudeTickIntegration.TickCapture.Ready) capture;
                if (ready.input().ownership() == BodyAttitudeOwnership.INACTIVE) {
                    release = BodyAttitudeService.suspensionCandidate(
                            precondition.component(),
                            BodyAttitudeComponent.NO_LOCAL_SIMULATION_STEP,
                            BodyAttitudeDecision.suspended(BodyAttitudeSuspensionReason.INACTIVE),
                            BodyAttitudeOwnership.INACTIVE, ready.input(), precondition);
                }
            }
            if (release != null) {
                if (!BodyAttitudeTransactionCoordinator.commitLogicalOnly(player, component, release)) {
                    return Result.FAILED;
                }
                finishLoad(player, slot);
                return Result.NOT_REQUIRED;
            }

            var ready = (BodyAttitudeTickIntegration.TickCapture.Ready) capture;
            var before = precondition.component();
            if (slot.pendingLoadedSeed().isEmpty()
                    && before.state().initialized()
                    && before.view().initialized()
                    && before.continuity() == BodyAttitudeContinuity.CONTINUOUS
                    && before.ownership() == BodyAttitudeOwnership.ACTIVE
                    && before.decision().equals(ready.input().decision())) {
                // Idempotent retry of an already accepted matching special contract.
                finishLoad(player, slot);
                return Result.NOT_REQUIRED;
            }

            var seed = slot.pendingLoadedSeed().orElse(null);

            BodyAttitudeState state;
            BodyRelativeViewState view;

            if (seed != null) {
                /*
                 * Persistence carries pose plus optional L_world only. The
                 * current receiving contract decides ownership before the
                 * state is published, and the current config generation owns
                 * effective inertia.
                 *
                 * A kinematic receiver consumes any saved dynamic continuity
                 * here instead of publishing it for one tick and clearing it
                 * later.
                 */
                state = installReceivingState(
                        seed.worldFromBody(),
                        seed.angularMomentumWorld()
                                .orElse(Vec3d.ZERO),
                        ready
                );

                view = BodyRelativeViewState.fromSemantic(
                        new SemanticView(
                                seed.worldFromController()
                        ),
                        seed.worldFromBody(),
                        new AttitudeSpaceTransform.LocalLookAngles(
                                0,
                                0
                        )
                );
            } else {
                var bootstrap =
                        ready.input().bootstrap().orElse(null);

                if (bootstrap == null) {
                    return Result.FAILED;
                }

                /*
                 * No persisted L exists. If the receiving contract is dynamic,
                 * this is an explicit KINEMATIC/inactive -> DYNAMIC handoff
                 * starting at L_world = 0, not a temporary kinematic publication.
                 */
                state = installReceivingState(
                        bootstrap.worldFromBody(),
                        Vec3d.ZERO,
                        ready
                );

                view = BodyRelativeViewState.activate(
                        state.currentWorldFromBody(),
                        ready.input().bootstrapLookForward(),
                        ready.input().lookScalars()
                                .yawDegrees(),
                        ready.input().lookScalars()
                                .pitchDegrees()
                );
            }

            if (!commitTarget(
                    player,
                    component,
                    precondition,
                    state,
                    view,
                    ready,
                    logicalStep,
                    seed == null
                            ? BodyAttitudeLogicalCandidate.Kind
                              .LIFECYCLE_BOOTSTRAP
                            : BodyAttitudeLogicalCandidate.Kind
                              .PERSISTENCE_RESTORE
            )) {
                return Result.FAILED;
            }

            finishLoad(player, slot);

            return seed == null
                    ? Result.BOOTSTRAPPED
                    : Result.RESTORED;
        }
    }

    /**
     * Resolves ownership before publication for both persistence restore and
     * no-seed lifecycle bootstrap.
     *
     * <p>Dynamic entry binds the current profile inertia and either resumes
     * saved L_world or explicitly starts at rest.</p>
     */
    private static BodyAttitudeState installReceivingState(
            cc.sighs.gravityengine.math.Quatd worldFromBody,
            Vec3d angularMomentumWorld,
            BodyAttitudeTickIntegration.TickCapture.Ready ready
    ) {
        return ready.input()
                .attitudeContract()
                .dynamicAttitudeOwnership()

                ? AttitudeDynamicHandoff.installDynamic(
                worldFromBody,
                angularMomentumWorld,
                ready.input()
                        .config()
                        .elytraEffectiveAngularInertia(),
                0L,
                0L,
                true
        )

                : AttitudeDynamicHandoff.installKinematic(
                worldFromBody,
                0L,
                0L,
                true
        );
    }

    /** Called only after accepting the receiving contract, under the component lock. */
    private static void finishLoad(Player player, BodyAttitudePersistenceSlot slot) {
        cc.sighs.gravityengine.player.CharacterControlRuntime.clearMode(player);
        slot.clearPendingLoadedSeed();
    }

    private static boolean commitTarget(
            Player player,
            BodyAttitudeComponent component,
            BodyAttitudeTransactionPrecondition precondition,
            BodyAttitudeState targetState,
            BodyRelativeViewState targetView,
            BodyAttitudeTickIntegration.TickCapture.Ready ready,
            long worldGameTick,
            BodyAttitudeLogicalCandidate.Kind kind
    ) {
        /*
         * A lifecycle install performs no integration, so its evidence is a
         * held orientation and its durable momentum lives on the state alone.
         */
        AngularTrajectory trajectory = AngularTrajectory.install(
                targetState.currentWorldFromBody());

        BodyAttitudeStepResult stationaryResult =
                new BodyAttitudeStepResult(
                        targetState,
                        Vec3d.ZERO,
                        0.0D,
                        0,
                        false,
                        trajectory
                );

        BodyAttitudeLogicalCandidate logical =
                new BodyAttitudeLogicalCandidate(
                        targetState,
                        targetView,
                        stationaryResult,
                        ready.input().decision(),
                        BodyAttitudeContinuity.CONTINUOUS,
                        ready.input().ownership(),
                        BodyAttitudeComponent
                                .NO_LOCAL_SIMULATION_STEP,
                        true,
                        trajectory,
                        kind,
                        precondition,
                        null);

        return BodyAttitudeTransactionCoordinator.commitLogicalOnly(player, component, logical);
    }
}

/** Player-side access to its independently owned attitude component. */
public interface Access {
    BodyAttitudeComponent gravityengine$bodyAttitude();

    /** Pure lookup used by render/debug paths; it never creates a component. */
    @Nullable
    BodyAttitudeComponent gravityengine$peekBodyAttitude();

    static BodyAttitudeComponent component(Player player) {
        Objects.requireNonNull(player, "player");
        return ((BodyAttitudeRuntime.Access) player).gravityengine$bodyAttitude();
    }

    @Nullable
    static BodyAttitudeComponent peek(Player player) {
        Objects.requireNonNull(player, "player");
        return ((BodyAttitudeRuntime.Access) player).gravityengine$peekBodyAttitude();
    }
}

/**
 * Entity access seam for the transient client-local look preview.
 *
 * <p>The preview (raw mouse look captured since the last fixed simulation
 * step, plus any current tick input) is client-owned state; this
 * seam lets common look/movement code read it without importing client
 * runtime classes.  The client {@code LocalPlayer} mixin implements the
 * interface; every other player has no client preview and reads
 * {@link BodyAttitudeInput#NONE}.  Server look evidence is never resolved
 * through this interface: server look uses accepted body state and Vanilla
 * look carriers, without a movement-input timeline.</p>
 */
public interface Input {
    BodyAttitudeInput gravityengine$pendingLook();

    /** Safe lookup: non-local or non-client players yield no preview input. */
    static BodyAttitudeInput pendingLook(Player player) {
        Objects.requireNonNull(player, "player");
        return player instanceof BodyAttitudeRuntime.Input access
                ? access.gravityengine$pendingLook()
                : BodyAttitudeInput.NONE;
    }
}

}
