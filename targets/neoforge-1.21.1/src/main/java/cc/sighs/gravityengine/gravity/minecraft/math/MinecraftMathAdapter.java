package cc.sighs.gravityengine.gravity.minecraft.math;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.collision.CellPos;
import cc.sighs.gravityengine.gravity.model.GravityFieldId;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

import java.util.Objects;

/**
 * Generic representation boundary between Minecraft values and canonical
 * GravityEngine mathematical values.
 *
 * <p>This adapter performs no domain mathematics. It exists so callers can
 * convert at the Minecraft boundary without depending on the collision
 * package merely to obtain a vector or block-position value.</p>
 */
public final class MinecraftMathAdapter {
    private MinecraftMathAdapter() {
    }

    public static Vec3d toVec3d(Vec3 vector) {
        Objects.requireNonNull(vector, "vector");
        return new Vec3d(vector.x, vector.y, vector.z);
    }

    public static Vec3 toMinecraft(Vec3d vector) {
        Objects.requireNonNull(vector, "vector");
        return new Vec3(vector.x(), vector.y(), vector.z());
    }

    public static CellPos toCellPos(BlockPos position) {
        Objects.requireNonNull(position, "position");
        return new CellPos(
                position.getX(),
                position.getY(),
                position.getZ()
        );
    }

    public static BlockPos toBlockPos(CellPos position) {
        Objects.requireNonNull(position, "position");
        return new BlockPos(
                position.x(),
                position.y(),
                position.z()
        );
    }

    /**
     * Field-identity boundary conversion.
     *
     * <p>A Minecraft {@link ResourceLocation} is a platform resource handle;
     * the loader-neutral field domain uses {@link GravityFieldId}. The
     * conversion happens exactly once at the Minecraft-facing publication or
     * presentation boundary.</p>
     */
    public static GravityFieldId toFieldId(ResourceLocation id) {
        Objects.requireNonNull(id, "id");
        return new GravityFieldId(
                id.getNamespace(),
                id.getPath()
        );
    }

    /** Inverse field-identity boundary conversion. */
    public static ResourceLocation toResourceLocation(
            GravityFieldId id
    ) {
        Objects.requireNonNull(id, "id");
        return ResourceLocation.fromNamespaceAndPath(
                id.namespace(),
                id.path()
        );
    }
}
