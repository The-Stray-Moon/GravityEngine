package cc.sighs.gravityengine.gravity.field;

/**
 * Pure position/velocity/time gravity field evaluator.
 *
 * <p>A field is a mathematical rule that maps one {@link GravityFieldQuery}
 * to one acceleration vector.  It never owns source identity, influence
 * geometry, revisions, or Minecraft/entity state; those live in the
 * instance/contribution layers.  In particular it has no notion of a
 * "center" unless the concrete evaluator is point-like, and no field may
 * import Minecraft or collision types.</p>
 */
public interface GravityField {

    /** Evaluates this field at one pure query point. */
    GravityFieldSample sample(GravityFieldQuery query);
}
