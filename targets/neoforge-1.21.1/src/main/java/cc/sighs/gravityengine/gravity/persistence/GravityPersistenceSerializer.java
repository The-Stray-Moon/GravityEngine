package cc.sighs.gravityengine.gravity.persistence;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.neoforge.attachment.IAttachmentHolder;
import net.neoforged.neoforge.attachment.IAttachmentSerializer;
import org.jetbrains.annotations.Nullable;

/**
 * The single current-writer serializer for durable gravity state.
 * The attachment is not synchronized: durable persistence and live
 * assignment/application packets have separate contracts.
 */
public final class GravityPersistenceSerializer
        implements IAttachmentSerializer<CompoundTag, GravityPersistenceSlot> {

    public static final GravityPersistenceSerializer INSTANCE =
            new GravityPersistenceSerializer();

    private GravityPersistenceSerializer() {}

    @Override
    public GravityPersistenceSlot read(
            IAttachmentHolder holder,
            CompoundTag tag,
            HolderLookup.Provider registries
    ) {
        GravityPersistenceSlot slot =
                GravityPersistenceSlot.forHolder(holder);
        GravityPersistenceCodec.DecodeResult decoded =
                GravityPersistenceCodec.decodeAttachmentRecord(tag);
        slot.applyDecoded(decoded, tag);
        GravityPersistence.diagnose(holder, decoded);
        return slot;
    }

    @Override
    public @Nullable CompoundTag write(
            GravityPersistenceSlot slot,
            HolderLookup.Provider registries
    ) {
        return GravityPersistence.encodeForSave(slot);
    }
}
