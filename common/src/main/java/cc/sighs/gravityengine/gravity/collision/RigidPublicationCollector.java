package cc.sighs.gravityengine.gravity.collision;

/** Sink for one immutable rigid obstacle publication. */
@FunctionalInterface
public interface RigidPublicationCollector {

    void addObstacle(DynamicCollisionObstacleSnapshot obstacle);
}