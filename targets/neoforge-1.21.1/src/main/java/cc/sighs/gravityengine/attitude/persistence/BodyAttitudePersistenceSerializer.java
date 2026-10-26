package cc.sighs.gravityengine.attitude.persistence;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.math.Quatd;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.DataResult;
import net.minecraft.world.entity.player.Player;
import org.slf4j.Logger;
import org.jetbrains.annotations.Nullable;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.neoforged.neoforge.attachment.IAttachmentHolder;
import net.neoforged.neoforge.attachment.IAttachmentSerializer;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Versioned NeoForge attachment serializer for the persistence seed.
 *
 * <p>Format 6 stores dynamic continuity as {@code L_world} only. Effective
 * inertia is profile/config-owned and is therefore rebound by the receiving
 * restore contract instead of being duplicated on disk. Only format 6 is supported.</p>
 */
public final class BodyAttitudePersistenceSerializer
        implements IAttachmentSerializer<
                CompoundTag,
                BodyAttitudePersistenceSlot
        > {

    public static final BodyAttitudePersistenceSerializer INSTANCE =
            new BodyAttitudePersistenceSerializer();

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final int FORMAT_VERSION = 6;

    private static final String TAG_VERSION = "Version";

    private static final String TAG_QX = "BodyQX";
    private static final String TAG_QY = "BodyQY";
    private static final String TAG_QZ = "BodyQZ";
    private static final String TAG_QW = "BodyQW";

    private static final String TAG_CONTROLLER_X = "ControllerQX";
    private static final String TAG_CONTROLLER_Y = "ControllerQY";
    private static final String TAG_CONTROLLER_Z = "ControllerQZ";
    private static final String TAG_CONTROLLER_W = "ControllerQW";

    private static final String TAG_ANGULAR_DYNAMICS =
            "AngularDynamics";

    private static final String TAG_MOMENTUM_X =
            "AngularMomentumX";
    private static final String TAG_MOMENTUM_Y =
            "AngularMomentumY";
    private static final String TAG_MOMENTUM_Z =
            "AngularMomentumZ";

    private BodyAttitudePersistenceSerializer() {}

    @Override
    public BodyAttitudePersistenceSlot read(
            IAttachmentHolder holder,
            CompoundTag tag,
            HolderLookup.Provider registries
    ) {
        BodyAttitudePersistenceSlot slot =
                BodyAttitudePersistenceSlot.forHolder(holder);

        DataResult<BodyAttitudePersistentSeed> decoded = decode(tag);
        decoded.error().ifPresent(error -> LOGGER.warn(
                "Rejected gravityengine:body_attitude_persistence for entity {}: {}; "
                        + "discarding entire seed, using normal no-seed bootstrap",
                ((Player) holder).getUUID(), error.message()));
        // result(), never resultOrPartial(): only a fully valid seed may be staged.
        decoded.result().ifPresent(slot::stageLoadedSeed);
        return slot;
    }

    @Override
    public @Nullable CompoundTag write(
            BodyAttitudePersistenceSlot slot,
            HolderLookup.Provider registries
    ) {
        return slot.snapshotForSave()
                .map(BodyAttitudePersistenceSerializer::encode)
                .orElse(null);
    }

    static CompoundTag encode(
            BodyAttitudePersistentSeed seed
    ) {
        CompoundTag tag = new CompoundTag();

        tag.putInt(TAG_VERSION, FORMAT_VERSION);

        Quatd q = seed.worldFromBody();
        tag.putDouble(TAG_QX, q.x());
        tag.putDouble(TAG_QY, q.y());
        tag.putDouble(TAG_QZ, q.z());
        tag.putDouble(TAG_QW, q.w());

        Quatd controller = seed.worldFromController();
        tag.putDouble(TAG_CONTROLLER_X, controller.x());
        tag.putDouble(TAG_CONTROLLER_Y, controller.y());
        tag.putDouble(TAG_CONTROLLER_Z, controller.z());
        tag.putDouble(TAG_CONTROLLER_W, controller.w());

        seed.angularMomentumWorld().ifPresent(momentum -> {
            CompoundTag angular = new CompoundTag();

            angular.putDouble(
                    TAG_MOMENTUM_X,
                    momentum.x()
            );
            angular.putDouble(
                    TAG_MOMENTUM_Y,
                    momentum.y()
            );
            angular.putDouble(
                    TAG_MOMENTUM_Z,
                    momentum.z()
            );

            tag.put(
                    TAG_ANGULAR_DYNAMICS,
                    angular
            );
        });

        return tag;
    }

    /**
     * Decodes a present record. Absence is represented by no attachment key, not
     * an empty compound. Invalid records are diagnosed by read and reset to an
     * empty slot; the existing load bootstrap owns reconstruction of live state.
     */
    static DataResult<BodyAttitudePersistentSeed> decode(CompoundTag tag) {
        try {
            requireType(tag, TAG_VERSION, Tag.TAG_INT, TAG_VERSION);
            int version = tag.getInt(TAG_VERSION);
            if (version != FORMAT_VERSION) {
                return DataResult.error(() -> "Version: unsupported format " + version);
            }
            Set<String> rootKeys = new HashSet<>(Set.of(TAG_VERSION,
                    TAG_QX, TAG_QY, TAG_QZ, TAG_QW,
                    TAG_CONTROLLER_X, TAG_CONTROLLER_Y, TAG_CONTROLLER_Z, TAG_CONTROLLER_W));
            if (tag.contains(TAG_ANGULAR_DYNAMICS)) rootKeys.add(TAG_ANGULAR_DYNAMICS);

            Quatd body = new Quatd(
                    finiteDouble(tag, TAG_QX, TAG_QX), finiteDouble(tag, TAG_QY, TAG_QY),
                    finiteDouble(tag, TAG_QZ, TAG_QZ), finiteDouble(tag, TAG_QW, TAG_QW));
            Quatd controller = new Quatd(
                    finiteDouble(tag, TAG_CONTROLLER_X, TAG_CONTROLLER_X),
                    finiteDouble(tag, TAG_CONTROLLER_Y, TAG_CONTROLLER_Y),
                    finiteDouble(tag, TAG_CONTROLLER_Z, TAG_CONTROLLER_Z),
                    finiteDouble(tag, TAG_CONTROLLER_W, TAG_CONTROLLER_W));
            requireKeys(tag, rootKeys, "record");

            Optional<Vec3d> momentum = Optional.empty();
            if (tag.contains(TAG_ANGULAR_DYNAMICS)) {
                requireType(tag, TAG_ANGULAR_DYNAMICS, Tag.TAG_COMPOUND, TAG_ANGULAR_DYNAMICS);
                CompoundTag angular = tag.getCompound(TAG_ANGULAR_DYNAMICS);
                momentum = Optional.of(new Vec3d(
                        finiteDouble(angular, TAG_MOMENTUM_X, "AngularDynamics." + TAG_MOMENTUM_X),
                        finiteDouble(angular, TAG_MOMENTUM_Y, "AngularDynamics." + TAG_MOMENTUM_Y),
                        finiteDouble(angular, TAG_MOMENTUM_Z, "AngularDynamics." + TAG_MOMENTUM_Z)));
                Set<String> angularKeys = new HashSet<>(Set.of(
                        TAG_MOMENTUM_X, TAG_MOMENTUM_Y, TAG_MOMENTUM_Z));
                requireKeys(angular, angularKeys, TAG_ANGULAR_DYNAMICS);
            }
            // Keep domain normalization/degeneracy validation in the immutable seed.
            return DataResult.success(new BodyAttitudePersistentSeed(body, controller, momentum));
        } catch (IllegalArgumentException malformed) {
            return DataResult.error(() -> "record (worldFromBody=BodyQX/Y/Z/W, "
                    + "worldFromController=ControllerQX/Y/Z/W, angularMomentumWorld=AngularDynamics): "
                    + malformed.getMessage());
        }
        // Unexpected programming failures propagate to NeoForge's attachment
        // error boundary, rather than being silently treated as missing data.
    }

    private static void requireType(CompoundTag tag, String key, int type, String path) {
        if (!tag.contains(key, type)) {
            throw new IllegalArgumentException(path + ": missing or wrong NBT type (expected " + type + ")");
        }
    }

    private static double finiteDouble(CompoundTag tag, String key, String path) {
        requireType(tag, key, Tag.TAG_DOUBLE, path);
        double value = tag.getDouble(key);
        if (!Double.isFinite(value)) throw new IllegalArgumentException(path + ": must be finite");
        return value;
    }

    private static void requireKeys(CompoundTag tag, Set<String> expected, String path) {
        if (!tag.getAllKeys().equals(expected)) {
            Set<String> unexpected = new java.util.TreeSet<>(tag.getAllKeys());
            unexpected.removeAll(expected);
            throw new IllegalArgumentException(path + ": unexpected fields " + unexpected);
        }
    }
}
