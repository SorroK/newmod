package com.tntpvp.rustrender.nativebridge;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Extracts and loads the platform-specific {@code rustrender_core} library.
 *
 * <p>Fabric jars cannot be loaded directly from the classpath archive, so the
 * library is written to disk first. The destination directory is keyed by the
 * first 8 hex characters of the library's SHA-256, which makes extraction
 * idempotent across restarts and mod updates, and avoids the Windows
 * "file locked by another process" failure that a fixed filename causes.
 */
public final class NativeLibraryLoader {

    private static final String PLATFORM = detectPlatform();
    private static final String LIBRARY_NAME = detectLibraryName();

    private NativeLibraryLoader() {
    }

    /**
     * Extracts the bundled library under {@code extractionRoot} and loads it.
     */
    public static void load(Path extractionRoot) throws IOException {
        String resource = "/natives/" + PLATFORM + "/" + LIBRARY_NAME;

        try (InputStream in = NativeLibraryLoader.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException(
                        "No native library bundled for platform '" + PLATFORM
                                + "' (looked for classpath resource " + resource + "). "
                                + "A jar built on one platform contains only that platform's binary.");
            }

            byte[] bytes = in.readAllBytes();
            String hash = sha256Hex(bytes).substring(0, 8);
            Path directory = extractionRoot.resolve("natives").resolve(PLATFORM).resolve(hash);
            Files.createDirectories(directory);

            Path target = directory.resolve(LIBRARY_NAME);
            if (!Files.isRegularFile(target) || Files.size(target) != bytes.length) {
                writeAtomically(directory, target, bytes);
            }

            loadAbsolute(target);
        }
    }

    /**
     * Loads the library from an already-extracted directory. Used by the
     * development harnesses, which run without a mod jar.
     */
    public static void loadFromDirectory(Path directory) {
        Path library = directory.resolve(LIBRARY_NAME);
        if (!Files.isRegularFile(library)) {
            throw new IllegalStateException("No native library at " + library.toAbsolutePath());
        }
        loadAbsolute(library);
    }

    private static void loadAbsolute(Path library) {
        System.load(library.toAbsolutePath().toString());
    }

    private static void writeAtomically(Path directory, Path target, byte[] bytes) throws IOException {
        Path temporary = Files.createTempFile(directory, LIBRARY_NAME, ".tmp");
        try {
            Files.write(temporary, bytes);
            try {
                Files.move(temporary, target,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JLS but unavailable", e);
        }
    }

    /** Platform key matching the {@code natives/<platform>/} layout inside the jar. */
    public static String platform() {
        return PLATFORM;
    }

    /** File name of the library on this platform, per {@link System#mapLibraryName}. */
    public static String libraryName() {
        return LIBRARY_NAME;
    }

    private static String detectPlatform() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        String osName = os.contains("win") ? "windows" : (os.contains("mac") || os.contains("darwin")) ? "macos" : "linux";
        String archName = (arch.equals("aarch64") || arch.equals("arm64")) ? "arm64" : "x64";
        return osName + "-" + archName;
    }

    private static String detectLibraryName() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return "rustrender_core.dll";
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return "librustrender_core.dylib";
        }
        return "librustrender_core.so";
    }
}
