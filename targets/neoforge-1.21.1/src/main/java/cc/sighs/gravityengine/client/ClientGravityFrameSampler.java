package cc.sighs.gravityengine.client;

import cc.sighs.gravityengine.gravity.presentation.ClientGravityPresentationState;

import cc.sighs.gravityengine.gravity.GravityFrame;
import cc.sighs.gravityengine.gravity.minecraft.access.GravityEntityAccess;
import cc.sighs.gravityengine.gravity.minecraft.geometry.GravityEntityGeometry;
import cc.sighs.gravityengine.gravity.minecraft.math.MinecraftMathAdapter;
import cc.sighs.gravityengine.math.Quatd;
import cc.sighs.gravityengine.math.geometry.BodyOrientation3d;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;

/** Read-only render sampling of tick-owned gravity endpoints and Vanilla position history. */
public final class ClientGravityFrameSampler {
    private static final Map<Entity, ClientGravityPresentationState> STATES = new WeakHashMap<>();

    private ClientGravityFrameSampler() {}

    /** Immutable quaternion components cached with a render snapshot. */
    public record CachedRotation(float x, float y, float z, float w) {
        static CachedRotation from(GravityFrame frame) {
            Objects.requireNonNull(frame, "frame");

            Quatd rotation =
                    BodyOrientation3d.quaternion(frame.orientation());

            return new CachedRotation(
                    (float) rotation.x(),
                    (float) rotation.y(),
                    (float) rotation.z(),
                    (float) rotation.w()
            );
        }

        /** Returns a mutable copy suitable for JOML and PoseStack APIs. */
        public Quaternionf quaternion() {
            return new Quaternionf(x, y, z, w);
        }
    }

    /**
     * One immutable render-time view shared by camera, entity render and debug
     * consumers. A fallback snapshot is explicit and exists only before a
     * completed frame is available or after continuity is invalidated.
     *
     * <p>The body is a rigid presentation pose: {@code center} is Vanilla's
     * interpolated position anchor plus world +Y times the current half-height.
     * {@code feet} is derived from that center under {@code frame} as
     * {@code center - frame.up * presentationHalfHeight}. {@code feet} and
     * {@code center} always satisfy the same relation with the interpolated
     * {@code frame}, so a transition that only rotates gravity never drifts the
     * presentation center.</p>
     */
    public record RenderSnapshot(
            Vec3 feet,
            Vec3 center,
            double presentationHalfHeight,
            GravityFrame frame,
            long tick,
            long revision,
            boolean fallback,
            CachedRotation rotation
    ) {
        public RenderSnapshot {
            Objects.requireNonNull(feet, "feet");
            Objects.requireNonNull(center, "center");
            Objects.requireNonNull(frame, "frame");
            Objects.requireNonNull(rotation, "rotation");
            if (!Double.isFinite(presentationHalfHeight)
                    || presentationHalfHeight < 0.0D) {
                throw new IllegalArgumentException(
                        "presentationHalfHeight must be finite and non-negative");
            }
            Vec3 expectedCenter = feet.add(
                    MinecraftMathAdapter.toMinecraft(
                            frame.up().multiply(presentationHalfHeight)));
            if (expectedCenter.distanceToSqr(center) > 1.0E-12D) {
                throw new IllegalArgumentException(
                        "feet/center/frame/half-height must describe one reference-aligned character");
            }
            if (tick < 0L) throw new IllegalArgumentException("tick must be non-negative");
            if (revision < 0L) throw new IllegalArgumentException("revision must be non-negative");
        }

    }

    /** Samples using the entity's currently committed application state. */
    public static RenderSnapshot sample(Entity entity, float partialTick) {
        Objects.requireNonNull(entity, "entity");
        var runtime = GravityEntityAccess.cast(entity).gravityengine$gravityComponent().operationState();
        float progress = sanitizePartialTick(partialTick);
        var state = STATES.get(entity);
        boolean fallback = state == null;
        GravityFrame frame = fallback ? presentationTarget(entity)
                : state.sampleAt(entity.tickCount + progress);
        long tick = Math.max(0L, entity.level().getGameTime());
        return renderSnapshot(GravityEntityGeometry.interpolatedPositionAnchor(entity, progress),
                GravityEntityGeometry.dimensions(entity).height(), frame, tick,
                runtime.presentationRevision(), fallback);
    }

    /** Called once after the client's entity ticks, observing only the latest operation publication. */
    static void tick(Entity entity) {
        var state = STATES.computeIfAbsent(entity, ignored -> new ClientGravityPresentationState());
        state.tick(presentationTarget(entity), entity.tickCount);
    }

    private static GravityFrame presentationTarget(Entity entity) {
        if (!cc.sighs.gravityengine.gravity.policy.GravityInfluencePolicy.usesCustomPresentation(entity)) {
            return GravityFrame.DEFAULT;
        }
        var runtime = GravityEntityAccess.cast(entity).gravityengine$gravityComponent().operationState();
        var completed = runtime.completedPresentationFrame();
        return completed == null
                ? cc.sighs.gravityengine.gravity.minecraft.GravityFrameAccess.authoritativeFrame(entity)
                : completed.frame();
    }

    private static double displayTime(Entity entity) {
        return entity.tickCount + sanitizePartialTick(net.minecraft.client.Minecraft.getInstance()
                .getTimer().getGameTimeDeltaPartialTick(true));
    }

    static void beforePhysicalCommit(Entity entity) {
        // Seed the old visual authority before assignment/suppression/geometry changes.
        STATES.computeIfAbsent(entity, ignored -> {
            var state = new ClientGravityPresentationState();
            // Initial history is constant, so no fractional clock is needed.
            state.tick(presentationTarget(entity), entity.tickCount);
            return state;
        });
    }

    static void afterPhysicalCommit(Entity entity) {
        // In .249 Minecraft.runTick advances Timer, drains network tasks, then
        // ticks entities. Here entity.tickCount can still be the old integer
        // while Timer already contains the new residual (e.g. 156.90 -> 156.06).
        // Never rebuild a visual trajectory at that mixed time. Physical state
        // is already installed; ClientTickEvent.Post observes the latest target.
        STATES.get(entity).requestConvergence();
    }

    /** Keep rendering through a gravity disable transition until the world basis is reached. */
    public static boolean usesPresentation(Entity entity) {
        if (cc.sighs.gravityengine.gravity.policy.GravityInfluencePolicy.usesCustomPresentation(entity)) return true;
        if (entity.isRemoved() || entity.isSpectator() || entity.isPassenger()
                || entity instanceof net.minecraft.world.entity.LivingEntity living && living.isSleeping()) return false;
        var state = STATES.get(entity);
        return state != null && state.transitioning(displayTime(entity));
    }

    static RenderSnapshot renderSnapshot(Vec3 positionAnchor, float height, GravityFrame frame,
            long tick, long revision, boolean fallback) {
        double halfHeight = height * 0.5D;
        Vec3 center = GravityEntityGeometry.bodyCenterFromPositionAnchor(positionAnchor, height);
        return new RenderSnapshot(center.subtract(
                        MinecraftMathAdapter.toMinecraft(
                                frame.up().multiply(halfHeight))), center,
                halfHeight, frame, tick, revision, fallback, CachedRotation.from(frame));
    }

    static float sanitizePartialTick(float partialTick) {
        return Float.isFinite(partialTick) ? Math.clamp(partialTick, 0, 1) : 0;
    }

    static void remove(Entity entity) {
        if (entity != null) STATES.remove(entity);
    }

    static void clearLevel(ClientLevel level) {
        if (level != null) STATES.keySet().removeIf(entity -> entity.level() == level);
    }

    static void clearAll() { STATES.clear(); }
    static int cachedEntityCount() { return STATES.size(); }
}
