package cc.sighs.gravityengine.gravity.model;

/**
 * Persistent provenance of an entity's assigned gravity state.
 *
 * <p>FIELD assignments are snapshots published by the world field authority.
 * DIRECT assignments are explicit API/script overrides and bypass field
 * composition until cleared.</p>
 */
public enum GravityAuthorityMode {
    FIELD(0),
    DIRECT(1);

    private final int networkId;

    GravityAuthorityMode(int networkId) {
        this.networkId = networkId;
    }

    public int networkId() {
        return networkId;
    }

    public static GravityAuthorityMode fromNetworkId(int id) {
        return switch (id) {
            case 0 -> FIELD;
            case 1 -> DIRECT;
            default -> throw new IllegalArgumentException(
                    "unknown gravity authority network id: " + id
            );
        };
    }
}
