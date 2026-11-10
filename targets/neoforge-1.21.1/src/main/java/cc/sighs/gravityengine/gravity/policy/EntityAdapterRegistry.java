package cc.sighs.gravityengine.gravity.policy;

import cc.sighs.gravityengine.api.EntityGravityAdapter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/** One exact-type definition owner. No entity state or capability result cache. */
public final class EntityAdapterRegistry {
    private static final Map<EntityType<?>, Function<Entity, EntityGravityAdapter>> DEFINITIONS = new IdentityHashMap<>();
    private static volatile Map<EntityType<?>, Function<Entity, EntityGravityAdapter>> frozen;
    private static final ThreadLocal<Boolean> EVALUATING = ThreadLocal.withInitial(() -> false);
    private EntityAdapterRegistry() {}

    @SuppressWarnings("unchecked") // Exact EntityType registration and dispatch.
    public static synchronized <E extends Entity> void register(EntityType<E> type, Function<E, EntityGravityAdapter> adapter) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(adapter, "adapter");
        if (frozen != null) throw new IllegalStateException("entity adapters must register before entity integration");
        if (DEFINITIONS.containsKey(type)) throw new IllegalArgumentException("duplicate entity adapter: " + type);
        // Exact EntityType identity is checked before dispatch.
        DEFINITIONS.put(type, entity -> adapter.apply((E) entity));
    }

    private static synchronized Map<EntityType<?>, Function<Entity, EntityGravityAdapter>> freeze() {
        if (frozen == null) frozen = Map.copyOf(DEFINITIONS);
        return frozen;
    }

    public static EntityGravityAdapter resolve(Entity entity) {
        var definitions = frozen;
        if (definitions == null) definitions = freeze();
        var adapter = definitions.get(entity.getType());
        if (adapter == null) return null;
        if (EVALUATING.get()) throw new IllegalStateException("recursive entity capability callback");
        EVALUATING.set(true);
        try {
            return Objects.requireNonNull(adapter.apply(entity), "entity adapter result");
        } catch (RuntimeException failure) {
            throw new IllegalStateException("entity adapter failed for " + entity.getType(), failure);
        } finally {
            EVALUATING.remove();
        }
    }
}
