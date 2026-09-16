package cc.sighs.gravityengine.attitude.runtime;

import net.minecraft.world.entity.player.Player;

import java.util.concurrent.atomic.AtomicLong;

/** Server-process monotonic allocator for authoritative attitude streams. */
public final class BodyAttitudeStreamEpochService {
    private static final AtomicLong NEXT_STREAM_EPOCH = new AtomicLong();

    private BodyAttitudeStreamEpochService() {}

    public static long ensureServerStream(Player player) {
        if (player.level().isClientSide()) {
            throw new IllegalArgumentException("authoritative streams are server-owned");
        }
        BodyAttitudeComponent component = cc.sighs.gravityengine.attitude.runtime.BodyAttitudeRuntime.Access.component(player);
        BodyAttitudeComponent.Snapshot snapshot = component.snapshot();
        if (snapshot.authoritativeStreamOpen()
                && snapshot.authoritativeStreamEpoch()
                != BodyAttitudeComponent.NO_AUTHORITATIVE_STREAM_EPOCH) {
            return snapshot.authoritativeStreamEpoch();
        }
        long epoch = allocateStreamEpoch();
        BodyAttitudeTransactionCoordinator.openAuthoritativeStream(
                player, component, epoch);
        return epoch;
    }

    /**
     * Opens an unconditionally fresh server stream epoch while preserving the
     * already accepted actor/controller state.
     *
     * <p>This is the load/replacement-player chronology boundary. Unlike
     * {@link #beginNewServerStream(Player)}, it deliberately does not call
     * invalidateContinuity(): physical load restoration has already established
     * the accepted state and this method only starts new network chronology.</p>
     */
    public static long beginFreshServerStreamPreservingContinuity(
            Player player
    ) {
        if (player.level().isClientSide()) {
            throw new IllegalArgumentException(
                    "authoritative streams are server-owned"
            );
        }

        BodyAttitudeComponent component =
                cc.sighs.gravityengine.attitude.runtime.BodyAttitudeRuntime.Access.component(player);

        long epoch = allocateStreamEpoch();

        BodyAttitudeTransactionCoordinator
                .openAuthoritativeStream(
                        player,
                        component,
                        epoch
                );

        return epoch;
    }

    /** Process-monotonic allocation also covers replacement Player instances. */
    static long allocateStreamEpoch() {
        return NEXT_STREAM_EPOCH.updateAndGet(previous ->
                previous == Long.MAX_VALUE
                        ? failOverflow()
                        : previous + 1L);
    }

    public static long beginNewServerStream(Player player) {
        if (player.level().isClientSide()) {
            throw new IllegalArgumentException("authoritative streams are server-owned");
        }
        BodyAttitudeComponent component = cc.sighs.gravityengine.attitude.runtime.BodyAttitudeRuntime.Access.component(player);
        BodyAttitudeTransactionCoordinator.invalidateContinuity(
                player, component);
        return ensureServerStream(player);
    }

    private static long failOverflow() {
        throw new IllegalStateException("body attitude stream epoch overflow");
    }
}
