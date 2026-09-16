package cc.sighs.gravityengine.attitude.persistence;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.attachment.IAttachmentHolder;
import net.neoforged.neoforge.attachment.IAttachmentSerializer;
import org.joml.Quaterniond;

import java.util.Optional;

/**
 * Versioned NeoForge attachment serializer for the persistence seed.
 *
 * <p>Decode is representation recovery only: it stages an immutable pending
 * seed and never publishes {@code BodyAttitudeState}, never installs character geometry and
 * never opens an authoritative stream. Malformed persistence data fails
 * closed to an empty seed rather than partially initializing attitude.</p>
 */
public final class BodyAttitudePersistenceSerializer
        implements IAttachmentSerializer<
                CompoundTag,
                BodyAttitudePersistenceSlot
        > {

    public static final BodyAttitudePersistenceSerializer INSTANCE =
            new BodyAttitudePersistenceSerializer();

    private static final int FORMAT_VERSION = 4;

    private static final String TAG_VERSION = "Version";

    private static final String TAG_QX = "BodyQX";
    private static final String TAG_QY = "BodyQY";
    private static final String TAG_QZ = "BodyQZ";
    private static final String TAG_QW = "BodyQW";

    private static final String TAG_WX = "AngularVelocityX";
    private static final String TAG_WY = "AngularVelocityY";
    private static final String TAG_WZ = "AngularVelocityZ";

    private static final String TAG_CONTROLLER_X = "ControllerQX";
    private static final String TAG_CONTROLLER_Y = "ControllerQY";
    private static final String TAG_CONTROLLER_Z = "ControllerQZ";
    private static final String TAG_CONTROLLER_W = "ControllerQW";
    private static final String TAG_ELYTRA = "ElytraDynamics";



    private BodyAttitudePersistenceSerializer() {}

    @Override
    public BodyAttitudePersistenceSlot read(
            IAttachmentHolder holder,
            CompoundTag tag,
            HolderLookup.Provider registries
    ) {
        BodyAttitudePersistenceSlot slot =
                BodyAttitudePersistenceSlot.forHolder(holder);

        decode(tag).ifPresent(slot::stageLoadedSeed);

        return slot;
    }

    @Override
    public CompoundTag write(
            BodyAttitudePersistenceSlot slot,
            HolderLookup.Provider registries
    ) {
        return slot.snapshotForSave()
                .map(BodyAttitudePersistenceSerializer::encode)
                .orElseGet(CompoundTag::new);
    }

    static CompoundTag encode(
            BodyAttitudePersistentSeed seed
    ) {
        CompoundTag tag = new CompoundTag();

        tag.putInt(TAG_VERSION, FORMAT_VERSION);

        Quaterniond q = seed.worldFromBody();
        tag.putDouble(TAG_QX, q.x);
        tag.putDouble(TAG_QY, q.y);
        tag.putDouble(TAG_QZ, q.z);
        tag.putDouble(TAG_QW, q.w);

        Quaterniond controller = seed.worldFromController();
        tag.putDouble(TAG_CONTROLLER_X, controller.x); tag.putDouble(TAG_CONTROLLER_Y, controller.y);
        tag.putDouble(TAG_CONTROLLER_Z, controller.z); tag.putDouble(TAG_CONTROLLER_W, controller.w);
        seed.elytraDynamics().ifPresent(dynamics -> {
            CompoundTag elytra = new CompoundTag();
            Vec3 omega = dynamics.angularVelocityWorld();
            elytra.putDouble(TAG_WX, omega.x); elytra.putDouble(TAG_WY, omega.y); elytra.putDouble(TAG_WZ, omega.z);
            tag.put(TAG_ELYTRA, elytra);
        });

        return tag;
    }

    static Optional<BodyAttitudePersistentSeed> decode(
            CompoundTag tag
    ) {
        if (!tag.contains(TAG_VERSION, Tag.TAG_INT)
                || tag.getInt(TAG_VERSION) != FORMAT_VERSION) {
            return Optional.empty();
        }

        if (!hasDouble(tag, TAG_QX)
                || !hasDouble(tag, TAG_QY)
                || !hasDouble(tag, TAG_QZ)
                || !hasDouble(tag, TAG_QW)
                || !hasDouble(tag, TAG_CONTROLLER_X) || !hasDouble(tag, TAG_CONTROLLER_Y)
                || !hasDouble(tag, TAG_CONTROLLER_Z) || !hasDouble(tag, TAG_CONTROLLER_W)) {
            return Optional.empty();
        }

        var keys = new java.util.HashSet<>(java.util.Set.of(TAG_VERSION, TAG_QX, TAG_QY, TAG_QZ, TAG_QW,
                TAG_CONTROLLER_X, TAG_CONTROLLER_Y, TAG_CONTROLLER_Z, TAG_CONTROLLER_W));
        if (tag.contains(TAG_ELYTRA)) keys.add(TAG_ELYTRA);
        if (!tag.getAllKeys().equals(keys)) return Optional.empty();
        try {
            Optional<BodyAttitudePersistentSeed.ElytraDynamicsSeed> dynamics = Optional.empty();
            if (tag.contains(TAG_ELYTRA)) {
                if (!tag.contains(TAG_ELYTRA, Tag.TAG_COMPOUND)) return Optional.empty();
                CompoundTag elytra = tag.getCompound(TAG_ELYTRA);
                if (!elytra.getAllKeys().equals(java.util.Set.of(TAG_WX, TAG_WY, TAG_WZ))
                        || !hasDouble(elytra, TAG_WX) || !hasDouble(elytra, TAG_WY) || !hasDouble(elytra, TAG_WZ))
                    return Optional.empty();
                dynamics = Optional.of(new BodyAttitudePersistentSeed.ElytraDynamicsSeed(
                        new Vec3(elytra.getDouble(TAG_WX), elytra.getDouble(TAG_WY), elytra.getDouble(TAG_WZ))));
            }
            return Optional.of(new BodyAttitudePersistentSeed(
                    new Quaterniond(tag.getDouble(TAG_QX), tag.getDouble(TAG_QY), tag.getDouble(TAG_QZ), tag.getDouble(TAG_QW)),
                    new Quaterniond(tag.getDouble(TAG_CONTROLLER_X), tag.getDouble(TAG_CONTROLLER_Y),
                            tag.getDouble(TAG_CONTROLLER_Z), tag.getDouble(TAG_CONTROLLER_W)), dynamics));
        } catch (RuntimeException malformed) {
            return Optional.empty();
        }
    }

    private static boolean hasDouble(
            CompoundTag tag,
            String key
    ) {
        return tag.contains(key, Tag.TAG_DOUBLE);
    }
}
