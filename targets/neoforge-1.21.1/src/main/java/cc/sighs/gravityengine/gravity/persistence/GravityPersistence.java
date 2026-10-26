package cc.sighs.gravityengine.gravity.persistence;

import cc.sighs.gravityengine.GravityEngineAttachments;
import com.mojang.logging.LogUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.attachment.IAttachmentHolder;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.Objects;
import java.util.Optional;

/**
 * Materializes durable attachments and bootstraps loads without an attachment.
 *
 */
public final class GravityPersistence {
    private static final Logger LOGGER = LogUtils.getLogger();

    private GravityPersistence() {}

    /**
     * Materializes the durable attachment on an entity.
     *
     * <p>NeoForge serializes only attachment instances present on the holder,
     * so this must be called before the next save of a durable non-empty
     * gravity state. The slot itself snapshots live state at write time.</p>
     */
    public static GravityPersistenceSlot materialize(Entity entity) {
        Objects.requireNonNull(entity, "entity");
        return entity.getData(GravityEngineAttachments.GRAVITY_PERSISTENCE);
    }

    @Nullable
    static CompoundTag encodeForSave(GravityPersistenceSlot slot) {
        Objects.requireNonNull(slot, "slot");
        Optional<cc.sighs.gravityengine.gravity.model.GravityEntityState.StoredAssignment>
                live = slot.snapshotForSave();
        if (live.isPresent()) {
            return GravityPersistenceCodec.encode(live.get()).orElse(null);
        }
        /*
         * An uninterpretable record is preserved verbatim until a later
         * authoritative assignment supersedes it. Malformed/unsupported data
         * must never be silently rewritten as default gravity.
         */
        return slot.preservedUndecodableRecord();
    }

    /**
     * Completes native attachment loading. An existing slot, including one
     * retained by NeoForge's merge semantics, remains authoritative. Only an
     * entity without a slot needs the default destination-world bootstrap.
     */
    public static void initializeAfterLoad(Entity entity) {
        Objects.requireNonNull(entity, "entity");
        if (entity.getExistingDataOrNull(
                GravityEngineAttachments.GRAVITY_PERSISTENCE) == null) {
            cc.sighs.gravityengine.gravity.minecraft.access.GravityEntityAccess
                    .cast(entity).gravityengine$gravityComponent().state().resetForLoad();
        }
    }

    static void diagnose(
            IAttachmentHolder holder,
            GravityPersistenceCodec.DecodeResult decoded
    ) {
        switch (decoded.status()) {
            case ABSENT, CURRENT -> {
                /* Valid or intentionally empty. */
            }
            case MALFORMED, UNSUPPORTED_LEGACY, UNSUPPORTED_FUTURE -> {
                String owner = holder instanceof Entity entity
                        ? entity.getUUID().toString()
                        : String.valueOf(holder);
                LOGGER.warn(
                        "GravityEngine persistence record for entity {} was "
                                + "rejected as {} and was not applied",
                        owner,
                        decoded.status()
                );
            }
        }
    }
}
