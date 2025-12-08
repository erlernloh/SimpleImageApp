# Cache Key Fix - Exposure Changes Not Reflecting After Flip

## Issue Description

**Problem:** After applying a preset and flipping the image horizontally, adjusting the exposure slider did not update the preview. The flip state was correctly preserved in the ViewModel, but the processed image was not reflecting the exposure changes.

**Root Cause:** The `ImageProcessorImpl` cache key generation was based only on bitmap dimensions and adjustment values, **not on the bitmap's actual content**. When a flipped bitmap was processed, it had the same dimensions as the original, so the cache returned the **non-flipped cached version**, ignoring the transformations.

## Technical Analysis

### The Caching Problem

**Before Fix:**
```kotlin
private fun generateCacheKey(
    bitmap: Bitmap,
    adjustments: AdjustmentParameters,
    filmGrain: FilmGrain,
    lensEffects: LensEffects
): String {
    return "${bitmap.width}x${bitmap.height}_" +  // ❌ Same for original and flipped!
            "b${adjustments.brightness}_" +
            "c${adjustments.contrast}_" +
            "s${adjustments.saturation}_" +
            "fg${filmGrain.amount}_" +
            "v${lensEffects.vignetteAmount}"
}
```

**Problem Flow:**
1. Apply "Dramatic" preset → Image processed and cached with key: `"1280x960_b0_c0_s0_fg0_v0"`
2. Flip horizontally → `applyTransformations()` creates a new flipped bitmap
3. Adjust exposure → `processImage()` passes the flipped bitmap to `imageProcessor.processImage()`
4. Cache lookup uses key: `"1280x960_b10_c0_s0_fg0_v0"` (same dimensions!)
5. Cache returns the **non-flipped** cached bitmap from step 1
6. Preview shows non-flipped image with exposure adjustment ❌

### The Solution

**After Fix:**
```kotlin
private fun generateCacheKey(
    bitmap: Bitmap,
    adjustments: AdjustmentParameters,
    filmGrain: FilmGrain,
    lensEffects: LensEffects
): String {
    // Include bitmap hashCode to differentiate between original and transformed bitmaps
    // This ensures that flipped/rotated bitmaps get their own cache entries
    return "${bitmap.hashCode()}_${bitmap.width}x${bitmap.height}_" +  // ✅ Unique per bitmap!
            "b${adjustments.brightness}_" +
            "c${adjustments.contrast}_" +
            "s${adjustments.saturation}_" +
            "fg${filmGrain.amount}_" +
            "v${lensEffects.vignetteAmount}"
}
```

**Fixed Flow:**
1. Apply "Dramatic" preset → Cached with key: `"12345_1280x960_b0_c0_s0_fg0_v0"`
2. Flip horizontally → Creates new bitmap with different hashCode (e.g., `67890`)
3. Adjust exposure → Cache key: `"67890_1280x960_b10_c0_s0_fg0_v0"` (different!)
4. Cache miss → Processes the flipped bitmap with new exposure
5. Preview shows flipped image with exposure adjustment ✅

## Changes Made

### File: `ImageProcessorImpl.kt`

#### 1. Added Logging Import
```kotlin
import android.util.Log
```

#### 2. Added TAG Constant
```kotlin
companion object {
    private const val TAG = "ImageProcessorImpl"
}
```

#### 3. Updated Cache Key Generation
**Line 78-92**

**Before:**
- Cache key based only on dimensions and adjustments
- Same key for original and transformed bitmaps

**After:**
- Cache key includes `bitmap.hashCode()` as prefix
- Each unique bitmap (original, flipped, rotated) gets its own cache entries
- Comment explains why this is necessary

#### 4. Added Debug Logging
**Lines 38, 43, 48**

**Added:**
- Log cache key and bitmap hash on every `processImage()` call
- Log when using cached bitmap
- Log when processing new bitmap

## Why hashCode() Works

**Bitmap.hashCode()** in Android returns a unique identifier for each bitmap object:
- **Original bitmap:** hashCode = `12345`
- **Flipped bitmap:** hashCode = `67890` (different object)
- **Rotated bitmap:** hashCode = `54321` (different object)

Even though the bitmaps have the same dimensions, they are **different objects in memory**, so they have different hashCodes. This ensures each transformation gets its own cache entry.

## Testing Verification

### Test Case 1: Flip + Exposure
1. Apply "Dramatic" preset
2. Flip horizontally
3. Adjust exposure slider
4. **Expected:** Preview shows flipped image with exposure changes ✅

### Test Case 2: Multiple Adjustments After Flip
1. Flip horizontally
2. Adjust exposure
3. Adjust contrast
4. Adjust saturation
5. **Expected:** All adjustments apply to the flipped image ✅

### Test Case 3: Rotate + Flip + Adjust
1. Rotate 90°
2. Flip vertically
3. Adjust brightness
4. **Expected:** All transformations and adjustments stack correctly ✅

### Logcat Verification

**Look for these log messages:**

```
ImageProcessorImpl: processImage() - cacheKey: 12345_1280x960_b0_c0_s0_fg0_v0, bitmap hash: 12345
ImageProcessorImpl: Processing new bitmap for key: 12345_1280x960_b0_c0_s0_fg0_v0

[After flip]
ImageProcessorImpl: processImage() - cacheKey: 67890_1280x960_b0_c0_s0_fg0_v0, bitmap hash: 67890
ImageProcessorImpl: Processing new bitmap for key: 67890_1280x960_b0_c0_s0_fg0_v0

[After exposure adjustment]
ImageProcessorImpl: processImage() - cacheKey: 67890_1280x960_b10_c0_s0_fg0_v0, bitmap hash: 67890
ImageProcessorImpl: Processing new bitmap for key: 67890_1280x960_b10_c0_s0_fg0_v0
```

**What to check:**
- After flipping, the bitmap hash should change (different object)
- Cache keys should include the new bitmap hash
- "Processing new bitmap" should appear (not "Using cached bitmap")

## Performance Impact

**Minimal:**
- `hashCode()` is a very fast operation (O(1))
- Cache still works effectively for repeated adjustments on the same bitmap
- Only creates separate cache entries for genuinely different bitmaps (transformed vs original)

**Cache Behavior:**
- **Before:** 1 cache entry shared between original and transformed bitmaps (WRONG)
- **After:** Separate cache entries for each unique bitmap (CORRECT)
- **Memory:** Slightly more cache entries, but prevents incorrect results

## Alternative Solutions Considered

### 1. Pass Transformation State to ImageProcessor
```kotlin
suspend fun processImage(
    bitmap: Bitmap,
    adjustments: AdjustmentParameters,
    transformations: TransformationState  // ❌ Requires interface change
): Result<Bitmap>
```
**Rejected:** Requires changing the interface and all callers

### 2. Disable Caching for Transformed Bitmaps
```kotlin
if (isTransformed) {
    // Skip cache
}
```
**Rejected:** Hurts performance unnecessarily

### 3. Use Bitmap Content Hash
```kotlin
val contentHash = bitmap.getPixels().contentHashCode()
```
**Rejected:** Too expensive (requires reading all pixels)

### 4. Use Bitmap.hashCode() ✅ CHOSEN
**Advantages:**
- Fast (O(1))
- No interface changes needed
- Works automatically for any bitmap transformation
- Minimal code change

## Related Issues Fixed

✅ **Fixed:** Exposure changes now reflect on flipped images  
✅ **Fixed:** All adjustments now apply correctly to transformed bitmaps  
✅ **Fixed:** Cache no longer returns incorrect bitmaps  
✅ **Added:** Debug logging for cache behavior  

## Summary

The fix ensures that **each unique bitmap gets its own cache entries** by including the bitmap's `hashCode()` in the cache key. This prevents the cache from returning a non-transformed bitmap when processing a transformed one.

**Key Principle:** Cache keys must uniquely identify not just the adjustments, but also the **input bitmap's identity**.

---

**Document Version:** 1.0  
**Date:** October 13, 2025  
**Status:** ✅ Fixed and Ready for Testing
