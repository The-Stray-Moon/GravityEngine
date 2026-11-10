package cc.sighs.gravityengine.gravity.integration;

import cc.sighs.gravityengine.gravity.collision.ImpactDamage;
import cc.sighs.gravityengine.gravity.minecraft.access.GravityEntityAccess;
import cc.sighs.gravityengine.gravity.minecraft.math.MinecraftMathAdapter;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import java.util.ArrayList;
import java.util.List;

/** One collision supplies speed before response; one callback owns damage.
 * No damage history, distance estimate or server packet displacement becomes velocity.
 * Player evidence lives only inside the existing prediction/send scope. */
public final class ImpactDamageIntegration {
    private static final ThreadLocal<LivingEntity> APPLYING = new ThreadLocal<>();
    private static final ThreadLocal<Prediction> PREDICTION = new ThreadLocal<>();
    private ImpactDamageIntegration() {}

    /** Material address is storage-space (also for moving blocks), not world contact position.
     * Null material means a wall/ceiling impact with the ordinary damage response. */
    public record Impact(float distance, ResourceLocation materialDimension, BlockPos materialPosition) {
        public Impact {
            if (!Float.isFinite(distance) || distance < 0) throw new IllegalArgumentException("invalid impact distance");
            if ((materialDimension == null) != (materialPosition == null)) throw new IllegalArgumentException("partial impact material");
            if (materialPosition != null) materialPosition = materialPosition.immutable();
        }
    }

    public static boolean applying(LivingEntity entity) { return APPLYING.get() == entity; }

    public static Prediction predict(Entity player) { return new Prediction(player); }
    public static final class Prediction implements AutoCloseable {
        private final Prediction parent = PREDICTION.get();
        private final Entity player;
        private final List<Impact> impacts = new ArrayList<>();
        private Prediction(Entity player) { this.player = player; PREDICTION.set(this); }
        public List<Impact> take() { var result = List.copyOf(impacts); impacts.clear(); return result; }
        public boolean hasImpacts() { return !impacts.isEmpty(); }
        @Override public void close() {
            if (PREDICTION.get() != this) throw new IllegalStateException("impact prediction scope order");
            if (parent == null) PREDICTION.remove(); else PREDICTION.set(parent);
        }
    }

    /** Called at Entity.move's checkFallDamage seam, before bounce or velocity clipping. */
    public static void contact(Entity entity, Vec3 requested, Vec3 resolved, BlockState state, BlockPos pos) {
        if (!(entity instanceof LivingEntity actor) || entity instanceof ServerPlayer || entity.noPhysics) return;
        var velocity = MinecraftMathAdapter.toVec3d(entity.getDeltaMovement());
        double speed = 0;
        BlockContactResolver.Resolved material = null;
        boolean nativeLanding = false;
        if (EntityMovementIntegration.engineOwnsActiveMovement(entity)) {
            var rt = GravityEntityAccess.cast(entity).gravityengine$gravityComponent().operationState();
            var landing = BlockContactResolver.landing(entity);
            if (landing.isPresent()) {
                var c = landing.get();
                speed = Math.max(0, -velocity.subtract(c.surfaceVelocity()).dot(c.normal()));
                material = BlockContactResolver.resolve(entity, c).orElse(null);
            }
            var exact = rt.currentMoveResult();
            if (exact != null && !exact.indeterminate()) {
                for (var c : exact.contactVelocityConstraints()) {
                    double approach = Math.max(0, c.minimumDot() - velocity.dot(c.normal()));
                    if (approach > speed + 1.0E-7) { speed = approach; material = null; }
                }
            }
            var passive = rt.currentPassiveMoveResult();
            if (passive != null && !passive.indeterminate()) {
                for (var c : passive.impacts()) {
                    double approach = Math.max(0, -velocity.subtract(c.surfaceVelocity()).dot(c.normal()));
                    if (approach > speed + 1.0E-7) { speed = approach; material = null; }
                }
            }
        } else {
            if (entity.verticalCollisionBelow) { speed = Math.max(0, -velocity.y()); nativeLanding = true; }
            if (entity.verticalCollision && requested.y > 0 && velocity.y() > speed) {
                speed = velocity.y(); nativeLanding = false;
            }
            if (!net.minecraft.util.Mth.equal(requested.x, resolved.x) && Math.abs(velocity.x()) > speed) {
                speed = Math.abs(velocity.x()); nativeLanding = false;
            }
            if (!net.minecraft.util.Mth.equal(requested.z, resolved.z) && Math.abs(velocity.z()) > speed) {
                speed = Math.abs(velocity.z()); nativeLanding = false;
            }
        }
        if (speed <= 0) return;
        Level materialLevel = material != null ? material.level() : nativeLanding ? entity.level() : null;
        BlockPos materialPos = material != null ? material.position() : nativeLanding ? pos : null;
        var impact = new Impact(ImpactDamage.equivalentFallDistance(speed),
                materialLevel == null ? null : materialLevel.dimension().location(), materialPos);
        if (entity.level().isClientSide) {
            var prediction = PREDICTION.get();
            if (prediction != null && prediction.player == entity) prediction.impacts.add(impact);
        } else {
            apply(actor, impact, materialLevel, material != null ? material.state() : state);
        }
    }

    public static void receive(ServerPlayer player, Impact impact) {
        Level level = impact.materialDimension() == null ? null : player.server.getLevel(
                net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, impact.materialDimension()));
        if (impact.materialDimension() != null && (level == null || !level.hasChunkAt(impact.materialPosition()))) return;
        apply(player, impact, level, level == null ? null : level.getBlockState(impact.materialPosition()));
    }

    private static void apply(LivingEntity actor, Impact impact, Level materialLevel, BlockState state) {
        var previous = APPLYING.get();
        APPLYING.set(actor);
        try {
            if (materialLevel != null) {
                state.getBlock().fallOn(materialLevel, state, impact.materialPosition(), actor, impact.distance());
            } else {
                actor.causeFallDamage(impact.distance(), 1.0F, actor.damageSources().flyIntoWall());
            }
        } finally {
            if (previous == null) APPLYING.remove(); else APPLYING.set(previous);
        }
    }
}
