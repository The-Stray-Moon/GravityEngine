package cc.sighs.gravityengine.math.geometry;

import cc.sighs.gravityengine.api.math.Vec3d;
import java.util.Objects;
import java.util.Optional;

/** Immutable world-axis-aligned bounds containing only kernel-required operations. */
public record Aabb3d(
        double minX,
        double minY,
        double minZ,
        double maxX,
        double maxY,
        double maxZ
) {
    public Aabb3d {
        requireFinite(minX, "minX");
        requireFinite(minY, "minY");
        requireFinite(minZ, "minZ");
        requireFinite(maxX, "maxX");
        requireFinite(maxY, "maxY");
        requireFinite(maxZ, "maxZ");
        if (minX > maxX || minY > maxY || minZ > maxZ) {
            throw new IllegalArgumentException("minimum bounds must not exceed maximum bounds");
        }
    }

    public Vec3d center() {
        return new Vec3d(
                (this.minX + this.maxX) * 0.5D,
                (this.minY + this.maxY) * 0.5D,
                (this.minZ + this.maxZ) * 0.5D
        );
    }

    public Vec3d size() {
        return new Vec3d(
                this.maxX - this.minX,
                this.maxY - this.minY,
                this.maxZ - this.minZ
        );
    }

    public Vec3d halfExtents() {
        return new Vec3d(
                (this.maxX - this.minX) * 0.5D,
                (this.maxY - this.minY) * 0.5D,
                (this.maxZ - this.minZ) * 0.5D
        );
    }

    public boolean intersects(Aabb3d other) {
        Objects.requireNonNull(other, "other");
        return this.maxX >= other.minX && other.maxX >= this.minX
                && this.maxY >= other.minY && other.maxY >= this.minY
                && this.maxZ >= other.minZ && other.maxZ >= this.minZ;
    }

    public boolean contains(Vec3d point) {
        Objects.requireNonNull(point, "point");
        OrthonormalFrame3d.requireFinite(point, "point");
        return point.x() >= this.minX && point.x() <= this.maxX
                && point.y() >= this.minY && point.y() <= this.maxY
                && point.z() >= this.minZ && point.z() <= this.maxZ;
    }

    public Aabb3d expanded(double amount) {
        requireFinite(amount, "amount");
        return new Aabb3d(
                this.minX - amount, this.minY - amount, this.minZ - amount,
                this.maxX + amount, this.maxY + amount, this.maxZ + amount
        );
    }

    public Aabb3d translated(Vec3d displacement) {
        Objects.requireNonNull(displacement, "displacement");
        OrthonormalFrame3d.requireFinite(displacement, "displacement");
        return new Aabb3d(
                this.minX + displacement.x(),
                this.minY + displacement.y(),
                this.minZ + displacement.z(),
                this.maxX + displacement.x(),
                this.maxY + displacement.y(),
                this.maxZ + displacement.z()
        );
    }

    /**
     * Conservative directional expansion of an {@code Aabb3d} along a free
     * displacement vector: each minimum coordinate shifts by the negative
     * displacement component and each maximum coordinate by the positive
     * component.  This is the pure equivalent of Minecraft's
     * {@code expandTowards} and stays inside the neutral geometry layer.
     */
    public Aabb3d expandTowards(Vec3d displacement) {
        Objects.requireNonNull(displacement, "displacement");
        OrthonormalFrame3d.requireFinite(displacement, "displacement");
        double dx = displacement.x();
        double dy = displacement.y();
        double dz = displacement.z();
        return new Aabb3d(
                dx < 0.0D ? this.minX + dx : this.minX,
                dy < 0.0D ? this.minY + dy : this.minY,
                dz < 0.0D ? this.minZ + dz : this.minZ,
                dx > 0.0D ? this.maxX + dx : this.maxX,
                dy > 0.0D ? this.maxY + dy : this.maxY,
                dz > 0.0D ? this.maxZ + dz : this.maxZ
        );
    }

    public Aabb3d move(Vec3d displacement) {
        return translated(displacement);
    }

    public Aabb3d inflate(double amount) {
        return expanded(amount);
    }

    public Aabb3d union(Aabb3d other) {
        Objects.requireNonNull(other, "other");
        return new Aabb3d(
                Math.min(this.minX, other.minX),
                Math.min(this.minY, other.minY),
                Math.min(this.minZ, other.minZ),
                Math.max(this.maxX, other.maxX),
                Math.max(this.maxY, other.maxY),
                Math.max(this.maxZ, other.maxZ)
        );
    }

    public Optional<Aabb3d> intersection(Aabb3d other) {
        Objects.requireNonNull(other, "other");
        double x0 = Math.max(this.minX, other.minX);
        double y0 = Math.max(this.minY, other.minY);
        double z0 = Math.max(this.minZ, other.minZ);
        double x1 = Math.min(this.maxX, other.maxX);
        double y1 = Math.min(this.maxY, other.maxY);
        double z1 = Math.min(this.maxZ, other.maxZ);
        if (x0 > x1 || y0 > y1 || z0 > z1) return Optional.empty();
        return Optional.of(new Aabb3d(x0, y0, z0, x1, y1, z1));
    }

    public Obb3d asObb() {
        return new Obb3d(
                (this.minX + this.maxX) * 0.5D,
                (this.minY + this.maxY) * 0.5D,
                (this.minZ + this.maxZ) * 0.5D,
                (this.maxX - this.minX) * 0.5D,
                (this.maxY - this.minY) * 0.5D,
                (this.maxZ - this.minZ) * 0.5D,
                OrthonormalFrame3d.IDENTITY
        );
    }

    private static void requireFinite(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite: " + value);
        }
    }
}
