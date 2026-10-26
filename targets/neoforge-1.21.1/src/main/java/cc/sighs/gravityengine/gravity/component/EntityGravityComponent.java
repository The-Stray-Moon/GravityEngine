package cc.sighs.gravityengine.gravity.component;

import cc.sighs.gravityengine.gravity.field.GravityFieldRuntime;
import cc.sighs.gravityengine.gravity.model.GravityEntityState;
import cc.sighs.gravityengine.gravity.runtime.GravityOperationState;
import cc.sighs.gravityengine.gravity.runtime.MinecraftMoveInteropState;
import net.minecraft.world.entity.Entity;

import java.util.Objects;

/**
 * Minecraft entity attachment for GravityEngine.
 *
 * <p>This type owns only Minecraft concerns:</p>
 *
 * <ul>
 *   <li>the {@link Entity} owner;</li>
 *   <li>ownership of the loader-neutral
 *       {@link GravityEntityState} domain state;</li>
 *   <li>ownership of the loader-neutral {@link GravityOperationState}
 *       operation/runtime state;</li>
 *   <li>ownership of the target-only {@link MinecraftMoveInteropState};</li>
 *   <li>the entity-local binding to the current level's field runtime
 *       episode.</li>
 * </ul>
 *
 * <p>It deliberately owns no persistence encoding, no field-runtime trust
 * generation, no entity-incarnation identity and no network lifetime. Durable
 * gravity data is owned by the dedicated NeoForge attachment/serializer, field
 * query coverage is owned by each provider evaluation, and network
 * lifecycle uses the native entity lifecycle.</p>
 */
public final class EntityGravityComponent {
    private final Entity entity;
    private final GravityEntityState state = new GravityEntityState();
    private final GravityOperationState operationState =
            new GravityOperationState();
    private final MinecraftMoveInteropState moveInterop =
            new MinecraftMoveInteropState(operationState);

    /**
     * Level field-runtime episode this entity's live evidence belongs to.
     *
     * <p>One {@link GravityFieldRuntime} instance is exactly one level episode.
     * A different instance means the entity's live FIELD evidence came from a
     * world that no longer exists; evidence becomes UNKNOWN again while
     * any authoritative durable tuple is protected.</p>
     */
    private GravityFieldRuntime boundRuntime;

    public EntityGravityComponent(Entity entity) {
        this.entity = Objects.requireNonNull(entity);
    }

    public Entity entity() {
        return entity;
    }

    /** The loader-neutral domain state this attachment adapts. */
    public GravityEntityState state() {
        return state;
    }

    /** The loader-neutral operation/movement/runtime state. */
    public GravityOperationState operationState() {
        return operationState;
    }

    /** The target-only movement interoperability state. */
    public MinecraftMoveInteropState moveInterop() {
        return moveInterop;
    }

    /**
     * Binds this entity to the current level field-runtime episode.
     *
     * <p>A different runtime instance invalidates live FIELD evidence captured
     * from the previous episode, including confirmed absence. The next
     * complete query resolves UNKNOWN; the binding never certifies coverage.</p>
     */
    public boolean bindFieldRuntime(GravityFieldRuntime runtime) {
        Objects.requireNonNull(runtime, "runtime");
        if (boundRuntime == runtime) return false;
        boolean changed = false;
        if (!entity.level().isClientSide()) {
            changed = state.invalidateFieldEvidence();
            operationState.clearInfluenceTransientState();
        }
        boundRuntime = runtime;
        return changed;
    }
}
