package cc.sighs.gravityengine.gravity.collision;

import java.util.Objects;

/**
 * Mutable, operation-owned monotonic budget accounting for one collision
 * operation.
 *
 * <p>Every bounded counter only increases until a bound is exceeded, after
 * which the tracker records the first limit reason and subsequent bounded
 * records return {@code false}.</p>
 *
 * <p>The tracker contains only accounting used by the current collision and
 * geometry-transition pipelines. Future speculative traversal or step-specific
 * budgets must be introduced only together with a concrete consumer.</p>
 */
public final class CollisionWorkTracker {
    private final CollisionWorkBudget budget;

    private long blockPositionsVisited;
    private long blockShapesEvaluated;
    private int blockPrimitiveCount;
    private int obstaclesProduced;
    private int sceneQueries;
    private int dynamicSurfaceSnapshots;
    private int worldBorderSnapshots;
    private int sourceSphereSnapshots;
    private int narrowPhaseTests;
    private int contactsCollected;
    private int recoveryPasses;
    private int voxelReducerChecks;
    private int voxelIndexRegistrations;

    private boolean limitExceeded;
    private String limitReason;

    public CollisionWorkTracker(CollisionWorkBudget budget) {
        this.budget = Objects.requireNonNull(budget, "budget");
    }

    /** Records one visited block position. */
    public boolean recordBlockPosition() {
        if (this.limitExceeded) return false;
        if (this.budget.maxBlockPositions() - this.blockPositionsVisited < 1L) {
            flagLimit(
                    "maxBlockPositions=" + this.budget.maxBlockPositions()
            );
            return false;
        }
        this.blockPositionsVisited++;
        return true;
    }

    /** Diagnostic-only. */
    public boolean recordBlockShapeEvaluated() {
        if (this.limitExceeded) return false;
        if (this.blockShapesEvaluated == Long.MAX_VALUE) {
            flagLimit("blockShapesEvaluated overflow");
            return false;
        }
        this.blockShapesEvaluated++;
        return true;
    }

    /** Diagnostic-only primitive count. */
    public void recordBlockPrimitives(int count) {
        if (count <= 0) return;

        if ((long) this.blockPrimitiveCount + count > Integer.MAX_VALUE) {
            this.blockPrimitiveCount = Integer.MAX_VALUE;
            flagLimit("blockPrimitiveCount overflow");
            return;
        }

        this.blockPrimitiveCount += count;
    }

    /** Diagnostic-only scene query count. */
    public void recordSceneQuery() {
        if (this.sceneQueries == Integer.MAX_VALUE) {
            flagLimit("sceneQueries overflow");
            return;
        }
        this.sceneQueries++;
    }

    /** Diagnostic-only moving-surface snapshot count. */
    public void recordDynamicSurfaceSnapshot() {
        if (this.dynamicSurfaceSnapshots == Integer.MAX_VALUE) {
            flagLimit("dynamicSurfaceSnapshots overflow");
            return;
        }
        this.dynamicSurfaceSnapshots++;
    }

    /** Diagnostic-only world-border snapshot count. */
    public void recordWorldBorderSnapshot() {
        if (this.worldBorderSnapshots == Integer.MAX_VALUE) {
            flagLimit("worldBorderSnapshots overflow");
            return;
        }
        this.worldBorderSnapshots++;
    }

    /** Diagnostic-only source-sphere snapshot count. */
    public void recordSourceSphereSnapshot() {
        if (this.sourceSphereSnapshots == Integer.MAX_VALUE) {
            flagLimit("sourceSphereSnapshots overflow");
            return;
        }
        this.sourceSphereSnapshots++;
    }

    public boolean recordObstacles(int count) {
        if (count <= 0) return true;
        if (this.limitExceeded) return false;

        if (this.budget.maxObstaclePrimitives()
                - this.obstaclesProduced < count) {
            flagLimit(
                    "maxObstaclePrimitives="
                            + this.budget.maxObstaclePrimitives()
            );
            return false;
        }

        this.obstaclesProduced += count;
        return true;
    }

    public boolean recordNarrowPhaseTest() {
        if (this.limitExceeded) return false;

        if (this.budget.maxNarrowPhaseTests()
                - this.narrowPhaseTests < 1) {
            flagLimit(
                    "maxNarrowPhaseTests="
                            + this.budget.maxNarrowPhaseTests()
            );
            return false;
        }

        this.narrowPhaseTests++;
        return true;
    }

    public boolean recordContacts(int count) {
        if (count <= 0) return true;
        if (this.limitExceeded) return false;

        if (this.budget.maxCollectedContacts()
                - this.contactsCollected < count) {
            flagLimit(
                    "maxCollectedContacts="
                            + this.budget.maxCollectedContacts()
            );
            return false;
        }

        this.contactsCollected += count;
        return true;
    }

    public boolean recordRecoveryPass() {
        if (this.limitExceeded) return false;

        if (this.budget.maxRecoveryPasses()
                - this.recoveryPasses < 1) {
            flagLimit(
                    "maxRecoveryPasses="
                            + this.budget.maxRecoveryPasses()
            );
            return false;
        }

        this.recoveryPasses++;
        return true;
    }

    public boolean recordVoxelReducerChecks(int count) {
        if (count <= 0) return true;
        if (this.limitExceeded) return false;

        if (this.budget.maxVoxelReducerChecks()
                - this.voxelReducerChecks < count) {
            flagLimit(
                    "maxVoxelReducerChecks="
                            + this.budget.maxVoxelReducerChecks()
            );
            return false;
        }

        this.voxelReducerChecks += count;
        return true;
    }

    public boolean recordVoxelIndexRegistrations(int count) {
        if (count <= 0) return true;
        if (this.limitExceeded) return false;

        if (this.budget.maxVoxelIndexRegistrations()
                - this.voxelIndexRegistrations < count) {
            flagLimit(
                    "maxVoxelIndexRegistrations="
                            + this.budget.maxVoxelIndexRegistrations()
            );
            return false;
        }

        this.voxelIndexRegistrations += count;
        return true;
    }

    public boolean limitExceeded() {
        return this.limitExceeded;
    }

    public String limitReason() {
        return this.limitReason;
    }

    public int remainingNarrowPhaseTests() {
        return Math.max(
                0,
                this.budget.maxNarrowPhaseTests()
                        - this.narrowPhaseTests
        );
    }

    public boolean canAffordNarrowPhaseTests(int required) {
        return !limitExceeded()
                && required > 0
                && remainingNarrowPhaseTests() >= required;
    }

    public CollisionWorkDiagnostics snapshot() {
        return new CollisionWorkDiagnostics(
                this.blockPositionsVisited,
                this.blockShapesEvaluated,
                this.blockPrimitiveCount,
                this.obstaclesProduced,
                this.sceneQueries,
                this.dynamicSurfaceSnapshots,
                this.worldBorderSnapshots,
                this.sourceSphereSnapshots,
                this.narrowPhaseTests,
                this.contactsCollected,
                this.recoveryPasses,
                this.voxelReducerChecks,
                this.voxelIndexRegistrations,
                this.limitExceeded,
                this.limitReason
        );
    }

    private void flagLimit(String reason) {
        if (!this.limitExceeded) {
            this.limitExceeded = true;
            this.limitReason = reason;
        }
    }
}