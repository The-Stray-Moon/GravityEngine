package cc.sighs.gravityengine.math.geometry;

import org.joml.Vector3d;

/** Explicitly call-owned mutable storage reused within one OBB pair solve. */
public final class ObbScratch {
    static final int AXIS_COUNT = 15;

    final Vector3d[] axes = vectors(AXIS_COUNT);
    final double[] penetrations = new double[AXIS_COUNT];
    final double[] entryTimes = new double[AXIS_COUNT];
    final double[] activeNormals = new double[AXIS_COUNT * 3];
    final int[] activeAxisIndices = new int[AXIS_COUNT];
    int activeCount;

    void clearActive() {
        this.activeCount = 0;
    }

    void addActive(int axisIndex, double x, double y, double z) {
        for (int i = 0; i < this.activeCount; i++) {
            int offset = i * 3;
            double dot = this.activeNormals[offset] * x
                    + this.activeNormals[offset + 1] * y
                    + this.activeNormals[offset + 2] * z;
            if (dot >= GeometryTolerance.SAME_NORMAL_DOT) return;
        }
        int offset = this.activeCount * 3;
        this.activeNormals[offset] = x;
        this.activeNormals[offset + 1] = y;
        this.activeNormals[offset + 2] = z;
        this.activeAxisIndices[this.activeCount] = axisIndex;
        this.activeCount++;
    }

    private static Vector3d[] vectors(int count) {
        Vector3d[] result = new Vector3d[count];
        for (int i = 0; i < count; i++) result[i] = new Vector3d();
        return result;
    }
}
