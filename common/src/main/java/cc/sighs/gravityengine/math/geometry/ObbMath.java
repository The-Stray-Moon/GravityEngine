package cc.sighs.gravityengine.math.geometry;

import cc.sighs.gravityengine.api.math.Vec3d;
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

    /** Immutable enclosing bounds without a throwaway OBB or mutable destination. */
    public static Aabb3d enclosingAabb(
            Vec3d center,
            Vec3d halfExtents,
            OrthonormalFrame3d frame
    ) {
        Objects.requireNonNull(center, "center");
        Objects.requireNonNull(halfExtents, "halfExtents");
        Objects.requireNonNull(frame, "frame");
        OrthonormalFrame3d.requireFinite(center, "center");
        OrthonormalFrame3d.requireFinite(halfExtents, "halfExtents");
        if (halfExtents.x() < 0.0D
                || halfExtents.y() < 0.0D
                || halfExtents.z() < 0.0D) {
            throw new IllegalArgumentException(
                    "halfExtents must be non-negative: " + halfExtents
            );
        }
        double extentX = frame.extentAlongWorldX(
                halfExtents.x(),
                halfExtents.y(),
                halfExtents.z()
        );
        double extentY = frame.extentAlongWorldY(
                halfExtents.x(),
                halfExtents.y(),
                halfExtents.z()
        );
        double extentZ = frame.extentAlongWorldZ(
                halfExtents.x(),
                halfExtents.y(),
                halfExtents.z()
        );
        return new Aabb3d(
                center.x() - extentX,
                center.y() - extentY,
                center.z() - extentZ,
                center.x() + extentX,
                center.y() + extentY,
                center.z() + extentZ
        );
    }
}
