package cc.sighs.gravityengine.gravity.collision;

import cc.sighs.gravityengine.gravity.kinematic.SweepTimeWindow;
import cc.sighs.gravityengine.gravity.kinematic.geometry.CollisionBody;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;

/** Exact-body collision change relative to an already-authoritative body.
 * This value reports new and deeper penetration separately because different
 * commit families have different policies: geometry changes reject
 * either. Packet comparison separately retains Vanilla's old-occupancy
 * exemption; ordinary physical translation must not deepen penetration. */
public record BodyCollisionDelta(boolean introducedNewPenetration,
                                 boolean deepenedExistingPenetration,
                                 double baselineWorstPenetration,
                                 double candidateWorstPenetration,
                                 Rejection rejection) {
    public boolean legal() { return !introducedNewPenetration && !deepenedExistingPenetration; }
    /** Vanilla-like translation endpoint rule: an existing overlap may remain
     * or deepen; only a collision absent from the authoritative start is new. */
    public boolean introducesNoNewPenetration() { return !introducedNewPenetration; }
    public boolean baselineOverlap() { return baselineWorstPenetration > CollisionTolerances.PENETRATION_EPSILON; }

    public record Rejection(double oldPenetration, double candidatePenetration, CollisionObstacle obstacle) {
        public String obstacleLabel() { return label(obstacle); }
    }

    public static String label(CollisionObstacle obstacle) {
        return switch (obstacle) {
            case BlockObstacle block -> "block:(" + block.blockPos().getX() + ","
                    + block.blockPos().getY() + "," + block.blockPos().getZ() + ")";
            case EntityObstacle entity -> "entity:" + entity.sourceId() + ":" + entity.primitiveId();
            case WorldBorderObstacle ignored -> "world_border";
            case SphereObstacle sphere -> "sphere:(" + sphere.sphere().center().x() + ","
                    + sphere.sphere().center().y() + "," + sphere.sphere().center().z() + ")";
        };
    }

    /** Preserves movement endpoint query order, narrow-phase order, time zero,
     * and aggregate decisions, including when diagnostic retention is off. */
    public static BodyCollisionDelta compare(CollisionBody baseline, CollisionBody candidate,
                                            CollisionObstacleQuery query, boolean retainRejection) {
        var candidateObstacles = query.query(candidate, new Vector3d());
        var oldObstacles = query.query(baseline, new Vector3d());
        var accumulator = new Accumulator(retainRejection);
        var context = new ObbQueryContext();
        for (var obstacle : union(oldObstacles, candidateObstacles)) {
            var oldContact = CollisionNarrowPhase.staticContact(baseline, obstacle, context);
            var candidateContact = CollisionNarrowPhase.staticContact(candidate, obstacle, context);
            accumulator.add(oldContact, candidateContact, obstacle);
        }
        return accumulator.result();
    }

    /** Operation-local baseline shared by a movement or angular trajectory.
     * Cached contacts never become a new baseline at each segment. Translation
     * and attitude/geometry changes use retained depth to reject penetration
     * ratcheting. Packet old-occupancy exemption is a separate policy. New candidates are
     * queried only through the same frozen scene. */
    public static final class Baseline {
        private final CollisionBody body;
        private final CollisionScene scene;
        private final ObbQueryContext context;
        private final double timeTicks;
        private final double normalizedTime;
        private final SweepTimeWindow instant;
        private final List<CollisionObstacle> oldObstacles;
        private final TreeMap<CollisionObstacle, ContactState> contacts =
                new TreeMap<>(CollisionObstacle.STABLE_COMPARATOR);
        private double worstPenetration;

        public Baseline(CollisionBody body, CollisionScene scene, double timeTicks, ObbQueryContext context) {
            this.body = body;
            this.scene = scene;
            this.context = context;
            if (!Double.isFinite(timeTicks) || timeTicks < 0
                    || timeTicks > scene.time().intervalTicks() + CollisionTolerances.TOI_EPSILON) {
                throw new IllegalArgumentException("baseline time outside scene");
            }
            this.timeTicks = timeTicks;
            normalizedTime = Math.max(0, Math.min(1, timeTicks / scene.time().intervalTicks()));
            instant = new SweepTimeWindow(timeTicks, 0);
            oldObstacles = scene.query(body, new Vector3d(), instant);
            for (var obstacle : oldObstacles) baselineContact(obstacle);
            ArrayList<CollisionContact> observedContacts = new ArrayList<>();
            for (ContactState state : contacts.values()) {
                if (state.contact() != null) observedContacts.add(state.contact());
            }
            context.recordCollisionBaseline(oldObstacles.size(), observedContacts);
        }

        public double worstPenetration() { return worstPenetration; }

        public BodyCollisionDelta compare(CollisionBody candidate) {
            return compareAt(candidate, timeTicks);
        }

        /** Compares a candidate at its operation-local obstacle time against
         * this immutable authority baseline. Intermediate candidates never
         * become a new baseline, so penetration cannot ratchet deeper. */
        public BodyCollisionDelta compareAt(CollisionBody candidate, double candidateTimeTicks) {
            if (!Double.isFinite(candidateTimeTicks) || candidateTimeTicks < 0
                    || candidateTimeTicks > scene.time().intervalTicks()
                    + CollisionTolerances.TOI_EPSILON) {
                throw new IllegalArgumentException("candidate time outside scene");
            }
            double candidateNormalizedTime = Math.max(0, Math.min(1,
                    candidateTimeTicks / scene.time().intervalTicks()));
            SweepTimeWindow candidateInstant = new SweepTimeWindow(candidateTimeTicks, 0);
            // Capturing the complete baseline already validated this identity
            // comparison. Do not collect its obstacles a second time.
            if (candidate == body && candidateTimeTicks == timeTicks) {
                return new BodyCollisionDelta(false, false, worstPenetration, worstPenetration, null);
            }
            var accumulator = new Accumulator(true);
            for (var obstacle : union(oldObstacles,
                    scene.query(candidate, new Vector3d(), candidateInstant))) {
                var oldContact = baselineContact(obstacle);
                Optional<CollisionContact> candidateContact =
                        contact(candidate, obstacle, candidateNormalizedTime);
                boolean oldPresent = oldContact.present();
                if (!oldPresent && candidateContact.isPresent()) {
                    oldPresent = touchedAtBaseline(obstacle);
                }
                accumulator.add(oldPresent, oldContact.penetration(),
                        candidateContact, obstacle);
            }
            return accumulator.result();
        }

        /** Each enclosure contains every exact body in the interval. For each
         * obstacle either enclosure can prove the unchanged depth bound. Their
         * overlap is UNKNOWN, never evidence that an exact body collided. */
        public boolean enclosesLegalInterval(CollisionBody first, CollisionBody second) {
            if (first == second) return compare(first).legal();
            var obstacles = union(oldObstacles, union(scene.query(first, new Vector3d(), instant),
                    scene.query(second, new Vector3d(), instant)));
            for (var obstacle : obstacles) {
                ContactState old = baselineContact(obstacle);
                double allowed = old.penetration() + CollisionTolerances.PENETRATION_EPSILON;
                var a = contact(first, obstacle);
                if (a.isEmpty() || a.get().penetration() <= allowed) continue;
                var b = contact(second, obstacle);
                if (b.isPresent() && b.get().penetration() > allowed) return false;
            }
            return true;
        }

        /** Both prescribed trajectories occupy the same absolute window. The
         * rigid envelope is proof-only: overlap requests refinement, not a hit. */
        public boolean enclosesLegalIntervalAt(CollisionBody first, CollisionBody second, SweepTimeWindow window) {
            double lo = window.normalizedStartTicks(scene.time().intervalTicks());
            double hi = Math.min(1, lo + window.normalizedDurationTicks(scene.time().intervalTicks()));
            var obstacles = union(oldObstacles, union(scene.query(first, new Vector3d(), window),
                    scene.query(second, new Vector3d(), window)));
            for (var obstacle : obstacles) {
                ContactState old = baselineContact(obstacle);
                double allowed = old.penetration() + CollisionTolerances.PENETRATION_EPSILON;
                CollisionObstacle enclosure = obstacle;
                if (obstacle instanceof EntityObstacle entity) {
                    var bounds = entity.snapshot().hasRigidBox()
                            ? entity.motion().envelope(entity.snapshot().localBody(), lo, hi)
                            : cc.sighs.gravityengine.gravity.kinematic.geometry.OrientedBox.axisAligned(entity.sweptBounds(lo, hi));
                    enclosure = new EntityObstacle(0, bounds.enclosingAabb(), bounds);
                }
                var a = contact(first, enclosure, 0);
                if (a.isEmpty() || a.get().penetration() <= allowed) continue;
                var b = contact(second, enclosure, 0);
                if (b.isPresent() && b.get().penetration() > allowed) return false;
            }
            return true;
        }

        boolean hasMeaningfulOverlap() {
            return worstPenetration > CollisionTolerances.PENETRATION_EPSILON;
        }

        /** Intermediate route legs cannot adopt deeper occupancy as a new start. */
        boolean permitsExistingDepth(CollisionContact current) {
            ContactState old = baselineContact(current.obstacle());
            return old.present() && old.penetration() > CollisionTolerances.PENETRATION_EPSILON
                    && current.penetration() <= old.penetration() + CollisionTolerances.PENETRATION_EPSILON;
        }

        private record ContactState(
                boolean present,
                double penetration,
                CollisionContact contact
        ) {}

        private ContactState baselineContact(CollisionObstacle obstacle) {
            return contacts.computeIfAbsent(obstacle, key -> {
                var contact = contact(body, key);
                if (contact.isPresent()) {
                    double depth = contact.get().penetration();
                    worstPenetration = Math.max(worstPenetration, depth);
                    return new ContactState(true, depth, contact.get());
                }
                return new ContactState(false, 0, null);
            });
        }

        /** Touching matters only if the candidate actually penetrates this
         * obstacle. Defer that distinction so baseline capture preserves the
         * former one-narrow-phase-test-per-candidate work-budget contract. */
        private boolean touchedAtBaseline(CollisionObstacle obstacle) {
            if (!context.workTracker().recordNarrowPhaseTest()) {
                throw new CollisionComplexityLimitException(
                        "body baseline contact classification exceeds collision budget");
            }
            var current = CollisionNarrowPhase.currentContactProbeResult(
                    body, new Vector3d(), obstacle, normalizedTime, context);
            if (current.indeterminate()) {
                throw new CollisionComplexityLimitException(
                        "body baseline contact classification indeterminate");
            }
            return current.initialState() != SweepInitialState.SEPARATED;
        }

        private Optional<CollisionContact> contact(CollisionBody candidate, CollisionObstacle obstacle) {
            return contact(candidate, obstacle, normalizedTime);
        }

        private Optional<CollisionContact> contact(CollisionBody candidate,
                                                   CollisionObstacle obstacle,
                                                   double atNormalizedTime) {
            if (!context.workTracker().recordNarrowPhaseTest()) {
                throw new CollisionComplexityLimitException("body collision delta exceeds collision budget");
            }
            return CollisionNarrowPhase.staticContactAt(
                    candidate, obstacle, atNormalizedTime, context);
        }
    }

    private static List<CollisionObstacle> union(List<CollisionObstacle> old, List<CollisionObstacle> candidates) {
        var union = new ArrayList<>(old);
        for (var obstacle : candidates) {
            if (union.stream().noneMatch(existing -> CollisionObstacle.STABLE_COMPARATOR.compare(existing, obstacle) == 0)) {
                union.add(obstacle);
            }
        }
        return union;
    }

    private static final class Accumulator {
        private final boolean retainRejection;
        private boolean introduced, deepened;
        private double oldWorst, candidateWorst;
        private Rejection rejection;
        private Accumulator(boolean retainRejection) { this.retainRejection = retainRejection; }

        private void add(Optional<CollisionContact> old, Optional<CollisionContact> candidate, CollisionObstacle obstacle) {
            add(old.isPresent(), old.map(CollisionContact::penetration).orElse(0.0), candidate, obstacle);
        }

        private void add(boolean oldPresent, double oldDepth, Optional<CollisionContact> candidate, CollisionObstacle obstacle) {
            oldWorst = Math.max(oldWorst, oldDepth);
            if (candidate.isEmpty()) return;
            double depth = candidate.get().penetration();
            candidateWorst = Math.max(candidateWorst, depth);
            boolean isNew = !oldPresent;
            boolean isDeeper = !isNew && depth > oldDepth + CollisionTolerances.PENETRATION_EPSILON;
            introduced |= isNew;
            deepened |= isDeeper;
            if (retainRejection && rejection == null && (isNew || isDeeper)) {
                rejection = new Rejection(oldDepth, depth, obstacle);
            }
        }

        private BodyCollisionDelta result() {
            return new BodyCollisionDelta(introduced, deepened, oldWorst, candidateWorst, rejection);
        }
    }
}
