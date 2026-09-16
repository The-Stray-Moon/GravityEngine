package cc.sighs.gravityengine.player;

import cc.sighs.gravityengine.attitude.BodyAttitudeConfigSnapshot;
import cc.sighs.gravityengine.attitude.runtime.BodyAttitudeRuntime;
import cc.sighs.gravityengine.attitude.runtime.BodyAttitudeControlPolicyResolver;
import cc.sighs.gravityengine.attitude.runtime.MinecraftBodyAttitudeSnapshotAdapter;

import cc.sighs.gravityengine.gravity.GravityFrame;
import cc.sighs.gravityengine.gravity.minecraft.access.CharacterControlAccess;
import cc.sighs.gravityengine.gravity.movement.*;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import java.util.Objects;
import java.util.Optional;

/** One coherent entity/config capture produces the shared logical-step policy. */
public final class CharacterControlRuntime {
    private static final CharacterControlPlan ORDINARY = new CharacterControlPlan(
            CharacterLocomotionTechnique.GROUND_AIR,
            CharacterControlBasis.GRAVITY_TANGENT,
            CharacterAttitudeContract.REFERENCE_ALIGNED,
            CharacterPresentationIntent.NORMAL
    );

    private CharacterControlRuntime() {}

    public static void clearMode(Player player) {
        if (player instanceof CharacterControlAccess access) {
            access.gravityengine$characterMode().clear();
            access.gravityengine$characterControl().clear();
        }
    }

    public static void captureSprintIntent(Player player, boolean pressed) {
        ((CharacterControlAccess) player).gravityengine$characterControl()
                .captureSprintIntent(player.tickCount, pressed);
    }

    /**
     * Ordinary resolution outside an operation-owned pre-step capture.
     *
     * Uses the currently published endpoint fact.
     */
    public static CharacterControlPlan resolve(
            LivingEntity entity,
            GravityFrame frame
    ) {
        if (!(entity instanceof Player player)) {
            return ORDINARY;
        }

        var config = BodyAttitudeRuntime.Config.forPlayer(player).orElse(null);
        if (config == null) {
            clearMode(player);
            return ORDINARY;
        }

        return resolve(
                player,
                frame,
                config,
                player.tickCount,
                liveTerminalSupport(player)
        );
    }

    /**
     * Travel-operation resolution.
     *
     * {@code terminalSupportAtStepStart} is captured by the owning gravity
     * operation before geometry preparation is allowed to invalidate the live
     * endpoint slot.
     *
     * A support-preserving geometry transition may therefore consume the
     * previous completed endpoint as immutable pre-step evidence without
     * weakening the runtime invalidation rules.
     */
    public static CharacterControlPlan resolve(
            LivingEntity entity,
            GravityFrame frame,
            Optional<Boolean> terminalSupportAtStepStart
    ) {
        Objects.requireNonNull(
                terminalSupportAtStepStart,
                "terminalSupportAtStepStart"
        );

        if (!(entity instanceof Player player)) {
            return ORDINARY;
        }

        var config = BodyAttitudeRuntime.Config.forPlayer(player).orElse(null);
        if (config == null) {
            clearMode(player);
            return ORDINARY;
        }

        return resolve(
                player,
                frame,
                config,
                player.tickCount,
                terminalSupportAtStepStart
        );
    }

    /**
     * Logical/presentation resolution that is not inside the opening phase of
     * the travel operation.
     *
     * The current tick's travel resolution normally already installed the
     * shared cached plan, so later attitude capture observes that same plan.
     */
    public static CharacterControlPlan resolve(
            Player player,
            GravityFrame frame,
            BodyAttitudeConfigSnapshot config,
            long logicalStep
    ) {
        return resolve(
                player,
                frame,
                config,
                logicalStep,
                liveTerminalSupport(player)
        );
    }

    private static CharacterControlPlan resolve(
            Player player,
            GravityFrame frame,
            BodyAttitudeConfigSnapshot config,
            long logicalStep,
            Optional<Boolean> terminalSupportAtStepStart
    ) {
        Objects.requireNonNull(
                terminalSupportAtStepStart,
                "terminalSupportAtStepStart"
        );

        var access = (CharacterControlAccess) player;
        var cache = access.gravityengine$characterControl();

        /*
         * One accepted policy owns the whole logical step. In particular, a
         * movement completed later in this same tick must not retroactively
         * change locomotion or attitude ownership.
         */
        var plan = cache.at(logicalStep);
        if (plan != null) {
            return plan;
        }

        var state = MinecraftBodyAttitudeSnapshotAdapter.snapshot(player);

        boolean eligible =
                BodyAttitudeControlPolicyResolver
                        .findSuspension(state)
                        .isEmpty();

        var mode = access.gravityengine$characterMode();

        /*
         * gameplayGrounded remains the Vanilla/gameplay carrier.
         * terminalSupportAtStepStart remains the physical attitude authority.
         *
         * Crucially, the terminal fact supplied by the travel operation is the
         * immutable fact captured before support-preserving geometry evolution.
         */
        var groundFacts = new CharacterGroundFacts(
                state.onGround(),
                terminalSupportAtStepStart
        );

        /*
         * One accepted policy owns the whole logical step. Swim eligibility is
         * resolved from this step's immutable evidence, retention and this
         * step's single toggle consumption happen before the plan is installed,
         * and no later terminal evidence may retroactively rewrite it.
         *
         * Ordinary ground/air motion is never derived from this eligibility:
         * low gravity, absent/unknown support, a gameplay onGround change or a
         * threshold crossing alone never select free attitude.
         */
        boolean swimEligible =
                CharacterLocomotionControlPolicyResolver.lowGravitySwimEligible(
                        state,
                        eligible,
                        groundFacts,
                        frame,
                        config
                );

        mode.retainIfEligible(swimEligible);

        if (cache.consumeSprintIntent(logicalStep)) {
            mode.pressSprintIntent(swimEligible);
        }

        plan = CharacterLocomotionControlPolicyResolver.resolve(
                state,
                eligible,
                mode.swimActive(),
                groundFacts,
                frame,
                config
        );

        cache.install(logicalStep, plan);
        return plan;
    }

    private static Optional<Boolean> liveTerminalSupport(Player player) {
        return cc.sighs.gravityengine.gravity.minecraft.access.GravityEntityAccess
                .cast(player)
                .gravityengine$gravityComponent().runtime()
                .completedEndpointGround()
                .map(
                        cc.sighs.gravityengine.gravity.runtime.GravityRuntimeState
                                .CompletedEndpointGround::terminalSupported
                );
    }

    /**
     * Unknown physical endpoints bootstrap the receiving contract through
     * ordinary policy. The gameplay carrier is captured separately and never
     * supplies terminal support.
     */
    public static CharacterGroundFacts groundFacts(
            Player player,
            boolean gameplayGrounded
    ) {
        return new CharacterGroundFacts(
                gameplayGrounded,
                liveTerminalSupport(player)
        );
    }

    /**
     * Native jump precedes travel. Inspect its policy without consuming input
     * or caching a plan.
     */
    public static CharacterLocomotionTechnique jumpTechnique(
            LivingEntity entity,
            GravityFrame frame
    ) {
        if (!(entity instanceof Player player)) {
            return ORDINARY.locomotion();
        }

        var config = BodyAttitudeRuntime.Config.forPlayer(player).orElse(null);
        if (config == null) {
            return ORDINARY.locomotion();
        }

        var state = MinecraftBodyAttitudeSnapshotAdapter.snapshot(player);

        return CharacterLocomotionControlPolicyResolver.resolve(
                state,
                BodyAttitudeControlPolicyResolver
                        .findSuspension(state)
                        .isEmpty(),
                ((CharacterControlAccess) player)
                        .gravityengine$characterMode()
                        .swimActive(),
                groundFacts(player, state.onGround()),
                frame,
                config
        ).locomotion();
    }
}
