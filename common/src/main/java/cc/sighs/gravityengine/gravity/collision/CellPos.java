package cc.sighs.gravityengine.gravity.collision;

/**
 * Immutable integer block-cell provenance.
 *
 * <p>This is neutral kernel state, not a Minecraft position type. The target
 * adapter converts it to/from a platform position at the boundary.</p>
 *
 * <p>Natural ordering is deterministic and is defined as {@code x}, then
 * {@code y}, then {@code z}, matching the collision provenance ordering used
 * by the authoritative NeoForge target.</p>
 */
public record CellPos(int x, int y, int z) implements Comparable<CellPos> {
    public CellPos offset(int dx, int dy, int dz) {
        return new CellPos(this.x + dx, this.y + dy, this.z + dz);
    }

    @Override
    public int compareTo(CellPos other) {
        int result = Integer.compare(this.x, other.x);
        if (result != 0) {
            return result;
        }
        result = Integer.compare(this.y, other.y);
        if (result != 0) {
            return result;
        }
        return Integer.compare(this.z, other.z);
    }
}
