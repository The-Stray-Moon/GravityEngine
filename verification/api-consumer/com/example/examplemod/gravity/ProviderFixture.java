package com.example.examplemod.gravity;

import cc.sighs.gravityengine.api.GravityEngineApi;
import cc.sighs.gravityengine.api.GravityFieldProvider;
import cc.sighs.gravityengine.api.field.FieldCoverage;
import cc.sighs.gravityengine.api.field.GravityFieldProviderResult;
import cc.sighs.gravityengine.api.field.GravityFieldQuery;
import cc.sighs.gravityengine.api.field.GravityFieldBounds;
import cc.sighs.gravityengine.api.field.GravityFieldDiscovery;
import cc.sighs.gravityengine.api.field.GravityContribution;
import cc.sighs.gravityengine.api.field.GravityFieldCompositionMode;
import cc.sighs.gravityengine.api.math.Vec3d;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import java.util.IdentityHashMap;
import java.util.Map;

/** Explicit test source-domain owner, registered by the test mod before Levels.
 * Tests control query discovery independently of publication membership.
 * Production consumers replace the query predicate with their own source index. */
public final class ProviderFixture {
    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("examplemod", "sources");
    public static final ResourceLocation SECOND_ID = ResourceLocation.fromNamespaceAndPath("examplemod", "other_sources");
    private static final Map<Level, Session> SESSIONS = new IdentityHashMap<>();
    private static final Map<Level, Session> SECOND_SESSIONS = new IdentityHashMap<>();
    private static Level missingSessionLevel;

    /** Per-Level test callbacks isolate intentional failures from other frozen domains. */
    public static final class LifecycleProbe {
        public int factories, evaluations, closes;
        public java.util.function.Consumer<ResourceLocation> factory = id -> {};
        public java.util.function.Consumer<ResourceLocation> opening = id -> {};
        public java.util.function.Consumer<ResourceLocation> evaluation = id -> {};
        public java.util.function.Function<ResourceLocation, java.util.List<GravityContribution>> contributions = id -> java.util.List.of();
        public java.util.function.Consumer<ResourceLocation> closing = id -> {};
    }
    private static final Map<Level, LifecycleProbe> PROBES = new IdentityHashMap<>();
    public static void probe(Level level, LifecycleProbe probe) {
        if (probe == null) PROBES.remove(level); else PROBES.put(level, probe);
    }

    public static void register() {
        GravityEngineApi.registerFieldProvider(ID, level -> create(level, ID, SESSIONS));
        GravityEngineApi.registerFieldProvider(SECOND_ID, level -> create(level, SECOND_ID, SECOND_SESSIONS));
        boolean duplicateRejected = false;
        try { GravityEngineApi.registerFieldProvider(ID, level -> create(level, ID, SESSIONS)); }
        catch (IllegalArgumentException expected) { duplicateRejected = true; }
        if (!duplicateRejected) throw new AssertionError("duplicate provider registration accepted");
    }

    public static boolean hasSessions(Level level) {
        return SESSIONS.containsKey(level) && SECOND_SESSIONS.containsKey(level);
    }

    public static int sessionCount(Level level) {
        return (SESSIONS.containsKey(level) ? 1 : 0) + (SECOND_SESSIONS.containsKey(level) ? 1 : 0);
    }

    public static void missingSession(Level level) { missingSessionLevel = level; }

    private static Session create(ServerLevel level, ResourceLocation id, Map<Level, Session> owner) {
        var probe = PROBES.get(level);
        if (probe != null) { probe.factories++; probe.factory.accept(id); }
        if (level == missingSessionLevel && id.equals(ID)) return null;
        var session = new Session(level, id, owner);
        owner.put(level, session);
        return session;
    }

    public static void coverage(Level level, FieldCoverage coverage) {
        SESSIONS.get(level).coverage = query -> coverage;
    }

    public static void secondCoverage(Level level, java.util.function.Function<GravityFieldQuery, FieldCoverage> coverage) {
        SECOND_SESSIONS.get(level).coverage = coverage;
    }

    /** Same provider contract without a publication or synthetic evaluator. */
    public static void boundedSource(Level level, GravityFieldBounds bounds) {
        boundedSource(level, bounds, Vec3d.ZERO);
    }

    public static void boundedSource(Level level, GravityFieldBounds bounds, Vec3d acceleration) {
        var session = SESSIONS.get(level);
        session.acceleration = acceleration;
        session.discovery = bounds == null ? GravityFieldDiscovery.SAMPLING_ONLY
                : new GravityFieldDiscovery(java.util.List.of(bounds), false);
    }

    private static final class Session implements GravityFieldProvider {
        private Vec3d acceleration = Vec3d.ZERO;
        private GravityFieldDiscovery discovery = GravityFieldDiscovery.SAMPLING_ONLY;
        private final GravityFieldProvider publications;
        private final ServerLevel level;
        private final ResourceLocation id;
        private final Map<Level, Session> owner;
        private java.util.function.Function<GravityFieldQuery, FieldCoverage> coverage = query -> FieldCoverage.COMPLETE;

        private Session(ServerLevel level, ResourceLocation id, Map<Level, Session> owner) {
            this.level = level;
            this.id = id;
            this.owner = owner;
            this.publications = GravityEngineApi.publicationProvider(level, id, query -> coverage.apply(query));
        }

        public void onOpen() {
            var probe = PROBES.get(level);
            if (probe != null) probe.opening.accept(id);
        }

        public GravityFieldDiscovery blockDiscovery() { return discovery; }

        public GravityFieldProviderResult evaluate(GravityFieldQuery query) {
            var probe = PROBES.get(level);
            if (probe != null) { probe.evaluations++; probe.evaluation.accept(id); }
            var published = publications.evaluate(query);
            var contributions = new java.util.ArrayList<>(published.contributions());
            if (probe != null) contributions.addAll(probe.contributions.apply(id));
            var p = query.position();
            if (discovery.bounds().stream().anyMatch(b -> p.x()>=b.minX() && p.x()<=b.maxX()
                    && p.y()>=b.minY() && p.y()<=b.maxY() && p.z()>=b.minZ() && p.z()<=b.maxZ()))
                contributions.add(new GravityContribution("examplemod:bounded", "examplemod:source", 0,0,0,
                        acceleration, GravityFieldCompositionMode.OVERRIDE, 0));
            return new GravityFieldProviderResult(published.coverage(), contributions);
        }

        public void close() {
            owner.remove(level, this);
            var probe = PROBES.get(level);
            if (probe != null) { probe.closes++; probe.closing.accept(id); }
        }
    }
}
