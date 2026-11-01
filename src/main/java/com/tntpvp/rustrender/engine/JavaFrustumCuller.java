package com.tntpvp.rustrender.engine;

/**
 * Pure-Java reference implementation of the batch visibility test.
 *
 * <p>This is deliberately a direct transcription of {@code native/src/frustum.rs}:
 * it is the fallback when the native core is unavailable, and the oracle the
 * parity check compares against, so a bug in one shows up as a mismatch
 * instead of silently culling chunks.
 */
public final class JavaFrustumCuller {

    /** Floats per plane: {@code [nx, ny, nz, d]}. */
    public static final int PLANE_STRIDE = 4;

    /** Floats per box: {@code [minX, minY, minZ, maxX, maxY, maxZ]}. */
    public static final int BOX_STRIDE = 6;

    private JavaFrustumCuller() {
    }

    /**
     * Same contract as the native entry point: fills {@code mask} with 1/0 and
     * returns the number of visible boxes.
     */
    public static int cull(float[] planes, float[] bounds, byte[] mask, int count) {
        if (planes.length < 6 * PLANE_STRIDE) {
            throw new IllegalArgumentException("planes buffer too small: need " + (6 * PLANE_STRIDE) + " floats");
        }
        if (bounds.length < count * BOX_STRIDE) {
            throw new IllegalArgumentException("bounds buffer too small for " + count + " boxes");
        }
        if (mask.length < count) {
            throw new IllegalArgumentException("mask buffer too small for " + count + " boxes");
        }

        int visible = 0;

        for (int index = 0; index < count; index++) {
            int base = index * BOX_STRIDE;
            float minX = bounds[base];
            float minY = bounds[base + 1];
            float minZ = bounds[base + 2];
            float maxX = bounds[base + 3];
            float maxY = bounds[base + 4];
            float maxZ = bounds[base + 5];

            boolean isVisible = minX <= maxX && minY <= maxY && minZ <= maxZ;

            if (isVisible) {
                for (int plane = 0; plane < 6 * PLANE_STRIDE && isVisible; plane += PLANE_STRIDE) {
                    float nx = planes[plane];
                    float ny = planes[plane + 1];
                    float nz = planes[plane + 2];
                    float d = planes[plane + 3];

                    float px = nx >= 0.0f ? maxX : minX;
                    float py = ny >= 0.0f ? maxY : minY;
                    float pz = nz >= 0.0f ? maxZ : minZ;

                    if (nx * px + ny * py + nz * pz + d < 0.0f) {
                        isVisible = false;
                    }
                }
            }

            mask[index] = (byte) (isVisible ? 1 : 0);
            if (isVisible) {
                visible++;
            }
        }

        return visible;
    }
}
