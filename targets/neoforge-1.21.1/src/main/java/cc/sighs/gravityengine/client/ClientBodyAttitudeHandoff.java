package cc.sighs.gravityengine.client;

import cc.sighs.gravityengine.attitude.SemanticView;
import cc.sighs.gravityengine.gravity.look.GravityLocalLook;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import org.joml.Quaterniond;
import org.joml.Quaternionf;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;

/**
 * Client-only presentation bridge across rapid BodyAttitude ownership changes.
 *
 * Gameplay/control ownership changes immediately.  The bridge owns only the
 * displayed body/controller pose.
 *
 * A new ownership transition retargets from the pose that is actually visible
 * at that tick boundary.  It never cancels an unfinished transition and jumps
 * to a newly bootstrapped pose.
 */
public final class ClientBodyAttitudeHandoff {
    private static final double DURATION_TICKS =
            3.0D;

    private static final Map<Player, Transition> TRANSITIONS =
            new WeakHashMap<>();

    private ClientBodyAttitudeHandoff() {}

    /**
     * ACTIVE -> INACTIVE.
     */
    public static void retargetToGravity(
            Player player,
            BodyAttitudeRenderSnapshot retiringAttitude
    ) {
        Objects.requireNonNull(
                player,
                "player"
        );

        Objects.requireNonNull(
                retiringAttitude,
                "retiringAttitude"
        );

        Transition previous =
                TRANSITIONS.get(player);

        BodyAttitudeRenderSnapshot source;

        if (isActive(player, previous)) {
            /*
             * Previous transition may itself have been heading toward attitude.
             * Sample exactly what was visible at this tick boundary first.
             */
            source =
                    sampleTransition(
                            player,
                            previous,
                            0.0F,
                            previous.target()
                                    == Target.ATTITUDE
                                    ? retiringAttitude
                                    : null
                    );

            if (source == null) {
                source = retiringAttitude;
            }
        } else {
            source = retiringAttitude;
        }

        TRANSITIONS.put(
                player,
                new Transition(
                        player.tickCount,
                        source,
                        Target.GRAVITY
                )
        );
    }

    /**
     * INACTIVE -> ACTIVE.
     */
    public static void retargetToAttitude(
            Player player,
            BodyAttitudeRenderSnapshot activeTarget
    ) {
        Objects.requireNonNull(
                player,
                "player"
        );

        Objects.requireNonNull(
                activeTarget,
                "activeTarget"
        );

        Transition previous =
                TRANSITIONS.get(player);

        BodyAttitudeRenderSnapshot source;

        if (isActive(player, previous)) {
            /*
             * Continue exactly from the previously displayed bridge pose.
             */
            source =
                    sampleTransition(
                            player,
                            previous,
                            0.0F,
                            previous.target()
                                    == Target.ATTITUDE
                                    ? activeTarget
                                    : null
                    );

            if (source == null) {
                source =
                        gravityTarget(
                                player,
                                0.0F,
                                activeTarget
                        );
            }
        } else {
            /*
             * No previous bridge:
             *
             * before activation the screen was owned by the normal gravity
             * renderer, so use that exact representation as the source.
             */
            source =
                    gravityTarget(
                            player,
                            0.0F,
                            activeTarget
                    );
        }

        TRANSITIONS.put(
                player,
                new Transition(
                        player.tickCount,
                        source,
                        Target.ATTITUDE
                )
        );
    }

    public static boolean active(
            Player player
    ) {
        return player != null
                && isActive(
                player,
                TRANSITIONS.get(player)
        );
    }

    /**
     * Samples the current bridge.
     *
     * attitudeTarget is the normal BodyAttitude pose for this render frame.
     * It is null while logical BodyAttitude ownership is inactive.
     */
    @Nullable
    public static BodyAttitudeRenderSnapshot sample(
            Player player,
            float partialTick,
            @Nullable BodyAttitudeRenderSnapshot attitudeTarget
    ) {
        Objects.requireNonNull(
                player,
                "player"
        );

        Transition transition =
                TRANSITIONS.get(player);

        if (!isActive(
                player,
                transition
        )) {
            return null;
        }

        return sampleTransition(
                player,
                transition,
                ClientGravityFrameSampler
                        .sanitizePartialTick(partialTick),
                attitudeTarget
        );
    }

    public static void remove(
            Player player
    ) {
        if (player != null) {
            TRANSITIONS.remove(player);
        }
    }

    public static void clear() {
        TRANSITIONS.clear();
    }

    @Nullable
    private static BodyAttitudeRenderSnapshot sampleTransition(
            Player player,
            Transition transition,
            float partialTick,
            @Nullable BodyAttitudeRenderSnapshot attitudeTarget
    ) {
        double elapsed =
                elapsedTicks(
                        player,
                        transition,
                        partialTick
                );

        if (elapsed >= DURATION_TICKS) {
            return null;
        }

        double progress =
                Math.clamp(
                        elapsed / DURATION_TICKS,
                        0.0D,
                        1.0D
                );

        /*
         * Zero velocity at both endpoints.
         */
        double smooth =
                progress
                        * progress
                        * (3.0D - 2.0D * progress);

        BodyAttitudeRenderSnapshot target =
                transition.target()
                        == Target.GRAVITY

                        ? gravityTarget(
                        player,
                        partialTick,
                        transition.source()
                )

                        : attitudeTarget != null
                          ? attitudeTarget

                          /*
                           * Defensive fallback only.  Under normal tick ordering,
                           * ATTITUDE target always has a renderable snapshot.
                           */
                          : transition.source();

        return blend(
                transition.source(),
                target,
                smooth
        );
    }

    private static BodyAttitudeRenderSnapshot gravityTarget(
            Player player,
            float partialTick,
            BodyAttitudeRenderSnapshot metadataTemplate
    ) {
        ClientGravityFrameSampler.RenderSnapshot gravity =
                ClientGravityFrameSampler.sample(
                        player,
                        partialTick
                );

        float bodyYaw =
                Mth.rotLerp(
                        partialTick,
                        player.yBodyRotO,
                        player.yBodyRot
                );

        Quaterniond targetBody =
                asDouble(
                        GravityLocalLook.lookQuaternion(
                                gravity.frame(),
                                bodyYaw,
                                0.0F,
                                0.0F
                        )
                );

        float viewYaw =
                player.getViewYRot(partialTick);

        float viewPitch =
                player.getViewXRot(partialTick);

        Quaterniond targetController =
                asDouble(
                        GravityLocalLook.lookQuaternion(
                                gravity.frame(),
                                viewYaw,
                                viewPitch,
                                0.0F
                        )
                );

        SemanticView view =
                new SemanticView(
                        targetController
                );

        return new BodyAttitudeRenderSnapshot(
                targetBody,
                view.forward(),
                view,

                metadataTemplate.viewLocalYaw(),
                metadataTemplate.viewLocalPitch(),

                metadataTemplate.localStateRevision(),
                metadataTemplate.lastLocalSimulationStep(),
                metadataTemplate.lifecycleEpoch(),

                metadataTemplate.authoritativeRevision(),
                metadataTemplate.authoritativeServerGameTick(),
                metadataTemplate.authoritativeStreamEpoch(),
                metadataTemplate.authoritativeConfigGeneration(),
                cc.sighs.gravityengine.gravity.movement.CharacterAttitudeContract.REFERENCE_ALIGNED
        );
    }

    private static BodyAttitudeRenderSnapshot blend(
            BodyAttitudeRenderSnapshot source,
            BodyAttitudeRenderSnapshot target,
            double progress
    ) {
        Quaterniond body =
                BodyAttitudeInterpolation.shortestArc(
                        source.worldFromBody(),
                        target.worldFromBody(),
                        progress
                );

        Quaterniond controller =
                BodyAttitudeInterpolation.shortestArc(
                        source.cameraView()
                                .worldFromController(),

                        target.cameraView()
                                .worldFromController(),

                        progress
                );

        SemanticView view =
                new SemanticView(
                        controller
                );

        return new BodyAttitudeRenderSnapshot(
                body,
                view.forward(),
                view,

                source.viewLocalYaw(),
                source.viewLocalPitch(),

                target.localStateRevision(),
                target.lastLocalSimulationStep(),
                target.lifecycleEpoch(),

                target.authoritativeRevision(),
                target.authoritativeServerGameTick(),
                target.authoritativeStreamEpoch(),
                target.authoritativeConfigGeneration(), target.attitudeContract()
        );
    }

    private static boolean isActive(
            Player player,
            @Nullable Transition transition
    ) {
        return transition != null
                && elapsedTicks(
                player,
                transition,
                0.0F
        ) < DURATION_TICKS;
    }

    private static double elapsedTicks(
            Player player,
            Transition transition,
            float partialTick
    ) {
        long ticks =
                Math.max(
                        0L,
                        (long) player.tickCount
                                - transition.startTick()
                );

        return ticks + partialTick;
    }

    private static Quaterniond asDouble(
            Quaternionf value
    ) {
        return new Quaterniond(
                value.x,
                value.y,
                value.z,
                value.w
        ).normalize();
    }

    private enum Target {
        GRAVITY,
        ATTITUDE
    }

    private record Transition(
            long startTick,
            BodyAttitudeRenderSnapshot source,
            Target target
    ) {
        Transition {
            Objects.requireNonNull(
                    source,
                    "source"
            );

            Objects.requireNonNull(
                    target,
                    "target"
            );
        }
    }
}