package cc.sighs.gravityengine.gravity.field;

import cc.sighs.gravityengine.math.geometry.Aabb3d;
import org.joml.Vector3dc;

import java.util.Optional;

/**
 * Infinite influence volume (uniform/global fields).  The index stores these
 * in the global candidate set and never enumerates cells for them.
 */
public final class InfiniteInfluenceVolume
        implements GravityInfluenceVolume {

    public static final InfiniteInfluenceVolume INSTANCE =
            new InfiniteInfluenceVolume();

    private InfiniteInfluenceVolume() {}

    @Override
    public boolean contains(Vector3dc position) {
        return true;
    }

    @Override
    public Optional<Aabb3d> finiteBounds() {
        return Optional.empty();
    }
}
