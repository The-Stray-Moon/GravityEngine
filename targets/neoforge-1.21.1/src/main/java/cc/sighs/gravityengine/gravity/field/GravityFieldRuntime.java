package cc.sighs.gravityengine.gravity.field;

import cc.sighs.gravityengine.gravity.model.GravityFieldId;
import net.minecraft.world.level.Level;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;

/** Level session owner and sole provider aggregation boundary. Common owns
 * pure composition and publication indexing. Query coverage is never cached
 * as readiness; unload closes every provider and discards all live sources. */
public final class GravityFieldRuntime implements GravityFieldEvaluationSource {
    private enum Phase { INITIALIZING, OPENING, OPEN, CLOSING, CLOSED }
    private volatile Phase phase = Phase.INITIALIZING;
    private boolean publishing;
    private boolean samplingPublications;
    private net.minecraft.resources.ResourceLocation callbackProvider;
    private boolean evaluating;
    private final Level level;
    private final Map<net.minecraft.resources.ResourceLocation, cc.sighs.gravityengine.api.GravityFieldProvider> providers = new java.util.TreeMap<>();
    @Override
    public boolean revisionCoversEvaluation() { return false; }

    @Override
    public long publicationRevision() { return registry.publicationRevision(); }

    @Override
    public cc.sighs.gravityengine.gravity.acceleration.GravityFieldEvaluation evaluate(
            cc.sighs.gravityengine.api.field.GravityFieldQuery query) {
        requireOpenThread();
        Objects.requireNonNull(query, "query");
        if (evaluating || samplingPublications || publishing) throw new IllegalStateException("recursive field provider evaluation");
        evaluating = true;
        try {
            var coverage = level.isClientSide()
                    ? cc.sighs.gravityengine.api.field.FieldCoverage.INCOMPLETE
                    : cc.sighs.gravityengine.api.field.FieldCoverage.COMPLETE;
            var contributions = new java.util.ArrayList<cc.sighs.gravityengine.api.field.GravityContribution>();
            var owners = new java.util.HashMap<String, net.minecraft.resources.ResourceLocation>();
            for (var entry : providers.entrySet()) {
                callbackProvider = entry.getKey();
                cc.sighs.gravityengine.api.field.GravityFieldProviderResult result;
                try { result = Objects.requireNonNull(entry.getValue().evaluate(query), "field provider result"); }
                catch (RuntimeException failure) { throw providerFailure(entry.getKey(), "evaluate " + query, failure); }
                if (result.coverage() == cc.sighs.gravityengine.api.field.FieldCoverage.INCOMPLETE) coverage = result.coverage();
                for (var contribution : result.contributions()) {
                    var previous = owners.putIfAbsent(contribution.id(), entry.getKey());
                    if (previous != null) {
                        throw new IllegalStateException("duplicate field contribution: " + contribution.id()
                                + " providers=" + previous + "," + entry.getKey()
                                + " level=" + level.dimension().location() + " query=" + query);
                    }
                    contributions.add(contribution);
                }
            }
            callbackProvider = null;
            try {
                return new cc.sighs.gravityengine.gravity.acceleration.GravityFieldEvaluation(query,
                        GravityFieldService.composeContributions(contributions, query), coverage, publicationRevision());
            } catch (RuntimeException failure) {
                throw new IllegalStateException("field composition level=" + level.dimension().location()
                        + " query=" + query + " providers=" + providers.keySet(), failure);
            }
        } finally {
            callbackProvider = null;
            evaluating = false;
        }
    }

    /** Fresh ephemeral discovery hints, independent of publication revision/coverage. */
    public cc.sighs.gravityengine.api.field.GravityFieldDiscovery blockDiscovery() {
        requireOpenThread();
        if (evaluating || publishing || samplingPublications) throw new IllegalStateException("recursive discovery");
        evaluating = true;
        try {
            var publications = registry.blockDiscovery();
            var bounds = new java.util.ArrayList<>(publications.bounds());
            boolean unbounded = publications.unbounded();
            for (var entry : providers.entrySet()) {
                // Discovery is not an evaluation callback: publication sampling is forbidden.
                try {
                    var discovery = Objects.requireNonNull(entry.getValue().blockDiscovery(), "block discovery");
                    bounds.addAll(discovery.bounds());
                    unbounded |= discovery.unbounded();
                } catch (RuntimeException failure) { throw providerFailure(entry.getKey(), "blockDiscovery", failure); }
            }
            return unbounded ? cc.sighs.gravityengine.api.field.GravityFieldDiscovery.UNBOUNDED
                    : bounds.isEmpty() ? cc.sighs.gravityengine.api.field.GravityFieldDiscovery.SAMPLING_ONLY
                    : new cc.sighs.gravityengine.api.field.GravityFieldDiscovery(bounds, false);
        } finally { evaluating = false; }
    }

    private IllegalStateException providerFailure(net.minecraft.resources.ResourceLocation provider,
            String stage, RuntimeException cause) {
        return new IllegalStateException("field provider " + provider + " level=" + level.dimension().location()
                + " phase=" + stage, cause);
    }

    /** Receipt binds the token to the runtime which actually committed it. */
    public record PublicationReceipt(GravityFieldRuntime runtime, GravityFieldRegistry.PublicationToken token) {}

    public static PublicationReceipt publish(Level level, net.minecraft.resources.ResourceLocation provider,
            GravityFieldInstance instance) {
        return acquire(level, true).publish(provider, instance);
    }

    public PublicationReceipt publish(net.minecraft.resources.ResourceLocation provider, GravityFieldInstance instance) {
        requireMutable();
        requireProvider(provider);
        Objects.requireNonNull(instance, "instance");
        if (phase == Phase.OPENING && !provider.equals(callbackProvider)) {
            throw new IllegalStateException("onOpen may publish only its own provider domain");
        }
        publishing = true;
        try {
            return new PublicationReceipt(this, registry.publish(instance, provider.toString()));
        } catch (RuntimeException failure) {
            failure.addSuppressed(new IllegalStateException("field provider " + provider + " level="
                    + level.dimension().location() + " phase=publish field=" + instance.id()));
            throw failure;
        } finally {
            publishing = false;
        }
    }

    public java.util.List<cc.sighs.gravityengine.api.field.GravityContribution> samplePublications(
            net.minecraft.resources.ResourceLocation provider, cc.sighs.gravityengine.api.field.GravityFieldQuery query) {
        requireOpenThread();
        requireProvider(provider);
        if (publishing || samplingPublications || (evaluating && !provider.equals(callbackProvider))) {
            throw new IllegalStateException("invalid provider publication sampling context");
        }
        samplingPublications = true;
        try {
            return GravityFieldService.contributions(registry.query(query.position(), provider.toString()), query);
        } finally {
            samplingPublications = false;
        }
    }

    private void requireProvider(net.minecraft.resources.ResourceLocation provider) {
        if (!providers.containsKey(Objects.requireNonNull(provider, "provider"))) {
            throw new IllegalArgumentException("unregistered field provider: " + provider);
        }
    }

    private static void requireLevelThread(Level level) {
        Objects.requireNonNull(level, "level");
        if (level instanceof net.minecraft.server.level.ServerLevel server && !server.getServer().isSameThread()) {
            throw new IllegalStateException("field operation must run on the owning Level thread");
        }
    }

    private void requireOpenThread() {
        requireLevelThread(level);
        if (phase != Phase.OPEN) throw new IllegalStateException("field runtime phase: " + phase);
    }

    private void requireMutable() {
        requireLevelThread(level);
        if (phase != Phase.OPEN && phase != Phase.OPENING) {
            throw new IllegalStateException("field runtime phase: " + phase);
        }
        if (evaluating || publishing || samplingPublications) {
            throw new IllegalStateException("publication mutation during field callback");
        }
    }

    private static final Map<Level, GravityFieldRuntime> INSTANCES =
            new IdentityHashMap<>();

    private final GravityFieldRegistry registry =
            new GravityFieldRegistry();

    private final cc.sighs.gravityengine.gravity.integration.FallingBlockRechecks fallingBlocks =
            new cc.sighs.gravityengine.gravity.integration.FallingBlockRechecks();

    public cc.sighs.gravityengine.gravity.integration.FallingBlockRechecks fallingBlocks() {
        return fallingBlocks;
    }

    private GravityFieldRuntime(Level level) {
        this.level = level;
    }

    private void initialize() {
        if (level instanceof net.minecraft.server.level.ServerLevel server) {
            for (var definition : GravityFieldProviderRegistry.definitions().entrySet()) {
                try {
                    providers.put(definition.getKey(), Objects.requireNonNull(definition.getValue().apply(server),
                            "missing field provider session"));
                } catch (RuntimeException failure) { throw providerFailure(definition.getKey(), "factory", failure); }
            }
            // One bootstrap of native loaded identities, followed only by load/unload events.
            for (var holder : ((cc.sighs.gravityengine.mixin.ChunkMapFallingAccess)server.getChunkSource().chunkMap)
                    .gravityengine$visibleChunks()) fallingBlocks.loaded(server, holder.getPos());
        }
        phase = Phase.OPENING;
        for (var entry : providers.entrySet()) {
            callbackProvider = entry.getKey();
            try { entry.getValue().onOpen(); }
            catch (RuntimeException failure) { throw providerFailure(entry.getKey(), "onOpen", failure); }
        }
        callbackProvider = null;
        phase = Phase.OPEN;
    }

    public static GravityFieldRuntime get(Level level) {
        return acquire(level, false);
    }

    private static GravityFieldRuntime acquire(Level level, boolean publication) {
        requireLevelThread(level); // before any factory or slot creation
        GravityFieldRuntime runtime;
        synchronized (INSTANCES) {
            runtime = INSTANCES.get(level);
            if (runtime != null) {
                if (runtime.phase == Phase.OPEN || (publication && runtime.phase == Phase.OPENING)) return runtime;
                throw new IllegalStateException("field runtime phase: " + runtime.phase);
            }
            runtime = new GravityFieldRuntime(level);
            INSTANCES.put(level, runtime);
        }
        try {
            runtime.initialize();
            return runtime;
        } catch (RuntimeException | Error failure) {
            try { runtime.clear(); }
            catch (RuntimeException | Error closing) { if (closing != failure) failure.addSuppressed(closing); }
            finally { synchronized (INSTANCES) { INSTANCES.remove(level, runtime); } }
            throw failure;
        }
    }

    /** Non-creating snapshot access: only fully opened sessions are usable. */
    public static GravityFieldRuntime getIfPresent(Level level) {
        synchronized (INSTANCES) {
            var runtime = INSTANCES.get(level);
            return runtime != null && runtime.phase == Phase.OPEN ? runtime : null;
        }
    }

    /** Release checks the exact bound session, including its OPENING phase. */
    public void release(GravityFieldId id, GravityFieldRegistry.PublicationToken token) {
        synchronized (INSTANCES) {
            if (INSTANCES.get(level) != this || phase == Phase.CLOSING || phase == Phase.CLOSED) return;
        }
        requireMutable();
        registry.removeIfOwned(id, token);
    }

    /** Keep the closing slot installed until all callbacks finish, preventing resurrection. */
    public static void remove(Level level) {
        requireLevelThread(level);
        GravityFieldRuntime runtime;
        synchronized (INSTANCES) { runtime = INSTANCES.get(level); }
        if (runtime == null || runtime.phase == Phase.CLOSING || runtime.phase == Phase.CLOSED) return;
        runtime.requireOpenThread();
        runtime.requireMutable();
        try { runtime.clear(); }
        finally { synchronized (INSTANCES) { INSTANCES.remove(level, runtime); } }
    }

    public static int loadedLevelCount() {
        synchronized (INSTANCES) {
            return INSTANCES.size();
        }
    }

    public static void blockChanged(net.minecraft.server.level.ServerLevel level, net.minecraft.core.BlockPos pos) {
        var runtime = getIfPresent(level);
        if (runtime != null) runtime.fallingBlocks.changed(level, pos);
    }

    /** Cleanup callbacks must never recreate a runtime already removed by level unload. */
    public static void chunkUnloaded(Level level, net.minecraft.world.level.ChunkPos pos) {
        var runtime = getIfPresent(level);
        if (runtime != null) runtime.fallingBlocks.unloaded(pos);
    }

    /**
     * The loader-neutral registration authority for this level.
     *
     * <p>Minecraft-facing callers convert their platform values at this
     * boundary and then use only the common registry; physics and composition
     * code never receives a {@link Level}.</p>
     */
    public GravityFieldRegistry registry() {
        return this.registry;
    }

    private void clear() {
        phase = Phase.CLOSING;
        callbackProvider = null;
        Throwable failure = null;
        try {
            try { registry.clear(); }
            catch (RuntimeException | Error exception) { failure = exception; }
            fallingBlocks.clear();
            var sessions = new java.util.ArrayList<>(providers.entrySet());
            for (int i = sessions.size() - 1; i >= 0; i--) {
                try { sessions.get(i).getValue().close(); }
                catch (RuntimeException | Error exception) {
                    Throwable contextual = exception instanceof RuntimeException runtimeException
                            ? providerFailure(sessions.get(i).getKey(), "close", runtimeException) : exception;
                    if (failure == null) failure = contextual;
                    else if (failure != contextual) failure.addSuppressed(contextual);
                }
            }
        } finally {
            providers.clear();
            phase = Phase.CLOSED;
        }
        if (failure instanceof RuntimeException exception) throw exception;
        if (failure instanceof Error error) throw error;
    }
}
