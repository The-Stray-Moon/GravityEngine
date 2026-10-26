package cc.sighs.gravityengine.api.field;

import cc.sighs.gravityengine.api.math.Vec3d;
import java.util.Optional;

/**
 * Spatial extent used by the field index before an evaluator is invoked.
 *
 * <p>Finite volumes report their world-axis-aligned bounds so the index can
 * bucket them; an infinite volume reports {@link Optional#empty()} and is
 * stored in the global (non-bucketed) candidate set.</p>
 *
 * <p>Implementations use only supported API types: this interface,
 * {@link Vec3d}, and {@link GravityFieldBounds}.</p>
 */
public interface GravityInfluenceVolume {

    boolean contains(Vec3d position);

    /**
     * Finite world bounds for bucketing, or empty for an infinite field.
     * Every finite volume must be contained by its returned bounds; the
     * engine converts the returned value into its internal bounds before
     * spatial indexing.
     */
    Optional<GravityFieldBounds> finiteBounds();
}
