package cc.sighs.gravityengine.math.geometry;

import java.util.Objects;

/** Allocation-controlled derived OBB geometry. */
public final class ObbMath {
    private ObbMath() {}

    public static MutableAabb3d enclosingAabb(Obb3d box, MutableAabb3d dest) {
        Objects.requireNonNull(box, "box");
        Objects.requireNonNull(dest, "dest");
        double extentX = box.worldExtentX();
        double extentY = box.worldExtentY();
        double extentZ = box.worldExtentZ();
        return dest.set(
                box.centerX() - extentX,
                box.centerY() - extentY,
                box.centerZ() - extentZ,
                box.centerX() + extentX,
                box.centerY() + extentY,
                box.centerZ() + extentZ
        );
    }
}
