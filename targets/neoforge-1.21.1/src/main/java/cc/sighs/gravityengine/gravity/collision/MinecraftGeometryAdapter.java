package cc.sighs.gravityengine.gravity.collision;

import cc.sighs.gravityengine.math.geometry.Aabb3d;
import cc.sighs.gravityengine.math.geometry.MutableAabb3d;
import cc.sighs.gravityengine.math.geometry.Obb3d;
import cc.sighs.gravityengine.math.geometry.OrthonormalFrame3d;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.Objects;

/**
 * The sole conversion boundary between Minecraft values and the pure geometry kernel.
 */
public final class MinecraftGeometryAdapter {
    private MinecraftGeometryAdapter() {}

    public static Vector3d toJoml(Vec3 vector, Vector3d dest) {
        Objects.requireNonNull(vector, "vector");
        return Objects.requireNonNull(dest, "dest")
                .set(vector.x, vector.y, vector.z);
    }

    public static Vec3 toMinecraft(Vector3dc vector) {
        Objects.requireNonNull(vector, "vector");
        return new Vec3(
                vector.x(),
                vector.y(),
                vector.z()
        );
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
        return toObb3d(
                box,
                Vec3.ZERO
        );
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
