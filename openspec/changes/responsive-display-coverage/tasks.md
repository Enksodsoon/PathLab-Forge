# Tasks

## 1. Responsive workspace state and layout

- [x] 1.1 Align Forge panel state with the 960px navigator and 1200px inspector modes, preserve separate docked preferences, and verify breakpoint transitions plus retained editing state in component tests.
- [x] 1.2 Correct workspace height, overlay bounds, safe-area padding, and narrow/short-screen scrolling; keep loaded-slide actions in the compact header with explicit accessible names; verify no unintended document overflow and reachable required controls in rendered browser checks.
- [x] 1.3 Add touch, focus-visible, reduced-motion, enlarged-text, and forced-colors coverage; verify primary targets and visible state under those settings.

## 2. Teaching image coordinate correctness

- [x] 2.1 Normalize teaching selection to the actual image bounds, ignore letterbox gutters, and add bounded 0.01 X/Y fields with Shift+Arrow adjustment; verify keyboard behavior and invalidation in focused tests.
- [x] 2.2 Add deterministic non-square synthetic tiles with recognizable landmarks; verify selection after pan, zoom, resize, and orientation changes within one source-image pixel.

## 3. Rendered responsive and resource verification

- [x] 3.1 Add the pinned Playwright harness and test 320x568, 390x844, 568x320, 667x375, 768x1024, 1024x768, 1280x720, 1440x900, 1920x1080, 2560x1440, 3440x1440, and 3840x2160 in Chromium; test each affected breakpoint at -1, exact, and +1px; include 320px and 480px heights with a focused form; verify no document overflow, clipped controls, or obscured focus.
- [ ] 3.2 Verify mouse, keyboard, touch emulation, and mixed pointer-capability changes without disabling mouse or keyboard; test theme, reduced motion, enlarged text, and forced colors; verify 44x44px primary targets, textarea focus visibility, and screen-reader reading order; run Firefox and WebKit phone, landscape, tablet, and desktop representatives.
- [x] 3.3 Verify 20 alternating narrow/wide cycles keep one workspace viewer, no accumulating handlers, and at most two active image-loader jobs. When workspace primary, overview, and teaching preview overlap, verify their three configured `maxImageCacheCount` values total 96 (32 each); record actual loaded cache counts diagnostically because OpenSeadragon applies a soft eviction threshold.
- [ ] 3.4 Cover populated, empty, loading, error, and permission-dependent states for every current Forge workspace, including local and Viewer libraries, Study Pack authoring, teaching-region preview, and feature management; include long filenames, unbroken identifiers and URLs, multiline prompts and errors, and the largest existing library result page.
- [ ] 3.5 Check actual 200% and 400% browser zoom in Edge separately from DPR 1, 2, and 3; record screen-reader checks where available and identify unavailable checks without treating emulation as hardware validation.

## 4. Integration and evidence

- [x] 4.1 Run the complete Forge frontend tests, production build, and required Gradle/repository checks; record any baseline or environment failures separately.
- [x] 4.2 Review rendered screenshots and console output, resolve material mismatches, run strict OpenSpec validation, and mark tasks complete only when their acceptance evidence is recorded.

## Workflow follow-up

- Archive the fully implemented change after acceptance evidence is complete; validate the archived spec before opening the final PR.

Tasks 3.2, 3.4, and 3.5 remain open. Browser representatives and mixed-input emulation pass, but screen-reader reading order was not verified with a reader; route-state coverage does not include every workspace and the largest paginated result; and Edge 200%/400% browser zoom was not available. DPR 1/2/3 and root-font enlargement emulation are covered separately.
