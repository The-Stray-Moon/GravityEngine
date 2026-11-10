package cc.sighs.gravityengine.gravity.collision;

import cc.sighs.gravityengine.math.geometry.Aabb3d;
import java.util.*;

/** Immutable operation-local broadphase. Each primitive occurs exactly once;
 * large primitives are never replicated into spatial cells. Small scenes scan. */
final class CapturedBlockIndex {
    private static final int INDEX_THRESHOLD = 64;
    private static final int LEAF_SIZE = 8;
    private final List<BlockObstacle> blocks;
    private final Node root;
    private record Node(Aabb3d bounds, int from, int to, Node left, Node right) {}

    CapturedBlockIndex(List<BlockObstacle> input) {
        blocks = List.copyOf(input);
        root = blocks.size() < INDEX_THRESHOLD ? null : build(blocks, 0, blocks.size());
    }

    private static Node build(List<BlockObstacle> blocks, int from, int to) {
        // Native capture order groups nearby cells. Preserve it and build a packed
        // hierarchy in linear work; sorting every subtree costs more than the queries.
        if (to-from <= LEAF_SIZE) {
            double minX=Double.POSITIVE_INFINITY, minY=minX, minZ=minX;
            double maxX=Double.NEGATIVE_INFINITY, maxY=maxX, maxZ=maxX;
            for (int i=from;i<to;i++) {
                var b=blocks.get(i).bounds();
                minX=Math.min(minX,b.minX());minY=Math.min(minY,b.minY());minZ=Math.min(minZ,b.minZ());
                maxX=Math.max(maxX,b.maxX());maxY=Math.max(maxY,b.maxY());maxZ=Math.max(maxZ,b.maxZ());
            }
            return new Node(new Aabb3d(minX,minY,minZ,maxX,maxY,maxZ),from,to,null,null);
        }
        int middle=(from+to)>>>1;
        var left=build(blocks,from,middle); var right=build(blocks,middle,to);
        var a=left.bounds(); var b=right.bounds();
        return new Node(new Aabb3d(Math.min(a.minX(),b.minX()),Math.min(a.minY(),b.minY()),Math.min(a.minZ(),b.minZ()),
                Math.max(a.maxX(),b.maxX()),Math.max(a.maxY(),b.maxY()),Math.max(a.maxZ(),b.maxZ())),from,to,left,right);
    }

    int query(Aabb3d bounds, List<CollisionObstacle> result) {
        return root == null ? scan(0, blocks.size(), bounds, result) : query(root, bounds, result);
    }

    private int query(Node node, Aabb3d bounds, List<CollisionObstacle> result) {
        if (!node.bounds().intersects(bounds)) return 0;
        return node.left() == null ? scan(node.from(), node.to(), bounds, result)
                : query(node.left(), bounds, result) + query(node.right(), bounds, result);
    }

    private int scan(int from, int to, Aabb3d bounds, List<CollisionObstacle> result) {
        for (int i=from; i<to; i++) {
            var block = blocks.get(i);
            if (block.bounds().intersects(bounds)) result.add(block);
        }
        return to-from;
    }
}
