package cc.sighs.gravityengine.math.geometry;

/** Explicitly caller-owned mutable destination for allocation-controlled bounds queries. */
public final class MutableAabb3d {
    private double minX;
    private double minY;
    private double minZ;
    private double maxX;
    private double maxY;
    private double maxZ;

    public MutableAabb3d set(
            double minX,
            double minY,
            double minZ,
            double maxX,
            double maxY,
            double maxZ
    ) {
        if (!Double.isFinite(minX) || !Double.isFinite(minY) || !Double.isFinite(minZ)
                || !Double.isFinite(maxX) || !Double.isFinite(maxY) || !Double.isFinite(maxZ)) {
            throw new IllegalArgumentException("bounds must be finite");
        }
        if (minX > maxX || minY > maxY || minZ > maxZ) {
            throw new IllegalArgumentException("minimum bounds must not exceed maximum bounds");
        }
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxY = maxY;
        this.maxZ = maxZ;
        return this;
    }

    public double minX() { return this.minX; }
    public double minY() { return this.minY; }
    public double minZ() { return this.minZ; }
    public double maxX() { return this.maxX; }
    public double maxY() { return this.maxY; }
    public double maxZ() { return this.maxZ; }

    public Aabb3d immutable() {
        return new Aabb3d(
                this.minX, this.minY, this.minZ,
                this.maxX, this.maxY, this.maxZ
        );
    }
}
