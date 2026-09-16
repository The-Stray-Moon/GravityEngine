package cc.sighs.gravityengine.gravity.model;

/**
 * Why an assigned gravity application is currently suppressed.
 */
public enum GravitySuppressionReason {
    NONE(0),
    CREATIVE_FLIGHT(1);

    private final int networkId;

    GravitySuppressionReason(int networkId) {
        this.networkId = networkId;
    }

    public int networkId() {
        return this.networkId;
    }

    public static GravitySuppressionReason fromNetworkId(int id) {
        return switch (id) {
            case 0 -> NONE;
            case 1 -> CREATIVE_FLIGHT;
            default -> throw new IllegalArgumentException(
                    "unknown gravity suppression network id: " + id
            );
        };
    }
}