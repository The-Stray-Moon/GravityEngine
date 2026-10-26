package cc.sighs.gravityengine.gravity.integration.compat.sable;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.collision.*;
import cc.sighs.gravityengine.gravity.collision.provider.*;
import cc.sighs.gravityengine.gravity.kinematic.KinematicStepContext;
import cc.sighs.gravityengine.gravity.kinematic.geometry.OrientedBox;
import cc.sighs.gravityengine.math.Quatd;
import cc.sighs.gravityengine.math.geometry.*;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.lang.ref.WeakReference;
import java.util.*;

/** Internal, exact-version adapter. Bounds hooks maintain a spatial index;
 * capture never walks Sable's global sublevel list. No platform object enters common. */
public final class SableRigidCollisionProvider implements ExternalRigidCollisionProvider {
    public static final String ID = "gravityengine:sable";
    private static final Map<Level, SableRigidCollisionProvider> LEVELS = Collections.synchronizedMap(new WeakHashMap<>());
    private static final ThreadLocal<SubLevel> REBASING = new ThreadLocal<>();
    private final WeakReference<Level> level;
    private final Map<UUID, Entry> entries = new HashMap<>();
    private final Map<Long, Entry> sources = new HashMap<>();
    private final Map<Cell, Set<Entry>> buckets = new HashMap<>();
    private final Set<Entry> large = new HashSet<>();
    private long nextSource = 1;
    private record Cell(int x, int y, int z) {}
    private record PrimitiveKey(BlockPos position, int box, OrientedBox shape) {}
    private record PlotBounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        static PlotBounds capture(dev.ryanhcode.sable.companion.math.BoundingBox3ic bounds) {
            return new PlotBounds(bounds.minX(), bounds.minY(), bounds.minZ(),
                    bounds.maxX(), bounds.maxY(), bounds.maxZ());
        }
    }
    private static final class Entry {
        final WeakReference<SubLevel> body;
        final long source;
        long revision;
        long epoch = 1;
        long nextPrimitive;
        long teleportTick = Long.MIN_VALUE;
        Pose3d teleportPose;
        final List<Pose3d> physicsSamples = new ArrayList<>();
        int sampleSteps;
        long sampleTick = Long.MIN_VALUE;
        String sampleFailure;
        final Vector3d pivot;
        final Vector3d scale;
        final Map<PrimitiveKey, Long> ids = new LinkedHashMap<>();
        final Map<BlockPos, java.util.List<net.minecraft.world.phys.AABB>> geometry = new HashMap<>();
        final Map<Long, PrimitiveKey> keys = new HashMap<>();
        List<Cell> cells = List.of();
        Aabb3d bounds;
        PlotBounds plotBounds;
        Entry(SubLevel body, long source) {
            this.body = new WeakReference<>(body); this.source = source;
            var origin = body.getPlot().getCenterBlock();
            pivot = new Vector3d(origin.getX(), origin.getY(), origin.getZ());
            scale = new Vector3d(body.logicalPose().scale());
        }
    }

    private SableRigidCollisionProvider(Level level) { this.level = new WeakReference<>(level); }

    /** Sable calls this on its Level owner thread after updating global bounds. */
    public static void update(SubLevel body) {
        Level level = body.getLevel();
        cc.sighs.gravityengine.gravity.integration.compat.create.CreateCompatibility.invalidateBounds(level);
        var provider = LEVELS.computeIfAbsent(level, SableRigidCollisionProvider::new);
        RigidCollisionPublicationRegistry.register(level, provider);
        var entry = provider.entries.get(body.getUniqueId());
        if (entry == null || entry.body.get() != body) {
            if (entry != null) provider.removeEntry(body.getUniqueId(), entry);
            entry = new Entry(body, provider.nextSource++);
            provider.entries.put(body.getUniqueId(), entry);
            provider.sources.put(entry.source, entry);
        }
        provider.unindex(entry);
        if (!entry.scale.equals(body.logicalPose().scale())) {
            // A scale edit atomically replaces geometry; COM rebasing preserves the fixed plot basis.
            entry.epoch = Math.incrementExact(entry.epoch);
            entry.ids.clear(); entry.keys.clear(); entry.geometry.clear();
            entry.scale.set(body.logicalPose().scale());
            entry.physicsSamples.clear(); entry.sampleSteps = 0; entry.sampleFailure = null;
            entry.teleportTick = level.getGameTime(); entry.teleportPose = new Pose3d(body.logicalPose());
        }
        entry.revision++;
        entry.plotBounds = PlotBounds.capture(body.getPlot().getBoundingBox());
        // Index the whole interval, including bodies that start outside the fixed discovery reach.
        // Excessive relevant motion is refused at capture; it must never disappear from discovery.
        try {
            entry.bounds = motion(entry, body, KinematicStepContext.fullTick(level.getGameTime(), 0))
                    .sweptBounds(plotShape(entry, body), 0, 1);
        } catch (CollisionSceneCoverageException unsupported) {
            entry.bounds = discontinuousBounds(body);
            // Failed/partial captures still retain every observed intermediate
            // excursion. Endpoint-only discovery loses a 0 -> 2 -> 0 path.
            for(var observed:entry.physicsSamples) entry.bounds=entry.bounds.union(poseBounds(body,observed));
        }
        entry.cells = cells(entry.bounds);
        if (entry.cells.isEmpty() || entry.sampleFailure != null) provider.large.add(entry);
        else for (var cell : entry.cells) provider.buckets.computeIfAbsent(cell, ignored -> new HashSet<>()).add(entry);
    }

    /** A block edit is not a sublevel lifecycle change. Neutral geometry is only an
     * invalidation fingerprint, never a shape returned to an actor. Context-dependent
     * states conservatively retire their own cell; all resolves still query the actor. */
    public static void geometryChanged(SubLevel body, BlockPos position, net.minecraft.world.level.block.state.BlockState state) {
        var provider = LEVELS.get(body.getLevel());
        if (provider == null) return;
        var entry = provider.entries.get(body.getUniqueId());
        if (entry == null || entry.body.get() != body) return;
        var old = entry.geometry.get(position);
        if (old != null) {
            var current = state.getCollisionShape(body.getLevel(), position,
                    net.minecraft.world.phys.shapes.CollisionContext.empty()).toAabbs();
            if (state.getBlock().hasDynamicShape() || !old.equals(current)) retireCell(entry, position);
        }
        // Material is deliberately not cached. Equal shape keeps support but reads new behavior.
        // Sable updates plot bounds during the block-change callback, but its
        // SubLevel global bounds may wait until its next tick. Reindex only when
        // the actual plot candidate domain changes, including a newly added cell.
        if (!entry.plotBounds.equals(PlotBounds.capture(body.getPlot().getBoundingBox()))) update(body);
    }

    private static void retireCell(Entry entry, BlockPos pos) {
        entry.ids.entrySet().removeIf(e -> {
            if (!e.getKey().position().equals(pos)) return false;
            entry.keys.remove(e.getValue());
            return true;
        });
        entry.geometry.remove(pos);
    }

    public static void chunkReplacing(SubLevel body, net.minecraft.world.level.ChunkPos globalChunk) {
        var provider = LEVELS.get(body.getLevel());
        if (provider == null) return;
        var entry = provider.entries.get(body.getUniqueId());
        if (entry == null || entry.body.get() != body) return;
        for (var position : java.util.List.copyOf(entry.geometry.keySet()))
            if (new net.minecraft.world.level.ChunkPos(position).equals(globalChunk)) retireCell(entry, position);
    }

    public static void rebase(SubLevel body, Runnable nativeTeleport) {
        var previous = REBASING.get(); REBASING.set(body);
        try { nativeTeleport.run(); }
        finally { if (previous == null) REBASING.remove(); else REBASING.set(previous); }
    }

    public static void teleported(SubLevel body) {
        if (REBASING.get() == body) return;
        var provider = LEVELS.get(body.getLevel());
        var entry = provider == null ? null : provider.entries.get(body.getUniqueId());
        if (entry == null || entry.body.get() != body) return;
        entry.epoch = Math.incrementExact(entry.epoch);
        entry.teleportTick = body.getLevel().getGameTime();
        entry.teleportPose = new Pose3d(body.logicalPose());
        entry.physicsSamples.clear(); entry.sampleSteps = 0; entry.sampleFailure = null;
        update(body);
    }

    /** The native Level clock advances after Sable's physics phase. This is a
     * phase-label transition for that captured interval, never query-time grace
     * for stale samples. Time jumps and nonadvancing clocks do not relabel it. */
    public static void clockAdvanced(Level level,long before,long after) {
        if(after!=before+1) return;
        var provider=LEVELS.get(level);
        if(provider==null) return;
        for(var entry:provider.entries.values()) {
            if(entry.sampleTick==before) entry.sampleTick=after;
            if(entry.teleportTick==before) entry.teleportTick=after;
        }
    }

    /** Native tick start resets transient evidence; no samples survive a pose lifetime. */
    public static void beginMotion(SubLevel body) {
        update(body);
        var entry = LEVELS.get(body.getLevel()).entries.get(body.getUniqueId());
        entry.teleportTick = Long.MIN_VALUE; entry.teleportPose = null;
        entry.physicsSamples.clear(); entry.physicsSamples.add(new Pose3d(body.logicalPose()));
        entry.sampleTick = body.getLevel().getGameTime();
        entry.sampleSteps = 0; entry.sampleFailure = null;
    }

    public static void physicsSample(dev.ryanhcode.sable.sublevel.ServerSubLevel body, double phase,
                                      dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem system) {
        var provider = LEVELS.get(body.getLevel());
        var entry = provider == null ? null : provider.entries.get(body.getUniqueId());
        if (entry == null) { beginMotion(body); entry = LEVELS.get(body.getLevel()).entries.get(body.getUniqueId());
            entry.sampleFailure = "missing native tick-start sample"; }
        if (entry.sampleTick != body.getLevel().getGameTime()) entry.sampleFailure = "native sample belongs to a different tick";
        if (entry.sampleFailure != null) { update(body); return; }
        if (entry.sampleSteps == 0) entry.sampleSteps = (int)Math.round(1/phase);
        if (entry.sampleSteps < 1 || entry.sampleSteps > CollisionWorkBudget.defaults().maxSupportTrajectorySegments()
                || Math.abs(phase - (double)entry.physicsSamples.size()/entry.sampleSteps) > 1e-8) {
            entry.sampleFailure = "missing or over-budget native substep samples"; update(body); return;
        }
        double angular = system.getPhysicsHandle(body).getAngularVelocity(new Vector3d()).length();
        if (!Double.isFinite(angular) || angular/(20*entry.sampleSteps) >= Math.PI) {
            entry.sampleFailure = "native angular motion exceeds sample coverage"; update(body); return;
        }
        entry.physicsSamples.add(new Pose3d(body.logicalPose()));
        update(body);
    }

    public static void remove(SubLevel body) {
        cc.sighs.gravityengine.gravity.integration.compat.create.CreateCompatibility.invalidateBounds(body.getLevel());
        var provider = LEVELS.get(body.getLevel());
        if (provider == null) return;
        var entry = provider.entries.get(body.getUniqueId());
        if (entry != null && entry.body.get() == body) provider.removeEntry(body.getUniqueId(), entry);
    }

    public static void unload(Level level) {
        LEVELS.remove(level);
        RigidCollisionPublicationRegistry.unregister(level, ID);
    }
    private void removeEntry(UUID uuid, Entry entry) { unindex(entry); entries.remove(uuid); sources.remove(entry.source); }
    private void unindex(Entry entry) {
        large.remove(entry);
        for (var cell : entry.cells) {
            var values = buckets.get(cell);
            if (values != null) { values.remove(entry); if (values.isEmpty()) buckets.remove(cell); }
        }
    }

    private static List<Cell> cells(Aabb3d b) {
        int x0 = (int)Math.floor(b.minX()/32), x1 = (int)Math.floor(b.maxX()/32);
        int y0 = (int)Math.floor(b.minY()/32), y1 = (int)Math.floor(b.maxY()/32);
        int z0 = (int)Math.floor(b.minZ()/32), z1 = (int)Math.floor(b.maxZ()/32);
        long nx = (long)x1-x0+1, ny = (long)y1-y0+1, nz = (long)z1-z0+1;
        if (nx > 4096 || ny > 4096 || nz > 4096 || nx*ny*nz > 4096
                || x1 == Integer.MAX_VALUE || y1 == Integer.MAX_VALUE || z1 == Integer.MAX_VALUE)
            return List.of();
        var result = new ArrayList<Cell>();
        for (int x=x0; x<=x1; x++) for (int y=y0; y<=y1; y++) for (int z=z0; z<=z1; z++) result.add(new Cell(x,y,z));
        return result;
    }

    private static void selectBounded(Set<Entry> selected, Set<Entry> candidates) {
        for (var entry : candidates) {
            selected.add(entry);
            if (selected.size() > CollisionWorkBudget.defaults().maxObstaclePrimitives())
                throw new CollisionComplexityLimitException("Sable candidate budget");
        }
    }

    @Override public String id() { return ID; }
    @Override public boolean isLive() { return level.get() != null; }
    @Override public boolean isSourceLive(long source, KinematicStepContext time) {
        var entry = sources.get(source);
        return entry != null && entry.body.get() != null && !entry.body.get().isRemoved();
    }

    @Override public void capture(ExternalRigidCollisionQuery query, RigidPublicationCollector output) {
        Level world = Objects.requireNonNull(level.get(), "unloaded Sable level");
        var actor = cc.sighs.gravityengine.gravity.integration.collision.MinecraftCollisionSubject.current(world);
        if (actor == null) throw new CollisionSceneCoverageException(CollisionSceneCoverageException.Reason.MISSING_SUBJECT, "Sable shape capture requires a collision subject");
        var selected = new HashSet<Entry>();
        selectBounded(selected, large);
        var queryCells = cells(query.dynamicBounds());
        if (queryCells.isEmpty()) throw new CollisionComplexityLimitException("Sable discovery cell budget");
        for (var cell : queryCells) selectBounded(selected, buckets.getOrDefault(cell, Set.of()));
        long checkedBlocks = 0;
        int checkedBodies = 0;
        for (var entry : selected.stream().sorted(Comparator.comparingLong(e -> e.source)).toList()) {
            if (++checkedBodies > CollisionWorkBudget.defaults().maxObstaclePrimitives())
                throw new CollisionComplexityLimitException("Sable candidate budget");
            SubLevel body = entry.body.get();
            if (body == null || body.isRemoved()) continue;
            // An unobserved interval has no proven spatial exclusion. Never
            // turn missing motion evidence into a successful empty query.
            if(entry.sampleFailure!=null) throw unsupported(entry.sampleFailure);
            if (!entry.bounds.intersects(query.staticBounds())) continue;
            if (body.getPlot() instanceof dev.ryanhcode.sable.sublevel.plot.ServerLevelPlot serverPlot) {
                var children = serverPlot.getContraptions();
                if (children.size() > CollisionWorkBudget.defaults().maxObstaclePrimitives())
                    throw new CollisionComplexityLimitException("Sable contraption discovery budget");
                if (children.stream().anyMatch(c -> c.sable$isValid() && c.sable$shouldCollide()
                        && !cc.sighs.gravityengine.gravity.integration.compat.create.CreateCompatibility.publishes(c)))
                    throw unsupported("Sable kinematic contraptions need a separate motion publication; source=" + entry.source);
            }
            var start = entry.teleportTick == query.time().gameTick() ? new Pose3d(entry.teleportPose) : start(body);
            var motion = motion(entry, body, query.time());
            var context = context(actor, start);
            double reach = motion.maximumPointDisplacement(plotShape(entry, body));
            if (!Double.isFinite(reach)) throw unsupported("Sable non-finite swept discovery bound");
            // A material point reaching the query must begin within this
            // conservative excursion. Work budgets bound the resulting scan.
            Aabb3d local = inverseBounds(start, query.staticBounds().inflate(reach + CollisionTolerances.CONTACT_SKIN));
            var plot = body.getPlot().getBoundingBox();
            int x0 = Math.max(plot.minX(), (int)Math.floor(local.minX())-1);
            int y0 = Math.max(plot.minY(), (int)Math.floor(local.minY())-1);
            int z0 = Math.max(plot.minZ(), (int)Math.floor(local.minZ())-1);
            int x1 = Math.min(plot.maxX(), (int)Math.floor(local.maxX())+1);
            int y1 = Math.min(plot.maxY(), (int)Math.floor(local.maxY())+1);
            int z1 = Math.min(plot.maxZ(), (int)Math.floor(local.maxZ())+1);
            for (int x=x0; x<=x1; x++) for (int y=y0; y<=y1; y++) for (int z=z0; z<=z1; z++) {
                if (++checkedBlocks > CollisionWorkBudget.defaults().maxBlockPositions())
                    throw new CollisionComplexityLimitException("Sable block discovery budget");
                var pos = new BlockPos(x,y,z);
                var chunk = body.getPlot().getChunk(body.getPlot().toLocal(new net.minecraft.world.level.ChunkPos(pos)));
                if (chunk == null) throw unsupported("Sable plot chunk data unavailable at " + pos);
                var state = chunk.getBlockState(pos);
                if (state.isAir()) continue;
                var boxes = state.getCollisionShape(world, pos, context).toAabbs();
                for (int i=0; i<boxes.size(); i++) {
                    var shape = shape(entry, pos, boxes.get(i));
                    // Shapes are evaluated for every subject. Only exactly equal immutable
                    // geometry shares an identity; never reuse one actor's queried shape.
                    var key = new PrimitiveKey(pos, i, shape);
                    if (!entry.ids.containsKey(key) && entry.ids.size() >= CollisionWorkBudget.defaults().maxObstaclePrimitives()) {
                        // Eviction only retires continuity; IDs are never reused. Bound cache lifetime/work.
                        var oldest = entry.ids.keySet().iterator().next().position();
                        retireCell(entry, oldest);
                    }
                    long id = entry.ids.computeIfAbsent(key, ignored -> {
                        long allocated = entry.nextPrimitive++; entry.keys.put(allocated, key); return allocated;
                    });
                    var primitive = new DynamicCollisionObstacleSnapshot(entry.source, id, shape, motion);
                    if (primitive.operationSweptBounds().intersects(query.staticBounds())) output.addObstacle(primitive);
                }
                if (!boxes.isEmpty()) entry.geometry.put(pos, state.getCollisionShape(world, pos,
                        net.minecraft.world.phys.shapes.CollisionContext.empty()).toAabbs());
            }
        }
    }

    @Override public Optional<DynamicCollisionObstacleSnapshot> resolve(RigidObstacleIdentity identity, KinematicStepContext time) {
        var entry = sources.get(identity.sourceId());
        if (entry == null || !isSourceLive(entry.source, time)) return Optional.empty();
        var key = entry.keys.get(identity.primitiveId());
        if (key == null) return Optional.empty();
        var body = entry.body.get();
        Level world = level.get();
        if (world == null || body == null) return Optional.empty();
        var actor = cc.sighs.gravityengine.gravity.integration.collision.MinecraftCollisionSubject.current(world);
        if (actor == null) return Optional.empty();
        try {
            var motion = motion(entry, body, time);
            var chunk = body.getPlot().getChunk(body.getPlot().toLocal(new net.minecraft.world.level.ChunkPos(key.position())));
            if (chunk == null) throw unsupported("Sable support plot chunk unavailable");
            var boxes = chunk.getBlockState(key.position()).getCollisionShape(world, key.position(), context(actor, start(body))).toAabbs();
            if (key.box() >= boxes.size()) return Optional.empty();
            var shape = shape(entry, key.position(), boxes.get(key.box()));
            if (!shape.equals(key.shape())) return Optional.empty();
            return Optional.of(new DynamicCollisionObstacleSnapshot(entry.source, identity.primitiveId(), shape, motion));
        } catch (CollisionSceneCoverageException unsupported) {
            cc.sighs.gravityengine.gravity.debug.CollisionCoverageDiagnostics.report(actor, unsupported);
            return Optional.empty();
        }
    }

    public static Optional<cc.sighs.gravityengine.gravity.integration.BlockContactResolver.Resolved>
    resolveBlockContact(net.minecraft.world.entity.Entity actor, GravitySupportContact contact) {
        var identity = contact.faceIdentity();
        if (identity == null || !ID.equals(identity.providerNamespace())) return Optional.empty();
        var provider = LEVELS.get(actor.level());
        if (provider == null) return Optional.empty();
        var time = KinematicStepContext.fullTick(actor.level().getGameTime(), 0);
        Optional<DynamicCollisionObstacleSnapshot> obstacle;
        try (var subject = cc.sighs.gravityengine.gravity.integration.collision.MinecraftCollisionSubject.open(actor)) {
            obstacle = RigidCollisionPublicationRegistry.resolve(actor.level(), identity.obstacleIdentity(), time);
        }
        if (obstacle.isEmpty()) return Optional.empty();
        var entry = provider.sources.get(identity.sourceId());
        var key = entry == null ? null : entry.keys.get(identity.primitiveId());
        if (key == null) return Optional.empty();
        return Optional.of(new cc.sighs.gravityengine.gravity.integration.BlockContactResolver.Resolved(
                actor.level(), key.position(), actor.level().getBlockState(key.position()),
                context(actor, start(entry.body.get())), contact,
                Optional.of(obstacle.get().motion().poseAt(1))));
    }

    /** Entity state remains real; positional isAbove uses the captured plot basis. */
    private static net.minecraft.world.phys.shapes.CollisionContext context(net.minecraft.world.entity.Entity actor, Pose3d pose) {
        var box = cc.sighs.gravityengine.gravity.minecraft.collision.MinecraftCollisionGeometryAdapter
                .toAabb3d(actor.getBoundingBox());
        double bottom = inverseBounds(pose, box).minY();
        return new net.minecraft.world.phys.shapes.EntityCollisionContext(actor) {
            @Override public boolean isAbove(net.minecraft.world.phys.shapes.VoxelShape shape, BlockPos pos, boolean fallback) {
                return bottom > pos.getY() + shape.max(net.minecraft.core.Direction.Axis.Y) - 1.0E-5F;
            }
        };
    }

    private static CollisionSceneCoverageException unsupported(String message) {
        return new CollisionSceneCoverageException(CollisionSceneCoverageException.Reason.UNSUPPORTED_GEOMETRY, message);
    }

    private static OrientedBox plotShape(Entry entry, SubLevel body) {
        var b = body.getPlot().getBoundingBox();
        return shape(entry, BlockPos.ZERO, new net.minecraft.world.phys.AABB(
                b.minX(), b.minY(), b.minZ(), b.maxX()+1.0, b.maxY()+1.0, b.maxZ()+1.0));
    }

    /** Changing scale/pivot is unsupported. Index a sphere envelope at both endpoints
     * so a nearby unsupported body is refused before any native solver can take ownership. */
    private static Aabb3d discontinuousBounds(SubLevel body) {
        var a = start(body); var b = body.logicalPose();
        var plot = body.getPlot().getBoundingBox();
        // Bound pivot distance and scale independently. Their product can peak
        // between endpoints, and zero/negative scale cannot form an OrientedBox.
        var radiusVector = new Vector3d();
        for (int axis = 0; axis < 3; axis++) {
            double lo = axis == 0 ? plot.minX() : axis == 1 ? plot.minY() : plot.minZ();
            double hi = (axis == 0 ? plot.maxX() : axis == 1 ? plot.maxY() : plot.maxZ()) + 1.0;
            double p = a.rotationPoint().get(axis), q = b.rotationPoint().get(axis);
            double distance = Math.max(Math.max(Math.abs(lo-p), Math.abs(hi-p)),
                    Math.max(Math.abs(lo-q), Math.abs(hi-q)));
            radiusVector.setComponent(axis, distance * Math.max(Math.abs(a.scale().get(axis)), Math.abs(b.scale().get(axis))));
        }
        double radius = radiusVector.length();
        return new Aabb3d(Math.min(a.position().x,b.position().x)-radius,
                Math.min(a.position().y,b.position().y)-radius, Math.min(a.position().z,b.position().z)-radius,
                Math.max(a.position().x,b.position().x)+radius, Math.max(a.position().y,b.position().y)+radius,
                Math.max(a.position().z,b.position().z)+radius);
    }

    private static Aabb3d poseBounds(SubLevel body,Pose3d pose) {
        var plot=body.getPlot().getBoundingBox();
        var extent=new Vector3d();
        for(int axis=0;axis<3;axis++) {
            double lo=axis==0?plot.minX():axis==1?plot.minY():plot.minZ();
            double hi=(axis==0?plot.maxX():axis==1?plot.maxY():plot.maxZ())+1.;
            extent.setComponent(axis,Math.max(Math.abs(lo-pose.rotationPoint().get(axis)),
                    Math.abs(hi-pose.rotationPoint().get(axis)))*Math.abs(pose.scale().get(axis)));
        }
        double r=extent.length();var p=pose.position();
        return new Aabb3d(p.x-r,p.y-r,p.z-r,p.x+r,p.y+r,p.z+r);
    }

    private static Pose3d start(SubLevel body) {
        var start = new Pose3d(body.lastPose());
        if (start.rotationPoint().lengthSquared() == 0) start.rotationPoint().set(body.logicalPose().rotationPoint());
        return start;
    }
    private static RigidTrajectory motion(Entry entry, SubLevel body, KinematicStepContext time) {
        if (time.intervalTicks() != 1) throw unsupported("Sable collision publication covers one tick");
        if (entry.sampleFailure != null) throw unsupported(entry.sampleFailure);
        if (entry.sampleSteps > 0) {
            if(entry.sampleTick!=time.gameTick()) throw unsupported("stale native substep trajectory");
            if (entry.physicsSamples.size()!=entry.sampleSteps+1) throw unsupported("incomplete native substep trajectory");
            var segments = new ArrayList<RigidTrajectory>();
            for (int i=0;i<entry.sampleSteps;i++)
                segments.add(segment(entry, new Pose3d(entry.physicsSamples.get(i)), entry.physicsSamples.get(i+1), time, 1.0/entry.sampleSteps));
            return new SampledRigidTrajectory(segments);
        }
        var a = entry.teleportTick == time.gameTick() ? new Pose3d(entry.teleportPose) : start(body);
        var b = body.logicalPose();
        return segment(entry, a, b, time, 1);
    }
    private static RigidTrajectory segment(Entry entry, Pose3d a, Pose3d b, KinematicStepContext time, double interval) {
        if (b.scale().x <= 0 || b.scale().y <= 0 || b.scale().z <= 0)
            throw unsupported("Sable nonpositive scale has no rigid geometry");
        // Scale changes install a fresh geometry at the endpoint, never an
        // invented continuous deformation. update() has retired old identities.
        if (!a.scale().equals(b.scale())) a.set(b);
        // Express both endpoints about the current native COM, preserving each
        // endpoint's actual world transform. The primitive basis stays fixed.
        var shift = new Vector3d(b.rotationPoint()).sub(a.rotationPoint()).mul(a.scale());
        a.position().add(a.orientation().transform(shift));
        a.rotationPoint().set(b.rotationPoint());
        var delta = new Quaterniond(b.orientation()).mul(new Quaterniond(a.orientation()).conjugate()).normalize();
        if (delta.w < 0) delta.mul(-1);
        double length = Math.sqrt(delta.x*delta.x + delta.y*delta.y + delta.z*delta.z);
        double angle = 2*Math.atan2(length, delta.w);
        Vec3d angular = length == 0 ? Vec3d.ZERO : new Vec3d(delta.x,delta.y,delta.z).multiply(angle/length);
        Vec3d displacement = vec(b.position()).subtract(vec(a.position()));
        var parent = new RigidMotionSnapshot(new RigidPose(vec(a.position()), BodyOrientation3d.frame(
                new Quatd(a.orientation().x, a.orientation().y, a.orientation().z, a.orientation().w))),
                displacement.x(), displacement.y(), displacement.z(), angular.x(), angular.y(), angular.z(),
                time.gameTick(), entry.revision, entry.epoch, interval, true);
        var offset = new Vector3d(entry.pivot).sub(b.rotationPoint()).mul(b.scale());
        if (offset.lengthSquared() == 0) return parent;
        var basis = new RigidMotionSnapshot(new RigidPose(vec(offset), OrthonormalFrame3d.IDENTITY),
                Vec3d.ZERO, Vec3d.ZERO, time.gameTick(), entry.revision, entry.epoch, interval);
        return new ComposedRigidTrajectory(parent, basis, entry.revision, entry.epoch);
    }
    /** Captures the same parent publication used by plot collision. */
    public static RigidTrajectory captureMotion(SubLevel body, KinematicStepContext time) {
        var provider = LEVELS.get(body.getLevel());
        var entry = provider == null ? null : provider.entries.get(body.getUniqueId());
        if (entry == null || entry.body.get() != body) throw unsupported("Sable parent publication unavailable");
        return motion(entry, body, time);
    }
    public static Vec3d plotOrigin(SubLevel body) {
        var p = body.getPlot().getCenterBlock();
        return new Vec3d(p.getX(), p.getY(), p.getZ());
    }
    public static void captureEnvironment(cc.sighs.gravityengine.gravity.integration.MovementEnvironmentCapture.Builder result) {
        var world=result.actor().level(); var provider=LEVELS.get(world);
        if(provider==null) return;
        var selected=new HashSet<Entry>(); selectBounded(selected,provider.large);
        var domain=cells(result.bounds());
        if(domain.isEmpty()) throw new CollisionComplexityLimitException("environment source discovery budget");
        for(var cell:domain) selectBounded(selected,provider.buckets.getOrDefault(cell,Set.of()));
        var time=KinematicStepContext.fullTick(world.getGameTime(),0);
        for(var entry:selected.stream().sorted(Comparator.comparingLong(e->e.source)).toList()) {
            var body=entry.body.get();
            if(body==null || body.isRemoved()) continue;
            if(entry.sampleFailure!=null) throw unsupported(entry.sampleFailure);
            if(!entry.bounds.intersects(result.bounds())) continue;
            var trajectory=motion(entry,body,time);
            var first=trajectory.poseAt(0);
            var inverse=BodyOrientation3d.quaternion(first.orientation()).conjugate();
            var min=new Vector3d(Double.POSITIVE_INFINITY);var max=new Vector3d(Double.NEGATIVE_INFINITY);
            var bounds=result.bounds();
            for(int i=0;i<8;i++) {
                var p=inverse.transform(new Vec3d((i&1)==0?bounds.minX():bounds.maxX(),
                        (i&2)==0?bounds.minY():bounds.maxY(),(i&4)==0?bounds.minZ():bounds.maxZ()).subtract(first.center()));
                var local=new Vector3d(p.x(),p.y(),p.z()).div(entry.scale).add(entry.pivot);
                min.min(local);max.max(local);
            }
            var plot=body.getPlot();var limits=plot.getBoundingBox();
            for(int x=Math.max(limits.minX(),(int)Math.floor(min.x));x<=Math.min(limits.maxX(),Math.floor(max.x));x++)
                for(int y=Math.max(limits.minY(),(int)Math.floor(min.y));y<=Math.min(limits.maxY(),Math.floor(max.y));y++)
                    for(int z=Math.max(limits.minZ(),(int)Math.floor(min.z));z<=Math.min(limits.maxZ(),Math.floor(max.z));z++) {
                        result.visit();var position=new BlockPos(x,y,z);
                        var chunk=plot.getChunk(plot.toLocal(new net.minecraft.world.level.ChunkPos(position)));
                        if(chunk==null) throw unsupported("environment plot chunk unavailable");
                        var voxel=trajectory.bodyAt(shape(entry,position,new net.minecraft.world.phys.AABB(0,0,0,1,1,1)),0);
                        result.accept(world,position,chunk.getBlockState(position),voxel,trajectory.velocityAt(voxel.center(),0));
                    }
        }
    }
    public static List<OrientedBox> fluidOcclusion(
            cc.sighs.gravityengine.gravity.integration.MovementEnvironmentCapture.Builder result) {
        var level=result.actor().level();
        var container=dev.ryanhcode.sable.sublevel.water_occlusion.WaterOcclusionContainer.getContainer(level);
        if(container==null) return List.of();
        var captured=new ArrayList<OrientedBox>();
        for(var region:container.getRegions()) {
            result.visit();
            var volume=region.getVolume();
            var min=volume.getMinBlockPos();var max=volume.getMaxBlockPos();
            var body=dev.ryanhcode.sable.Sable.HELPER.getContaining(level,min);
            Entry entry=null;RigidTrajectory trajectory=null;var domain=result.bounds();
            if(body!=null) {
                var provider=LEVELS.get(level);
                entry=provider==null?null:provider.entries.get(body.getUniqueId());
                if(entry==null || entry.body.get()!=body) throw unsupported("water occlusion source unavailable");
                trajectory=motion(entry,body,KinematicStepContext.fullTick(level.getGameTime(),0));
                var pose=trajectory.poseAt(0);var low=new Vector3d(Double.POSITIVE_INFINITY);var high=new Vector3d(Double.NEGATIVE_INFINITY);
                for(int corner=0;corner<8;corner++) {
                    var world=new Vec3d((corner&1)==0?domain.minX():domain.maxX(),
                            (corner&2)==0?domain.minY():domain.maxY(),(corner&4)==0?domain.minZ():domain.maxZ());
                    var local=pose.orientation().worldToLocal(world.subtract(pose.center()));
                    var point=new Vector3d(local.x(),local.y(),local.z()).div(entry.scale).add(entry.pivot);
                    low.min(point);high.max(point);
                }
                domain=new Aabb3d(low.x,low.y,low.z,high.x,high.y,high.z);
            }
            int minX=Math.max(min.getX(),(int)Math.floor(domain.minX())),maxX=Math.min(max.getX(),(int)Math.floor(domain.maxX()));
            int minY=Math.max(min.getY(),(int)Math.floor(domain.minY())),maxY=Math.min(max.getY(),(int)Math.floor(domain.maxY()));
            int minZ=Math.max(min.getZ(),(int)Math.floor(domain.minZ())),maxZ=Math.min(max.getZ(),(int)Math.floor(domain.maxZ()));
            if(minX>maxX || minY>maxY || minZ>maxZ) continue;
            if(region.isDirty()) throw unsupported("water occlusion awaits native rebuild");
            for(int x=minX;x<=maxX;x++) for(int y=minY;y<=maxY;y++) for(int z=minZ;z<=maxZ;z++) {
                result.visit();if(!volume.getOccupied(x,y,z)) continue;
                var box=body==null?new OrientedBox(new Vec3d(x+.5,y+.5,z+.5),.5,OrthonormalFrame3d.IDENTITY)
                        :trajectory.bodyAt(shape(entry,new BlockPos(x,y,z),new net.minecraft.world.phys.AABB(0,0,0,1,1,1)),0);
                if(box.enclosingAabb().intersects(result.bounds())) captured.add(box);
            }
        }
        return List.copyOf(captured);
    }
    public static Optional<SableMovementCompatibility.ParentMotion> parentMotion(net.minecraft.world.entity.Entity entity,
                                                                                KinematicStepContext time) {
        var body = dev.ryanhcode.sable.Sable.HELPER.getContaining(entity);
        if (body == null) return Optional.empty();
        if (!body.logicalPose().scale().equals(1, 1, 1)) throw unsupported("scaled parent cannot compose Create rigid geometry");
        return Optional.of(new SableMovementCompatibility.ParentMotion(captureMotion(body, time), plotOrigin(body), body.getUniqueId()));
    }
    private static OrientedBox shape(Entry entry, BlockPos pos, net.minecraft.world.phys.AABB box) {
        var center = new Vector3d((box.minX+box.maxX)*.5+pos.getX(), (box.minY+box.maxY)*.5+pos.getY(),
                (box.minZ+box.maxZ)*.5+pos.getZ()).sub(entry.pivot).mul(entry.scale);
        var half = new Vector3d(box.getXsize()*.5, box.getYsize()*.5, box.getZsize()*.5).mul(entry.scale);
        return new OrientedBox(vec(center), vec(half), OrthonormalFrame3d.IDENTITY);
    }
    private static Aabb3d inverseBounds(Pose3d pose, Aabb3d bounds) {
        Vector3d min = new Vector3d(Double.POSITIVE_INFINITY), max = new Vector3d(Double.NEGATIVE_INFINITY);
        for (int i=0;i<8;i++) {
            var v = pose.transformPositionInverse(new Vector3d((i&1)==0?bounds.minX():bounds.maxX(),
                    (i&2)==0?bounds.minY():bounds.maxY(), (i&4)==0?bounds.minZ():bounds.maxZ()));
            min.min(v); max.max(v);
        }
        return new Aabb3d(min.x,min.y,min.z,max.x,max.y,max.z);
    }
    private static Vec3d vec(Vector3dc v) { return new Vec3d(v.x(),v.y(),v.z()); }
}
