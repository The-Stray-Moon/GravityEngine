package cc.sighs.gravityengine.gravity.integration.compat.sable;

import cc.sighs.gravityengine.api.math.Vec3d;
import net.minecraft.core.BlockPos;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Transient evidence attached to the native mass tracker. Native build/edit
 * calls are the only producers; no second world scan or mass authority exists. */
public final class SableMassPoints {
    public static final int MAX_POINTS = 65_536;
    public interface Access {
        SableMassPoints gravityengine$massPoints();
        void gravityengine$massPoints(SableMassPoints points);
    }
    public record Point(Vec3d position, double mass) {}
    // First moments are cell-relative: plot coordinates can be very large.
    // Native replacement adds the new material before subtracting the old one.
    // Exact binary-double operands cancel without a scale-dependent zero cutoff.
    // This evidence is updated only by native build/edit calls, not each physics step.
    private record Moment(BigDecimal mass, BigDecimal x, BigDecimal y, BigDecimal z) {
        private static final Moment ZERO = new Moment(BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO);
        Moment add(Vec3d local, double delta) {
            var amount = new BigDecimal(delta);
            return new Moment(mass.add(amount), x.add(new BigDecimal(local.x()).multiply(amount)),
                    y.add(new BigDecimal(local.y()).multiply(amount)),
                    z.add(new BigDecimal(local.z()).multiply(amount)));
        }
        Vec3d center() {
            return new Vec3d(x.divide(mass, MathContext.DECIMAL128).doubleValue(),
                    y.divide(mass, MathContext.DECIMAL128).doubleValue(),
                    z.divide(mass, MathContext.DECIMAL128).doubleValue());
        }
    }
    private final Map<BlockPos, Moment> points = new HashMap<>();
    private List<Point> snapshot;
    private boolean complete = true;
    private int editing;
    public void beginEdit() { editing++; snapshot = null; }
    public void endEdit(boolean succeeded) {
        if (editing <= 0) throw new IllegalStateException("unbalanced native mass edit");
        editing--;
        if (!succeeded) incomplete();
    }

    public void add(BlockPos position, Vec3d center, double deltaMass) {
        snapshot = null;
        if (!complete) return;
        if (!center.isFinite() || !Double.isFinite(deltaMass)) { incomplete(); return; }
        if (deltaMass == 0) return;
        var previous = points.get(position);
        var local = center.subtract(new Vec3d(position.getX(), position.getY(), position.getZ()));
        if (!local.isFinite()) { incomplete(); return; }
        var moment = (previous == null ? Moment.ZERO : previous).add(local, deltaMass);
        if (moment.mass().signum() < 0 || !Double.isFinite(moment.mass().doubleValue())) {
            incomplete(); return;
        }
        if (moment.mass().signum() == 0) {
            if (moment.x().signum() != 0 || moment.y().signum() != 0 || moment.z().signum() != 0) {
                incomplete(); return;
            }
            points.remove(position); return;
        }
        if (!moment.center().isFinite()) { incomplete(); return; }
        if (previous == null && points.size() >= MAX_POINTS) { incomplete(); return; }
        points.put(position.immutable(), moment);
    }

    private void incomplete() {
        if(complete) org.slf4j.LoggerFactory.getLogger(SableMassPoints.class).warn(
                "Native mass evidence rejected; distributed gravity waits for the next native tracker rebuild");
        complete = false; points.clear(); snapshot=null;
    }
    /** Null is explicitly incomplete evidence, never an empty successful capture. */
    public List<Point> snapshot() {
        if (!complete || editing != 0) return null;
        if (snapshot == null) snapshot = points.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(entry -> new Point(entry.getValue().center()
                        .add(new Vec3d(entry.getKey().getX(), entry.getKey().getY(), entry.getKey().getZ())),
                        entry.getValue().mass().doubleValue())).toList();
        return snapshot;
    }
}
