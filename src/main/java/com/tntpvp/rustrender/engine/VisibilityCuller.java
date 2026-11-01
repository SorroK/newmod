package com.tntpvp.rustrender.engine;

import com.tntpvp.rustrender.nativebridge.NativeRendererCore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.Arrays;

/**
 * The seam between the game and the native core.
 *
 * <p>Holds reusable direct buffers so a frame's worth of culling costs exactly
 * one JNI crossing, and falls back to {@link JavaFrustumCuller} permanently if
 * the native path ever misbehaves. Not thread-safe: it is only ever touched
 * from the render thread.
 */
public final class VisibilityCuller {

    private static final Logger LOGGER = LoggerFactory.getLogger("RustRender");

    /** Floats per plane: {@code [nx, ny, nz, d]}. */
    public static final int PLANE_FLOATS = 24;

    /** Floats per box: {@code [minX, minY, minZ, maxX, maxY, maxZ]}. */
    public static final int BOX_FLOATS = 6;

    private static final int INITIAL_CAPACITY = 4096;

    private final float[] planes = new float[PLANE_FLOATS];
    private final boolean nativeAvailable;

    private ByteBuffer planeBuffer;
    private ByteBuffer boundsBuffer;
    private ByteBuffer maskBuffer;
    private int capacity;

    private boolean nativeDisabled;
    private long nativeCalls;
    private long fallbackCalls;

    public VisibilityCuller() {
        this.nativeAvailable = NativeRendererCore.isAvailable();
        resize(INITIAL_CAPACITY);
        Arrays.fill(planes, 0.0f);
    }

    /**
     * Copies the six frustum planes into the native plane buffer.
     *
     * @param equations 24 floats: six {@code [nx, ny, nz, d]} inward-facing planes
     */
    public void setFrustum(float[] equations) {
        if (equations.length < PLANE_FLOATS) {
            throw new IllegalArgumentException("expected " + PLANE_FLOATS + " floats, got " + equations.length);
        }
        System.arraycopy(equations, 0, planes, 0, PLANE_FLOATS);
        planeBuffer.clear();
        planeBuffer.asFloatBuffer().put(planes);
    }

    /**
     * Culls {@code count} boxes.
     *
     * @param bounds {@code count * 6} floats
     * @param mask receives {@code count} bytes of 1/0
     * @return the number of visible boxes
     */
    public int cull(float[] bounds, byte[] mask, int count) {
        if (count < 0) {
            throw new IllegalArgumentException("count must be >= 0");
        }
        if (count == 0) {
            return 0;
        }
        if (bounds.length < count * BOX_FLOATS) {
            throw new IllegalArgumentException("bounds too small for " + count + " boxes");
        }
        if (mask.length < count) {
            throw new IllegalArgumentException("mask too small for " + count + " boxes");
        }
        if (count > capacity) {
            resize(count);
        }

        if (nativeAvailable && !nativeDisabled) {
            try {
                boundsBuffer.clear();
                boundsBuffer.asFloatBuffer().put(bounds, 0, count * BOX_FLOATS);
                maskBuffer.clear();

                int visible = NativeRendererCore.cullBatch(planeBuffer, boundsBuffer, maskBuffer, count);
                maskBuffer.get(0, mask, 0, count);
                nativeCalls++;
                return visible;
            } catch (RuntimeException | UnsatisfiedLinkError e) {
                nativeDisabled = true;
                LOGGER.error("Native culling failed; falling back to the Java implementation for the rest of the session", e);
            }
        }

        fallbackCalls++;
        return JavaFrustumCuller.cull(planes, bounds, mask, count);
    }

    /**
     * Direct view of the bounds staging buffer.
     *
     * <p>A producer that writes section bounds straight into this view skips
     * the per-frame array upload, which otherwise dominates the cost of the
     * native path: copying 8,192 sections is ~196 KB per frame, several times
     * more expensive than testing them.
     *
     * <p>The returned view is invalidated by any growth of the staging buffer,
     * so call {@link #ensureCapacity(int)} first and re-fetch after a resize.
     */
    public FloatBuffer boundsView() {
        boundsBuffer.clear();
        return boundsBuffer.asFloatBuffer();
    }

    /**
     * Culls using whatever {@link #boundsView()} already contains, without
     * uploading a bounds array. Used by the benchmark to separate kernel cost
     * from staging cost, and by the renderer hook when it fills the buffer
     * directly.
     */
    public int cullPreloaded(byte[] mask, int count) {
        if (count < 0 || count > capacity) {
            throw new IllegalArgumentException("count " + count + " outside 0.." + capacity);
        }
        if (count == 0) {
            return 0;
        }
        if (mask.length < count) {
            throw new IllegalArgumentException("mask too small for " + count + " boxes");
        }

        if (nativeAvailable && !nativeDisabled) {
            try {
                maskBuffer.clear();
                int visible = NativeRendererCore.cullBatch(planeBuffer, boundsBuffer, maskBuffer, count);
                maskBuffer.get(0, mask, 0, count);
                nativeCalls++;
                return visible;
            } catch (RuntimeException | UnsatisfiedLinkError e) {
                nativeDisabled = true;
                LOGGER.error("Native culling failed; falling back to the Java implementation for the rest of the session", e);
            }
        }

        float[] bounds = new float[count * BOX_FLOATS];
        boundsBuffer.clear();
        boundsBuffer.asFloatBuffer().get(bounds, 0, count * BOX_FLOATS);
        fallbackCalls++;
        return JavaFrustumCuller.cull(planes, bounds, mask, count);
    }

    /**
     * Grows the staging buffers to hold at least {@code sections} boxes.
     * Call before {@link #boundsView()} when more than 4,096 sections may be
     * submitted, since growth replaces the underlying buffer.
     */
    public void ensureCapacity(int sections) {
        if (sections > capacity) {
            resize(sections);
        }
    }

    public boolean isNativeActive() {
        return nativeAvailable && !nativeDisabled;
    }

    public String backendName() {
        return isNativeActive() ? "rust" : "java";
    }

    public long nativeCalls() {
        return nativeCalls;
    }

    public long fallbackCalls() {
        return fallbackCalls;
    }

    private void resize(int newCapacity) {
        capacity = newCapacity;
        planeBuffer = NativeRendererCore.allocateDirect(PLANE_FLOATS * Float.BYTES);
        boundsBuffer = NativeRendererCore.allocateDirect(newCapacity * BOX_FLOATS * Float.BYTES);
        maskBuffer = NativeRendererCore.allocateDirect(newCapacity);
        // Re-apply the current planes: the buffers were just replaced.
        planeBuffer.asFloatBuffer().put(planes);
    }
}
