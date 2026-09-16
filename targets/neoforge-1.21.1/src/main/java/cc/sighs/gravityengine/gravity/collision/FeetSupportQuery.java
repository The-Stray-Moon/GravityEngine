package cc.sighs.gravityengine.gravity.collision;

import cc.sighs.gravityengine.gravity.GravityFrame;
import cc.sighs.gravityengine.gravity.kinematic.SweepTimeWindow;
import cc.sighs.gravityengine.gravity.kinematic.geometry.CollisionBody;
import cc.sighs.gravityengine.gravity.kinematic.geometry.CharacterCapsule;
import cc.sighs.gravityengine.gravity.kinematic.geometry.OrientedBox;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Selects exposed finite support from actual lower-capsule sweep witnesses.
 * Generic boxes retain their exact bottom-face intersection. Movement and terminal
 * support share this query; distance and obstacle time are explicit. It owns no hard response. */
public final class FeetSupportQuery {
    private FeetSupportQuery() {}

    public record Candidate(SupportFaceIdentity identity, CollisionObstacle obstacle,
                            Vector3d normal, Vector3d point, double distance, double upDot, double obstacleTime) {
        public Candidate { normal = new Vector3d(normal); point = new Vector3d(point); }
        @Override public Vector3d normal() { return new Vector3d(normal); }
        @Override public Vector3d point() { return new Vector3d(point); }
        public GravitySupportContact support() {
            return new GravitySupportContact(normal, obstacle.velocityAt(point, obstacleTime), point,
                    obstacle instanceof BlockObstacle
                            ? (identity.provesBlockFace(normal, point)
                                ? GravitySupportContact.SupportGeometryKind.TRUSTED_CURRENT_BLOCK_FACE
                                : GravitySupportContact.SupportGeometryKind.SWEEP_CONTACT)
                            : GravitySupportContact.SupportGeometryKind.REAL_OBSTACLE_FACE, identity);
        }
    }

    public record Result(List<Candidate> candidates, boolean indeterminate) {
        public Result { candidates = List.copyOf(candidates); }
        public Candidate selected() { return candidates.isEmpty() ? null : candidates.getFirst(); }
    }

    public static Vector3d anchor(CollisionBody body, GravityFrame frame) {
        if (body instanceof CharacterCapsule c) return c.bottomPoint();
        Vector3d up = new Vector3d(frame.up().x, frame.up().y, frame.up().z);
        double offset = body instanceof OrientedBox box ? SupportRegionPolicy.bottomOffset(box, up)
                : ((CharacterCapsule)body).radius() + ((CharacterCapsule)body).halfSegmentLength();
        return body.center().fma(-offset, up);
    }

    public static Result query(
            CollisionBody body,
            GravityFrame frame,
            CollisionScene scene,
            double time,
            double distance,
            SupportFaceIdentity previous,
            ObbQueryContext context
    ) {
        if (!Double.isFinite(time)
                || time < 0.0D
                || time > scene.time().intervalTicks()
                || !Double.isFinite(distance)
                || distance < 0.0D) {
            throw new IllegalArgumentException(
                    "query interval"
            );
        }

        CollisionWorkTracker work =
                context.supportWorkTracker();

        Vector3d feet =
                anchor(body, frame);

        Vector3d up = new Vector3d(frame.up().x, frame.up().y, frame.up().z);

        double epsilon =
                GravityGroundProbe.PROBE_DISTANCE;

        /*
         * Broad-phase only. Never use this envelope as support geometry.
         */
        CollisionBody envelope = OrientedBox.axisAligned(body.enclosingAabb().inflate(epsilon));

        List<CollisionObstacle> obstacles =
                scene.querySupport(
                        envelope,
                        new Vector3d(up)
                                .mul(-distance),
                        new SweepTimeWindow(
                                time,
                                0.0D)
                );

        if (work.limitExceeded()) {
            return new Result(
                    List.of(),
                    true
            );
        }

        List<Candidate> candidates =
                new ArrayList<>();

        for (CollisionObstacle obstacle
                : obstacles) {

            if (!work.recordNarrowPhaseTest()) {
                return new Result(
                        List.of(),
                        true
                );
            }

            OrientedBox faceBody;

            if (obstacle instanceof BlockObstacle block) {
                faceBody =
                        OrientedBox.axisAligned(
                                block.bounds());
            } else if (obstacle
                    instanceof EntityObstacle entity) {
                if (!entity.snapshot().hasRigidBox()) continue;
                faceBody =
                        entity.bodyAt(
                                time
                                        / scene.time()
                                        .intervalTicks());
            } else {
                /*
                 * Curved obstacles and borders remain hard occupancy only.
                 * Never invent a finite support face for them.
                 */
                continue;
            }

            if (body instanceof CharacterCapsule capsule) {
                // The actual rounded body moves down through the frozen scene. The
                // contact-sized lift permits current touching support without a flat foot.
                double travel = distance + 2*epsilon;
                CharacterCapsule lifted = capsule.move(new Vector3d(up).mul(epsilon));
                var result = CollisionNarrowPhase.currentContactProbeResult(lifted,
                        new Vector3d(up).mul(-travel),obstacle,time/scene.time().intervalTicks(),context);
                if (result.indeterminate()) return new Result(List.of(),true);
                for (var contact : result.contacts()) {
                    double down = travel*contact.timeOfImpact()-epsilon;
                    CharacterCapsule at = capsule.move(new Vector3d(up).mul(-down));
                    Vector3d witness = at.closestPointTo(contact.point());
                    // Lower endpoint hemisphere plus the existing contact-sized band.
                    if (witness.sub(at.center()).dot(at.axis()) > -at.halfSegmentLength()+epsilon) continue;
                    Vector3d normal = contact.normal(), point = contact.point();
                    double upDot = normal.dot(up);
                    if (upDot <= CollisionTolerances.ENTERING_PLANE_EPSILON) continue;
                    Vector3d local = faceBody.worldPointToLocal(point,new Vector3d());
                    Vector3d h = faceBody.halfExtents();
                    int face = 0; double nearest = Double.POSITIVE_INFINITY, alignment = -Double.MAX_VALUE;
                    for (int f=0;f<6;f++) {
                        double gap = Math.abs(local.get(f/2)-(f%2==0 ? -1:1)*h.get(f/2));
                        double facing = faceBody.localVectorToWorld(new Vector3d().setComponent(f/2,f%2==0 ? -1:1),new Vector3d()).dot(normal);
                        if (gap < nearest-1e-12 || Math.abs(gap-nearest)<=1e-12 && facing>alignment) {
                            nearest=gap; alignment=facing; face=f;
                        }
                    }
                    Vector3d sample = new Vector3d(point).fma(Math.max(0,down),up);
                    if (occluded(sample,point,normal,obstacle,obstacles,time/scene.time().intervalTicks(),context)) continue;
                    SupportFaceIdentity identity = obstacle instanceof BlockObstacle block
                            ? new SupportFaceIdentity(block.blockPos(),block.bounds(),-1,face)
                            : SupportFaceIdentity.dynamic((EntityObstacle)obstacle,face);
                    candidates.add(new Candidate(
                            identity,
                            obstacle,
                            normal,
                            point,
                            Math.max(0.0D, down),
                            upDot,
                            time / scene.time().intervalTicks()
                    ));
                }
                continue;
            }

            for (int face = 0;
                 face < 6;
                 face++) {

                int axis =
                        face / 2;

                double sign =
                        face % 2 == 0
                                ? -1.0D
                                : 1.0D;

                Vector3d localNormal =
                        new Vector3d()
                                .setComponent(
                                        axis,
                                        sign);

                Vector3d normal =
                        faceBody.localVectorToWorld(
                                localNormal,
                                new Vector3d());

                double upDot =
                        normal.dot(up);

                if (upDot
                        <= CollisionTolerances
                        .ENTERING_PLANE_EPSILON) {
                    continue;
                }

                SupportRegionPolicy.FootFaceIntersection intersection;
                if (body instanceof OrientedBox box) {
                    intersection = SupportRegionPolicy.footFaceIntersection(box, faceBody, axis, sign, up, distance);
                } else continue;

                if (intersection == null) {
                    context.recordFootFace(
                            body,
                            obstacle,
                            face,
                            normal,
                            upDot,
                            false,
                            false,
                            time,
                            distance
                    );
                    continue;
                }

                Vector3d witness =
                        intersection.witness();

                double travel =
                        new Vector3d(feet)
                                .sub(witness)
                                .dot(up);

                Vector3d sample =
                        new Vector3d(witness)
                                .fma(
                                        travel,
                                        up);

                boolean hidden =
                        occluded(
                                sample,
                                witness,
                                normal,
                                obstacle,
                                obstacles,
                                time
                                        / scene.time()
                                        .intervalTicks(),
                                context
                        );

                context.recordFootFace(
                        body,
                        obstacle,
                        face,
                        normal,
                        upDot,
                        true,
                        !hidden,
                        time,
                        distance
                );

                if (work.limitExceeded()) {
                    return new Result(
                            List.of(),
                            true
                    );
                }

                if (hidden) {
                    continue;
                }

                SupportFaceIdentity identity =
                        obstacle
                                instanceof BlockObstacle block
                                ? new SupportFaceIdentity(
                                block.blockPos(),
                                block.bounds(),
                                -1,
                                face)
                                : SupportFaceIdentity
                                .dynamic(
                                        (EntityObstacle)
                                        obstacle,
                                        face);

                double firstDistance =
                        intersection.firstDistance();

                candidates.add(
                        new Candidate(
                                identity,
                                obstacle,
                                normal,
                                witness,
                                Math.max(0.0D, firstDistance),
                                upDot,
                                time
                                        / scene.time()
                                        .intervalTicks()
                        )
                );
            }
        }

        if (!work.recordContacts(
                candidates.size())) {
            return new Result(
                    List.of(),
                    true
            );
        }

        candidates.sort(
                Comparator
                        .<Candidate>comparingInt(
                                candidate ->
                                        candidate.upDot()
                                                >= TerrainTraversalPolicy
                                                .MIN_CONTINUOUS_SUPPORT_UP_DOT
                                                ? 0
                                                : 1)
                        .thenComparingInt(
                                candidate ->
                                        candidate.identity()
                                                .equals(
                                                        previous)
                                                ? 0
                                                : 1)
                        .thenComparingDouble(
                                candidate ->
                                        Math.abs(
                                                candidate.distance()))
                        .thenComparing(
                                Comparator
                                        .comparingDouble(
                                                Candidate::upDot)
                                        .reversed())
                        .thenComparing(
                                Candidate::obstacle,
                                CollisionObstacle
                                        .STABLE_COMPARATOR)
                        .thenComparingInt(
                                candidate ->
                                        candidate.identity()
                                                .face())
        );

        return new Result(
                candidates,
                false
        );
    }

    /** A real exposed face must also be reachable by its finite locator ray.
     * This tests the frozen obstacle geometry, never the enclosing AABB of an
     * exact entity body. Touching a neighbouring piece's boundary at a seam is
     * allowed; crossing its interior is not. No result creates a response plane. */
    private static boolean occluded(Vector3d sample, Vector3d point, Vector3d normal,
                                    CollisionObstacle owner, List<CollisionObstacle> obstacles,
                                    double normalizedTime, ObbQueryContext context) {
        Vector3d outside = new Vector3d(point).fma(CollisionTolerances.VOXEL_OCCLUSION_PROBE_DISTANCE, normal);
        // Contact-sized signed travel permits a face just above the locator.
        // Do not treat that tolerated overlap with a coplanar neighbour as an
        // obstructed snap corridor; exposure is still checked below.
        boolean hasCorridor = sample.distanceSquared(point)
                > GravityGroundProbe.PROBE_DISTANCE * GravityGroundProbe.PROBE_DISTANCE;
        for (CollisionObstacle obstacle : obstacles) {
            if (obstacle == owner) continue;
            if (!context.supportWorkTracker()
                    .recordNarrowPhaseTest()) return true;
            if (obstacle instanceof SphereObstacle sphere) {
                Vector3d delta = new Vector3d(point).sub(sample);
                double lengthSquared = delta.lengthSquared();
                double t = lengthSquared == 0 ? 0 : Math.clamp(
                        new Vector3d(sphere.center()).sub(sample).dot(delta) / lengthSquared, 0, 1);
                if (new Vector3d(sample).fma(t, delta).distanceSquared(sphere.center())
                        < sphere.sphere().radius() * sphere.sphere().radius()) return true;
                continue;
            }
            if (obstacle instanceof EntityObstacle entity && entity.exactBodyAt(normalizedTime) instanceof CharacterCapsule c) {
                CharacterCapsule interior = (CharacterCapsule) c.deflated(CollisionTolerances.CCD_CONVERGENCE_GAP);
                if (interior.closestPointTo(outside).distanceSquared(outside) <= 1e-24) return true;
                if (hasCorridor && interior.segmentInterval(sample, point) != null) return true;
                continue;
            }
            OrientedBox box = switch (obstacle) {
                case BlockObstacle block -> OrientedBox.axisAligned(block.bounds());
                case EntityObstacle entity -> entity.bodyAt(normalizedTime);
                case WorldBorderObstacle border -> OrientedBox.axisAligned(border.bounds());
                default -> throw new IllegalStateException("unhandled obstacle: " + obstacle);
            };
            Vector3d half = box.halfExtents();
            Vector3d localOutside = box.worldPointToLocal(outside, new Vector3d());
            if (Math.abs(localOutside.x) < half.x && Math.abs(localOutside.y) < half.y
                    && Math.abs(localOutside.z) < half.z) return true;
            if (hasCorridor && crossesInterior(box.worldPointToLocal(sample, new Vector3d()),
                    box.worldPointToLocal(point, new Vector3d()), half)) return true;
        }
        return false;
    }

    /** Open segment/box-interior slab test; endpoint-only contact is not occlusion. */
    private static boolean crossesInterior(Vector3d start, Vector3d end, Vector3d half) {
        double enter = 0, exit = 1;
        for (int axis = 0; axis < 3; axis++) {
            double origin = start.get(axis), delta = end.get(axis) - origin;
            double extent = half.get(axis);
            if (delta == 0) {
                if (Math.abs(origin) >= extent) return false;
            } else {
                double a = (-extent - origin) / delta, b = (extent - origin) / delta;
                enter = Math.max(enter, Math.min(a, b));
                exit = Math.min(exit, Math.max(a, b));
                if (enter >= exit) return false;
            }
        }
        return enter < exit;
    }
}
