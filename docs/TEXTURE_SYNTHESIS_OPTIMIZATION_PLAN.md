# Texture Synthesis Optimization - 3-Phase Implementation Plan

## Overview
Optimize texture synthesis from O(n²) complexity to enable real-time processing on 70MP+ images through adaptive processing, hybrid CPU-GPU parallelization, and full GPU acceleration.

**Current Performance:** ~44 seconds for 70MP image (7296×9728)  
**Target Performance:** <1 second for 70MP image

---

## PHASE 1: Adaptive Processing + NEON SIMD (Quick Wins)
**Goal:** 5-8x speedup through algorithmic optimization  
**Target:** ~5-8 seconds for 70MP image  
**Files Modified:** `texture_synthesis.cpp`, `texture_synthesis.h`, `UltraDetailPipeline.kt`

### 1.1: Adaptive Detail Map with Confidence-Based Pixel Skipping
**File:** `app/src/main/cpp/texture_synthesis.cpp`

**Changes:**
- Enhance `computeDetailMap()` to calculate synthesis necessity score (0-1)
- Add variance threshold check: skip pixels with variance > 0.05 (already textured)
- Add edge protection: reduce synthesis weight near edges (preserve structure)
- Implement adaptive step size: `step = variance < 0.01 ? patchSize/4 : patchSize`

**Expected Result:** Skip 70-80% of pixels that don't need synthesis

**Validation:**
- Log: "Adaptive detail map: X% pixels require synthesis"
- Verify: Output quality matches original (no visible degradation)
- Measure: Processing time reduction

---

### 1.2: NEON SIMD Vectorization for Patch SSD
**File:** `app/src/main/cpp/texture_synthesis.cpp`

**Changes:**
- Add NEON intrinsics to `computePatchSSD()`:
  ```cpp
  // Process 4 pixels at once using float32x4_t
  float32x4_t diff = vsubq_f32(pixels1, pixels2);
  float32x4_t sq = vmulq_f32(diff, diff);
  ssd += vaddvq_f32(sq);
  ```
- Vectorize `computeLocalVariance()` for RGB channels
- Add ARM NEON detection and fallback to scalar code

**Expected Result:** 2-4x speedup on patch comparison (inner loop)

**Validation:**
- Verify: NEON and scalar paths produce identical results (unit test)
- Log: "Using NEON SIMD acceleration" or "Fallback to scalar"
- Measure: Patch SSD computation time

---

### 1.3: Optimize Search Radius + Early Termination
**File:** `app/src/main/cpp/texture_synthesis.cpp`

**Changes:**
- Reduce default `searchRadius` from 32 to 20 pixels (minimal quality loss)
- Add early termination in `findBestPatch()`:
  ```cpp
  if (score < bestScore * 0.1f) break; // Found excellent match
  ```
- Implement coarse-to-fine search:
  1. Search at 2x stride for candidates
  2. Refine top 5 candidates at 1x stride

**Expected Result:** 2-3x speedup in patch search

**Validation:**
- Compare output quality: searchRadius=32 vs searchRadius=20
- Verify: <5% quality degradation (PSNR/SSIM metrics)
- Measure: Average search iterations per pixel

---

### 1.4: Progress Callbacks and Logging
**File:** `app/src/main/cpp/texture_synthesis.cpp`, `texture_synthesis.h`

**Changes:**
- Add progress callback to `TextureSynthParams`:
  ```cpp
  using ProgressCallback = std::function<void(int processed, int total, float avgDetail)>;
  ProgressCallback progressCallback;
  ```
- Update `synthesizeGuided()` to report progress every 1000 patches
- Add detailed timing logs for each stage:
  - Detail map computation
  - Patch search
  - Patch blending

**Expected Result:** Real-time progress monitoring

**Validation:**
- Verify: Progress callback fires at regular intervals
- Log: "TextureSynth: Stage X/Y - Z% complete"
- UI: Progress bar updates smoothly

---

### 1.5: Update UltraDetailPipeline.kt Integration
**File:** `app/src/main/java/com/imagedit/app/ultradetail/UltraDetailPipeline.kt`

**Changes:**
- Remove 50MP size limit (now that synthesis is optimized)
- Add progress reporting from native callback:
  ```kotlin
  _state.value = PipelineState.ProcessingBurst(
      ProcessingStage.TEXTURE_SYNTHESIS, 
      progress, 
      "Synthesizing texture: $processed/$total patches"
  )
  ```
- Add timing logs for texture synthesis stage
- Update `TextureSynthConfig` with optimized defaults:
  ```kotlin
  TextureSynthConfig(
      patchSize = 7,
      searchRadius = 20,  // Reduced from 24
      blendWeight = 0.4f
  )
  ```

**Expected Result:** Seamless integration with pipeline progress

**Validation:**
- Verify: UI shows texture synthesis progress
- Log: "Stage 3.5d: Texture synthesis complete in Xms"
- Test: 70MP image processes without hanging

---

### 1.6: Phase 1 Testing and Validation
**Deliverables:**
- Unit tests for NEON vs scalar equivalence
- Benchmark suite comparing Phase 1 vs baseline
- Quality metrics (PSNR, SSIM) vs original algorithm
- Performance report with timing breakdown

**Success Criteria:**
- ✅ 5-8x speedup on 70MP images
- ✅ <5% quality degradation
- ✅ No crashes or memory leaks
- ✅ Progress reporting works correctly

---

## PHASE 2: Hybrid CPU-GPU Tiled Processing
**Goal:** 3-5x additional speedup through parallelization  
**Target:** ~1-2 seconds for 70MP image  
**Files Created:** `texture_synthesis_tiled.cpp`, `texture_synthesis.comp`  
**Files Modified:** `texture_synthesis.h`, `ultradetail_jni.cpp`, `NativeMFSRPipeline.kt`

### 2.1: Tiled Processing Architecture Design
**File:** `app/src/main/cpp/texture_synthesis.h`

**Changes:**
- Add `TileSynthConfig` struct:
  ```cpp
  struct TileSynthConfig {
      int tileSize = 512;           // Base tile size
      int overlap = 64;             // Overlap for blending
      bool useGPU = true;           // Enable GPU tiles
      int numCPUThreads = 4;        // CPU worker threads
      int numGPUStreams = 2;        // Concurrent GPU streams
      TileScheduleMode mode = ALTERNATING; // ODD_CPU_EVEN_GPU
  };
  ```
- Define tile layout algorithm:
  - Calculate grid: `numTilesX = ceil(width / (tileSize - overlap))`
  - Assign tiles: odd indices → CPU, even indices → GPU
  - Track dependencies for overlap blending

**Expected Result:** Clear tile processing strategy

**Validation:**
- Document: Tile layout diagram with overlap regions
- Verify: No gaps or double-processing
- Calculate: Expected parallelism factor

---

### 2.2: CPU Thread Pool for Odd Tiles
**File:** `app/src/main/cpp/texture_synthesis_tiled.cpp` (new)

**Changes:**
- Create `TiledTextureSynthProcessor` class
- Implement CPU thread pool using `std::thread`:
  ```cpp
  class CPUTileWorker {
      void processTile(const RGBImage& input, RGBImage& output, 
                       const TileRegion& region);
      // Uses Phase 1 optimized synthesis
  };
  ```
- Add tile queue with work-stealing for load balancing
- Implement tile extraction with overlap handling

**Expected Result:** Parallel CPU processing of odd tiles

**Validation:**
- Verify: All CPU threads utilized (htop/task manager)
- Log: "CPU tile X/Y processed in Zms"
- Test: Thread safety (no race conditions)

---

### 2.3: GPU Compute Shader Stub
**File:** `app/src/main/shaders/texture_synthesis.comp` (new)

**Changes:**
- Create GLSL compute shader skeleton:
  ```glsl
  #version 320 es
  layout(local_size_x = 16, local_size_y = 16) in;
  
  layout(binding = 0) uniform sampler2D inputTex;
  layout(binding = 1, rgba8) uniform writeonly image2D outputImg;
  
  void main() {
      ivec2 coord = ivec2(gl_GlobalInvocationID.xy);
      // TODO: Implement patch search
      // For now: simple copy
      vec4 color = texelFetch(inputTex, coord, 0);
      imageStore(outputImg, coord, color);
  }
  ```
- Add shader compilation in native code
- Implement basic GPU dispatch for even tiles

**Expected Result:** GPU tiles process (even if just copying)

**Validation:**
- Verify: Shader compiles without errors
- Log: "GPU shader initialized successfully"
- Test: Even tiles are processed by GPU

---

### 2.4: Tile Overlap Blending
**File:** `app/src/main/cpp/texture_synthesis_tiled.cpp`

**Changes:**
- Implement Gaussian blending in overlap regions:
  ```cpp
  void blendOverlap(RGBImage& output, 
                    const RGBImage& tile1, const RGBImage& tile2,
                    const OverlapRegion& region) {
      // Weight = Gaussian falloff from tile center
      float weight = exp(-distFromCenter^2 / sigma^2);
      output = tile1 * weight + tile2 * (1 - weight);
  }
  ```
- Handle 4-way overlaps at tile corners
- Add feathering to prevent visible seams

**Expected Result:** Seamless tile blending

**Validation:**
- Visual: No visible seams between tiles
- Metric: Gradient magnitude at tile boundaries < threshold
- Test: Various tile sizes and overlap amounts

---

### 2.5: GPU Compute Shader Initialization
**File:** `app/src/main/cpp/NativeMFSRPipeline.cpp` (or new `gpu_compute.cpp`)

**Changes:**
- Add OpenGL ES 3.2 context creation
- Implement shader compilation and linking:
  ```cpp
  class ComputeShaderManager {
      GLuint compileShader(const char* source);
      void dispatch(int numGroupsX, int numGroupsY);
      void bindTextures(GLuint input, GLuint output);
  };
  ```
- Add GPU buffer management for tile data
- Implement synchronization (glMemoryBarrier)

**Expected Result:** GPU compute infrastructure ready

**Validation:**
- Verify: OpenGL ES 3.2 context created
- Log: "GPU compute shader compiled: X workgroups"
- Test: Simple compute shader runs successfully

---

### 2.6: Hybrid Scheduler
**File:** `app/src/main/cpp/texture_synthesis_tiled.cpp`

**Changes:**
- Implement tile scheduler:
  ```cpp
  class HybridScheduler {
      void schedule(const TileSynthConfig& config);
      // 1. Submit odd tiles to CPU pool
      // 2. Submit even tiles to GPU queue
      // 3. Wait for completion (std::future + GPU fence)
      // 4. Blend overlaps
  };
  ```
- Add load balancing: if GPU is idle, steal CPU tiles
- Implement progress aggregation from both CPU and GPU

**Expected Result:** Coordinated CPU-GPU execution

**Validation:**
- Verify: CPU and GPU run simultaneously (profiler)
- Log: "Hybrid scheduler: X CPU tiles, Y GPU tiles"
- Measure: GPU utilization % and CPU utilization %

---

### 2.7: JNI Bindings for Tiled Synthesis
**File:** `app/src/main/cpp/ultradetail_jni.cpp`

**Changes:**
- Add JNI function:
  ```cpp
  JNIEXPORT jint JNICALL
  Java_NativeMFSRPipeline_nativeTextureSynthesisTiled(
      JNIEnv* env, jclass, jobject inputBitmap, jobject outputBitmap,
      jint tileSize, jint overlap, jboolean useGPU
  ) {
      // Convert bitmaps to RGBImage
      // Create TileSynthConfig
      // Call TiledTextureSynthProcessor
      // Return status
  }
  ```
- Add error handling and resource cleanup
- Implement progress callback via JNI

**Expected Result:** Kotlin can call tiled synthesis

**Validation:**
- Verify: JNI function signature matches Kotlin declaration
- Test: Bitmap data transfers correctly
- Log: JNI call succeeds without crashes

---

### 2.8: Kotlin Wrapper Update
**File:** `app/src/main/java/com/imagedit/app/ultradetail/NativeMFSRPipeline.kt`

**Changes:**
- Add tiled synthesis function:
  ```kotlin
  external fun nativeTextureSynthesisTiled(
      input: Bitmap, output: Bitmap,
      tileSize: Int, overlap: Int, useGPU: Boolean
  ): Int
  
  fun synthesizeTextureTiled(
      input: Bitmap, output: Bitmap,
      config: TileSynthConfig = TileSynthConfig()
  ): Boolean {
      return nativeTextureSynthesisTiled(
          input, output, 
          config.tileSize, config.overlap, config.useGPU
      ) == 0
  }
  ```
- Update `UltraDetailPipeline.kt` to use tiled version for large images
- Add device capability check for GPU compute support

**Expected Result:** Seamless Kotlin integration

**Validation:**
- Verify: Kotlin code compiles
- Test: Tiled synthesis called from pipeline
- Log: "Using tiled texture synthesis (CPU+GPU)"

---

### 2.9: Phase 2 Testing and Validation
**Deliverables:**
- Benchmark: Tiled vs non-tiled performance
- GPU profiling: utilization, memory bandwidth
- Quality comparison: tiled vs single-pass
- Seam detection tests for overlap blending

**Success Criteria:**
- ✅ 15-40x total speedup (Phase 1 + Phase 2)
- ✅ No visible seams between tiles
- ✅ CPU and GPU both >80% utilized
- ✅ <1% quality degradation vs Phase 1

---

## PHASE 3: Full GPU Compute Shader Implementation
**Goal:** 10-20x additional speedup through full GPU acceleration  
**Target:** <0.1 seconds for 70MP image  
**Files Created:** `detail_map.comp`, `patch_search.comp`, `patch_blend.comp`, `gpu_texture_synth.cpp`  
**Files Modified:** All Phase 2 files

### 3.1: GPU Detail Map Computation
**File:** `app/src/main/shaders/detail_map.comp` (new)

**Changes:**
- Port `computeDetailMap()` to GPU:
  ```glsl
  layout(local_size_x = 16, local_size_y = 16) in;
  
  layout(binding = 0) uniform sampler2D inputTex;
  layout(binding = 1, r32f) uniform writeonly image2D varianceMap;
  layout(binding = 2, r32f) uniform writeonly image2D edgeMap;
  layout(binding = 3, r32f) uniform writeonly image2D confidenceMap;
  
  void main() {
      ivec2 coord = ivec2(gl_GlobalInvocationID.xy);
      
      // Compute local variance using texture sampling
      float variance = computeVariance(coord, radius);
      imageStore(varianceMap, coord, vec4(variance));
      
      // Compute edge magnitude (Sobel)
      float edge = computeEdge(coord);
      imageStore(edgeMap, coord, vec4(edge));
      
      // Compute synthesis confidence
      float conf = computeConfidence(variance, edge);
      imageStore(confidenceMap, coord, vec4(conf));
  }
  ```
- Use GPU texture sampling hardware for neighborhood access
- Implement parallel reduction for statistics

**Expected Result:** Detail map computed on GPU

**Validation:**
- Compare: GPU vs CPU detail maps (should be identical)
- Measure: GPU detail map time vs CPU time
- Verify: No artifacts in variance/edge maps

---

### 3.2: GPU Patch Search with Shared Memory
**File:** `app/src/main/shaders/patch_search.comp` (new)

**Changes:**
- Implement parallel patch search:
  ```glsl
  shared vec4 patchCache[PATCH_SIZE][PATCH_SIZE];
  
  void main() {
      ivec2 coord = ivec2(gl_GlobalInvocationID.xy);
      
      // Load target patch into shared memory
      loadPatchToShared(coord, patchCache);
      barrier();
      
      // Each thread searches a subset of candidates
      float bestScore = 1e10;
      ivec2 bestMatch = coord;
      
      for (int sy = searchStart; sy < searchEnd; sy++) {
          for (int sx = 0; sx < imageWidth; sx++) {
              float score = computePatchSSD(coord, ivec2(sx, sy));
              if (score < bestScore) {
                  bestScore = score;
                  bestMatch = ivec2(sx, sy);
              }
          }
      }
      
      // Store best match
      imageStore(matchMap, coord, vec4(bestMatch, bestScore, 0));
  }
  ```
- Use shared memory for patch caching (16x16 workgroup)
- Implement parallel reduction for finding best match

**Expected Result:** Massively parallel patch search

**Validation:**
- Compare: GPU vs CPU patch matches (should be similar)
- Measure: Search time per pixel
- Profile: Shared memory bank conflicts

---

### 3.3: GPU Patch Blending Shader
**File:** `app/src/main/shaders/patch_blend.comp` (new)

**Changes:**
- Implement GPU patch blending:
  ```glsl
  void main() {
      ivec2 coord = ivec2(gl_GlobalInvocationID.xy);
      
      // Read best match from previous pass
      vec4 matchData = texelFetch(matchMap, coord, 0);
      ivec2 sourceCoord = ivec2(matchData.xy);
      float confidence = texelFetch(confidenceMap, coord, 0).r;
      
      if (confidence < 0.1) {
          // No blending needed
          imageStore(output, coord, texelFetch(input, coord, 0));
          return;
      }
      
      // Blend patch with Gaussian falloff
      vec4 blended = blendPatch(coord, sourceCoord, confidence);
      imageStore(output, coord, blended);
  }
  ```
- Implement Gaussian falloff in shader
- Add atomic operations for multi-patch blending

**Expected Result:** GPU-accelerated blending

**Validation:**
- Compare: GPU vs CPU blending (visual comparison)
- Verify: Gaussian falloff is smooth
- Test: No artifacts at patch boundaries

---

### 3.4: GPU Spatial Hash for Fast Patch Indexing
**File:** `app/src/main/cpp/gpu_texture_synth.cpp` (new)

**Changes:**
- Build spatial hash on GPU:
  ```cpp
  class GPUPatchIndex {
      // Hash patches by (avgColor, variance, edgeOrientation)
      void buildIndex(const RGBImage& image);
      
      // Query: returns buffer of candidate patch locations
      GLuint querySimilarPatches(const PatchDescriptor& query);
  };
  ```
- Use compute shader for hash table construction
- Implement GPU hash table lookup in patch search shader

**Expected Result:** O(1) patch lookup instead of O(n)

**Validation:**
- Measure: Index build time vs search time savings
- Verify: Hash collisions are minimal
- Compare: Quality vs brute-force search

---

### 3.5: GPU Buffer Management and Synchronization
**File:** `app/src/main/cpp/gpu_texture_synth.cpp`

**Changes:**
- Implement GPU buffer pool:
  ```cpp
  class GPUBufferPool {
      GLuint allocate(size_t size, GLenum usage);
      void release(GLuint buffer);
      void synchronize(); // glMemoryBarrier + glFinish
  };
  ```
- Add multi-pass synchronization:
  1. Detail map pass → barrier
  2. Patch search pass → barrier
  3. Patch blend pass → barrier
- Implement ping-pong buffers for iterative refinement

**Expected Result:** Correct GPU execution order

**Validation:**
- Verify: No race conditions (validation layers)
- Test: Multi-pass results are correct
- Profile: GPU idle time between passes

---

### 3.6: Unified GPU Pipeline Orchestrator
**File:** `app/src/main/cpp/gpu_texture_synth.cpp`

**Changes:**
- Create end-to-end GPU pipeline:
  ```cpp
  class GPUTextureSynthPipeline {
      void initialize(const GPUConfig& config);
      
      TextureSynthResult synthesize(const RGBImage& input) {
          // 1. Upload input to GPU texture
          // 2. Dispatch detail map shader
          // 3. Dispatch patch search shader
          // 4. Dispatch patch blend shader
          // 5. Download result from GPU
          return result;
      }
      
      void cleanup();
  };
  ```
- Add GPU memory management (texture upload/download)
- Implement shader hot-reloading for debugging

**Expected Result:** Single-call GPU synthesis

**Validation:**
- Verify: End-to-end pipeline works
- Measure: Total GPU time (upload + compute + download)
- Test: Multiple invocations don't leak memory

---

### 3.7: Fallback Logic Based on Device Capabilities
**File:** `app/src/main/cpp/texture_synthesis_tiled.cpp`

**Changes:**
- Implement capability detection:
  ```cpp
  enum SynthMode {
      MODE_GPU_FULL,      // Phase 3: Full GPU
      MODE_HYBRID,        // Phase 2: CPU+GPU tiled
      MODE_CPU_OPTIMIZED, // Phase 1: CPU with NEON
      MODE_CPU_FALLBACK   // Original CPU
  };
  
  SynthMode detectBestMode() {
      if (hasOpenGLES32() && hasComputeShaders()) 
          return MODE_GPU_FULL;
      if (hasOpenGLES31()) 
          return MODE_HYBRID;
      if (hasNEON()) 
          return MODE_CPU_OPTIMIZED;
      return MODE_CPU_FALLBACK;
  }
  ```
- Add automatic fallback on GPU errors
- Log selected mode for debugging

**Expected Result:** Adaptive execution strategy

**Validation:**
- Test: Each mode works independently
- Verify: Fallback triggers on GPU failure
- Log: "Texture synthesis mode: X"

---

### 3.8: Error Handling and Resource Cleanup
**File:** All Phase 3 files

**Changes:**
- Add comprehensive error handling:
  - Shader compilation errors → fallback to CPU
  - GPU memory allocation failures → reduce tile size
  - Timeout detection → abort and return partial result
- Implement RAII wrappers for GPU resources:
  ```cpp
  class GLTexture {
      GLuint id_;
      ~GLTexture() { glDeleteTextures(1, &id_); }
  };
  ```
- Add GPU memory leak detection (debug builds)

**Expected Result:** Robust error handling

**Validation:**
- Test: Shader compilation failure
- Test: Out-of-GPU-memory scenario
- Verify: No resource leaks (valgrind/ASan)

---

### 3.9: Phase 3 Testing and Validation
**Deliverables:**
- Full GPU benchmark vs Phase 2
- GPU profiling report (NSight, RenderDoc)
- Quality comparison: GPU vs CPU reference
- Device compatibility matrix

**Success Criteria:**
- ✅ 150-800x total speedup vs original
- ✅ <0.1 seconds for 70MP image
- ✅ <2% quality degradation vs CPU reference
- ✅ Works on 90%+ of target devices

---

### 3.10: Performance Profiling and Final Optimization
**File:** All files

**Changes:**
- Profile with Android GPU Inspector / Snapdragon Profiler
- Identify bottlenecks:
  - Memory bandwidth (texture upload/download)
  - Compute occupancy (workgroup size tuning)
  - Synchronization overhead (reduce barriers)
- Optimize hot paths:
  - Reduce texture fetches (use shared memory)
  - Optimize workgroup size (16x16 vs 32x32)
  - Minimize CPU-GPU transfers
- Add performance metrics logging:
  ```cpp
  struct PerfMetrics {
      float detailMapMs;
      float patchSearchMs;
      float patchBlendMs;
      float uploadMs;
      float downloadMs;
      float totalMs;
  };
  ```

**Expected Result:** Maximum performance

**Validation:**
- Measure: Each optimization's impact
- Profile: GPU utilization >95%
- Document: Performance characteristics per device tier

---

## File Structure Summary

### New Files Created
```
app/src/main/cpp/
├── texture_synthesis_tiled.cpp      # Phase 2: Tiled CPU-GPU processing
├── gpu_texture_synth.cpp            # Phase 3: Full GPU pipeline
└── gpu_compute.cpp                  # GPU compute infrastructure

app/src/main/shaders/
├── texture_synthesis.comp           # Phase 2: Basic GPU tile processing
├── detail_map.comp                  # Phase 3: GPU detail map
├── patch_search.comp                # Phase 3: GPU patch search
└── patch_blend.comp                 # Phase 3: GPU patch blending

docs/
└── TEXTURE_SYNTHESIS_OPTIMIZATION_PLAN.md  # This file
```

### Modified Files
```
app/src/main/cpp/
├── texture_synthesis.cpp            # Phase 1: NEON + adaptive
├── texture_synthesis.h              # All phases: new APIs
├── ultradetail_jni.cpp              # Phase 2+3: JNI bindings
└── NativeMFSRPipeline.cpp           # Phase 2+3: GPU init

app/src/main/java/com/imagedit/app/ultradetail/
├── NativeMFSRPipeline.kt            # All phases: Kotlin wrappers
└── UltraDetailPipeline.kt           # All phases: integration
```

---

## Testing Strategy

### Unit Tests
- NEON SIMD correctness (Phase 1)
- Tile overlap blending (Phase 2)
- GPU shader output validation (Phase 3)

### Integration Tests
- End-to-end pipeline with all phases
- Fallback logic (GPU → Hybrid → CPU)
- Memory leak detection

### Performance Tests
- Benchmark suite: 10MP, 30MP, 70MP, 100MP images
- Device matrix: Budget, Mid-range, Flagship
- Thermal throttling behavior

### Quality Tests
- PSNR/SSIM vs reference implementation
- Visual inspection for artifacts
- Edge preservation metrics

---

## Success Metrics

| Phase | Target Speedup | Target Time (70MP) | Quality Loss | Device Support |
|-------|---------------|-------------------|--------------|----------------|
| Baseline | 1x | ~44s | 0% | 100% |
| Phase 1 | 5-8x | ~5-8s | <5% | 100% |
| Phase 2 | 15-40x | ~1-2s | <1% | 90% (OpenGL ES 3.1+) |
| Phase 3 | 150-800x | <0.1s | <2% | 80% (OpenGL ES 3.2+) |

---

## Risk Mitigation

### Technical Risks
1. **GPU compute not available** → Fallback to Phase 2 or Phase 1
2. **GPU memory limitations** → Reduce tile size dynamically
3. **Quality degradation** → Add quality validation gates
4. **Device fragmentation** → Extensive device testing matrix

### Implementation Risks
1. **Complexity creep** → Strict phase boundaries, no mixing
2. **Regression bugs** → Comprehensive test suite before each phase
3. **Performance variance** → Profile on multiple device tiers
4. **Maintenance burden** → Clear code documentation and architecture docs

---

## Timeline Estimate

- **Phase 1:** 3-5 days (algorithmic optimization)
- **Phase 2:** 7-10 days (hybrid CPU-GPU architecture)
- **Phase 3:** 10-14 days (full GPU implementation)
- **Testing & Polish:** 5-7 days

**Total:** 25-36 days for complete implementation

---

## Audit Checklist

After each phase, verify:

- [ ] All file references are correct (no broken imports)
- [ ] Data flow is consistent (input → processing → output)
- [ ] Progress reporting works end-to-end
- [ ] Error handling covers all failure modes
- [ ] Memory is properly managed (no leaks)
- [ ] Performance meets target metrics
- [ ] Quality meets acceptance criteria
- [ ] Code is documented and maintainable
- [ ] Tests pass on all target devices
- [ ] Logs provide useful debugging information

---

## Notes

- Each phase builds on the previous, but can work independently
- Fallback logic ensures graceful degradation
- Quality gates prevent shipping degraded output
- Performance metrics guide optimization priorities
- Device capability detection ensures broad compatibility
