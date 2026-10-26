package cc.sighs.gravityengine.gravity.minecraft.collision;

import cc.sighs.gravityengine.math.geometry.Aabb3d;
import cc.sighs.gravityengine.math.geometry.MutableAabb3d;
import cc.sighs.gravityengine.math.geometry.Obb3d;
import cc.sighs.gravityengine.math.geometry.OrthonormalFrame3d;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Objects;

/**
 * Representation boundary for Minecraft collision geometry.
 *
 * <p>AABB/OBB conversion is collision-specific and remains separate from the
 * generic Minecraft math adapter.</p>
 */
public final class MinecraftCollisionGeometryAdapter {
    private MinecraftCollisionGeometryAdapter() {
    }

    public static Aabb3d toAabb3d(AABB box) {
        Objects.requireNonNull(box, "box");
        return new Aabb3d(
                box.minX,
                box.minY,
                box.minZ,
                box.maxX,
                box.maxY,
                box.maxZ
        );
    }

    public static AABB toMinecraft(Aabb3d box) {
        Objects.requireNonNull(box, "box");
        return new AABB(
                box.minX(),
                box.minY(),
                box.minZ(),
                box.maxX(),
                box.maxY(),
                box.maxZ()
        );
    }

    public static AABB toMinecraft(MutableAabb3d box) {
        Objects.requireNonNull(box, "box");
        return new AABB(
                box.minX(),
                box.minY(),
                box.minZ(),
                box.maxX(),
                box.maxY(),
                box.maxZ()
        );
    }

    public static Obb3d toObb3d(AABB box) {
        return toObb3d(box, Vec3.ZERO);
    }

    private static Obb3d toObb3d(
            AABB box,
            Vec3 translation
    ) {
        Objects.requireNonNull(box, "box");
        Objects.requireNonNull(translation, "translation");

        return new Obb3d(
                (box.minX + box.maxX) * 0.5D + translation.x,
                (box.minY + box.maxY) * 0.5D + translation.y,
                (box.minZ + box.maxZ) * 0.5D + translation.z,
                (box.maxX - box.minX) * 0.5D,
                (box.maxY - box.minY) * 0.5D,
                (box.maxZ - box.minZ) * 0.5D,
                OrthonormalFrame3d.IDENTITY
        );
    }
}
