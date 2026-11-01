# RustRender

A Fabric mod for **Minecraft 1.21.11** that moves part of the client rendering
path into a native **Rust** core, with a pure-Java fallback.

## What this is, and what it is not

**It is** a working native bridge: a Rust `cdylib` compiled and packaged into
the mod jar, extracted to disk and loaded over JNI, exposing an ABI-checked,
panic-contained batch API. The first accelerated kernel is conservative
frustum/AABB visibility testing for terrain sections.

**It is not** a rewritten rendering engine. Replacing Minecraft's renderer is a
multi-year effort on the scale of Sodium or Iris, and it is not achievable by
replacing classes one at a time from a mod. What is achievable — and what this
project is built around — is moving specific, measurable hot loops out of the
JVM while keeping vanilla's structure.

Two hard constraints shape the design, both verified during setup:

- **JNI, not the Foreign Function & Memory API.** 1.21.11 runs on Java 21,
  where `java.lang.foreign` is a *preview* API requiring `--enable-preview`,
  which a mod cannot make the launcher pass. JNI is the only distributable
  option at this game version.
- **One native crossing per frame, not per object.** JNI downcalls cost tens of
  nanoseconds. Culling 8,000 sections one call at a time would spend more time
  crossing the boundary than doing the maths. The API is therefore batched:
  Java fills one direct buffer, Rust writes one visibility mask.

## Architecture

```
┌────────────────────────────── Minecraft / Fabric ──────────────────────────────┐
│  (render thread)                                                               │
│  VisibilityCuller ──setFrustum(float[24])──► plane buffer (direct, reusable)   │
│        │                                                                       │
│        └──cull(float[] bounds, byte[] mask, int count)──┐                      │
└──────────────────────────────────────────────────────────┼────────────────────┘
                                                           │  1 JNI call / frame
┌──────────────────────────────────────────────────────────▼────────────────────┐
│  NativeRendererCore (JNI)                                                      │
│    · ABI handshake · release-build check · pointer-size check                  │
└──────────────────────────────────────────────────────────┬────────────────────┘
                                                           │ zero copy
┌──────────────────────────────────────────────────────────▼────────────────────┐
│  Rust core (native/src)                                                        │
│    frustum.rs  →  6-plane conservative AABB test, SoA batch loop               │
│    lib.rs      →  JNI entry points, buffer validation, catch_unwind           │
└───────────────────────────────────────────────────────────────────────────────┘
```

Failure handling is deliberate: a missing binary, an ABI mismatch, a debug
build, or a panic inside Rust each degrade to `JavaFrustumCuller` — the same
algorithm in Java, used as both fallback and parity oracle — instead of
crashing the game.

Native binaries are extracted to `<gameDir>/rustrender/natives/<platform>/<sha8>/`
and loaded from there. The content-addressed directory makes extraction
idempotent and avoids the Windows "library locked by another process" failure
that a fixed filename causes on update.

## Measured results

Measured on this machine (2 vCPU cloud sandbox, JDK 21.0.13, Rust 1.99.1,
release builds), 8,192 sections per batch, **median of 5 interleaved trials ×
4,000 batches**:

| Path | µs/batch | ns/section | vs Java | Trial spread |
|---|---:|---:|---:|---|
| Java reference (`JavaFrustumCuller`) | 53.55 | 6.54 | 1.00x | 53.3–55.5 µs |
| Rust, bounds uploaded per frame | 61.60 | 7.52 | 0.87x | 61.4–61.8 µs |
| Rust, zero-copy staging buffer | 56.88 | 6.94 | 0.94x | 56.9–57.0 µs |

Single-shot timings on this box varied enough to show 1.08x on one run, which
is why the harness reports a median. Repeat median runs across sessions land at
**0.81–0.87x** for the upload path and **0.88–0.94x** for zero-copy: native
never wins, but the gap is a few percent, not a cliff.

The native path is **consistently slower**, and the copy is only part of the
reason. Removing the per-frame upload recovers 4.7 µs of the 8.1 µs deficit;
the remaining 3.3 µs is the kernel and the crossing. A separate no-JNI Rust
diagnostic over a comparable batch measured ~6.3 ns/box, essentially the same
as the 6.54 ns/section Java achieves here — so the kernel itself is not faster.
A 6-plane AABB test is branch-bound on unpredictable data, and C2 already
compiles it well.

### Why the culler is not wired into the renderer

Reading the mapped 1.21.11 sources settled this:

```java
// net.minecraft.client.render.Frustum
private final FrustumIntersection frustumIntersection = new FrustumIntersection();

public boolean isVisible(Box box) {
    int i = this.intersectAab(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
    return i == -2 || i == -1;
}
```

Vanilla's visibility test is **JOML's `FrustumIntersection`** — a hand-tuned,
largely branchless implementation — behind a **per-box** API. Hooking
`isVisible(Box)` would mean one JNI crossing per section per frame, which the
measurements show is strictly worse than leaving it alone. Batching it would
mean replacing the caller's section iteration, i.e. the Sodium-scale rewrite
this project deliberately is not pretending to have done.

So the native core is **loaded but not yet on the render path**. Shipping a
hook that measures 0.8x would be a regression dressed up as an optimisation.
The bridge, the fallback, the parity harness and the packaging are the parts
that are finished and proven; the first real kernel still has to be one where
native actually wins.

## Building

Requires JDK 21, a Rust toolchain, and Gradle 9.2 or newer via the wrapper
(Loom 1.14.10 declares a Gradle 9.2 plugin API and will not load on 8.x).

```bash
source scripts/env.sh      # cloud sandbox toolchain (JDK 21, Gradle 9.8.2, cargo)
./gradlew build            # compiles Rust, then the mod, into build/libs/
```

`cargo build --release --locked` runs as part of `processResources`, so the jar
always contains a binary matching the source. The Windows cross-build is the
exception: it is skipped, not failed, when mingw-w64 is missing — the build log
says `Bundling native cores: linux-x64` in that case, so a jar without a
Windows DLL is never a mystery.

### Verification

```bash
cd native && cargo test --release   # 6 unit tests for the culling maths
./gradlew parityCheck               # Rust vs Java agreement on 2,000 random batches
./gradlew benchmark                 # timing comparison
```

## Platform coverage

| Platform | Path inside the jar | Status |
|---|---|---|
| Linux x64 | `natives/linux-x64/librustrender_core.so` | built, loaded and exercised here |
| Windows x64 | `natives/windows-x64/rustrender_core.dll` | cross-compiled and inspected here; **not executed on Windows** |

A jar built on Linux now carries the Windows DLL as well, cross-compiled with
mingw-w64:

```bash
scripts/install-mingw.sh   # one-off, needs no root; re-run after a workspace restore
./gradlew build            # bundles natives/linux-x64 and natives/windows-x64
```

The DLL is linked with `-C target-feature=+crt-static`, so it imports only
system libraries — `KERNEL32.dll`, `msvcrt.dll`, `ntdll.dll` and one
`api-ms-win-core-synch` API set. There is no `libgcc_s_seh-1.dll` or
`libstdc++-6.dll` for the game to supply, which is the usual reason a
cross-built native library fails to load on a player's machine.

Without mingw-w64 the build still succeeds: the task is skipped, the Windows
directory is simply absent, and Windows players get the Java fallback and a
warning in the log instead of a crash.

Verified on the shipped artifact: `file` reports `PE32+ executable (DLL)
(console) x86-64`, and all five JNI entry points are exported by name
(`Java_com_tntpvp_rustrender_nativebridge_...`). The calling convention matches
because the Rust entry points use `extern "system"`, which is the C convention
on x64.

macOS and Linux arm64 are already in the packaging map but are not built here;
each needs a runner for its own target. Windows on ARM is not covered.

## Notes on the target version

1.21.11 (2025-12-09) is the **last obfuscated, Java-21** Minecraft release.
From 26.1 onward the game ships deobfuscated, uses Java 25, and 26.2 adds an
optional Vulkan backend. Targeting 1.21.11 means Yarn `1.21.11+build.6`,
Loom 1.14, and an OpenGL-only Blaze3D backend; it also means the mod will not
carry over to 26.x without a port, since Yarn and Intermediary stop there.

## Roadmap

The measurement above sets the order of work, and it is not the order the name
suggests:

1. **Section meshing, not culling.** Meshing does far more work per byte than a
   visibility test (vertex generation, face culling, quad packing, light
   lookup), runs off the render thread, and its output is a buffer — so one
   crossing amortises over thousands of vertices instead of one box. This is
   the only place in the 1.21.11 renderer where a native kernel has a
   realistic chance of beating the JVM.
2. **SIMD in the existing kernel** — if culling is kept at all, an AVX2 path
   testing 8 boxes at a time is the only way it beats 6 ns/box. Verify it with
   the same harness before believing it.
3. **Cross-platform CI** — the natives matrix described above.
4. **In-game profiling** — a debug overlay reporting backend, cull counts and
   frame-time deltas, so improvements are measured rather than claimed.

## Licence

MIT.
