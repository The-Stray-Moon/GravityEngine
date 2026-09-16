package cc.sighs.gravityengine.math.geometry;

import org.joml.Vector3d;

import java.util.Arrays;
import java.util.Objects;

/** Immutable bounded storage for cotemporal SAT normals in candidate-axis order. */
public final class ActiveAxes {
    private static final ActiveAxes EMPTY = new ActiveAxes(new double[0], new int[0]);

    private final double[] normals;
    private final int[] axisIndices;

    private ActiveAxes(double[] normals, int[] axisIndices) {
        this.normals = normals;
        this.axisIndices = axisIndices;
    }

    static ActiveAxes empty() { return EMPTY; }

    static ActiveAxes copyOf(ObbScratch scratch) {
        if (scratch.activeCount == 0) return EMPTY;
        return new ActiveAxes(
                Arrays.copyOf(scratch.activeNormals, scratch.activeCount * 3),
                Arrays.copyOf(scratch.activeAxisIndices, scratch.activeCount)
        );
    }

    public int size() { return this.axisIndices.length; }
    public boolean isEmpty() { return this.axisIndices.length == 0; }

    public int axisIndex(int index) {
        return this.axisIndices[index];
    }

    public Vector3d normal(int index, Vector3d dest) {
        Objects.requireNonNull(dest, "dest");
        if (index < 0 || index >= size()) throw new IndexOutOfBoundsException(index);
        int offset = index * 3;
        return dest.set(
                this.normals[offset],
                this.normals[offset + 1],
                this.normals[offset + 2]
        );
    }
}
