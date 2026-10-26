package cc.sighs.gravityengine.client;

import cc.sighs.gravityengine.network.ClientboundBodyAttitudeStatePayload;
import cc.sighs.gravityengine.network.ClientboundPayloadDispatch;
import cc.sighs.gravityengine.network.SyncGravityStatePayload;

/**
 * Physical-client implementation of GravityEngine clientbound payload handling.
 *
 * <p>Common network registration knows only
 * {@link ClientboundPayloadDispatch}; all references to client runtime classes
 * terminate here.</p>
 */
public enum ClientNetworkPayloadHandlers
        implements ClientboundPayloadDispatch.Handlers {
    INSTANCE;

    public static void install() {
        ClientboundPayloadDispatch.install(INSTANCE);
    }

    @Override
    public void handlePlayerBodyCommit(cc.sighs.gravityengine.network.ClientboundPlayerBodyCommitPayload payload) {
        ClientPlayerBodyCommitHandler.handle(payload);
    }

    @Override
    public void handleGravitySync(SyncGravityStatePayload payload) {
        ClientGravitySyncService.handle(payload);
    }

    @Override
    public void handleBodyAttitudeState(
            ClientboundBodyAttitudeStatePayload payload
    ) {
        ClientBodyAttitudeSync.handle(payload);
    }
}
