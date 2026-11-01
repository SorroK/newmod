package com.tntpvp.rustrender.bench;

import com.tntpvp.rustrender.engine.JavaFrustumCuller;
import com.tntpvp.rustrender.engine.VisibilityCuller;
import com.tntpvp.rustrender.nativebridge.NativeRendererCore;

import java.nio.file.Path;
import java.util.Random;

/**
 * Compares the Rust culler against the Java reference implementation on
 * randomised frustums and boxes.
 *
 * <p>This matters more than a benchmark: a culler that is 3x faster but
 * disagrees with the reference on a boundary case removes visible chunks. Run
 * it with {@code ./gradlew parityCheck}; it exits non-zero on any mismatch.
 */
public final class ParityCheck {

    private static final int ITERATIONS = 2_000;
    private static final int MAX_BOXES = 512;

    private ParityCheck() {
    }

    public static void main(String[] args) {
        boolean loaded = NativeRendererCore.initialize(Path.of("."));
        if (!loaded) {
            System.err.println("FAIL: native core unavailable: " + NativeRendererCore.failureReason());
            System.exit(1);
        }
        System.out.println("Native core: " + NativeRendererCore.buildInfo());

        Random random = new Random(0xC0FFEEL);
        int mismatches = 0;
        int comparedBoxes = 0;
        int nonTrivialBatches = 0;

        for (int iteration = 0; iteration < ITERATIONS; iteration++) {
            int count = 1 + random.nextInt(MAX_BOXES);
            float[] planes = randomFrustum(random);
            float[] bounds = randomBoxes(random, count);

            byte[] nativeMask = new byte[count];
            byte[] javaMask = new byte[count];

            int nativeVisible = JavaFrustumCuller.cull(planes, bounds, javaMask, count);

            VisibilityCuller culler = new VisibilityCuller();
            culler.setFrustum(planes);
            int rustVisible = culler.cull(bounds, nativeMask, count);

            if (!culler.isNativeActive()) {
                System.err.println("FAIL: culler did not select the native backend");
                System.exit(1);
            }

            comparedBoxes += count;
            if (nativeVisible > 0 && nativeVisible < count) {
                nonTrivialBatches++;
            }

            if (rustVisible != nativeVisible) {
                mismatches++;
                System.err.printf("Mismatch at iteration %d: java=%d rust=%d (count=%d)%n",
                        iteration, nativeVisible, rustVisible, count);
            } else {
                for (int box = 0; box < count; box++) {
                    if (nativeMask[box] != javaMask[box]) {
                        mismatches++;
                        System.err.printf("Mismatch at iteration %d box %d: java=%d rust=%d%n",
                                iteration, box, javaMask[box], nativeMask[box]);
                        break;
                    }
                }
            }
        }

        System.out.printf("Compared %d batches (%d boxes, %d mixed-visibility batches)%n",
                ITERATIONS, comparedBoxes, nonTrivialBatches);

        if (mismatches > 0) {
            System.err.printf("FAIL: %d mismatch(es)%n", mismatches);
            System.exit(1);
        }

        System.out.println("PASS: native and Java cullers agree on all batches");
    }

    /** Random but valid frustum: six planes through a random point with random normals. */
    private static float[] randomFrustum(Random random) {
        float[] planes = new float[24];
        for (int plane = 0; plane < 6; plane++) {
            float nx = (float) random.nextGaussian();
            float ny = (float) random.nextGaussian();
            float nz = (float) random.nextGaussian();
            float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (length < 1e-4f) {
                nx = 1.0f;
                ny = 0.0f;
                nz = 0.0f;
                length = 1.0f;
            }
            nx /= length;
            ny /= length;
            nz /= length;

            // Push the plane a random distance out from the origin so we get a
            // mix of empty, partially filled and fully contained frustums.
            float distance = (float) (random.nextDouble() * 64.0 - 32.0);

            planes[plane * 4] = nx;
            planes[plane * 4 + 1] = ny;
            planes[plane * 4 + 2] = nz;
            planes[plane * 4 + 3] = -distance;
        }
        return planes;
    }

    private static float[] randomBoxes(Random random, int count) {
        float[] bounds = new float[count * 6];
        for (int box = 0; box < count; box++) {
            int base = box * 6;

            // Vanilla section size is 16 blocks; occasionally emit a degenerate
            // box so the invalid-input path is covered too.
            float size = random.nextInt(32) == 0 ? 0.0f : 16.0f;
            float x = (float) (random.nextDouble() * 512.0 - 256.0);
            float y = (float) (random.nextDouble() * 384.0 - 64.0);
            float z = (float) (random.nextDouble() * 512.0 - 256.0);

            bounds[base] = x;
            bounds[base + 1] = y;
            bounds[base + 2] = z;
            bounds[base + 3] = x + size;
            bounds[base + 4] = y + size;
            bounds[base + 5] = z + size;
        }
        return bounds;
    }
}
