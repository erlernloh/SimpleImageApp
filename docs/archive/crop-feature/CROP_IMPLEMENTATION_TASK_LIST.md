# Interactive Crop Feature - Implementation Task List

## Overview
Implement a fully interactive crop feature with draggable handles, visual feedback, and aspect ratio constraints for the photo editor.

---

## Phase 1: Core Crop UI Components (High Priority)

### Task 1.1: Create CropOverlay Composable
**Estimated Time:** 3-4 hours  
**Priority:** Critical  
**Dependencies:** None

**Requirements:**
- Create `CropOverlay.kt` in `ui/editor/components/`
- Draw crop rectangle with Canvas
- Draw corner handles (8dp circles)
- Draw edge handles (middle of each side)
- Draw grid lines (rule of thirds) inside crop area
- Dim/darken area outside crop rectangle
- Make overlay responsive to bitmap dimensions

**Deliverables:**
```kotlin
@Composable
fun CropOverlay(
    modifier: Modifier = Modifier,
    cropRect: Rect,
    imageBounds: Rect,
    aspectRatio: CropAspectRatio,
    onCropRectChange: (Rect) -> Unit
)
```

**Acceptance Criteria:**
- [ ] Crop rectangle visible with white/primary color border
- [ ] 8 handles visible (4 corners + 4 edges)
- [ ] Grid overlay shows rule of thirds
- [ ] Outside area is dimmed (alpha 0.5)
- [ ] Scales properly with different image sizes

---

### Task 1.2: Implement Touch Gesture Handling
**Estimated Time:** 4-5 hours  
**Priority:** Critical  
**Dependencies:** Task 1.1

**Requirements:**
- Detect touch on corner handles
- Detect touch on edge handles
- Detect touch inside crop area (for moving entire rectangle)
- Implement drag gestures for each handle type
- Add minimum crop size constraint (e.g., 50x50 dp)
- Keep crop rectangle within image bounds

**Deliverables:**
```kotlin
private fun handleTouchGesture(
    offset: Offset,
    gestureType: GestureType,
    currentRect: Rect,
    imageBounds: Rect
): Rect
```

**Acceptance Criteria:**
- [ ] Corner handles resize from that corner
- [ ] Edge handles resize from that edge only
- [ ] Dragging inside moves entire rectangle
- [ ] Crop never goes outside image bounds
- [ ] Minimum size enforced (50x50 dp)
- [ ] Smooth dragging with no jitter

---

### Task 1.3: Aspect Ratio Constraint Logic
**Estimated Time:** 3-4 hours  
**Priority:** High  
**Dependencies:** Task 1.2

**Requirements:**
- Implement aspect ratio locking for each preset
- Handle free-form (no constraint)
- Maintain aspect ratio during corner drag
- Maintain aspect ratio during edge drag
- Center crop when switching aspect ratios
- Calculate maximum crop size for each ratio

**Deliverables:**
```kotlin
private fun constrainToAspectRatio(
    rect: Rect,
    aspectRatio: Float?,
    imageBounds: Rect,
    anchorPoint: Offset
): Rect
```

**Acceptance Criteria:**
- [ ] 1:1 (Square) maintains perfect square
- [ ] 3:4 (Portrait) maintains 3:4 ratio
- [ ] 9:16 maintains 9:16 ratio
- [ ] 4:3 (Landscape) maintains 4:3 ratio
- [ ] 16:9 maintains 16:9 ratio
- [ ] Free allows any shape
- [ ] Switching ratios smoothly transitions

---

## Phase 2: Integration with Editor (Medium Priority)

### Task 2.1: Update PhotoEditorViewModel Crop Logic
**Estimated Time:** 2-3 hours  
**Priority:** High  
**Dependencies:** Task 1.3

**Requirements:**
- Store crop rectangle in screen coordinates
- Convert screen coordinates to bitmap coordinates
- Handle rotation/flip state during crop
- Update `applyCrop()` to use actual crop rectangle
- Add validation for crop rectangle

**Changes Needed:**
```kotlin
// In PhotoEditorViewModel.kt
data class CropState(
    val isActive: Boolean = false,
    val aspectRatio: CropAspectRatio = CropAspectRatio.FREE,
    val cropRect: Rect? = null,  // Screen coordinates
    val imageBounds: Rect? = null // Screen coordinates
)

fun updateCropRect(screenRect: Rect, imageBounds: Rect)
fun applyCrop() // Convert screen coords to bitmap coords
```

**Acceptance Criteria:**
- [ ] Crop rectangle updates in real-time
- [ ] Coordinate conversion is accurate
- [ ] Handles rotated/flipped images correctly
- [ ] Validates crop before applying
- [ ] Shows error if crop is invalid

---

### Task 2.2: Integrate CropOverlay into PhotoEditorScreen
**Estimated Time:** 2-3 hours  
**Priority:** High  
**Dependencies:** Task 2.1

**Requirements:**
- Add CropOverlay above image preview when in crop mode
- Calculate image bounds on screen
- Pass crop state to overlay
- Handle overlay callbacks
- Show/hide overlay based on crop mode
- Disable other controls during crop

**Changes Needed:**
```kotlin
// In PhotoEditorScreen.kt
Box(modifier = Modifier.fillMaxSize()) {
    // Image preview
    AsyncImage(...)
    
    // Crop overlay (when active)
    if (uiState.cropState.isActive) {
        CropOverlay(
            cropRect = cropRect,
            imageBounds = imageBounds,
            aspectRatio = uiState.cropState.aspectRatio,
            onCropRectChange = { viewModel.updateCropRect(it, imageBounds) }
        )
    }
}
```

**Acceptance Criteria:**
- [ ] Overlay appears when entering crop mode
- [ ] Overlay aligns perfectly with image
- [ ] Overlay disappears when exiting crop mode
- [ ] Other controls disabled during crop
- [ ] Undo/Redo disabled during crop

---

### Task 2.3: Add Crop Preview Functionality
**Estimated Time:** 2 hours  
**Priority:** Medium  
**Dependencies:** Task 2.2

**Requirements:**
- Show live preview of cropped area
- Update preview as user drags handles
- Debounce preview updates for performance
- Show original vs cropped comparison

**Deliverables:**
```kotlin
fun generateCropPreview(
    bitmap: Bitmap,
    cropRect: Rect
): Bitmap
```

**Acceptance Criteria:**
- [ ] Preview updates smoothly (debounced 100ms)
- [ ] Preview shows exact crop result
- [ ] No performance issues during drag
- [ ] Memory efficient (reuse preview bitmap)

---

## Phase 3: UI/UX Enhancements (Low Priority)

### Task 3.1: Add Visual Feedback and Animations
**Estimated Time:** 2-3 hours  
**Priority:** Low  
**Dependencies:** Task 2.2

**Requirements:**
- Animate crop rectangle when switching aspect ratios
- Highlight active handle during drag
- Show crop dimensions (width x height) during drag
- Add haptic feedback on handle grab
- Smooth transitions for all crop operations

**Deliverables:**
- Animated transitions using `animateFloatAsState`
- Handle highlight with larger size/different color
- Dimension overlay showing "1920 x 1080"

**Acceptance Criteria:**
- [ ] Smooth 300ms animation when switching ratios
- [ ] Active handle is 12dp (vs 8dp normal)
- [ ] Dimensions shown in overlay during drag
- [ ] Haptic feedback on Android
- [ ] All animations feel smooth

---

### Task 3.2: Add Crop Presets/Quick Actions
**Estimated Time:** 2 hours  
**Priority:** Low  
**Dependencies:** Task 2.2

**Requirements:**
- Add "Reset Crop" button (full image)
- Add "Center Crop" button (center with current ratio)
- Add "Rotate Crop 90°" button
- Add "Flip Crop" button (swap width/height)
- Save crop presets for quick access

**Deliverables:**
```kotlin
fun resetCropToFullImage()
fun centerCropWithRatio(ratio: CropAspectRatio)
fun rotateCrop90()
fun flipCropDimensions()
```

**Acceptance Criteria:**
- [ ] Reset button restores full image crop
- [ ] Center crop centers rectangle
- [ ] Rotate swaps width/height
- [ ] All actions are undoable
- [ ] Quick actions are easily accessible

---

### Task 3.3: Improve Crop UI Controls
**Estimated Time:** 2 hours  
**Priority:** Low  
**Dependencies:** Task 2.2

**Requirements:**
- Add zoom controls for precise cropping
- Add "Lock Aspect Ratio" toggle
- Add crop guide overlays (golden ratio, etc.)
- Add crop history (last 5 crops)
- Keyboard shortcuts for crop operations

**Deliverables:**
- Zoom in/out buttons
- Aspect ratio lock toggle
- Guide overlay selector
- Crop history dropdown

**Acceptance Criteria:**
- [ ] Zoom works smoothly (2x, 4x)
- [ ] Lock toggle prevents ratio changes
- [ ] Multiple guide overlays available
- [ ] History shows last 5 crops
- [ ] Keyboard shortcuts documented

---

## Phase 4: Testing & Optimization (Critical)

### Task 4.1: Unit Tests for Crop Logic
**Estimated Time:** 3 hours  
**Priority:** High  
**Dependencies:** All Phase 1 & 2 tasks

**Requirements:**
- Test aspect ratio constraint calculations
- Test coordinate conversion (screen ↔ bitmap)
- Test boundary validation
- Test minimum size enforcement
- Test rotation/flip handling

**Test Cases:**
```kotlin
@Test fun testAspectRatioConstraint_Square()
@Test fun testAspectRatioConstraint_Portrait()
@Test fun testCoordinateConversion_NoRotation()
@Test fun testCoordinateConversion_Rotated90()
@Test fun testBoundaryValidation()
@Test fun testMinimumSizeEnforcement()
```

**Acceptance Criteria:**
- [ ] 100% code coverage for crop logic
- [ ] All edge cases tested
- [ ] Tests pass consistently
- [ ] No flaky tests

---

### Task 4.2: UI Tests for Crop Interactions
**Estimated Time:** 3 hours  
**Priority:** Medium  
**Dependencies:** Task 4.1

**Requirements:**
- Test drag gestures on handles
- Test aspect ratio switching
- Test apply/cancel actions
- Test crop with rotated images
- Test crop with various image sizes

**Test Cases:**
```kotlin
@Test fun testDragCornerHandle()
@Test fun testDragEdgeHandle()
@Test fun testMoveCropRectangle()
@Test fun testSwitchAspectRatio()
@Test fun testApplyCrop()
@Test fun testCancelCrop()
```

**Acceptance Criteria:**
- [ ] All user interactions tested
- [ ] Tests run on emulator
- [ ] Tests cover happy path and edge cases
- [ ] Screenshots captured for visual regression

---

### Task 4.3: Performance Optimization
**Estimated Time:** 2-3 hours  
**Priority:** High  
**Dependencies:** All previous tasks

**Requirements:**
- Profile crop overlay rendering
- Optimize Canvas drawing
- Reduce recompositions
- Optimize bitmap operations
- Add loading states for slow operations

**Optimizations:**
- Use `remember` for expensive calculations
- Debounce crop rect updates
- Use `derivedStateOf` for computed values
- Lazy load crop preview
- Cache bitmap transformations

**Acceptance Criteria:**
- [ ] 60fps during drag operations
- [ ] < 100ms response time for handle grab
- [ ] No memory leaks
- [ ] Smooth on mid-range devices
- [ ] No ANR (Application Not Responding)

---

## Phase 5: Documentation & Polish (Low Priority)

### Task 5.1: User Documentation
**Estimated Time:** 1 hour  
**Priority:** Low  
**Dependencies:** All implementation tasks

**Requirements:**
- Add in-app tutorial for crop feature
- Create help text for each control
- Add tooltips for handles
- Document keyboard shortcuts
- Create video tutorial

**Deliverables:**
- Tutorial overlay on first use
- Help button in crop mode
- Tooltip system for handles
- Keyboard shortcut reference
- 30-second video tutorial

**Acceptance Criteria:**
- [ ] Tutorial shows on first crop
- [ ] Help text is clear and concise
- [ ] Tooltips appear on long-press
- [ ] Shortcuts are discoverable
- [ ] Video is professional quality

---

### Task 5.2: Developer Documentation
**Estimated Time:** 1 hour  
**Priority:** Low  
**Dependencies:** All implementation tasks

**Requirements:**
- Document CropOverlay API
- Add KDoc comments to all public functions
- Create architecture diagram
- Document coordinate systems
- Add troubleshooting guide

**Deliverables:**
- `CROP_ARCHITECTURE.md`
- KDoc for all public APIs
- Coordinate system diagram
- Troubleshooting guide

**Acceptance Criteria:**
- [ ] All public APIs documented
- [ ] Architecture is clear
- [ ] Coordinate systems explained
- [ ] Common issues documented
- [ ] Code examples provided

---

## Implementation Timeline

### Sprint 1 (Week 1) - Core Functionality
- **Day 1-2:** Task 1.1 - CropOverlay Composable
- **Day 3-4:** Task 1.2 - Touch Gesture Handling
- **Day 5:** Task 1.3 - Aspect Ratio Logic

### Sprint 2 (Week 2) - Integration
- **Day 1-2:** Task 2.1 - ViewModel Integration
- **Day 3:** Task 2.2 - Screen Integration
- **Day 4:** Task 2.3 - Crop Preview
- **Day 5:** Task 4.1 - Unit Tests

### Sprint 3 (Week 3) - Polish & Testing
- **Day 1:** Task 4.2 - UI Tests
- **Day 2:** Task 4.3 - Performance Optimization
- **Day 3:** Task 3.1 - Visual Feedback
- **Day 4:** Task 3.2 - Quick Actions
- **Day 5:** Task 5.1 & 5.2 - Documentation

**Total Estimated Time:** 35-45 hours (3 weeks)

---

## Technical Considerations

### Coordinate Systems
1. **Screen Coordinates:** Touch events, overlay drawing
2. **Bitmap Coordinates:** Actual crop operation
3. **Transformation:** Account for rotation, flip, scale

### Performance Targets
- **60 FPS** during drag operations
- **< 100ms** handle grab response
- **< 500ms** crop apply operation
- **< 50MB** memory overhead

### Edge Cases to Handle
1. Very small images (< 100x100)
2. Very large images (> 4000x4000)
3. Extreme aspect ratios (1:10, 10:1)
4. Rotated images (90°, 180°, 270°)
5. Flipped images
6. Low memory situations
7. Rapid gesture changes

### Libraries to Consider
- **Alternative:** Use `com.github.yalantis:ucrop` (saves 30+ hours)
- **Gesture:** Built-in Compose gestures sufficient
- **Canvas:** Built-in Canvas API sufficient
- **Math:** Kotlin stdlib sufficient

---

## Risk Assessment

### High Risk
- **Touch gesture accuracy:** May need fine-tuning for different screen sizes
- **Coordinate conversion:** Easy to introduce off-by-one errors
- **Performance:** Canvas drawing may be slow on old devices

### Medium Risk
- **Aspect ratio edge cases:** Corner cases may be tricky
- **Memory usage:** Large bitmaps may cause OOM
- **Testing complexity:** UI tests may be flaky

### Low Risk
- **UI design:** Well-defined requirements
- **Integration:** Clean architecture makes this straightforward
- **Documentation:** Standard process

---

## Success Criteria

### Must Have (MVP)
- [ ] Draggable crop rectangle with 8 handles
- [ ] Aspect ratio constraints work correctly
- [ ] Apply crop produces correct result
- [ ] Works on images of all sizes
- [ ] No crashes or ANRs
- [ ] 60 FPS performance

### Should Have
- [ ] Visual feedback during drag
- [ ] Crop preview
- [ ] Smooth animations
- [ ] Undo/redo support
- [ ] Unit tests for core logic

### Nice to Have
- [ ] Zoom controls
- [ ] Crop presets
- [ ] Guide overlays
- [ ] Keyboard shortcuts
- [ ] Tutorial system

---

## Alternative Approach: Use Library

### Option: Integrate uCrop Library
**Time Savings:** 30-35 hours  
**Trade-offs:**
- ✅ Battle-tested, production-ready
- ✅ Rich feature set out of the box
- ✅ Well-documented
- ❌ External dependency
- ❌ Less customization
- ❌ Larger APK size

**Recommendation:** 
- Use library if time is critical
- Build custom if you need full control and learning experience

---

## Next Steps

1. **Review this task list** with team/stakeholders
2. **Decide:** Custom implementation vs library
3. **Assign tasks** to developers
4. **Set up project board** with these tasks
5. **Begin Sprint 1** with Task 1.1

---

## Questions to Answer Before Starting

1. Do we need crop for video as well, or just images?
2. What's the minimum Android version to support?
3. Should crop work offline only, or sync with cloud?
4. Do we need to save crop history across sessions?
5. What's the maximum image size we need to support?
6. Are there any accessibility requirements?
7. Do we need to support landscape mode?
8. Should crop be available in batch operations?

---

**Document Version:** 1.0  
**Last Updated:** October 13, 2025  
**Author:** AI Assistant  
**Status:** Ready for Review
