package cc.sighs.gravityengine.math.geometry;

import cc.sighs.gravityengine.api.math.Vec3d;

/** Explicitly call-owned mutable storage reused within one OBB pair solve. */
public final class ObbScratch {
    static final int AXIS_COUNT = 15;
    private static final int COMPONENTS_PER_AXIS = 3;

    /*
     * Packed xyz triples:
     *
     * axis 0 -> [0, 1, 2]
     * axis 1 -> [3, 4, 5]
     * ...
     *
     * SAT/CCD scratch deliberately uses primitive mutable storage.
     * Immutable Vec3d remains the domain/result representation.
     */
    final double[] axes =
            new double[AXIS_COUNT * COMPONENTS_PER_AXIS];

    final double[] penetrations =
            new double[AXIS_COUNT];

    final double[] entryTimes =
            new double[AXIS_COUNT];

    final double[] activeNormals =
            new double[AXIS_COUNT * COMPONENTS_PER_AXIS];

    final int[] activeAxisIndices =
            new int[AXIS_COUNT];

    int activeCount;

    void setAxis(
            int axisIndex,
            double x,
            double y,
            double z
    ) {
        int offset = axisOffset(axisIndex);

        this.axes[offset] = x;
        this.axes[offset + 1] = y;
        this.axes[offset + 2] = z;
    }

    void crossAxes(
            int targetAxisIndex,
            int leftAxisIndex,
            int rightAxisIndex
    ) {
        int left = axisOffset(leftAxisIndex);
        int right = axisOffset(rightAxisIndex);

        double lx = this.axes[left];
        double ly = this.axes[left + 1];
        double lz = this.axes[left + 2];

        double rx = this.axes[right];
        double ry = this.axes[right + 1];
        double rz = this.axes[right + 2];

        setAxis(
                targetAxisIndex,
                ly * rz - lz * ry,
                lz * rx - lx * rz,
                lx * ry - ly * rx
        );
    }

    boolean normalizeAxis(int axisIndex) {
        int offset = axisOffset(axisIndex);

        double x = this.axes[offset];
        double y = this.axes[offset + 1];
        double z = this.axes[offset + 2];

        double lengthSquared =
                x * x + y * y + z * z;

        if (lengthSquared
                <= GeometryTolerance.DEGENERATE_AXIS_LENGTH_SQUARED) {
            return false;
        }

        if (!Double.isFinite(lengthSquared)) {
            throw new IllegalArgumentException(
                    "SAT axis length must be finite");
        }

        /*
         * The existing arithmetic would compute inverseLength == 1.0 and write
         * back the same components. This exact test therefore preserves the
         * normalization semantics while avoiding sqrt/multiply for identity
         * and other bit-exact unit axes.
         */
        if (lengthSquared == 1.0D) {
            return true;
        }

        double inverseLength =
                1.0D / Math.sqrt(lengthSquared);

        this.axes[offset] =
                x * inverseLength;

        this.axes[offset + 1] =
                y * inverseLength;

        this.axes[offset + 2] =
                z * inverseLength;

        return true;
    }

    double axisX(int axisIndex) {
        return this.axes[axisOffset(axisIndex)];
    }

    double axisY(int axisIndex) {
        return this.axes[axisOffset(axisIndex) + 1];
    }

    double axisZ(int axisIndex) {
        return this.axes[axisOffset(axisIndex) + 2];
    }

    double dotAxis(
            int axisIndex,
            Vec3d vector
    ) {
        int offset = axisOffset(axisIndex);

        return this.axes[offset] * vector.x()
                + this.axes[offset + 1] * vector.y()
                + this.axes[offset + 2] * vector.z();
    }

    void clearActive() {
        this.activeCount = 0;
    }

    void addActive(
            int axisIndex,
            double x,
            double y,
            double z
    ) {
        for (int i = 0; i < this.activeCount; i++) {
            int offset =
                    i * COMPONENTS_PER_AXIS;

            double dot =
                    this.activeNormals[offset] * x
                            + this.activeNormals[offset + 1] * y
                            + this.activeNormals[offset + 2] * z;

            if (dot >= GeometryTolerance.SAME_NORMAL_DOT) {
                return;
            }
        }

        int offset =
                this.activeCount * COMPONENTS_PER_AXIS;

        this.activeNormals[offset] = x;
        this.activeNormals[offset + 1] = y;
        this.activeNormals[offset + 2] = z;

        this.activeAxisIndices[this.activeCount] =
                axisIndex;

        this.activeCount++;
    }

    private static int axisOffset(int axisIndex) {
        return axisIndex * COMPONENTS_PER_AXIS;
    }
}
