package cc.sighs.gravityengine.gravity.integration.vanilla;

import cc.sighs.gravityengine.gravity.collision.MinecraftGeometryAdapter;
import cc.sighs.gravityengine.gravity.GravityFrame;
import cc.sighs.gravityengine.gravity.kinematic.geometry.*;
import cc.sighs.gravityengine.gravity.minecraft.GravityFrameAccess;
import cc.sighs.gravityengine.gravity.minecraft.geometry.GravityEntityGeometry;
import cc.sighs.gravityengine.math.geometry.ObbSat;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Objects;

/** Native cell callbacks use body occupancy; suffocation uses its separate
 * Vanilla-sized eye sensor. Neither query produces movement/support evidence. */
public final class VanillaBodySensors {
    private VanillaBodySensors() {}

    public static CollisionBody captureBody(Entity entity) {
        return GravityEntityGeometry.exactBody(entity, GravityFrameAccess.authoritativeFrame(entity));
    }

    /** Capsule callback occupancy is strict gap < 0. Movement/pose legality
     * instead uses its own penetration band; packet new occupancy first
     * erodes the body by Vanilla's packet margin. Do not unify these policies. */
    public static boolean intersects(CollisionBody body, AABB bounds) {
        if (body instanceof OrientedBox box) return ObbSat.overlapDetached(box.toObb3d(), MinecraftGeometryAdapter.toObb3d(bounds)).hasMtv();
        return cc.sighs.gravityengine.gravity.collision.CapsuleAabbCollision.intersects(
                (CharacterCapsule) body, MinecraftGeometryAdapter.toAabb3d(bounds));
    }

    public static boolean occupiesCell(CollisionBody body, BlockPos pos) {
        return intersects(body, new AABB(pos));
    }

    /**
     * Custom-gravity equivalent of NeoForge 21.1.249
     * {@code Entity.getOnPosLegacy()}.
     *
     * <p>Vanilla's offset sensor is {@code new BlockPos(floor(x),
     * floor(y - 0.2), floor(z))} - a world-axis 0.2-block down offset. For
     * custom gravity the same scalar offset/rounding rule is expressed along
     * {@code frame.down()} from the gravity-relative anchor. Default gravity is
     * exactly vanilla-identical. This sensor is used only when no exact
     * authoritative support block exists.</p>
     */
    public static BlockPos gravityRelativeOnPos(
            GravityFrame frame,
            Vec3 gravityFeet
    ) {
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(gravityFeet, "gravityFeet");
        return BlockPos.containing(
                gravityFeet.add(frame.down().scale(0.2D))
        );
    }

    public static OrientedBox eyeSensor(Entity entity) {
        var frame = GravityFrameAccess.authoritativeFrame(entity);
        var eye = GravityEntityGeometry.eyePosition(entity, GravityEntityGeometry.bodyCenter(entity, frame), frame);
        // Preserve the native float multiplication before promotion to double.
        float width = GravityEntityGeometry.dimensions(entity).width() * .8F;
        return OrientedBox.fromDimensions(MinecraftGeometryAdapter.toJoml(eye, new org.joml.Vector3d()),
                width, 1.0E-6D, frame.orientation());
    }

    public static boolean isInWall(Entity entity) {
        if (entity.noPhysics) return false;
        var sensor = eyeSensor(entity);
        var enclosure = MinecraftGeometryAdapter.toMinecraft(sensor.enclosingAabb());
        return BlockPos.betweenClosedStream(enclosure).anyMatch(pos -> {
            var state = entity.level().getBlockState(pos);
            if (state.isAir() || !state.isSuffocating(entity.level(), pos)) return false;
            return state.getCollisionShape(entity.level(), pos).toAabbs().stream()
                    .anyMatch(box -> intersects(sensor, box.move(pos)));
        });
    }
}
