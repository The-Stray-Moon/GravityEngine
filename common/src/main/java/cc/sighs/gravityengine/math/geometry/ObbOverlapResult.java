package cc.sighs.gravityengine.math.geometry;

import cc.sighs.gravityengine.api.math.Vec3d;
import java.util.Objects;

/** Immutable, unambiguous static SAT classification for one ordered OBB pair. */
public final class ObbOverlapResult {
    public enum Status {
        SEPARATED,
        TOUCHING,
        OVERLAPPING
    }

    private final Status status;
    private final double penetration;
    private final double normalX;
    private final double normalY;
    private final double normalZ;
    private final int axisIndex;

    private ObbOverlapResult(
            Status status,
            double penetration,
            double normalX,
            double normalY,
            double normalZ,
            int axisIndex
    ) {
        this.status = Objects.requireNonNull(status, "status");
        this.penetration = penetration;
        this.normalX = normalX;
        this.normalY = normalY;
        this.normalZ = normalZ;
        this.axisIndex = axisIndex;
    }

    static ObbOverlapResult separated(int axisIndex) {
        return new ObbOverlapResult(Status.SEPARATED, 0.0D, 0.0D, 0.0D, 0.0D, axisIndex);
    }

    static ObbOverlapResult touching(double x, double y, double z, int axisIndex) {
        return new ObbOverlapResult(Status.TOUCHING, 0.0D, x, y, z, axisIndex);
    }

    static ObbOverlapResult overlapping(
            double penetration,
            double x,
            double y,
            double z,
            int axisIndex
    ) {
        if (!Double.isFinite(penetration) || penetration <= 0.0D) {
            throw new IllegalArgumentException("overlap penetration must be finite and positive");
        }
        return new ObbOverlapResult(Status.OVERLAPPING, penetration, x, y, z, axisIndex);
    }

    public Status status() { return this.status; }
    public double penetration() { return this.penetration; }
    public int axisIndex() { return this.axisIndex; }
    public boolean hasNormal() { return this.status != Status.SEPARATED; }
    public boolean hasMtv() { return this.status == Status.OVERLAPPING; }

    public Vec3d normal() {
        if (!hasNormal()) throw new IllegalStateException("separated shapes have no collision normal");
        return new Vec3d(
                this.normalX, this.normalY, this.normalZ
        );
    }

    public Vec3d mtv() {
        if (!hasMtv()) throw new IllegalStateException("only overlapping shapes have an MTV");
        return new Vec3d(
                this.normalX * this.penetration,
                this.normalY * this.penetration,
                this.normalZ * this.penetration
        );
    }
}
