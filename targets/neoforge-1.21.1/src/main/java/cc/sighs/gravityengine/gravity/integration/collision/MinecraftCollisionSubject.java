package cc.sighs.gravityengine.gravity.integration.collision;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import java.util.Objects;

/** Invocation-scoped target context for the loader-neutral provider registry.
 * Never retained by a provider, scene or common state. Nested queries restore their caller.
 * All entry points (capture, transport, jump and packet validation) install the same actor.
 */
public final class MinecraftCollisionSubject implements AutoCloseable {
    private static final ThreadLocal<Entity> CURRENT = new ThreadLocal<>();
    private final Entity previous;
    private MinecraftCollisionSubject(Entity actor) {
        previous = CURRENT.get();
        CURRENT.set(Objects.requireNonNull(actor));
    }
    public static MinecraftCollisionSubject open(Entity actor) { return new MinecraftCollisionSubject(actor); }
    public static Entity current(Level level) {
        Entity actor = CURRENT.get();
        return actor != null && actor.level() == level ? actor : null;
    }
    @Override public void close() {
        if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
    }
}
