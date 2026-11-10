package cc.sighs.gravityengine.gravity.geometry;

/**
 * Whether the operation invoking pose preparation owns the position anchor.
 *
 * <p>This separates "is this candidate geometrically legal" from "may this call
 * boundary move the authoritative P". An externally owned translation proposal
 * (Vanilla's packet loop is the canonical example) must not be re-anchored
 * mid-preparation: its owner already captured the anchor it translates from
 * and to.</p>
 */
public enum PositionAuthorityPolicy {

    /** The operation owns P and may install a bounded support re-anchor. */
    OPERATION_MAY_REANCHOR,

    /**
     * An external owner captured P. Preparation must leave P untouched;
     * candidates that require a positional correction stay uninstalled.
     */
    EXTERNAL_POSITION_ANCHOR
}
