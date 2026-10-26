package cc.sighs.gravityengine.gravity.field;

import cc.sighs.gravityengine.api.field.GravityFieldBounds;
import cc.sighs.gravityengine.api.field.GravityInfluenceVolume;
import cc.sighs.gravityengine.api.math.Vec3d;
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
    public boolean contains(Vec3d position) {
        return true;
    }

    @Override
    public Optional<GravityFieldBounds> finiteBounds() {
        return Optional.empty();
    }
}
