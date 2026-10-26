package cc.sighs.gravityengine.gravity.integration.compat.create;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.collision.*;
import cc.sighs.gravityengine.gravity.collision.provider.*;
import cc.sighs.gravityengine.gravity.integration.BlockContactResolver;
import cc.sighs.gravityengine.gravity.integration.collision.MinecraftCollisionSubject;
import cc.sighs.gravityengine.gravity.kinematic.KinematicStepContext;
import cc.sighs.gravityengine.gravity.kinematic.geometry.OrientedBox;
import cc.sighs.gravityengine.math.Quatd;
import cc.sighs.gravityengine.math.geometry.*;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.ControlledContraptionEntity;
import com.simibubi.create.content.contraptions.OrientedContraptionEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.EntityCollisionContext;
import java.lang.ref.WeakReference;
import java.util.*;

/** Immutable publications of Create's native kinematic poses. Entity lifecycle
 * owns discovery; Create/Sable retain mounted transforms and rigid integration. */
public final class CreateRigidCollisionProvider implements ExternalRigidCollisionProvider {
    public static final String ID = "gravityengine:create";
    private static final Map<Level, CreateRigidCollisionProvider> LEVELS = Collections.synchronizedMap(new WeakHashMap<>());
    private final WeakReference<Level> level;
    private final Map<Long, Entry> sources = new LinkedHashMap<>();
    private final Map<UUID, Entry> entities = new HashMap<>();
    private record Cell(int x,int y,int z) {}
    private final Map<Cell,Set<Entry>> buckets=new HashMap<>();
    private final Set<Entry> unbounded=new HashSet<>();
    private boolean boundsDirty=true;
    private long indexedTick=Long.MIN_VALUE;
    private long nextSource = 1;
    private record Key(BlockPos position, int part, OrientedBox shape) {}
    private static final class Entry {
        final long source;
        final WeakReference<AbstractContraptionEntity> entity;
        WeakReference<Object> geometry = new WeakReference<>(null);
        long revision = 1, epoch = 1, nextPrimitive;
        RigidTrajectory last;
        java.util.UUID parentId;
        long parentEpoch;
        List<BlockPos> blockOrder;
        WeakReference<Object> blockOrderGeometry = new WeakReference<>(null);
        final Map<Key, Long> ids = new HashMap<>();
        final Map<Long, Key> keys = new HashMap<>();
        Entry(long source, AbstractContraptionEntity entity) { this.source = source; this.entity = new WeakReference<>(entity); }
    }
    private CreateRigidCollisionProvider(Level level) { this.level = new WeakReference<>(level); }
    public static void joined(Entity entity) {
        if (!(entity instanceof AbstractContraptionEntity contraption)) return;
        var provider = LEVELS.computeIfAbsent(entity.level(), CreateRigidCollisionProvider::new);
        RigidCollisionPublicationRegistry.register(entity.level(), provider);
        var previous = provider.entities.get(entity.getUUID());
        if (previous != null && previous.entity.get() == entity) return;
        if (previous != null) provider.sources.remove(previous.source);
        var entry = new Entry(provider.nextSource++, contraption);
        provider.entities.put(entity.getUUID(), entry);
        provider.sources.put(entry.source, entry);
        provider.boundsDirty=true;
    }
    public static void left(Entity entity) {
        var provider = LEVELS.get(entity.level());
        if (provider == null) return;
        var entry = provider.entities.get(entity.getUUID());
        if (entry != null && entry.entity.get() == entity) {
            provider.entities.remove(entity.getUUID()); provider.sources.remove(entry.source);
            provider.boundsDirty=true;
        }
    }
    public static void unload(Level level) { LEVELS.remove(level); RigidCollisionPublicationRegistry.unregister(level, ID); }
    /** Derived bounds only. Native transforms/geometry and the Level lifetime
     * remain authoritative; no subject-dependent shape is stored in the index. */
    public static void invalidateBounds(Level level) {
        var provider=LEVELS.get(level);
        if(provider!=null) provider.boundsDirty=true;
    }
    public static void motionChanged(Entity entity) {
        if(entity instanceof AbstractContraptionEntity) invalidateBounds(entity.level());
    }
    public static boolean publishes(Object entity) {
        if (!(entity instanceof AbstractContraptionEntity contraption)) return false;
        var provider = LEVELS.get(contraption.level());
        var entry = provider == null ? null : provider.entities.get(contraption.getUUID());
        return entry != null && entry.entity.get() == contraption;
    }
    public static void changed(AbstractContraptionEntity entity, BlockPos position) {
        var provider = LEVELS.get(entity.level());
        var entry = provider == null ? null : provider.entities.get(entity.getUUID());
        if (entry == null) return;
        provider.boundsDirty=true;
        entry.blockOrder=null;
        var geometry = entity.getContraption();
        var block = geometry == null ? null : geometry.getBlocks().get(position);
        var boxes = block == null || block.state().getBlock().hasDynamicShape() ? java.util.List.<net.minecraft.world.phys.AABB>of()
                : block.state().getCollisionShape(geometry.getContraptionWorld(), position,
                        net.minecraft.world.phys.shapes.CollisionContext.empty()).toAabbs();
        entry.ids.entrySet().removeIf(e -> {
            if (!e.getKey().position().equals(position)) return false;
            var key = e.getKey();
            if (key.part() < boxes.size() && key.shape().equals(localBox(position, boxes.get(key.part())))) return false;
            entry.keys.remove(e.getValue()); return true;
        });
        entry.revision++;
    }
    @Override public String id() { return ID; }
    @Override public boolean isLive() { return level.get() != null; }
    @Override public boolean isSourceLive(long source, KinematicStepContext time) {
        var entry = sources.get(source);
        return entry != null && entry.entity.get() != null && !entry.entity.get().isRemoved();
    }
    @Override public boolean mayAffect(ExternalRigidCollisionQuery query) {
        if (sources.size() > CollisionWorkBudget.defaults().maxObstaclePrimitives()) return true;
        for (var entry : candidates(query.staticBounds(),query.time())) {
            var entity = entry.entity.get();
            if (entity == null || entity.isRemoved() || !entity.collisionEnabled()) continue;
            var geometry = entity.getContraption();
            if (geometry == null || geometry.bounds == null) return true;
            try {
                if (trajectory(entity, query.time(), entry.revision, entry.epoch)
                        .sweptBounds(localBox(BlockPos.ZERO, geometry.bounds), 0, 1).intersects(query.staticBounds())) return true;
            } catch (CollisionSceneCoverageException unavailable) { return true; }
        }
        return false;
    }
    @Override public void capture(ExternalRigidCollisionQuery query, RigidPublicationCollector output) {
        var world = Objects.requireNonNull(level.get(), "unloaded Create provider");
        var actor = MinecraftCollisionSubject.current(world);
        if (actor == null) throw unavailable("Create capture requires the operation subject");
        if (sources.size() > CollisionWorkBudget.defaults().maxObstaclePrimitives())
            throw new CollisionComplexityLimitException("Create source discovery budget");
        int checked = 0;
        // Count every expanded primitive, including ones later rejected by
        // bounds. Stream native boxes so a complex shape cannot allocate an
        // unbounded intermediate list before the operation budget is checked.
        int[] primitiveWork = {0};
        for (var entry : candidates(query.staticBounds(),query.time())) {
            var entity = entry.entity.get();
            if (entity == null || entity.isRemoved() || !entity.collisionEnabled() || !entity.canCollideWith(actor)) continue;
            var geometry = entity.getContraption();
            if (geometry == null || geometry.bounds == null) throw unavailable("Create geometry not received");
            var motion = publication(entry, entity, query.time());
            if (!motion.sweptBounds(localBox(BlockPos.ZERO, geometry.bounds), 0, 1).intersects(query.staticBounds())) continue;
            var blocks = geometry.getBlocks();
            if (blocks.size() > CollisionWorkBudget.defaults().maxBlockPositions() - checked)
                throw new CollisionComplexityLimitException("Create block discovery budget");
            var context = context(actor, motion);
            for (var position : blockOrder(entry,geometry)) {
                checked++;
                var block=blocks.get(position);
                if(block==null) throw unavailable("Create geometry changed during capture");
                int[] part = {0};
                block.state().getCollisionShape(geometry.getContraptionWorld(), position, context)
                        .forAllBoxes((minX,minY,minZ,maxX,maxY,maxZ) -> {
                    if (++primitiveWork[0] > CollisionWorkBudget.defaults().maxObstaclePrimitives())
                        throw new CollisionComplexityLimitException("Create primitive expansion budget");
                    var shape = localBox(position, new net.minecraft.world.phys.AABB(minX,minY,minZ,maxX,maxY,maxZ));
                    var key = new Key(position.immutable(), part[0]++, shape);
                    if (!entry.ids.containsKey(key) && entry.ids.size() >= CollisionWorkBudget.defaults().maxObstaclePrimitives())
                        throw new CollisionComplexityLimitException("Create primitive identity budget");
                    long primitive = entry.ids.computeIfAbsent(key, ignored -> {
                        long id = entry.nextPrimitive++; entry.keys.put(id, key); return id;
                    });
                    var snapshot = new DynamicCollisionObstacleSnapshot(entry.source, primitive, shape, motion);
                    if (snapshot.operationSweptBounds().intersects(query.staticBounds())) output.addObstacle(snapshot);
                });
            }
        }
    }
    private static List<Cell> cells(Aabb3d bounds) {
        int x0=(int)Math.floor(bounds.minX()/32),x1=(int)Math.floor(bounds.maxX()/32);
        int y0=(int)Math.floor(bounds.minY()/32),y1=(int)Math.floor(bounds.maxY()/32);
        int z0=(int)Math.floor(bounds.minZ()/32),z1=(int)Math.floor(bounds.maxZ()/32);
        long nx=(long)x1-x0+1,ny=(long)y1-y0+1,nz=(long)z1-z0+1;
        if(nx>4096 || ny>4096 || nz>4096 || nx*ny*nz>4096
                || x1==Integer.MAX_VALUE || y1==Integer.MAX_VALUE || z1==Integer.MAX_VALUE) return List.of();
        var result=new ArrayList<Cell>();
        for(int x=x0;x<=x1;x++) for(int y=y0;y<=y1;y++) for(int z=z0;z<=z1;z++) result.add(new Cell(x,y,z));
        return result;
    }
    private void refreshBounds(KinematicStepContext time) {
        if(!boundsDirty && indexedTick==time.gameTick()) return;
        if(sources.size()>CollisionWorkBudget.defaults().maxObstaclePrimitives())
            throw new CollisionComplexityLimitException("Create source indexing budget");
        buckets.clear();unbounded.clear();
        int indexedCells=0;
        for(var entry:sources.values()) {
            var entity=entry.entity.get();
            if(entity==null || entity.isRemoved()) continue;
            var geometry=entity.getContraption();
            if(geometry==null || geometry.bounds==null) {unbounded.add(entry);continue;}
            try {
                var motion=trajectory(entity,time,entry.revision,entry.epoch);
                var covered=cells(motion.sweptBounds(localBox(BlockPos.ZERO,geometry.bounds),0,1));
                if((indexedCells+=covered.size())>CollisionWorkBudget.defaults().maxBlockPositions())
                    throw new CollisionComplexityLimitException("Create spatial index cell budget");
                if(covered.isEmpty()) unbounded.add(entry);
                else for(var cell:covered) buckets.computeIfAbsent(cell,key->new HashSet<>()).add(entry);
            } catch(CollisionSceneCoverageException missing) {
                // An unknown trajectory cannot justify spatial exclusion.
                unbounded.add(entry);
            }
        }
        indexedTick=time.gameTick();boundsDirty=false;
    }
    private List<Entry> candidates(Aabb3d bounds,KinematicStepContext time) {
        refreshBounds(time);
        var domain=cells(bounds);
        if(domain.isEmpty()) throw new CollisionComplexityLimitException("Create discovery cell budget");
        var selected=new HashSet<Entry>();
        selectBounded(selected,unbounded);
        for(var cell:domain) selectBounded(selected,buckets.getOrDefault(cell,Set.of()));
        return selected.stream().sorted(Comparator.comparingLong(entry->entry.source)).toList();
    }
    private static void selectBounded(Set<Entry> selected,Set<Entry> source) {
        for(var entry:source) {
            selected.add(entry);
            if(selected.size()>CollisionWorkBudget.defaults().maxObstaclePrimitives())
                throw new CollisionComplexityLimitException("Create local candidate budget");
        }
    }
    @Override public Optional<DynamicCollisionObstacleSnapshot> resolve(RigidObstacleIdentity identity, KinematicStepContext time) {
        var entry = sources.get(identity.sourceId());
        if (entry == null || !isSourceLive(entry.source, time)) return Optional.empty();
        var key = entry.keys.get(identity.primitiveId());
        var entity = entry.entity.get();
        var actor = MinecraftCollisionSubject.current(entity.level());
        if (key == null || actor == null || !entity.collisionEnabled() || !entity.canCollideWith(actor)) return Optional.empty();
        var geometry = entity.getContraption();
        if (geometry == null || entry.geometry.get() != geometry) return Optional.empty();
        var block = geometry.getBlocks().get(key.position());
        if (block == null) return Optional.empty();
        var motion = publication(entry, entity, time);
        var boxes = block.state().getCollisionShape(geometry.getContraptionWorld(), key.position(), context(actor, motion)).toAabbs();
        if (key.part() >= boxes.size() || !key.shape().equals(localBox(key.position(), boxes.get(key.part())))) return Optional.empty();
        return Optional.of(new DynamicCollisionObstacleSnapshot(entry.source, identity.primitiveId(), key.shape(), motion));
    }
    private static RigidTrajectory publication(Entry entry, AbstractContraptionEntity entity, KinematicStepContext time) {
        var parent = cc.sighs.gravityengine.gravity.integration.compat.sable.SableMovementCompatibility.parentMotion(entity, time).orElse(null);
        var parentId = parent == null ? null : parent.identity();
        long parentEpoch = parent == null ? 0 : parent.trajectory().continuityEpoch();
        if (entry.geometry.get() != entity.getContraption() || !Objects.equals(entry.parentId, parentId)
                || entry.parentEpoch != parentEpoch) {
            entry.geometry = new WeakReference<>(entity.getContraption()); entry.epoch++;
            entry.parentId = parentId; entry.parentEpoch = parentEpoch;
            entry.ids.clear(); entry.keys.clear(); entry.last = null;
        }
        var captured = trajectory(entity, time, entry.revision, entry.epoch,parent);
        if (!captured.equals(entry.last)) {
            entry.revision++;
            captured = trajectory(entity, time, entry.revision, entry.epoch,parent);
            entry.last = captured;
        }
        return captured;
    }
    /** Native Create 6.0.10 angleLerp is shortest-angle linear interpolation,
     * not quaternion endpoint interpolation. Oriented contraptions compose Y/Z/Y. */
    public static RigidTrajectory trajectory(AbstractContraptionEntity entity, KinematicStepContext time, long revision, long epoch) {
        return trajectory(entity,time,revision,epoch,
                cc.sighs.gravityengine.gravity.integration.compat.sable.SableMovementCompatibility.parentMotion(entity,time).orElse(null));
    }
    private static RigidTrajectory trajectory(AbstractContraptionEntity entity,KinematicStepContext time,long revision,long epoch,
            cc.sighs.gravityengine.gravity.integration.compat.sable.SableMovementCompatibility.ParentMotion parent) {
        if (time.intervalTicks() != 1) throw unavailable("Create capture covers one tick");
        var start = vec(entity.getPrevAnchorVec()).add(new Vec3d(.5, .5, .5));
        var end = vec(entity.getAnchorVec()).add(new Vec3d(.5, .5, .5));
        RigidTrajectory rotation;
        if (entity instanceof ControlledContraptionEntity controlled) {
            var rotationAxis = controlled.getRotationAxis();
            var axis = rotationAxis == null ? new Vec3d(0, 1, 0) : switch (rotationAxis) {
                case X -> new Vec3d(1, 0, 0); case Y -> new Vec3d(0, 1, 0); case Z -> new Vec3d(0, 0, 1);
            };
            // VecHelper.rotate returns the input for a null axis (pistons).
            rotation = rotation(axis, rotationAxis == null ? 0 : controlled.getAngle(0),
                    rotationAxis == null ? 0 : controlled.getAngle(1), time, revision, epoch);
        } else if (entity instanceof OrientedContraptionEntity oriented) {
            var yaw = rotation(new Vec3d(0, 1, 0), oriented.getViewYRot(0), oriented.getViewYRot(1), time, revision, epoch);
            var pitch = rotation(new Vec3d(0, 0, 1), oriented.getViewXRot(0), oriented.getViewXRot(1), time, revision, epoch);
            var initial = rotation(new Vec3d(0, 1, 0), oriented.getInitialYaw(), oriented.getInitialYaw(), time, revision, epoch);
            rotation = new ComposedRigidTrajectory(yaw, new ComposedRigidTrajectory(pitch, initial, revision, epoch), revision, epoch);
        } else throw unavailable("Create contraption has an unknown native rotation contract");
        if (parent != null) {
            var origin = parent.plotOrigin();
            start = start.subtract(origin); end = end.subtract(origin);
        }
        var translation = new RigidMotionSnapshot(new RigidPose(start, OrthonormalFrame3d.IDENTITY),
                end.subtract(start), Vec3d.ZERO, time.gameTick(), revision, epoch, 1);
        RigidTrajectory local = new ComposedRigidTrajectory(translation, rotation, revision, epoch);
        return parent == null ? local : new ComposedRigidTrajectory(
                parent.trajectory(),
                local, revision, epoch);
    }
    private static RigidMotionSnapshot rotation(Vec3d axis, double from, double to, KinematicStepContext time, long revision, long epoch) {
        double delta = net.minecraft.util.Mth.wrapDegrees(to-from);
        return new RigidMotionSnapshot(new RigidPose(Vec3d.ZERO, BodyOrientation3d.frame(Quatd.fromAxisAngle(axis, Math.toRadians(from)))),
                Vec3d.ZERO, axis.multiply(Math.toRadians(delta)), time.gameTick(), revision, epoch, 1);
    }
    private static OrientedBox localBox(BlockPos position, net.minecraft.world.phys.AABB box) {
        return new OrientedBox(new Vec3d((box.minX+box.maxX)*.5+position.getX()-.5,
                (box.minY+box.maxY)*.5+position.getY()-.5, (box.minZ+box.maxZ)*.5+position.getZ()-.5),
                new Vec3d(box.getXsize()*.5, box.getYsize()*.5, box.getZsize()*.5), OrthonormalFrame3d.IDENTITY);
    }
    private static EntityCollisionContext context(Entity actor, RigidTrajectory motion) {
        var pose = motion.poseAt(0);
        var inverse = BodyOrientation3d.quaternion(pose.orientation()).conjugate();
        var bounds = actor.getBoundingBox();
        double bottom = Double.POSITIVE_INFINITY;
        for (int i = 0; i < 8; i++) bottom = Math.min(bottom, inverse.transform(new Vec3d(
                (i&1)==0?bounds.minX:bounds.maxX, (i&2)==0?bounds.minY:bounds.maxY,
                (i&4)==0?bounds.minZ:bounds.maxZ).subtract(pose.center())).y()+.5);
        final double localBottom = bottom;
        return new EntityCollisionContext(actor) {
            @Override public boolean isAbove(net.minecraft.world.phys.shapes.VoxelShape shape, BlockPos position, boolean fallback) {
                return localBottom > position.getY()+shape.max(net.minecraft.core.Direction.Axis.Y)-1e-5;
            }
        };
    }
    public static Optional<BlockContactResolver.Resolved> resolveContact(Entity actor, GravitySupportContact contact) {
        var identity = contact.faceIdentity();
        if (identity == null || !ID.equals(identity.providerNamespace())) return Optional.empty();
        var provider = LEVELS.get(actor.level());
        if (provider == null) return Optional.empty();
        try (var subject = MinecraftCollisionSubject.open(actor)) {
            var found = RigidCollisionPublicationRegistry.resolve(actor.level(), identity.obstacleIdentity(),
                    KinematicStepContext.fullTick(actor.level().getGameTime(), 0));
            if (found.isEmpty()) return Optional.empty();
            var entry = provider.sources.get(identity.sourceId());
            var key = entry.keys.get(identity.primitiveId());
            var state = entry.entity.get().getContraption().getBlocks().get(key.position()).state();
            return Optional.of(new BlockContactResolver.Resolved(entry.entity.get().getContraption().getContraptionWorld(),
                    key.position(), state, context(actor, found.get().motion()),
                    contact, Optional.of(found.get().motion().poseAt(1))));
        }
    }
    private static Vec3d vec(Vec3 vector) { return new Vec3d(vector.x, vector.y, vector.z); }
    /** Only native addresses are cached. Shapes and materials remain subject-
     * specific reads, and native setBlock/replacement invalidates this order. */
    private static List<BlockPos> blockOrder(Entry entry,com.simibubi.create.content.contraptions.Contraption geometry) {
        var blocks=geometry.getBlocks();
        if(blocks.size()>CollisionWorkBudget.defaults().maxBlockPositions())
            throw new CollisionComplexityLimitException("Create address ordering budget");
        if(entry.blockOrder==null || entry.blockOrderGeometry.get()!=geometry || entry.blockOrder.size()!=blocks.size()) {
            entry.blockOrder=blocks.keySet().stream().map(BlockPos::immutable).sorted().toList();
            entry.blockOrderGeometry=new WeakReference<>(geometry);
        }
        return entry.blockOrder;
    }
    public static void captureEnvironment(cc.sighs.gravityengine.gravity.integration.MovementEnvironmentCapture.Builder result) {
        var world=result.actor().level();var provider=LEVELS.get(world);
        if(provider==null) return;
        if(provider.sources.size()>CollisionWorkBudget.defaults().maxObstaclePrimitives())
            throw new CollisionComplexityLimitException("Create environment source budget");
        var time=KinematicStepContext.fullTick(world.getGameTime(),0);
        for(var entry:provider.candidates(result.bounds(),time)) {
            var entity=entry.entity.get();
            if(entity==null || entity.isRemoved()) continue;
            var geometry=entity.getContraption();
            if(geometry==null || geometry.bounds==null) throw unavailable("Create environment geometry unavailable");
            var trajectory=publication(entry,entity,time);
            if(!trajectory.sweptBounds(localBox(BlockPos.ZERO,geometry.bounds),0,1).intersects(result.bounds())) continue;
            // Charge before allocating/sorting addresses or evaluating shapes.
            for(int i=0;i<geometry.getBlocks().size();i++) result.visit();
            for(var position:blockOrder(entry,geometry)) {
                var block=geometry.getBlocks().get(position);
                if(block==null) throw unavailable("Create environment changed during capture");
                var voxel=trajectory.bodyAt(localBox(position,new net.minecraft.world.phys.AABB(0,0,0,1,1,1)),0);
                if(voxel.enclosingAabb().intersects(result.bounds())) result.accept(geometry.getContraptionWorld(),
                        position,block.state(),voxel,trajectory.velocityAt(voxel.center(),0));
            }
        }
    }
    private static CollisionSceneCoverageException unavailable(String reason) { return new CollisionSceneCoverageException(reason); }
}
