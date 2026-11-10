package cc.sighs.gravityengine.network;

import java.util.Objects;

/**
 * Common-side dispatch seam for clientbound payload handlers.
 *
 * <p>Payload type/codec registration is common and therefore must remain
 * loadable on a dedicated server. The actual handler implementation is
 * installed only by the physical-client mod entry point.</p>
 *
 * <p>This class deliberately has no dependency on {@code client.*},
 * Minecraft client classes, or Dist-specific code.</p>
 */
public final class ClientboundPayloadDispatch {
    private static volatile Handlers handlers = UninstalledHandlers.INSTANCE;

    private ClientboundPayloadDispatch() {}

    /**
     * Client-only installation boundary.
     *
     * <p>The physical-client mod entry point installs exactly one immutable
     * handler implementation during mod construction, before any play payload
     * can be received.</p>
     */
    public static synchronized void install(Handlers installedHandlers) {
        Objects.requireNonNull(installedHandlers, "installedHandlers");

        if (handlers != UninstalledHandlers.INSTANCE
                && handlers != installedHandlers) {
            throw new IllegalStateException(
                    "clientbound payload handlers already installed"
            );
        }

        handlers = installedHandlers;
    }

    static void handlePlayerBodyCommit(ClientboundPlayerBodyCommitPayload payload) {
        requireInstalled().handlePlayerBodyCommit(payload);
    }

    static void handleGravitySync(SyncGravityStatePayload payload) {
        requireInstalled().handleGravitySync(payload);
    }

    static void handleBodyAttitudeState(
            ClientboundBodyAttitudeStatePayload payload
    ) {
        requireInstalled().handleBodyAttitudeState(payload);
    }

    private static Handlers requireInstalled() {
        Handlers current = handlers;

        if (current == UninstalledHandlers.INSTANCE) {
            throw new IllegalStateException(
                    "clientbound payload handler invoked without physical-client installation"
            );
        }

        return current;
    }

    /**
     * The only contract visible to common networking code.
     *
     * <p>Implementations are physical-client owned.</p>
     */
    public interface Handlers {
        default void handlePlayerBodyCommit(ClientboundPlayerBodyCommitPayload payload) {
            throw new IllegalStateException("player-body commit handler is not installed");
        }
        void handleGravitySync(SyncGravityStatePayload payload);

        void handleBodyAttitudeState(
                ClientboundBodyAttitudeStatePayload payload
        );
    }

    private enum UninstalledHandlers implements Handlers {
        INSTANCE;

        @Override
        public void handleGravitySync(SyncGravityStatePayload payload) {
            throw uninstalled();
        }

        @Override
        public void handleBodyAttitudeState(
                ClientboundBodyAttitudeStatePayload payload
        ) {
            throw uninstalled();
        }

        private static IllegalStateException uninstalled() {
            return new IllegalStateException(
                    "clientbound payload handlers are not installed"
            );
        }
    }
}
