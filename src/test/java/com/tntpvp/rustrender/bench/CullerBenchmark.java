package com.tntpvp.rustrender.bench;

import com.tntpvp.rustrender.engine.JavaFrustumCuller;
import com.tntpvp.rustrender.engine.VisibilityCuller;
import com.tntpvp.rustrender.nativebridge.NativeRendererCore;

import java.nio.FloatBuffer;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Random;
import java.util.function.IntSupplier;

/**
 * Times the native culler against the Java reference implementation.
 *
 * <p>Three paths are measured, because the difference between them is the
 * whole design question:
 *
 * <ol>
 *   <li><b>java</b> — the reference implementation, {@code float[]} in, mask out.</li>
 *   <li><b>rust + upload</b> — {@link VisibilityCuller#cull} as a naive port
 *       would call it: copy the bounds array into the native staging buffer,
 *       then one JNI call.</li>
 *   <li><b>rust zero-copy</b> — the producer writes bounds straight into
 *       {@link VisibilityCuller#boundsView()} and the frame pays only for the
 *       JNI call and the maths.</li>
 * </ol>
 *
 * <p>Interleaved trials with a median are used because single-shot timings on a
 * shared machine vary by more than 20%, which is wider than the effect being
 * measured. Reporting one sample would be lying with numbers.
 *
 * <p>These are kernel throughput figures, not a game benchmark: no Minecraft
 * classes are involved. Run with {@code ./gradlew benchmark}.
 */
public final class CullerBenchmark {

    private static final int BOX_COUNT = 8192;
    private static final int WARMUP_BATCHES = 1_000;
    private static final int TRIALS = 5;
    private static final int BATCHES_PER_TRIAL = 4_000;

    private CullerBenchmark() {
    }

    public static void main(String[] args) {
        if (!NativeRendererCore.initialize(Path.of("."))) {
            System.err.println("FAIL: native core unavailable: " + NativeRendererCore.failureReason());
            System.exit(1);
        }
        System.out.println("Native core: " + NativeRendererCore.buildInfo());
        System.out.printf("Batch size: %d sections, %d trials x %d batches%n%n",
                BOX_COUNT, TRIALS, BATCHES_PER_TRIAL);

        Random random = new Random(0x5EEDL);
        float[] planes = frustum();
        float[] bounds = sections(random, BOX_COUNT);
        byte[] mask = new byte[BOX_COUNT];

        VisibilityCuller culler = new VisibilityCuller();
        culler.setFrustum(planes);
        if (!culler.isNativeActive()) {
            System.err.println("FAIL: native backend was not selected");
            System.exit(1);
        }

        // Preload the staging buffer for the zero-copy measurement, exactly as
        // a renderer would while it walks its section list. Capacity must be
        // reserved first: growth replaces the buffer and invalidates the view.
        culler.ensureCapacity(BOX_COUNT);
        FloatBuffer view = culler.boundsView();
        view.put(bounds, 0, BOX_COUNT * 6);
        view.flip();

        // --- warm up every path --------------------------------------------
        for (int i = 0; i < WARMUP_BATCHES; i++) {
            JavaFrustumCuller.cull(planes, bounds, mask, BOX_COUNT);
            culler.cull(bounds, mask, BOX_COUNT);
            culler.cullPreloaded(mask, BOX_COUNT);
        }

        // --- correctness gate ----------------------------------------------
        // A faster wrong answer is worthless, so verify before timing.
        int javaVisible = JavaFrustumCuller.cull(planes, bounds, mask, BOX_COUNT);
        byte[] javaMask = mask.clone();

        int uploadVisible = culler.cull(bounds, mask, BOX_COUNT);
        if (uploadVisible != javaVisible || !Arrays.equals(mask, javaMask)) {
            System.err.println("FAIL: native (upload) disagrees with the Java reference");
            System.exit(1);
        }

        int zeroCopyVisible = culler.cullPreloaded(mask, BOX_COUNT);
        if (zeroCopyVisible != javaVisible || !Arrays.equals(mask, javaMask)) {
            System.err.println("FAIL: native (zero-copy) disagrees with the Java reference");
            System.exit(1);
        }

        // --- measure, interleaved so drift hits all three equally -----------
        long[] javaSamples = new long[TRIALS];
        long[] uploadSamples = new long[TRIALS];
        long[] zeroCopySamples = new long[TRIALS];

        for (int trial = 0; trial < TRIALS; trial++) {
            javaSamples[trial] = time(() -> JavaFrustumCuller.cull(planes, bounds, mask, BOX_COUNT));
            uploadSamples[trial] = time(() -> culler.cull(bounds, mask, BOX_COUNT));
            zeroCopySamples[trial] = time(() -> culler.cullPreloaded(mask, BOX_COUNT));
        }

        long javaMedian = median(javaSamples);
        long uploadMedian = median(uploadSamples);
        long zeroCopyMedian = median(zeroCopySamples);

        report("java          ", javaMedian, javaSamples);
        report("rust + upload ", uploadMedian, uploadSamples);
        report("rust zero-copy", zeroCopyMedian, zeroCopySamples);

        System.out.printf("%nSpeedup vs java (median): upload %.2fx, zero-copy %.2fx%n",
                javaMedian / (double) uploadMedian,
                javaMedian / (double) zeroCopyMedian);
        System.out.printf("Visible per batch: %d of %d%n", javaVisible, BOX_COUNT);
        System.out.printf("Native calls: %d, fallback calls: %d%n", culler.nativeCalls(), culler.fallbackCalls());
        System.out.println("\nNote: kernel throughput only, not frame rates.");
    }

    /** Runs the body {@code BATCHES_PER_TRIAL} times and returns total nanos. */
    private static long time(IntSupplier body) {
        long start = System.nanoTime();
        long checksum = 0;
        for (int i = 0; i < BATCHES_PER_TRIAL; i++) {
            // Consume the result so the JIT cannot eliminate the call.
            checksum += body.getAsInt();
        }
        if (checksum == Long.MIN_VALUE) {
            throw new IllegalStateException("impossible checksum");
        }
        return System.nanoTime() - start;
    }

    private static long median(long[] samples) {
        long[] sorted = samples.clone();
        Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }

    private static void report(String label, long medianNanos, long[] samples) {
        long min = Arrays.stream(samples).min().orElseThrow();
        long max = Arrays.stream(samples).max().orElseThrow();
        double perBatch = medianNanos / (double) BATCHES_PER_TRIAL;

        System.out.printf("%s : %8.3f us/batch  %6.2f ns/section  (spread %.3f-%.3f us)%n",
                label,
                perBatch / 1_000.0,
                perBatch / BOX_COUNT,
                (min / (double) BATCHES_PER_TRIAL) / 1_000.0,
                (max / (double) BATCHES_PER_TRIAL) / 1_000.0);
    }

    /** A representative perspective frustum, roughly 70 degrees vertical FOV. */
    private static float[] frustum() {
        double fov = Math.toRadians(70.0);
        double aspect = 16.0 / 9.0;
        double near = 0.05;
        double far = 512.0;

        double tanY = Math.tan(fov / 2.0);
        double tanX = tanY * aspect;

        // Inward-facing planes for a camera at the origin looking down -Z:
        // a point is inside when nx*x + ny*y + nz*z + d >= 0.
        float[] planes = new float[24];
        writePlane(planes, 0, 1.0, 0.0, -tanX, 0.0);   // left
        writePlane(planes, 1, -1.0, 0.0, -tanX, 0.0);  // right
        writePlane(planes, 2, 0.0, 1.0, -tanY, 0.0);   // bottom
        writePlane(planes, 3, 0.0, -1.0, -tanY, 0.0);  // top
        writePlane(planes, 4, 0.0, 0.0, -1.0, -near);  // near
        writePlane(planes, 5, 0.0, 0.0, 1.0, far);     // far
        return planes;
    }

    private static void writePlane(float[] destination, int index, double nx, double ny, double nz, double d) {
        double length = Math.sqrt(nx * nx + ny * ny + nz * nz);
        int base = index * 4;
        destination[base] = (float) (nx / length);
        destination[base + 1] = (float) (ny / length);
        destination[base + 2] = (float) (nz / length);
        destination[base + 3] = (float) (d / length);
    }

    /** Section-sized boxes spread across a 384-block-tall world. */
    private static float[] sections(Random random, int count) {
        float[] bounds = new float[count * 6];
        for (int i = 0; i < count; i++) {
            int base = i * 6;
            float x = (random.nextFloat() * 1024.0f) - 512.0f;
            float y = (random.nextFloat() * 384.0f) - 64.0f;
            float z = (random.nextFloat() * 1024.0f) - 512.0f;
            bounds[base] = x;
            bounds[base + 1] = y;
            bounds[base + 2] = z;
            bounds[base + 3] = x + 16.0f;
            bounds[base + 4] = y + 16.0f;
            bounds[base + 5] = z + 16.0f;
        }
        return bounds;
    }
}
