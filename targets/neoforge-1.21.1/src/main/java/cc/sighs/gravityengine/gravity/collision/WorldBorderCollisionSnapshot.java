package cc.sighs.gravityengine.gravity.collision;

import cc.sighs.gravityengine.math.geometry.Aabb3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Operation-frozen world-border collision geometry.
 *
 * <p>Minecraft represents the world-border collision shape using unbounded
 * voxel slabs. Those slabs may contain +/-Infinity and therefore must never
 * cross into the finite pure-geometry kernel.</p>
 *
 * <p>This snapshot freezes only the four finite horizontal border planes.
 * Each scene query materializes finite local slabs clipped to that query's
 * finite swept corridor. The solver therefore sees ordinary finite AABBs
 * while all subqueries of one scene still observe the same frozen border.</p>
 */
public record WorldBorderCollisionSnapshot(
        double minX,
        double minZ,
        double maxX,
        double maxZ
) {
    public WorldBorderCollisionSnapshot {
        requireFinite(minX, "minX");
        requireFinite(minZ, "minZ");
        requireFinite(maxX, "maxX");
        requireFinite(maxZ, "maxZ");

        if (minX > maxX || minZ > maxZ) {
            throw new IllegalArgumentException(
                    "world-border minimum must not exceed maximum: "
                            + "minX=" + minX
                            + ", minZ=" + minZ
                            + ", maxX=" + maxX
                            + ", maxZ=" + maxZ
            );
        }
    }

    /**
     * Builds the finite portions of the frozen border exterior that intersect
     * this finite movement corridor.
     *
     * <p>The border is vertically unbounded semantically, but the collision
     * kernel only needs the current swept corridor's finite Y range.</p>
     */
    public List<WorldBorderObstacle> intersect(Aabb3d searchBox) {
        Objects.requireNonNull(searchBox, "searchBox");

        List<WorldBorderObstacle> obstacles =
                new ArrayList<>(4);

        /*
         * West exterior: x < minX.
         *
         * Clip every other coordinate to the finite corridor. The obstacle
         * begins exactly at the border plane so a sweep from the legal side
         * detects the crossing continuously.
         */
        if (searchBox.minX() < this.minX) {
            obstacles.add(new WorldBorderObstacle(
                    new Aabb3d(
                            searchBox.minX(),
                            searchBox.minY(),
                            searchBox.minZ(),

                            Math.min(
                                    this.minX,
                                    searchBox.maxX()
                            ),
                            searchBox.maxY(),
                            searchBox.maxZ()
                    )
            ));
        }

        /*
         * East exterior: x > maxX.
         */
        if (searchBox.maxX() > this.maxX) {
            obstacles.add(new WorldBorderObstacle(
                    new Aabb3d(
                            Math.max(
                                    this.maxX,
                                    searchBox.minX()
                            ),
                            searchBox.minY(),
                            searchBox.minZ(),

                            searchBox.maxX(),
                            searchBox.maxY(),
                            searchBox.maxZ()
                    )
            ));
        }

        /*
         * North exterior: z < minZ.
         */
        if (searchBox.minZ() < this.minZ) {
            obstacles.add(new WorldBorderObstacle(
                    new Aabb3d(
                            searchBox.minX(),
                            searchBox.minY(),
                            searchBox.minZ(),

                            searchBox.maxX(),
                            searchBox.maxY(),
                            Math.min(
                                    this.minZ,
                                    searchBox.maxZ()
                            )
                    )
            ));
        }

        /*
         * South exterior: z > maxZ.
         */
        if (searchBox.maxZ() > this.maxZ) {
            obstacles.add(new WorldBorderObstacle(
                    new Aabb3d(
                            searchBox.minX(),
                            searchBox.minY(),
                            Math.max(
                                    this.maxZ,
                                    searchBox.minZ()
                            ),

                            searchBox.maxX(),
                            searchBox.maxY(),
                            searchBox.maxZ()
                    )
            ));
        }

        return List.copyOf(obstacles);
    }

    private static void requireFinite(
            double value,
            String name
    ) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(
                    name + " must be finite: " + value
            );
        }
    }
}