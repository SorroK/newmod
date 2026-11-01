//! JNI surface of the RustRender core.
//!
//! Design rules, learned the hard way by every mod that tried this before:
//!
//! * **One crossing per frame, not per object.** Downcalls cost tens of
//!   nanoseconds; calling one per chunk section would erase any gain from
//!   doing the maths natively.
//! * **Zero copy.** The Java side hands over direct `ByteBuffer`s allocated
//!   once at startup; the native side reads and writes them in place.
//! * **No panics across FFI.** Every entry point is wrapped in
//!   `catch_unwind`, and a panic becomes a normal Java exception instead of
//!   taking the game down.
//! * **No JNI lookups on foreign threads.** These functions only run on the
//!   calling (render) thread, so no cached class references are needed.

use jni::objects::{JByteBuffer, JClass};
use jni::sys::{jint, jlong, jboolean};
use jni::JNIEnv;
use std::panic::{catch_unwind, AssertUnwindSafe};

mod frustum;

/// Must match `NativeRendererCore.ABI_VERSION` on the Java side. Bumped on any
/// incompatible change to the entry points below.
const ABI_VERSION: jint = 1;

/// Size of the plane block handed to `cullBatch`, in floats.
const PLANE_FLOATS: usize = 6 * frustum::PLANE_STRIDE;

/// Number of bytes per box in the bounds buffer.
const BOX_BYTES: usize = frustum::BOX_STRIDE * std::mem::size_of::<f32>();

/// Java: `NativeRendererCore.abiVersion()`
#[no_mangle]
pub extern "system" fn Java_com_tntpvp_rustrender_nativebridge_NativeRendererCore_abiVersion(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    ABI_VERSION
}

/// Java: `NativeRendererCore.buildInfoNative()`
#[no_mangle]
pub extern "system" fn Java_com_tntpvp_rustrender_nativebridge_NativeRendererCore_buildInfoNative<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jni::sys::jstring {
    let profile = if cfg!(debug_assertions) { "debug" } else { "release" };
    let info = format!(
        "rustrender_core {} ({} {}, {})",
        env!("CARGO_PKG_VERSION"),
        std::env::consts::OS,
        std::env::consts::ARCH,
        profile,
    );

    match env.new_string(info) {
        Ok(string) => string.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

/// Java: `NativeRendererCore.cullBatchNative(ByteBuffer planes, ByteBuffer
/// bounds, ByteBuffer mask, int count)`
///
/// `planes`: 24 floats, six inward-facing `[nx, ny, nz, d]` planes.
/// `bounds`: `count * 6` floats, `[minX, minY, minZ, maxX, maxY, maxZ]` each.
/// `mask`:   `count` bytes, written with 1 (visible) or 0 (culled).
///
/// Returns the number of visible boxes, or -1 if the native call failed.
#[no_mangle]
pub extern "system" fn Java_com_tntpvp_rustrender_nativebridge_NativeRendererCore_cullBatchNative(
    mut env: JNIEnv,
    _class: JClass,
    planes_buf: JByteBuffer,
    bounds_buf: JByteBuffer,
    mask_buf: JByteBuffer,
    count: jint,
) -> jint {
    if count < 0 {
        let _ = env.throw_new("java/lang/IllegalArgumentException", "count must be >= 0");
        return -1;
    }
    let count = count as usize;

    let planes_ptr = match env.get_direct_buffer_address(&planes_buf) {
        Ok(ptr) if !ptr.is_null() => ptr,
        _ => {
            let _ = env.throw_new(
                "java/lang/IllegalArgumentException",
                "planes buffer is not a direct buffer",
            );
            return -1;
        }
    };
    let bounds_ptr = match env.get_direct_buffer_address(&bounds_buf) {
        Ok(ptr) if !ptr.is_null() => ptr,
        _ => {
            let _ = env.throw_new(
                "java/lang/IllegalArgumentException",
                "bounds buffer is not a direct buffer",
            );
            return -1;
        }
    };
    let mask_ptr = match env.get_direct_buffer_address(&mask_buf) {
        Ok(ptr) if !ptr.is_null() => ptr,
        _ => {
            let _ = env.throw_new(
                "java/lang/IllegalArgumentException",
                "mask buffer is not a direct buffer",
            );
            return -1;
        }
    };

    let planes_capacity = env.get_direct_buffer_capacity(&planes_buf).unwrap_or(0);
    let bounds_capacity = env.get_direct_buffer_capacity(&bounds_buf).unwrap_or(0);
    let mask_capacity = env.get_direct_buffer_capacity(&mask_buf).unwrap_or(0);

    if planes_capacity < PLANE_FLOATS * std::mem::size_of::<f32>() {
        let _ = env.throw_new(
            "java/lang/IllegalArgumentException",
            format!(
                "planes buffer needs {} bytes, has {}",
                PLANE_FLOATS * std::mem::size_of::<f32>(),
                planes_capacity
            ),
        );
        return -1;
    }
    if bounds_capacity < count * BOX_BYTES {
        let _ = env.throw_new(
            "java/lang/IllegalArgumentException",
            format!(
                "bounds buffer needs {} bytes, has {}",
                count * BOX_BYTES,
                bounds_capacity
            ),
        );
        return -1;
    }
    if mask_capacity < count {
        let _ = env.throw_new(
            "java/lang/IllegalArgumentException",
            format!("mask buffer needs {} bytes, has {}", count, mask_capacity),
        );
        return -1;
    }

    let result = catch_unwind(AssertUnwindSafe(|| {
        // SAFETY: capacities were validated above; the buffers are direct and
        // owned by the caller for the duration of the call.
        let planes = unsafe { std::slice::from_raw_parts(planes_ptr as *const f32, PLANE_FLOATS) };
        let bounds = unsafe { std::slice::from_raw_parts(bounds_ptr as *const f32, count * frustum::BOX_STRIDE) };
        let mask = unsafe { std::slice::from_raw_parts_mut(mask_ptr, count) };

        frustum::cull_batch(planes, bounds, mask, count)
    }));

    match result {
        Ok(visible) => visible as jint,
        Err(_) => {
            let _ = env.throw_new(
                "java/lang/IllegalStateException",
                "rustrender core panicked during cullBatch",
            );
            -1
        }
    }
}

/// Java: `NativeRendererCore.pointerSize()` — trivial call used by the loader
/// to prove the library is linked and callable before it is trusted.
#[no_mangle]
pub extern "system" fn Java_com_tntpvp_rustrender_nativebridge_NativeRendererCore_pointerSize(
    _env: JNIEnv,
    _class: JClass,
) -> jlong {
    std::mem::size_of::<usize>() as jlong
}

/// Java: `NativeRendererCore.isReleaseBuild()`
#[no_mangle]
pub extern "system" fn Java_com_tntpvp_rustrender_nativebridge_NativeRendererCore_isReleaseBuild(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    // `debug_assertions` are compiled out in release builds.
    (!cfg!(debug_assertions)) as jboolean
}
