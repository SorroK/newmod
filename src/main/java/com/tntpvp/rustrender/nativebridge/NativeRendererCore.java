package com.tntpvp.rustrender.nativebridge;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;

/**
 * Thin, fail-safe wrapper around the Rust renderer core.
 *
 * <p>Native code is treated as an optional accelerator, never as a
 * requirement: if the library is missing, fails its ABI handshake or throws,
 * every caller falls back to the pure-Java implementation and the game keeps
 * running. A rendering mod that can crash on a bad platform is worse than a
 * slower one.
 */
public final class NativeRendererCore {

    /** Must match {@code ABI_VERSION} in {@code native/src/lib.rs}. */
    public static final int ABI_VERSION = 1;

    private static volatile boolean available;
    private static volatile String buildInfo = "not loaded";
    private static volatile String failure = "not initialised";

    private NativeRendererCore() {
    }

    /**
     * Loads the native core, preferring an exploded build when the
     * {@code rustrender.native.dir} system property is set (used by the
     * benchmark harness, which runs without a mod jar).
     *
     * @param extractionRoot directory the mod may write its extracted library to
     * @return whether the native backend is usable
     */
    public static synchronized boolean initialize(Path extractionRoot) {
        if (available) {
            return true;
        }

        try {
            String override = System.getProperty("rustrender.native.dir");
            if (override != null && !override.isBlank()) {
                NativeLibraryLoader.loadFromDirectory(Path.of(override));
            } else {
                NativeLibraryLoader.load(extractionRoot);
            }
        } catch (Throwable t) {
            // UnsatisfiedLinkError, IOException, missing platform build...
            failure = t.getClass().getSimpleName() + ": " + t.getMessage();
            return false;
        }

        try {
            int abi = abiVersion();
            if (abi != ABI_VERSION) {
                failure = "ABI mismatch: mod expects " + ABI_VERSION + ", library reports " + abi;
                return false;
            }

            if (!isReleaseBuild()) {
                failure = "refusing to use a debug build of the native core";
                return false;
            }

            long pointerSize = pointerSize();
            if (pointerSize != 8L) {
                failure = "unsupported pointer size: " + pointerSize;
                return false;
            }

            buildInfo = buildInfoNative();
            available = true;
            failure = "none";
            return true;
        } catch (Throwable t) {
            failure = "handshake failed: " + t.getClass().getSimpleName() + ": " + t.getMessage();
            return false;
        }
    }

    public static boolean isAvailable() {
        return available;
    }

    public static String buildInfo() {
        return buildInfo;
    }

    public static String failureReason() {
        return failure;
    }

    /** Allocates a direct buffer in native byte order. */
    public static ByteBuffer allocateDirect(int bytes) {
        return ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder());
    }

    /**
     * Culls a batch of axis-aligned boxes against six frustum planes.
     *
     * @param planes 24 floats: six {@code [nx, ny, nz, d]} planes, inward facing
     * @param bounds {@code count * 6} floats: {@code [minX, minY, minZ, maxX, maxY, maxZ]}
     * @param mask receives {@code count} bytes, 1 for visible and 0 for culled
     * @param count number of boxes
     * @return number of visible boxes
     */
    public static int cullBatch(ByteBuffer planes, ByteBuffer bounds, ByteBuffer mask, int count) {
        if (!available) {
            throw new IllegalStateException("native renderer core is not loaded");
        }
        return cullBatchNative(planes, bounds, mask, count);
    }

    private static native int cullBatchNative(ByteBuffer planes, ByteBuffer bounds, ByteBuffer mask, int count);

    private static native int abiVersion();

    private static native String buildInfoNative();

    private static native long pointerSize();

    private static native boolean isReleaseBuild();
}
