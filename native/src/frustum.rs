//! Conservative frustum-vs-AABB visibility testing.
//!
//! Planes are stored as `[nx, ny, nz, d]` and are normalised, pointing *into*
//! the frustum, so a point is inside when `nx*x + ny*y + nz*z + d >= 0`.
//!
//! The test is conservative: a box is only rejected when it is provably
//! outside at least one plane. False positives (visible when actually
//! occluded) are acceptable, false negatives are not, because those would pop
//! chunks out of existence.

/// Number of floats per plane (nx, ny, nz, d).
pub const PLANE_STRIDE: usize = 4;

/// Number of floats per axis-aligned box (minX, minY, minZ, maxX, maxY, maxZ).
pub const BOX_STRIDE: usize = 6;

/// A single inward-facing clipping plane.
#[derive(Clone, Copy, Debug)]
pub struct Plane {
    pub x: f32,
    pub y: f32,
    pub z: f32,
    pub d: f32,
}

impl Plane {
    #[inline(always)]
    pub fn from_slice(data: &[f32], index: usize) -> Self {
        let base = index * PLANE_STRIDE;
        Self {
            x: data[base],
            y: data[base + 1],
            z: data[base + 2],
            d: data[base + 3],
        }
    }
}

/// A single axis-aligned bounding box.
#[derive(Clone, Copy, Debug)]
pub struct Aabb {
    pub min_x: f32,
    pub min_y: f32,
    pub min_z: f32,
    pub max_x: f32,
    pub max_y: f32,
    pub max_z: f32,
}

impl Aabb {
    #[inline(always)]
    pub fn from_slice(data: &[f32], index: usize) -> Self {
        let base = index * BOX_STRIDE;
        Self {
            min_x: data[base],
            min_y: data[base + 1],
            min_z: data[base + 2],
            max_x: data[base + 3],
            max_y: data[base + 4],
            max_z: data[base + 5],
        }
    }

    #[inline(always)]
    pub fn is_valid(&self) -> bool {
        self.min_x <= self.max_x && self.min_y <= self.max_y && self.min_z <= self.max_z
    }
}

/// Full 6-plane test. Returns `true` when the box may be visible.
#[inline(always)]
pub fn box_visible(planes: &[Plane; 6], aabb: &Aabb) -> bool {
    // Reject degenerate boxes outright: they cannot contribute geometry and
    // vanilla occasionally produces zero-extent sections at level borders.
    if !aabb.is_valid() {
        return false;
    }

    for plane in planes {
        // Pick the "positive vertex": the box corner furthest along the plane
        // normal. If that corner is behind the plane, the whole box is.
        let px = if plane.x >= 0.0 {
            aabb.max_x
        } else {
            aabb.min_x
        };
        let py = if plane.y >= 0.0 {
            aabb.max_y
        } else {
            aabb.min_y
        };
        let pz = if plane.z >= 0.0 {
            aabb.max_z
        } else {
            aabb.min_z
        };

        if plane.x * px + plane.y * py + plane.z * pz + plane.d < 0.0 {
            return false;
        }
    }

    true
}

/// Batch entry point: fills `out_mask[i]` with 1 for visible boxes, 0 for
/// culled ones, and returns the number of visible boxes.
///
/// Panics if the input slices are too small for `count`; the JNI layer
/// validates sizes before calling this, and a panic here is caught and turned
/// into a Java exception.
pub fn cull_batch(planes_data: &[f32], bounds_data: &[f32], out_mask: &mut [u8], count: usize) -> usize {
    assert!(
        planes_data.len() >= 6 * PLANE_STRIDE,
        "plane buffer too small: need 24 floats, got {}",
        planes_data.len()
    );
    assert!(
        bounds_data.len() >= count * BOX_STRIDE,
        "bounds buffer too small: need {} floats, got {}",
        count * BOX_STRIDE,
        bounds_data.len()
    );
    assert!(
        out_mask.len() >= count,
        "mask buffer too small: need {} bytes, got {}",
        count,
        out_mask.len()
    );

    let planes = [
        Plane::from_slice(planes_data, 0),
        Plane::from_slice(planes_data, 1),
        Plane::from_slice(planes_data, 2),
        Plane::from_slice(planes_data, 3),
        Plane::from_slice(planes_data, 4),
        Plane::from_slice(planes_data, 5),
    ];

    let mut visible = 0usize;

    // Non-iterator loop: this is the hot path, and it keeps LLVM free to
    // vectorise the stores.
    for index in 0..count {
        let aabb = Aabb::from_slice(bounds_data, index);
        let is_visible = box_visible(&planes, &aabb);
        out_mask[index] = is_visible as u8;
        visible += is_visible as usize;
    }

    visible
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Unit cube frustum: [-1, 1] on every axis.
    fn unit_frustum() -> [f32; 24] {
        [
            1.0, 0.0, 0.0, 1.0, // x >= -1
            -1.0, 0.0, 0.0, 1.0, // x <= 1
            0.0, 1.0, 0.0, 1.0, // y >= -1
            0.0, -1.0, 0.0, 1.0, // y <= 1
            0.0, 0.0, 1.0, 1.0, // z >= -1
            0.0, 0.0, -1.0, 1.0, // z <= 1
        ]
    }

    fn box_at(center: f32) -> [f32; 6] {
        [
            center - 0.25,
            center - 0.25,
            center - 0.25,
            center + 0.25,
            center + 0.25,
            center + 0.25,
        ]
    }

    #[test]
    fn inside_box_is_visible() {
        let planes = unit_frustum();
        let mut mask = [0u8; 1];
        assert_eq!(cull_batch(&planes, &box_at(0.0), &mut mask, 1), 1);
        assert_eq!(mask[0], 1);
    }

    #[test]
    fn outside_box_is_culled() {
        let planes = unit_frustum();
        let mut mask = [0u8; 1];
        assert_eq!(cull_batch(&planes, &box_at(5.0), &mut mask, 1), 0);
        assert_eq!(mask[0], 0);
    }

    #[test]
    fn straddling_box_is_kept() {
        let planes = unit_frustum();
        // Box spans -1.25..-0.75, crossing the x >= -1 plane.
        let mut data = [0.0f32; 6];
        data[0] = -1.25;
        data[1] = -0.1;
        data[2] = -0.1;
        data[3] = -0.75;
        data[4] = 0.1;
        data[5] = 0.1;
        let mut mask = [0u8; 1];
        assert_eq!(cull_batch(&planes, &data, &mut mask, 1), 1);
    }

    #[test]
    fn degenerate_box_is_culled() {
        let planes = unit_frustum();
        // min > max is invalid and must never be reported visible.
        let invalid = [0.5f32, 0.5, 0.5, -0.5, -0.5, -0.5];
        let mut mask = [0u8; 1];
        assert_eq!(cull_batch(&planes, &invalid, &mut mask, 1), 0);
    }

    #[test]
    fn mixed_batch_counts_visible() {
        let planes = unit_frustum();
        let bounds = [
            box_at(0.0).as_slice(),
            box_at(9.0).as_slice(),
            box_at(-0.5).as_slice(),
            box_at(-9.0).as_slice(),
        ]
        .concat();
        let mut mask = [0u8; 4];
        assert_eq!(cull_batch(&planes, &bounds, &mut mask, 4), 2);
        assert_eq!(mask, [1, 0, 1, 0]);
    }

    /// Diagnostic, not a correctness test: reports raw kernel throughput with
    /// the same batch shape as the Java benchmark, with no JNI involved.
    /// Run with `cargo test --release -- --ignored --nocapture`.
    #[test]
    #[ignore = "throughput diagnostic; run explicitly"]
    fn kernel_throughput() {
        use std::time::Instant;

        const COUNT: usize = 8192;
        const BATCHES: u32 = 2000;

        let planes = unit_frustum();
        let mut bounds = vec![0.0f32; COUNT * BOX_STRIDE];
        let mut mask = vec![0u8; COUNT];

        // Deterministic pseudo-random boxes, same spread as the Java harness.
        let mut state: u64 = 0x5EED;
        for index in 0..COUNT {
            state = state
                .wrapping_mul(6_364_136_223_846_793_005)
                .wrapping_add(1_442_695_040_888_963_407);
            let unit = (state >> 40) as f32 / (1u64 << 24) as f32;
            let base = index * BOX_STRIDE;
            // Spread so a meaningful fraction overlaps the unit frustum rather
            // than every box being rejected by the first plane.
            let x = unit * 128.0 - 64.0;
            let y = unit * 128.0 - 64.0;
            let z = unit * 128.0 - 64.0;
            bounds[base] = x;
            bounds[base + 1] = y;
            bounds[base + 2] = z;
            bounds[base + 3] = x + 16.0;
            bounds[base + 4] = y + 16.0;
            bounds[base + 5] = z + 16.0;
        }

        // Warm up.
        for _ in 0..200 {
            cull_batch(&planes, &bounds, &mut mask, COUNT);
        }

        let start = Instant::now();
        let mut total_visible = 0usize;
        for _ in 0..BATCHES {
            total_visible += cull_batch(&planes, &bounds, &mut mask, COUNT);
        }
        let elapsed = start.elapsed();

        let per_batch = elapsed.as_secs_f64() / f64::from(BATCHES);
        println!(
            "rust kernel: {:.3} us/batch  {:.2} ns/box  (visible/batch {})",
            per_batch * 1e6,
            per_batch * 1e9 / COUNT as f64,
            total_visible / BATCHES as usize
        );
        assert!(total_visible > 0);
    }

    #[test]
    fn empty_batch_is_a_noop() {
        let planes = unit_frustum();
        let mut mask: [u8; 0] = [];
        assert_eq!(cull_batch(&planes, &[], &mut mask, 0), 0);
    }
}
