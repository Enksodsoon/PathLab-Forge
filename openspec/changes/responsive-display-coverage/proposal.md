# Proposal

## Why

The Forge release candidate has viewport-dependent panel CSS whose behavior does not match its intended docked and overlay modes, and it has no rendered browser coverage for responsive transitions. Users need the existing editing workspace to remain usable at narrow and short sizes without losing in-progress image and annotation work.

## What Changes

- Correct navigator and inspector reflow at the approved 960px and 1200px thresholds while retaining independent desktop panel preferences.
- Keep the current workspace mounted across resize, orientation, and fullscreen changes; close transient overlays at mode transitions and keep their controls keyboard reachable.
- Make teaching-region capture use the actual displayed image rectangle and reject letterbox gutters.
- Add rendered responsive coverage for representative viewports, state transitions, focus, synthetic image geometry, and configured image-loader limits.

## Capabilities

### New Capabilities
- `responsive-forge-workspace`: Responsive workspace layout, panel state, keyboard focus, and in-progress Forge state behavior.
- `forge-spatial-selection`: Correct teaching-region selection against displayed image content.
- `forge-viewer-resource-limits`: Resource behavior of the image viewer under display changes.

### Modified Capabilities

None. This repository has no existing OpenSpec capability inventory.

## Impact

Affected code is limited to the Forge web frontend, its focused UI tests, and responsive browser harness. Public APIs, saved-data schemas, native picker behavior, viewer-loader concurrency, and existing visual design remain unchanged.
