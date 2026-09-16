package cc.sighs.gravityengine.gravity.field;

import cc.sighs.gravityengine.math.geometry.Aabb3d;
import org.joml.Vector3dc;

import java.util.Optional;

/**
 * Spatial extent used by the field index before an evaluator is invoked.
 *
 * <p>Finite volumes report their world-axis-aligned bounds so the index can
 * bucket them; an infinite volume reports {@link Optional#empty()} and is
 * stored in the global (non-bucketed) candidate set.</p>
 */
public interface GravityInfluenceVolume {

    boolean contains(Vector3dc position);

    /**
     * Finite world bounds for bucketing, or empty for an infinite field.
     * Every finite volume must be contained by its returned bounds.
     */
    Optional<Aabb3d> finiteBounds();
}
