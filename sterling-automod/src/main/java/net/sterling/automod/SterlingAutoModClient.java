package net.sterling.automod;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.sterling.automod.connection.AutoModConnectionState;
import net.sterling.automod.mixin.DisconnectedScreenAccessor;
import net.sterling.automod.screen.SterlingDownloadScreen;
import net.sterling.automod.util.SterlingConnectLink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SterlingAutoModClient implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("Sterling AutoMod");

    @Override
    public void onInitializeClient() {
        ScreenEvents.AFTER_INIT.register((minecraft, screen, scaledWidth, scaledHeight) -> {
            if (!(screen instanceof DisconnectedScreen)) {
                return;
            }

            String message = screen.getNarrationMessage().getString();
            var link = SterlingConnectLink.parse(message);
            if (link.isEmpty() && SterlingConnectLink.isRegistryMismatch(message)) {
                String serverAddress = AutoModConnectionState.getLastServerAddress();
                LOGGER.info("Detected Fabric registry mismatch; remembered server address available: {}",
                        serverAddress != null && !serverAddress.isBlank());
                link = SterlingConnectLink.forRegistryMismatch(serverAddress);
            }
            link.ifPresent(connectLink -> minecraft.setScreenAndShow(
                    new SterlingDownloadScreen(
                            minecraft,
                            ((DisconnectedScreenAccessor) screen).sterling$getParent(),
                            connectLink)));
        });
    }
}
