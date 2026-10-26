package cc.sighs.gravityengine.gravity.persistence;

import cc.sighs.gravityengine.gravity.component.EntityGravityComponent;
import cc.sighs.gravityengine.gravity.minecraft.access.GravityEntityAccess;
import cc.sighs.gravityengine.gravity.model.GravityAuthorityMode;
import cc.sighs.gravityengine.gravity.model.GravityEntityState;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.attachment.IAttachmentHolder;

import java.lang.ref.WeakReference;
import java.util.Objects;
import java.util.Optional;

/**
 * Durable gravity attachment value for one entity.
 *
 * <p>The slot is a thin adapter over the entity's live
 * {@link GravityEntityState}: it is deliberately not a second copy of live
 * state. On decode it installs the durable record into the live state, and on
 * save it snapshots exactly the durable tuple. Rejected records are diagnosed
 * at the read boundary and discarded; only current state is saved.</p>
 */
public final class GravityPersistenceSlot {
    private final WeakReference<Entity> owner;
    private GravityPersistenceCodec.DecodeStatus decodeStatus =
            GravityPersistenceCodec.DecodeStatus.ABSENT;

    private GravityPersistenceSlot(Entity owner) {
        this.owner = new WeakReference<>(Objects.requireNonNull(owner, "owner"));
    }

    public static GravityPersistenceSlot forHolder(IAttachmentHolder holder) {
        if (!(holder instanceof Entity entity)) {
            throw new IllegalArgumentException(
                    "Gravity persistence attachment requires an Entity holder: "
                            + holder
            );
        }
        return new GravityPersistenceSlot(entity);
    }

    /** Diagnostic outcome of the record that produced this slot. */
    public GravityPersistenceCodec.DecodeStatus decodeStatus() {
        return decodeStatus;
    }

    /**
     * Installs one decoded record into the live state.
     *
     * <p>Called by the serializer during entity load. A rejected record resets
     * derived state for bootstrap. Its diagnostic status is retained, but the
     * rejected payload is never stored or written back.</p>
     */
    public void applyDecoded(
            GravityPersistenceCodec.DecodeResult result
    ) {
        Objects.requireNonNull(result, "result");
        this.decodeStatus = result.status();
        Entity entity = owner.get();
        if (entity == null) return;
        GravityEntityState state = component(entity).state();
        /*
         * A load discards every derived value. Even an absent or malformed
         * durable record still requires a fresh destination-world bootstrap.
         */
        state.resetForLoad();
        if (result.status() == GravityPersistenceCodec.DecodeStatus.CURRENT) {
            result.assignment().ifPresent(state::restoreDurableAssignment);
        }
    }

    /**
     * The durable tuple to persist, or empty when the entity owns no durable
     * gravity fact.
     */
    public Optional<GravityEntityState.StoredAssignment> snapshotForSave() {
        Entity entity = owner.get();
        if (entity == null) return Optional.empty();
        GravityEntityState state = component(entity).state();
        if (state.assignedState().isDefault()
                && state.assignedAuthority() == GravityAuthorityMode.FIELD
                && state.durableFieldContinuity()
                == GravityEntityState.FieldContinuity.NONE
                && state.assignmentRevision() == 0L) {
            return Optional.empty();
        }
        return Optional.of(
                new GravityEntityState.StoredAssignment(
                        state.assignedState(),
                        state.assignedAuthority(),
                        state.assignmentRevision(),
                        state.durableFieldContinuity()
                )
        );
    }

    private static EntityGravityComponent component(Entity entity) {
        return GravityEntityAccess.cast(entity).gravityengine$gravityComponent();
    }
}
