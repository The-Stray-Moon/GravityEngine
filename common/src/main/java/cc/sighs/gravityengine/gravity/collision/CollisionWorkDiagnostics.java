package cc.sighs.gravityengine.gravity.collision;

/**
 * Immutable primitive counters describing how much work one collision operation
 * performed. Strings are formatted only when logging; the hot path stores and
 * reads raw ints/longs.
 */
public record CollisionWorkDiagnostics(
        long blockPositionsVisited,
        long blockShapesEvaluated,
        int blockPrimitiveCount,
        int obstaclesProduced,
        int sceneQueries,
        int dynamicSurfaceSnapshots,
        int worldBorderSnapshots,
        int sourceSphereSnapshots,
        int narrowPhaseTests,
        int contactsCollected,
        int recoveryPasses,
        int voxelReducerChecks,
        int voxelIndexRegistrations,
        boolean limitExceeded,
        String limitReason
) {
    public static CollisionWorkDiagnostics empty() {
        return new CollisionWorkDiagnostics(
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                false,
                null
        );
    }
}