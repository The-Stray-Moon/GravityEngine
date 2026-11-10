package cc.sighs.gravityengine.client;

import cc.sighs.gravityengine.gravity.minecraft.access.CharacterControlAccess;
import cc.sighs.gravityengine.gravity.movement.CharacterControlPlan;
import cc.sighs.gravityengine.gravity.movement.CharacterPresentationIntent;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;

/** Model-local action animation. Never writes entity fluid flags, pose, or root rotation. */
public final class ClientCharacterPresentation {
    /** Vanilla 1.21.1 swimAmount ramp, owned solely by this client presentation model. */
    private static final class SwimAnimation {
        private float previous;
        private float current;

        void tick(boolean active) {
            previous = current;
            current = active
                    ? Math.min(1, current + 0.09F)
                    : Math.max(0, current - 0.09F);
        }

        float amount(float partialTick) {
            return Mth.lerp(partialTick, previous, current);
        }
    }

    private record State(SwimAnimation animation, CharacterPresentationIntent intent) {}
    private static final Map<Player, State> PLAYERS = new WeakHashMap<>();
    private ClientCharacterPresentation() {}
    public static void tick(Player player) {
        Objects.requireNonNull(player, "player");
        /*
         * Local simulation consumes the plan this actor's own logical step
         * already resolved. Observers consume the accepted replicated state
         * installed by the body-attitude receive path; they never re-run local
         * mode resolution, eligibility or collision queries for a remote actor.
         * Both data sources surface through the same shared control carriers.
         */
        var access = (CharacterControlAccess) player;
        CharacterControlPlan acceptedPlan = player.isLocalPlayer()
                ? access.gravityengine$characterControl().at(player.tickCount)
                : null;
        CharacterPresentationIntent intent = acceptedPlan != null
                ? acceptedPlan.presentation()
                : access.gravityengine$characterMode().swimActive()
                        ? CharacterPresentationIntent.SWIM_ACTION
                        : CharacterPresentationIntent.NORMAL;
        var previous = PLAYERS.get(player);
        var animation = previous == null ? new SwimAnimation() : previous.animation();
        animation.tick(intent == CharacterPresentationIntent.SWIM_ACTION);
        PLAYERS.put(player, new State(animation, intent));
    }
    public static float swimAmount(LivingEntity entity, float partialTick) {
        State state = PLAYERS.get(entity);
        return state == null ? 0 : state.animation().amount(partialTick);
    }
    public static boolean swimAction(LivingEntity entity) {
        State state = PLAYERS.get(entity);
        return state != null && state.intent() == CharacterPresentationIntent.SWIM_ACTION;
    }
    public static void clear() { PLAYERS.clear(); }
    public static void remove(Player player) { PLAYERS.remove(player); }
}
