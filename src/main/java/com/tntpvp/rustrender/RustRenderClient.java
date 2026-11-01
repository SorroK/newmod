package com.tntpvp.rustrender;

import com.tntpvp.rustrender.nativebridge.NativeLibraryLoader;
import com.tntpvp.rustrender.nativebridge.NativeRendererCore;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * Client entry point.
 *
 * <p>Loads the native renderer core and reports what it found. Every hook is
 * optional: with no native library present the mod is inert rather than
 * broken, so a jar built for the wrong platform degrades to vanilla speed.
 */
public final class RustRenderClient implements ClientModInitializer {

    public static final String MOD_ID = "rustrender";

    private static final Logger LOGGER = LoggerFactory.getLogger("RustRender");

    private static boolean nativeActive;

    @Override
    public void onInitializeClient() {
        Path gameDirectory = FabricLoader.getInstance().getGameDir();
        Path extractionRoot = gameDirectory.resolve(MOD_ID);

        nativeActive = NativeRendererCore.initialize(extractionRoot);

        if (nativeActive) {
            LOGGER.info("Native renderer core loaded: {}", NativeRendererCore.buildInfo());
            LOGGER.info("Platform {} ({})", NativeLibraryLoader.platform(), NativeLibraryLoader.libraryName());
        } else {
            LOGGER.warn(
                    "Native renderer core unavailable ({}). Running with the Java fallback.",
                    NativeRendererCore.failureReason());
        }
    }

    public static boolean isNativeActive() {
        return nativeActive;
    }
}
