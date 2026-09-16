package cc.sighs.gravityengine.client;

import cc.sighs.gravityengine.gravity.GravityFrame;
import cc.sighs.gravityengine.gravity.minecraft.access.GravityEntityAccess;
import cc.sighs.gravityengine.gravity.minecraft.geometry.GravityEntityGeometry;
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
            Quaternionf rotation = frame.rotation();
            return new CachedRotation(
                    rotation.x(),
                    rotation.y(),
                    rotation.z(),
                    rotation.w()
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
                    frame.up().scale(presentationHalfHeight));
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
        var runtime = GravityEntityAccess.cast(entity).gravityengine$gravityComponent().runtime();
        float progress = sanitizePartialTick(partialTick);
        var completed = runtime.completedPresentationFrame();
        var state = STATES.get(entity);
        // A discontinuity can arrive between client tick and render. Bypass old endpoints
        // immediately, without acknowledging the watermark or advancing history in render.
        GravityFrame frame = state == null ? (completed == null ? null : completed.frame())
                : state.sample(runtime.lastPresentationDiscontinuityRevision(), completed, progress);
        boolean fallback = frame == null;
        if (fallback) {
            // Read accepted runtime evidence; do not sample a field or use pending assignment.
            frame = cc.sighs.gravityengine.gravity.minecraft.GravityFrameAccess.authoritativeFrame(entity);
        }
        long tick = Math.max(0L, entity.level().getGameTime());
        return renderSnapshot(GravityEntityGeometry.interpolatedPositionAnchor(entity, progress),
                GravityEntityGeometry.dimensions(entity).height(), frame, tick,
                runtime.presentationRevision(), fallback);
    }

    /** Called once after the client's entity ticks, observing only the latest operation publication. */
    static void tick(Entity entity) {
        var runtime = GravityEntityAccess.cast(entity).gravityengine$gravityComponent().runtime();
        STATES.computeIfAbsent(entity, ignored -> new ClientGravityPresentationState()).tick(
                entity.tickCount, runtime.presentationRevision(),
                runtime.lastPresentationDiscontinuityRevision(), runtime.completedPresentationFrame());
    }

    static RenderSnapshot renderSnapshot(Vec3 positionAnchor, float height, GravityFrame frame,
            long tick, long revision, boolean fallback) {
        double halfHeight = height * 0.5D;
        Vec3 center = GravityEntityGeometry.bodyCenterFromPositionAnchor(positionAnchor, height);
        return new RenderSnapshot(center.subtract(frame.up().scale(halfHeight)), center,
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