package cc.sighs.gravityengine.api.field;

import cc.sighs.gravityengine.api.math.Vec3d;
import java.util.Objects;

/** One active source's immutable, world-space acceleration for a query.
 * IDs are namespaced strings, globally unique within a Level. Source type and
 * numeric X/Y/Z precede the ID in deterministic composition order; named
 * sources use zero coordinates. Revision is source-owned, not coverage. */
public record GravityContribution(String id, String sourceType, int x, int y, int z,
                                  Vec3d acceleration, GravityFieldCompositionMode mode,
                                  long revision) {
    private static final java.util.regex.Pattern ID_PATTERN = java.util.regex.Pattern.compile("[a-z0-9_.-]+:[a-z0-9/._-]+");

    public GravityContribution {
        requireId(id);
        requireId(sourceType);
        Objects.requireNonNull(acceleration, "acceleration");
        Objects.requireNonNull(mode, "mode");
        if (!acceleration.isFinite() || revision < 0) {
            throw new IllegalArgumentException("finite acceleration and non-negative revision required");
        }
    }

    private static void requireId(String id) {
        Objects.requireNonNull(id, "id");
        if (!ID_PATTERN.matcher(id).matches()) {
            throw new IllegalArgumentException("invalid namespaced identity: " + id);
        }
    }
}
