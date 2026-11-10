package cc.sighs.gravityengine;

import cc.sighs.gravityengine.api.field.*;
import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.field.*;
import cc.sighs.gravityengine.gravity.model.GravityFieldId;
import cc.sighs.gravityengine.gravity.collision.*;
import cc.sighs.gravityengine.gravity.kinematic.*;
import cc.sighs.gravityengine.gravity.kinematic.geometry.OrientedBox;
import cc.sighs.gravityengine.math.geometry.Aabb3d;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.util.*;

/** Standalone, test-only identical-input measurements against baseline/current
 * production classes. No timing assertions and no alternate production solver.
 * Compile/run via scripts/measure-hot-paths.ps1. Timed loops never print. */
public final class HotPathMeasurements {
    private static final com.sun.management.ThreadMXBean MEMORY =
            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    private static volatile long sink;
    private static long contains, evaluations;
    private static final int WARMUP = 500, SAMPLES = 200;
    private static final GravityFieldQuery QUERY = GravityFieldQuery.at(Vec3d.ZERO);

    public static void main(String[] args) throws Exception {
        System.out.println("java=" + System.getProperty("java.version") + " vm=" + ManagementFactory.getRuntimeMXBean().getInputArguments()
                + " warmup=" + WARMUP + " samples=" + SAMPLES + " deterministic-grid seed=0");
        System.out.println("scenario,median_us,p95_us,bytes_per_sample,contains,evaluators,candidates");
        for (int n : new int[]{0,64,512}) for (int providers : new int[]{1,8,32}) fields(n, providers, false);
        fields(512, 8, true);
        for (int n : new int[]{16,256,4096}) for (int queries : new int[]{1,32,128}) scene(n, queries);
        System.out.println("sink=" + sink);
    }

    private static void fields(int count, int providers, boolean global) throws Exception {
        var registry = new GravityFieldRegistry();
        for (int i=0;i<count;i++) {
            var id = new GravityFieldId("bench", "field_"+i);
            var influence = new GravityInfluenceVolume() {
                public boolean contains(Vec3d point) { contains++; return true; }
                public Optional<GravityFieldBounds> finiteBounds() {
                    return global ? Optional.empty() : Optional.of(new GravityFieldBounds(-1,-1,-1,1,1,1));
                }
            };
            registry.publish(new GravityFieldInstance(id, GravityFieldOrder.named(id), q -> {
                evaluations++; return new GravityFieldSample(new Vec3d(0,-0.001,0));
            }, influence, GravityFieldCompositionMode.ADDITIVE, 0), "provider"+(i%providers));
        }
        Method scoped;
        try { scoped = GravityFieldRegistry.class.getMethod("query", Vec3d.class, String.class); }
        catch (NoSuchMethodException baseline) { scoped = null; }
        final Method query = scoped;
        Runnable operation = () -> {
            var values = new ArrayList<cc.sighs.gravityengine.api.field.GravityContribution>();
            for (int i=0;i<providers;i++) {
                String owner = "provider"+i;
                List<GravityFieldInstance> instances;
                if (query == null) instances = registry.query(QUERY.position()).stream()
                        .filter(f -> registry.isOwnedBy(f.id(), owner)).toList();
                else try {
                    @SuppressWarnings("unchecked") var selected = (List<GravityFieldInstance>) query.invoke(registry, QUERY.position(), owner);
                    instances = selected;
                } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
                values.addAll(GravityFieldService.contributions(instances, QUERY));
            }
            sink = GravityFieldService.composeContributions(values, QUERY).contributions().size();
        };
        measure("field-n"+count+"-p"+providers+(global?"-global":"-local"), operation, () -> 0);
    }

    private static void scene(int count, int queries) throws Exception {
        var blocks = new ArrayList<BlockObstacle>();
        for(int i=0;i<count;i++) {
            int x=i%64, z=i/64;
            blocks.add(new BlockObstacle(new CellPos(x,0,z),new Aabb3d(x,0,z,x+1,1,z+1)));
        }
        Aabb3d bounds = new Aabb3d(-8,-8,-8,80,8,80);
        var domain = CollisionCaptureDomain.around(bounds, 8);
        var time = new KinematicStepContext(1,1,1);
        var border = new WorldBorderCollisionSnapshot(-1000,-1000,1000,1000);
        java.util.function.Supplier<CapturedCollisionScene> build = () -> new CapturedCollisionScene(domain,1,1,time,
                new CollisionWorkTracker(CollisionWorkBudget.defaults()),border,blocks,List.of(),List.of(),List.of(),Map.of(),count);
        var body = OrientedBox.axisAligned(new Aabb3d(1.1,0,0.1,1.9,1,0.9));
        var window = new SweepTimeWindow(0,0);
        Method counter;
        try { counter = CapturedCollisionScene.class.getMethod("blockCandidateVisits"); }
        catch (NoSuchMethodException baseline) { counter = null; }
        final Method visits = counter;
        long[] candidates = {0};
        measure("scene-build-n"+count+"-q"+queries, () -> sink=build.get().evaluatedBlockPositions(), () -> 0);
        measure("scene-total-n"+count+"-q"+queries, () -> {
            var scene = build.get();
            for(int q=0;q<queries;q++) sink=scene.query(body,Vec3d.ZERO,window).size();
            try { candidates[0] = visits == null ? (long)count*queries : (long)visits.invoke(scene); }
            catch(ReflectiveOperationException failure) { throw new AssertionError(failure); }
        }, () -> candidates[0]);
        if (queries == 32) measure("scene-point-lookups-n"+count+"-q128", () -> {
            var scene=build.get();
            for(int i=0;i<128;i++) sink=scene.blockObstaclesAt(new CellPos(i%64,0,0)).size();
        }, () -> 0);
    }

    private static void measure(String label, Runnable action, java.util.function.LongSupplier candidates) {
        for(int i=0;i<WARMUP;i++) action.run();
        contains=0; evaluations=0;
        long[] nanos = new long[SAMPLES];
        long thread = Thread.currentThread().getId();
        long bytes = MEMORY.getThreadAllocatedBytes(thread);
        for(int i=0;i<SAMPLES;i++) { long start=System.nanoTime(); action.run(); nanos[i]=System.nanoTime()-start; }
        bytes=MEMORY.getThreadAllocatedBytes(thread)-bytes;
        Arrays.sort(nanos);
        System.out.printf(Locale.ROOT,"%s,%.3f,%.3f,%d,%d,%d,%d%n",label,
                nanos[SAMPLES/2]/1000.0,nanos[(int)(SAMPLES*.95)]/1000.0,bytes/SAMPLES,
                contains/SAMPLES,evaluations/SAMPLES,candidates.getAsLong());
    }
}
