package cc.sighs.gravityengine.attitude.persistence;

import cc.sighs.gravityengine.attitude.runtime.BodyAttitudeComponent;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.attachment.IAttachmentHolder;

import javax.annotation.Nullable;
import java.lang.ref.WeakReference;
import java.util.Objects;
import java.util.Optional;

/**
 * Player-owned attachment slot for the durable BodyAttitude seed.
 *
 * <p>The attachment value is NOT live attitude authority and is not
 * synchronized. It stores no second continuously advancing Qbody copy; it
 * stages the immutable one-shot seed decoded or captured before removal so the
 * load bootstrap owner can consume it after Vanilla has finished restoring
 * its own carriers.</p>
 */
public final class BodyAttitudePersistenceSlot {
    private final WeakReference<Player> owner;

    @Nullable
    private BodyAttitudePersistentSeed pendingLoadedSeed;

    private BodyAttitudePersistenceSlot(Player owner) {
        this.owner = new WeakReference<>(
                Objects.requireNonNull(owner, "owner")
        );
    }

    public static BodyAttitudePersistenceSlot forHolder(
            IAttachmentHolder holder
    ) {
        if (!(holder instanceof Player player)) {
            throw new IllegalArgumentException(
                    "BodyAttitude persistence attachment requires Player holder: "
                            + holder
            );
        }

        return new BodyAttitudePersistenceSlot(player);
    }

    /**
     * A pending restoration/removal seed remains the durable source until
     * successful restore or explicit discard. Otherwise capture the live actor.
     */
    public Optional<BodyAttitudePersistentSeed> snapshotForSave() {
        if (pendingLoadedSeed != null) return Optional.of(pendingLoadedSeed);
        Player player = owner.get();

        if (player != null) {
            BodyAttitudeComponent component =
                    cc.sighs.gravityengine.attitude.runtime.BodyAttitudeRuntime.Access.peek(player);

            if (component != null) {
                Optional<BodyAttitudePersistentSeed> live =
                        BodyAttitudePersistentSeed.capture(
                                component.snapshot()
                        );

                if (live.isPresent()) {
                    return live;
                }
            }
        }

        return Optional.empty();
    }

    public void stageLoadedSeed(
            BodyAttitudePersistentSeed seed
    ) {
        this.pendingLoadedSeed =
                Objects.requireNonNull(seed, "seed");
    }

    public Optional<BodyAttitudePersistentSeed> pendingLoadedSeed() {
        return Optional.ofNullable(pendingLoadedSeed);
    }

    /** Detach the save/clone source before live-state teardown, including End credits. */
    public void stageBeforeRemoval() {
        pendingLoadedSeed = snapshotForSave().orElse(null);
    }

    public void clearPendingLoadedSeed() {
        pendingLoadedSeed = null;
    }
}
