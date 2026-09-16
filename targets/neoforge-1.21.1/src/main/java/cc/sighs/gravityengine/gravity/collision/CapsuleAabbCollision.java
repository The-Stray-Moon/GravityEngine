package cc.sighs.gravityengine.gravity.collision;

import cc.sighs.gravityengine.gravity.kinematic.geometry.CharacterCapsule;
import cc.sighs.gravityengine.math.geometry.Aabb3d;
import org.joml.Vector3d;

import java.util.Arrays;
import java.util.Objects;

/** Exact segment-versus-box distance and translation-only capsule CCD. */
public final class CapsuleAabbCollision {
    private static final int MAX_CCD_ITERATIONS = 32;
    private static final double DISTANCE_NORMAL_EPSILON_SQUARED = 1.0E-24D;
    private static final double ADVANCE_EPSILON = 1.0E-12D;

    private CapsuleAabbCollision() {}

    public record ContactGeometry(
            Vector3d pointOnSegment,
            Vector3d pointOnObstacle,
            Vector3d normal,
            double signedGap,
            double penetration
    ) {}

    public record SweepGeometry(
            SweepInitialState initialState,
            ContactGeometry contact,
            double timeOfImpact,
            boolean indeterminate,
            int iterations,
            String diagnostic,
            CapsuleCcdConvergence.Diagnostics ccdDiagnostics
    ) {
        public SweepGeometry(
                SweepInitialState initialState,
                ContactGeometry contact,
                double timeOfImpact,
                boolean indeterminate,
                int iterations
        ) {
            this(
                    initialState, contact, timeOfImpact, indeterminate, iterations, "",
                    CapsuleCcdConvergence.Diagnostics.normal(iterations)
            );
        }

        public SweepGeometry(
                SweepInitialState initialState,
                ContactGeometry contact,
                double timeOfImpact,
                boolean indeterminate,
                int iterations,
                String diagnostic
        ) {
            this(
                    initialState, contact, timeOfImpact, indeterminate, iterations, diagnostic,
                    CapsuleCcdConvergence.Diagnostics.normal(iterations)
            );
        }

        public boolean hasContact() { return contact != null; }
    }

    public static ContactGeometry contactGeometry(CharacterCapsule capsule, Aabb3d box) {
        Objects.requireNonNull(capsule, "capsule");
        Objects.requireNonNull(box, "box");
        ContactScratch scratch = new ContactScratch();
        sample(capsule, 0.0D, 0.0D, 0.0D, box, scratch);
        return scratch.toImmutable();
    }

    /** Strict Boolean occupancy from the exact segment/AABB distance only. */
    public static boolean intersects(CharacterCapsule capsule, Aabb3d box) {
        ContactScratch scratch = new ContactScratch();
        return contactGapAt(capsule, 0, 0, 0, box, scratch) < 0;
    }

    /** Conservative advancement using the exact distance function. */
    public static SweepGeometry sweep(
            CharacterCapsule capsule,
            Vector3d relativeMovement,
            Aabb3d box
    ) {
        Objects.requireNonNull(capsule, "capsule");
        Objects.requireNonNull(relativeMovement, "relativeMovement");
        Objects.requireNonNull(box, "box");
        if (!isFinite(relativeMovement)) {
            return new SweepGeometry(
                    SweepInitialState.SEPARATED, null, 0.0D, true, 0,
                    "non-finite relativeMovement=" + relativeMovement
            );
        }

        ContactScratch geometry = new ContactScratch();
        sample(capsule, 0.0D, 0.0D, 0.0D, box, geometry);
        SweepInitialState initialState = classify(geometry.signedGap);
        if (initialState == SweepInitialState.OVERLAPPING) {
            return new SweepGeometry(initialState, geometry.toImmutable(), 0.0D, false, 0);
        }
        if (initialState == SweepInitialState.TOUCHING) {
            if (geometry.directionalDerivative(relativeMovement)
                    >= -CollisionTolerances.ENTERING_PLANE_EPSILON) {
                return new SweepGeometry(initialState, null, 0.0D, false, 0);
            }
            return new SweepGeometry(initialState, geometry.toImmutable(), 0.0D, false, 0);
        }

        // A fixed box face that separates the complete capsule for this entire
        // translation proves non-entry. This also handles a rounded end grazing a
        // finite floor edge: the distance normal before tangency must not create
        // a fictitious upward support-follow event from the contact band.
        Aabb3d bounds = capsule.enclosingAabb();
        if (nonEnteringSlab(bounds.minX(),bounds.maxX(),box.minX(),box.maxX(),relativeMovement.x)
                || nonEnteringSlab(bounds.minY(),bounds.maxY(),box.minY(),box.maxY(),relativeMovement.y)
                || nonEnteringSlab(bounds.minZ(),bounds.maxZ(),box.minZ(),box.maxZ(),relativeMovement.z))
            return new SweepGeometry(initialState,null,0,false,0);

        if (relativeMovement.lengthSquared()
                <= CollisionTolerances.ZERO_VECTOR_EPSILON_SQUARED) {
            return new SweepGeometry(initialState, null, 0.0D, false, 0);
        }

        double time = 0.0D;
        for (int iteration = 1; iteration <= MAX_CCD_ITERATIONS; iteration++) {
            double safeGap = geometry.signedGap - CollisionTolerances.CONTACT_SLOP;
            double derivative = geometry.directionalDerivative(relativeMovement);
            if (!Double.isFinite(derivative)) {
                return new SweepGeometry(
                        initialState, null, time, true, iteration,
                        describeIndeterminate(
                                capsule, relativeMovement, box, time, geometry,
                                iteration, ""
                        )
                );
            }
            // The signed gap between a convex capsule region and a convex box
            // is convex along a linear translation, so its directional
            // derivative is non-decreasing. A derivative that is already
            // non-negative therefore proves the remaining interval cannot
            // enter contact.
            if (derivative >= -CollisionTolerances.ENTERING_PLANE_EPSILON) {
                return new SweepGeometry(
                        initialState, null, time, false, iteration
                );
            }
            // A close but separating/tangent pair is not an entering event.
            // Check the distance derivative before accepting the convergence band.
            if (CapsuleCcdConvergence.reachedContactBand(geometry.signedGap)) {
                return new SweepGeometry(
                        initialState, geometry.toImmutable(), time, false, iteration
                );
            }
            double closingRate = -derivative;
            double remainingTime = 1.0D - time;
            double tangentRoot = safeGap / closingRate;
            if (!Double.isFinite(tangentRoot)) {
                return new SweepGeometry(
                        initialState, null, time, true, iteration,
                        describeIndeterminate(
                                capsule, relativeMovement, box, time, geometry,
                                iteration, ""
                        )
                );
            }
            if (tangentRoot > remainingTime + CollisionTolerances.TOI_EPSILON) {
                // The tangent lower bound to the contact boundary remains
                // outside the remaining interval; the convex distance function
                // lies above its tangent and therefore never reaches contact.
                return new SweepGeometry(
                        initialState, null, 1.0D, false, iteration
                );
            }
            double advance = Math.min(tangentRoot, remainingTime);
            if (advance <= ADVANCE_EPSILON) {
                // The tangent lower bound is at or below the minimum committed
                // advancement. A forced larger step could cross an unproven
                // first contact, so resolve the root with the bounded fallback.
                return resolveByFallback(
                        capsule, relativeMovement, box, initialState, time,
                        iteration, geometry
                );
            }
            time += advance;
            sample(
                    capsule,
                    relativeMovement.x * time,
                    relativeMovement.y * time,
                    relativeMovement.z * time,
                    box,
                    geometry
            );
        }
        return resolveByFallback(
                capsule, relativeMovement, box, initialState, time,
                MAX_CCD_ITERATIONS, geometry
        );
    }

    /**
     * Bounded convex fallback used only when the conservative-advancement loop
     * reaches its numerical iteration budget or the remaining interval is too
     * small for a meaningful bounded advance. It locates the minimum gap over
     * the remaining interval and brackets the earliest root by convexity.
     */
    private static SweepGeometry resolveByFallback(
            CharacterCapsule capsule,
            Vector3d relativeMovement,
            Aabb3d box,
            SweepInitialState initialState,
            double time,
            int caIterations,
            ContactScratch geometry
    ) {
        CapsuleCcdConvergence.Result result = CapsuleCcdConvergence.findEarliestContact(
                t -> {
                    return contactGapAt(
                            capsule,
                            relativeMovement.x * t,
                            relativeMovement.y * t,
                            relativeMovement.z * t,
                            box,
                            geometry
                    );
                },
                time,
                1.0D
        );
        if (result.isHit()) {
            double toi = result.timeOfImpact();
            sample(
                    capsule,
                    relativeMovement.x * toi,
                    relativeMovement.y * toi,
                    relativeMovement.z * toi,
                    box,
                    geometry
            );
            return new SweepGeometry(
                    initialState, geometry.toImmutable(), toi, false, caIterations,
                    "", CapsuleCcdConvergence.Diagnostics.fallback(
                    caIterations, result.minimumSamples(), result.rootSamples()
            )
            );
        }
        if (result.isNoHit()) {
            return new SweepGeometry(
                    initialState, null, 1.0D, false, caIterations,
                    "", CapsuleCcdConvergence.Diagnostics.fallback(
                    caIterations, result.minimumSamples(), result.rootSamples()
            )
            );
        }
        sample(
                capsule,
                relativeMovement.x * time,
                relativeMovement.y * time,
                relativeMovement.z * time,
                box,
                geometry
        );
        return new SweepGeometry(
                initialState, null, time, true, caIterations,
                describeIndeterminate(
                        capsule, relativeMovement, box, time, geometry, caIterations,
                        result.diagnostic()
                ),
                CapsuleCcdConvergence.Diagnostics.fallback(
                        caIterations, result.minimumSamples(), result.rootSamples()
                )
        );
    }

    /**
     * Exclusive CCD contact scalar: the exact segment/Aabb3d closest distance
     * minus the capsule radius, evaluated for one normalized translation time.
     *
     * <p>This scalar is defined consistently for separated, touching, capsule
     * overlap, and central-segment/Aabb3d intersection. When the central segment
     * touches or intersects the box the distance is zero, so the scalar is
     * {@code -radius}. It is deliberately independent of the face-penetration
     * representation used by {@link ContactGeometry} for recovery.</p>
     *
     * <p>The segment is a convex region translating linearly and the box is a
     * convex region, so the distance between them is a convex function of the
     * normalized translation time; subtracting the constant radius preserves
     * convexity. This is the property the bounded fallback relies on.</p>
     */
    private static double contactGapAt(
            CharacterCapsule capsule,
            double offsetX,
            double offsetY,
            double offsetZ,
            Aabb3d box,
            ContactScratch scratch
    ) {
        double half = capsule.halfSegmentLength();
        Vector3d center = capsule.center();
        Vector3d axis = capsule.axis();
        double ax = center.x - axis.x * half + offsetX;
        double ay = center.y - axis.y * half + offsetY;
        double az = center.z - axis.z * half + offsetZ;
        double bx = center.x + axis.x * half + offsetX;
        double by = center.y + axis.y * half + offsetY;
        double bz = center.z + axis.z * half + offsetZ;
        closestSample(ax, ay, az, bx, by, bz, box, scratch);
        double distanceSquared = Math.max(0.0D, scratch.distanceSquared);
        return Math.sqrt(distanceSquared) - capsule.radius();
    }

    private static String describeIndeterminate(
            CharacterCapsule capsule,
            Vector3d relativeMovement,
            Aabb3d box,
            double time,
            ContactScratch geometry,
            int iteration,
            String fallbackDetail
    ) {
        double safeGap = geometry.signedGap - CollisionTolerances.CONTACT_SLOP;
        double derivative = geometry.directionalDerivative(relativeMovement);
        double closingRate = -derivative;
        return "body=" + capsule
                + " obstacle=box" + box
                + " relativeMovement=" + relativeMovement
                + " time=" + time
                + " remainingTime=" + (1.0D - time)
                + " signedGap=" + geometry.signedGap
                + " safeGap=" + safeGap
                + " normal=" + new Vector3d(
                geometry.normalX, geometry.normalY, geometry.normalZ
        )
                + " derivative=" + derivative
                + " closingRate=" + closingRate
                + " iteration=" + iteration
                + (fallbackDetail == null || fallbackDetail.isBlank()
                ? "" : " fallback=" + fallbackDetail);
    }

    /** Populates one reusable sample; the conservative-advancement loop allocates nothing. */
    private static void sample(
            CharacterCapsule capsule,
            double offsetX,
            double offsetY,
            double offsetZ,
            Aabb3d box,
            ContactScratch scratch
    ) {
        double half = capsule.halfSegmentLength();
        Vector3d center = capsule.center();
        Vector3d axis = capsule.axis();
        double ax = center.x - axis.x * half + offsetX;
        double ay = center.y - axis.y * half + offsetY;
        double az = center.z - axis.z * half + offsetZ;
        double bx = center.x + axis.x * half + offsetX;
        double by = center.y + axis.y * half + offsetY;
        double bz = center.z + axis.z * half + offsetZ;
        closestSample(ax, ay, az, bx, by, bz, box, scratch);

        if (scratch.distanceSquared > DISTANCE_NORMAL_EPSILON_SQUARED) {
            double distance = Math.sqrt(scratch.distanceSquared);
            scratch.normalX = (scratch.segmentX - scratch.obstacleX) / distance;
            scratch.normalY = (scratch.segmentY - scratch.obstacleY) / distance;
            scratch.normalZ = (scratch.segmentZ - scratch.obstacleZ) / distance;
            scratch.signedGap = distance - capsule.radius();
            scratch.penetration = Math.max(0.0D, -scratch.signedGap);
            return;
        }
        intersectingSample(
                center.x + offsetX, center.y + offsetY, center.z + offsetZ,
                ax, ay, az, bx, by, bz, capsule.radius(), box, scratch
        );
    }

    private static void closestSample(
            double ax,
            double ay,
            double az,
            double bx,
            double by,
            double bz,
            Aabb3d box,
            ContactScratch scratch
    ) {
        double dx = bx - ax;
        double dy = by - ay;
        double dz = bz - az;
        int count = 0;
        scratch.breaks[count++] = 0.0D;
        scratch.breaks[count++] = 1.0D;
        count = addSlabBreaks(scratch.breaks, count, ax, dx, box.minX(), box.maxX());
        count = addSlabBreaks(scratch.breaks, count, ay, dy, box.minY(), box.maxY());
        count = addSlabBreaks(scratch.breaks, count, az, dz, box.minZ(), box.maxZ());
        Arrays.sort(scratch.breaks, 0, count);

        scratch.distanceSquared = Double.POSITIVE_INFINITY;
        scratch.segmentParameter = Double.POSITIVE_INFINITY;
        evaluateSample(ax, ay, az, dx, dy, dz, box, 0.0D, scratch);
        for (int index = 0; index < count - 1; index++) {
            double minimum = scratch.breaks[index];
            double maximum = scratch.breaks[index + 1];
            if (maximum < minimum) continue;
            evaluateSample(ax, ay, az, dx, dy, dz, box, minimum, scratch);
            evaluateSample(ax, ay, az, dx, dy, dz, box, maximum, scratch);
            if (maximum - minimum <= ADVANCE_EPSILON) continue;

            double midpoint = (minimum + maximum) * 0.5D;
            double numerator = 0.0D;
            double denominator = 0.0D;
            double px = ax + dx * midpoint;
            double py = ay + dy * midpoint;
            double pz = az + dz * midpoint;
            if (px < box.minX()) {
                numerator += dx * (ax - box.minX());
                denominator += dx * dx;
            } else if (px > box.maxX()) {
                numerator += dx * (ax - box.maxX());
                denominator += dx * dx;
            }
            if (py < box.minY()) {
                numerator += dy * (ay - box.minY());
                denominator += dy * dy;
            } else if (py > box.maxY()) {
                numerator += dy * (ay - box.maxY());
                denominator += dy * dy;
            }
            if (pz < box.minZ()) {
                numerator += dz * (az - box.minZ());
                denominator += dz * dz;
            } else if (pz > box.maxZ()) {
                numerator += dz * (az - box.maxZ());
                denominator += dz * dz;
            }
            if (denominator > 0.0D) {
                evaluateSample(
                        ax, ay, az, dx, dy, dz, box,
                        clamp(-numerator / denominator, minimum, maximum),
                        scratch
                );
            }
        }
    }

    private static void evaluateSample(
            double ax,
            double ay,
            double az,
            double dx,
            double dy,
            double dz,
            Aabb3d box,
            double time,
            ContactScratch scratch
    ) {
        double px = ax + dx * time;
        double py = ay + dy * time;
        double pz = az + dz * time;
        double qx = clamp(px, box.minX(), box.maxX());
        double qy = clamp(py, box.minY(), box.maxY());
        double qz = clamp(pz, box.minZ(), box.maxZ());
        double ex = px - qx;
        double ey = py - qy;
        double ez = pz - qz;
        double distanceSquared = ex * ex + ey * ey + ez * ez;
        int comparison = Double.compare(distanceSquared, scratch.distanceSquared);
        if (comparison > 0 || (comparison == 0 && time >= scratch.segmentParameter)) return;
        scratch.segmentX = px;
        scratch.segmentY = py;
        scratch.segmentZ = pz;
        scratch.obstacleX = qx;
        scratch.obstacleY = qy;
        scratch.obstacleZ = qz;
        scratch.segmentParameter = time;
        scratch.distanceSquared = distanceSquared;
    }

    /**
     * The spine intersects the box, so the capsule center is inside the
     * configuration-space core Z = box + [-halfSpine, halfSpine]. Z is a
     * zonotope generated by the three box edges and the spine. Its complete
     * facet normals are the box axes and spine cross box-edge directions.
     *
     * For an interior point the nearest facet plane gives the core's exact
     * exit distance d. Its perpendicular projection lies in that finite facet
     * (the ball of radius d is contained in Z). Offsetting Z by sphere(radius)
     * therefore gives MTD d + radius along the same normal. No edge/corner
     * direction outside this finite set can be shorter. Sphere degeneration
     * leaves only the box axes. This is not a six-box-face approximation.
     */
    private static void intersectingSample(
            double centerX,
            double centerY,
            double centerZ,
            double ax,
            double ay,
            double az,
            double bx,
            double by,
            double bz,
            double radius,
            Aabb3d box,
            ContactScratch scratch
    ) {
        scratch.bestPenetration = Double.POSITIVE_INFINITY;
        // Work relative to the box center to avoid cancellation of large world coordinates.
        double hx = (box.maxX()-box.minX())*.5, hy = (box.maxY()-box.minY())*.5,
                hz = (box.maxZ()-box.minZ())*.5;
        double cx = centerX-(box.minX()+hx), cy = centerY-(box.minY()+hy), cz = centerZ-(box.minZ()+hz);
        double sx = (bx-ax)*.5, sy = (by-ay)*.5, sz = (bz-az)*.5;
        selectDirectionPair(1,0,0,cx,cy,cz,sx,sy,sz,hx,hy,hz,radius,scratch);
        selectDirectionPair(0,1,0,cx,cy,cz,sx,sy,sz,hx,hy,hz,radius,scratch);
        selectDirectionPair(0,0,1,cx,cy,cz,sx,sy,sz,hx,hy,hz,radius,scratch);
        selectDirectionPair(0,sz,-sy,cx,cy,cz,sx,sy,sz,hx,hy,hz,radius,scratch);
        selectDirectionPair(-sz,0,sx,cx,cy,cz,sx,sy,sz,hx,hy,hz,radius,scratch);
        selectDirectionPair(sy,-sx,0,cx,cy,cz,sx,sy,sz,hx,hy,hz,radius,scratch);

        scratch.penetration = Math.max(0.0D, scratch.bestPenetration);
        scratch.signedGap = -scratch.penetration;
        // Obtain finite witnesses at the separated boundary, then pull the spine
        // witness back into the original capsule. Never invent a center/face pair.
        double tx = scratch.normalX*scratch.penetration;
        double ty = scratch.normalY*scratch.penetration;
        double tz = scratch.normalZ*scratch.penetration;
        closestSample(ax+tx,ay+ty,az+tz,bx+tx,by+ty,bz+tz,box,scratch);
        scratch.segmentX -= tx;
        scratch.segmentY -= ty;
        scratch.segmentZ -= tz;
    }

    private static void selectDirectionPair(double nx, double ny, double nz,
            double cx, double cy, double cz, double sx, double sy, double sz,
            double hx, double hy, double hz, double radius, ContactScratch scratch) {
        // Scale before normalization; even an almost parallel spine has a valid
        // cross direction. Omit only an exactly degenerate generator pair.
        double scale = Math.max(Math.abs(nx),Math.max(Math.abs(ny),Math.abs(nz)));
        if (scale == 0) return;
        nx /= scale; ny /= scale; nz /= scale;
        double length = Math.sqrt(nx*nx+ny*ny+nz*nz);
        nx /= length; ny /= length; nz /= length;
        double support = hx*Math.abs(nx)+hy*Math.abs(ny)+hz*Math.abs(nz)
                +Math.abs(sx*nx+sy*ny+sz*nz)+radius;
        double offset = cx*nx+cy*ny+cz*nz;
        selectFace(nx,ny,nz,support-offset,scratch);
        selectFace(-nx,-ny,-nz,support+offset,scratch);
    }

    private static void selectFace(
            double normalX,
            double normalY,
            double normalZ,
            double penetration,
            ContactScratch scratch
    ) {
        int comparison = Double.compare(penetration, scratch.bestPenetration);
        if (comparison > 0) return;
        if (comparison == 0 && compareNormal(
                normalX, normalY, normalZ,
                scratch.normalX, scratch.normalY, scratch.normalZ
        ) >= 0) return;
        scratch.normalX = normalX;
        scratch.normalY = normalY;
        scratch.normalZ = normalZ;
        scratch.bestPenetration = penetration;
    }

    private static int compareNormal(
            double leftX,
            double leftY,
            double leftZ,
            double rightX,
            double rightY,
            double rightZ
    ) {
        int comparison = Double.compare(leftX, rightX);
        if (comparison != 0) return comparison;
        comparison = Double.compare(leftY, rightY);
        if (comparison != 0) return comparison;
        return Double.compare(leftZ, rightZ);
    }

    private static int addSlabBreaks(
            double[] breaks,
            int count,
            double start,
            double delta,
            double minimum,
            double maximum
    ) {
        if (Math.abs(delta) <= ADVANCE_EPSILON) return count;
        double first = (minimum - start) / delta;
        double second = (maximum - start) / delta;
        if (first > 0.0D && first < 1.0D) breaks[count++] = first;
        if (second > 0.0D && second < 1.0D) breaks[count++] = second;
        return count;
    }

    private static SweepInitialState classify(double signedGap) {
        if (signedGap < -CollisionTolerances.PENETRATION_EPSILON) {
            return SweepInitialState.OVERLAPPING;
        }
        if (CapsuleCcdConvergence.reachedContactBand(signedGap)) {
            return SweepInitialState.TOUCHING;
        }
        return SweepInitialState.SEPARATED;
    }

    private static boolean nonEnteringSlab(double min, double max, double boxMin, double boxMax, double motion) {
        return min >= boxMax-CollisionTolerances.CONTACT_SLOP && motion >= 0
                || max <= boxMin+CollisionTolerances.CONTACT_SLOP && motion <= 0;
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static boolean isFinite(Vector3d vector) {
        return Double.isFinite(vector.x)
                && Double.isFinite(vector.y)
                && Double.isFinite(vector.z);
    }

    private static final class ContactScratch {
        private final double[] breaks = new double[8];
        private double segmentX;
        private double segmentY;
        private double segmentZ;
        private double obstacleX;
        private double obstacleY;
        private double obstacleZ;
        private double normalX;
        private double normalY;
        private double normalZ;
        private double signedGap;
        private double penetration;
        private double segmentParameter;
        private double distanceSquared;
        private double bestPenetration;

        private double directionalDerivative(Vector3d movement) {
            return movement.x * normalX + movement.y * normalY + movement.z * normalZ;
        }

        private ContactGeometry toImmutable() {
            return new ContactGeometry(
                    new Vector3d(segmentX, segmentY, segmentZ),
                    new Vector3d(obstacleX, obstacleY, obstacleZ),
                    new Vector3d(normalX, normalY, normalZ),
                    signedGap,
                    penetration
            );
        }
    }

}
