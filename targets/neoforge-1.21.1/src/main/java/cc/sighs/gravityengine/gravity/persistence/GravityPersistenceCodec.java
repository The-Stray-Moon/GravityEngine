package cc.sighs.gravityengine.gravity.persistence;

import cc.sighs.gravityengine.gravity.model.GravityAuthorityMode;
import cc.sighs.gravityengine.gravity.model.GravityEntityState;
import net.minecraft.nbt.CompoundTag;

import java.util.Objects;
import java.util.Optional;

/**
 * Current-format gravity attachment decoding/encoding boundary.
 * Older and future versions are rejected with a diagnostic status.
 */
public final class GravityPersistenceCodec {
    public enum DecodeStatus {
        ABSENT,
        CURRENT,
        MALFORMED,
        UNSUPPORTED_LEGACY,
        UNSUPPORTED_FUTURE
    }

    public record DecodeResult(
            DecodeStatus status,
            Optional<GravityEntityState.StoredAssignment> assignment
    ) {
        public DecodeResult {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(assignment, "assignment");
        }

        static DecodeResult of(DecodeStatus status) {
            return new DecodeResult(status, Optional.empty());
        }
    }

    static final String NBT_VERSION = "FormatVersion";
    static final String NBT_DOWN_X = "DownX";
    static final String NBT_DOWN_Y = "DownY";
    static final String NBT_DOWN_Z = "DownZ";
    static final String NBT_STRENGTH = "Strength";
    static final String NBT_ASSIGNMENT_REVISION = "AssignmentRev";
    static final String NBT_AUTHORITY = "Authority";
    static final String NBT_FIELD_CONTINUITY = "FieldContinuity";

    public static final int CURRENT_FORMAT_VERSION = 5;

    private GravityPersistenceCodec() {}

    /**
     * Decodes the current attachment payload.
     *
     * <p>An empty compound is {@link DecodeStatus#ABSENT}: NeoForge never
     * invokes the serializer for a value that was not written, and the
     * serializer itself returns {@code null} for an intentionally empty
     * durable record.</p>
     */
    public static DecodeResult decodeAttachmentRecord(CompoundTag record) {
        Objects.requireNonNull(record, "record");
        if (record.isEmpty()) return DecodeResult.of(DecodeStatus.ABSENT);
        if (!record.contains(NBT_VERSION, CompoundTag.TAG_INT)) {
            return DecodeResult.of(DecodeStatus.MALFORMED);
        }
        int version = record.getInt(NBT_VERSION);
        if (version > CURRENT_FORMAT_VERSION) {
            return DecodeResult.of(DecodeStatus.UNSUPPORTED_FUTURE);
        }
        if (version != CURRENT_FORMAT_VERSION) {
            return DecodeResult.of(
                    version > 0
                            ? DecodeStatus.UNSUPPORTED_LEGACY
                            : DecodeStatus.MALFORMED
            );
        }
        Optional<GravityEntityState.StoredAssignment> decoded =
                decodeCurrentRecord(record);
        return new DecodeResult(
                decoded.isPresent() ? DecodeStatus.CURRENT : DecodeStatus.MALFORMED,
                decoded
        );
    }

    static Optional<CompoundTag> encode(
            GravityEntityState.StoredAssignment assignment
    ) {
        Objects.requireNonNull(assignment, "assignment");
        if (assignment.state().isDefault()
                && assignment.authority() == GravityAuthorityMode.FIELD
                && assignment.fieldContinuity()
                == GravityEntityState.FieldContinuity.NONE
                && assignment.revision() == 0L) {
            /*
             * An ordinary absent default FIELD owns no durable fact. Omit the
             * record entirely; numeric equality with default gravity never
             * removes an explicit continuity seed.
             */
            return Optional.empty();
        }
        CompoundTag record = new CompoundTag();
        record.putInt(NBT_VERSION, CURRENT_FORMAT_VERSION);
        record.putDouble(NBT_DOWN_X, assignment.state().down().x());
        record.putDouble(NBT_DOWN_Y, assignment.state().down().y());
        record.putDouble(NBT_DOWN_Z, assignment.state().down().z());
        record.putDouble(NBT_STRENGTH, assignment.state().strength());
        record.putLong(NBT_ASSIGNMENT_REVISION, assignment.revision());
        record.putInt(NBT_AUTHORITY, assignment.authority().networkId());
        record.putBoolean(
                NBT_FIELD_CONTINUITY,
                assignment.fieldContinuity()
                        == GravityEntityState.FieldContinuity.SEED
        );
        return Optional.of(record);
    }

    static Optional<GravityEntityState.StoredAssignment> decodeCurrentRecord(
            CompoundTag record
    ) {
        Objects.requireNonNull(record, "record");

        if (!record.contains(NBT_VERSION, CompoundTag.TAG_INT)
                || record.getInt(NBT_VERSION) != CURRENT_FORMAT_VERSION) {
            return Optional.empty();
        }

        if (!record.contains(NBT_DOWN_X, CompoundTag.TAG_DOUBLE)
                || !record.contains(NBT_DOWN_Y, CompoundTag.TAG_DOUBLE)
                || !record.contains(NBT_DOWN_Z, CompoundTag.TAG_DOUBLE)
                || !record.contains(NBT_STRENGTH, CompoundTag.TAG_DOUBLE)
                || !record.contains(
                NBT_ASSIGNMENT_REVISION,
                CompoundTag.TAG_LONG
        )
                || !record.contains(NBT_AUTHORITY, CompoundTag.TAG_INT)
                || !record.contains(NBT_FIELD_CONTINUITY, CompoundTag.TAG_BYTE)) {
            return Optional.empty();
        }

        return GravityEntityState.decodeStoredAssignment(
                record.getDouble(NBT_DOWN_X),
                record.getDouble(NBT_DOWN_Y),
                record.getDouble(NBT_DOWN_Z),
                record.getDouble(NBT_STRENGTH),
                record.getLong(NBT_ASSIGNMENT_REVISION),
                record.getInt(NBT_AUTHORITY),
                record.getBoolean(NBT_FIELD_CONTINUITY)
        );
    }

}
