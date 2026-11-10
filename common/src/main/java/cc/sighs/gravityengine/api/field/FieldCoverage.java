package cc.sighs.gravityengine.api.field;

/** Coverage proof for one provider and one immutable field query. */
public enum FieldCoverage {
    /** The provider included every contribution in its declared domain. */
    COMPLETE,
    /** The provider cannot prove that its contribution set is complete. */
    INCOMPLETE
}
