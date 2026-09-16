package cc.sighs.gravityengine.gravity.field;

import cc.sighs.gravityengine.gravity.model.GravityFieldKey;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.joml.Vector3dc;

import java.util.*;

/**
 * Level-instance-local runtime owner of generic gravity fields.
 *
 * <p>This is the lifecycle authority for every {@link GravityFieldInstance},
 * regardless of producer. Block-backed gravity cores register their
 * mathematical field and deterministic key directly here; there is no
 * separate source registry.</p>
 *
 * <p>The runtime wrapper owns only Level lifecycle. Mathematical evaluation and
 * spatial indexing remain inside the field domain.</p>
 */
public final class GravityFieldRuntime {
    private static final Map<Level, GravityFieldRuntime> INSTANCES =
            new IdentityHashMap<>();

    private final ResourceKey<Level> dimension;
    private final GravityFieldIndex index;

    private GravityFieldRuntime(Level level) {
        Objects.requireNonNull(level, "level");
        this.dimension = level.dimension();
        this.index = new GravityFieldIndex(this.dimension);
    }

    public static GravityFieldRuntime get(Level level) {
        Objects.requireNonNull(level, "level");
        synchronized (INSTANCES) {
            return INSTANCES.computeIfAbsent(level, GravityFieldRuntime::new);
        }
    }

    /**
     * Removes a runtime without constructing one during unload.
     */
    public static void remove(Level level) {
        Objects.requireNonNull(level, "level");

        GravityFieldRuntime removed;
        synchronized (INSTANCES) {
            removed = INSTANCES.remove(level);
        }

        if (removed != null) {
            removed.clear();
        }
    }

    public static int loadedLevelCount() {
        synchronized (INSTANCES) {
            return INSTANCES.size();
        }
    }

    /** Removes one field without constructing a runtime during unload. */
    public static boolean remove(Level level, GravityFieldKey key) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(key, "key");
        GravityFieldRuntime runtime;
        synchronized (INSTANCES) {
            runtime = INSTANCES.get(level);
        }
        return runtime != null && runtime.remove(key);
    }

    /**
     * Revision-aware removal without constructing a runtime during unload.
     *
     * <p>Used by the block lifecycle adapter so a stale owner cannot remove a
     * newer accepted instance of the same key.</p>
     */
    public static boolean remove(
            Level level,
            GravityFieldKey key,
            long expectedRevision
    ) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(key, "key");
        GravityFieldRuntime runtime;
        synchronized (INSTANCES) {
            runtime = INSTANCES.get(level);
        }
        return runtime != null
                && runtime.remove(key, expectedRevision);
    }

    public ResourceKey<Level> dimension() {
        return this.dimension;
    }

    public boolean put(GravityFieldInstance instance) {
        Objects.requireNonNull(instance, "instance");
        return this.index.put(instance);
    }

    public boolean remove(GravityFieldKey key) {
        Objects.requireNonNull(key, "key");
        return this.index.remove(key);
    }

    /**
     * Removes the registered instance only while it still matches the
     * requesting revision.
     */
    public boolean remove(
            GravityFieldKey key,
            long expectedRevision
    ) {
        Objects.requireNonNull(key, "key");
        return this.index.remove(key, expectedRevision);
    }

    public Optional<GravityFieldInstance> get(GravityFieldKey key) {
        Objects.requireNonNull(key, "key");
        return this.index.get(key);
    }

    public List<GravityFieldInstance> query(Vector3dc position) {
        Objects.requireNonNull(position, "position");
        return this.index.query(position);
    }

    public List<GravityFieldInstance> allInstances() {
        return this.index.allInstances();
    }

    public int size() {
        return this.index.size();
    }

    public void clear() {
        this.index.clear();
    }
}
