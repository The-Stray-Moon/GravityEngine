package cc.sighs.gravityengine.gravity.integration;

import cc.sighs.gravityengine.gravity.collision.SupportTransport;
import cc.sighs.gravityengine.gravity.runtime.GravityOperationState;
import net.minecraft.world.entity.Entity;

import java.util.Objects;
import java.util.Optional;

/**
 * Target adapter for engine-owned persistent-support transport.
 *
 * <p>The common resolver already owns identity validation and transport
 * mathematics. This adapter performs the one platform read needed before the
 * collision scene exists: it locates the published rigid obstacle by
 * engine-owned source id so the capture envelope can include the transport
 * displacement. The operation then revalidates the same support against its
 * single captured scene before the transport reaches the solver.</p>
 */
public final class EngineSupportTransportIntegration {
    private EngineSupportTransportIntegration() {}

    /** Captures one release sample before Vanilla writes the jump velocity. */
    public static Optional<cc.sighs.gravityengine.api.math.Vec3d> captureJumpReleaseVelocity(
            Entity entity,
            GravityOperationState runtime
    ) {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(runtime, "runtime");
        var support = runtime.persistentSupportState();
        if (support == null || support.staticSupport()) {
            return Optional.empty();
        }
        var time = cc.sighs.gravityengine.gravity.kinematic.KinematicStepContext
                .fullTick(entity.level().getGameTime(), 0L);
        try (var subject = cc.sighs.gravityengine.gravity.integration.collision.MinecraftCollisionSubject.open(entity)) {
            return cc.sighs.gravityengine.gravity.collision.provider
                    .RigidCollisionPublicationRegistry.resolve(
                            entity.level(), support.identity().obstacleIdentity(), time)
                    .flatMap(obstacle -> cc.sighs.gravityengine.gravity.collision
                            .SupportTransportResolver.resolveReleaseVelocity(
                                    support, obstacle, time));
        }
    }

    /**
     * Scene-free preflight used only to widen the capture domain.
     */
    public static Optional<SupportTransport> preflight(
            Entity entity,
            GravityOperationState runtime,
            long gameTick,
            double intervalTicks
    ) {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(runtime, "runtime");

        if (entity instanceof net.minecraft.world.entity.item.FallingBlockEntity) return Optional.empty();
        if (runtime.movementMode() != GravityOperationState.MovementMode.GROUND_AIR) return Optional.empty();
        try (var subject = cc.sighs.gravityengine.gravity.integration.collision.MinecraftCollisionSubject.open(entity)) {
            return EngineSupportTransportPreflight.preflight(
                    runtime.persistentSupportState(),
                    entity.level(),
                    gameTick,
                    intervalTicks
            );
        }
    }

    /**
     * Revalidates preflight transport against the operation's one captured
     * scene and stages it for the next authoritative solve.
     *
     * <p>A mismatch means the two world reads described different obstacle
     * publications. The method fails closed: support is cleared and no
     * out-of-envelope displacement is applied.</p>
     */
    public static Optional<SupportTransport> stageResolvedTransport(
            GravityOperationState runtime,
            Optional<SupportTransport> preflight
    ) {
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(preflight, "preflight");

        if (runtime.collisionOperation() == null
                || runtime.persistentSupportState() == null) {
            return Optional.empty();
        }

        Optional<SupportTransport> resolved =
                runtime.resolveSupportTransport(
                        runtime.collisionOperation().scene()
                );
        if (resolved.isEmpty()) {
            return Optional.empty();
        }

        if (preflight.isEmpty()) {
            /*
             * The capture envelope was not widened, so a newly moving
             * transport cannot be allowed to reach the solver. Static/resting
             * resolution remains a valid no-op.
             */
            if (resolved.get().moving()) {
                runtime.clearPersistentSupportState();
                return Optional.empty();
            }
            return resolved;
        }

        SupportTransport expected = preflight.get();
        SupportTransport actual = resolved.get();
        /*
         * Preflight and captured-scene resolution must describe exactly the same
         * rigid publication. Checking only the endpoint chord is insufficient:
         * different angular trajectories can share the same endpoint displacement.
         */
        if (!expected.equals(actual)) {
            runtime.clearPersistentSupportState();
            return Optional.empty();
        }

        runtime.stageEngineSupportTransport(actual);
        return Optional.of(actual);
    }
}
