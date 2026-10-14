# Spec Delta

## Purpose

Defines how the Forge workspace adapts its existing panels and editing controls to available viewport space while keeping active work reachable and intact.

## ADDED Requirements

### Requirement: Workspace panels reflow at their declared breakpoints
The workspace MUST keep the navigator docked above 960 CSS pixels and the inspector docked above 1200 CSS pixels; at narrower widths each panel MUST use an overlay that does not reduce the image stage to an unusable width.

#### Scenario: Desktop and intermediate panel layout
- **WHEN** the viewport crosses 960px and 1200px in either direction
- **THEN** the navigator and inspector switch between their specified docked and overlay modes without document-level horizontal overflow

### Requirement: Transient panels follow layout-mode transitions
Forge MUST keep separate desktop preferences for the navigator and inspector, close transient overlays when their mode changes, and prevent the navigator and inspector from being open together at widths of 960px or less.

#### Scenario: Narrow panels are mutually exclusive
- **WHEN** a user opens the navigator or inspector at 960px or below
- **THEN** the other overlay closes, and returning to a docked layout restores each panel's prior desktop preference

### Requirement: Loaded-slide actions remain compact and named
At widths of 600 CSS pixels or less, loaded-slide actions MUST fit within the header using compact controls with explicit accessible names.

#### Scenario: Use slide actions on a phone
- **WHEN** a slide is loaded at a viewport width of 600 CSS pixels or less
- **THEN** the user can reach every slide action from the header without horizontal overflow, and assistive technology can identify each control by name

### Requirement: Viewport changes preserve active editing state
Viewport changes MUST preserve active workspace state without remounting the viewer or repeating a mutation.

#### Scenario: Resize while editing
- **WHEN** the user resizes or rotates the display with a slide and unsaved crop or annotation work active
- **THEN** the same slide, series, revision, pan or zoom, crop draft, annotation tool and edits, upload or conversion progress, library filters, Study Pack fields, imported tasks, teaching-region selection, validation messages, and any active PIVOT or learner task, confidence, selected point, hints, elapsed time, consent, session, answer, or queued events remain available without repeating a mutation

### Requirement: Overlay focus remains available and reversible
An opened Forge overlay MUST receive keyboard focus, a dismissed overlay MUST return focus to its trigger or visible replacement, and a closed overlay MUST not expose its controls to keyboard navigation.

#### Scenario: Dismiss an overlay after a breakpoint change
- **WHEN** a user opens a panel, changes viewport mode, and presses Escape
- **THEN** only the topmost dismissible overlay closes and focus returns to a visible control for that panel

### Requirement: Essential controls support touch and accessibility modes
Essential workspace actions MUST remain available without hover, primary touch controls MUST be at least 44 by 44 CSS pixels, and focus, selection, and control boundaries MUST remain visible in enlarged-text, reduced-motion, and forced-colors modes.

#### Scenario: Touch-capable narrow workspace
- **WHEN** a touch-capable device or touch emulation is used at a narrow viewport
- **THEN** essential controls remain reachable at touch size while mouse and keyboard interactions continue to work

### Requirement: Responsive acceptance covers current workspace states and display conditions
Responsive acceptance MUST cover each current Forge workspace in populated, empty, loading, error, and permission-dependent states where those states exist. The Chromium matrix MUST include 320x568, 390x844, 568x320, 667x375, 768x1024, 1024x768, 1280x720, 1440x900, 1920x1080, 2560x1440, 3440x1440, and 3840x2160, each affected breakpoint at -1, exact, and +1px, and 320px and 480px viewport heights. Browser zoom MUST be checked separately from device pixel ratio.

#### Scenario: Long content and display scaling
- **WHEN** a user views a long filename, unbroken identifier or URL, multiline prompt or error, or the largest existing paginated result at a narrow or zoomed display
- **THEN** required controls remain reachable, long values remain available through visible or accessible detail, and the document does not gain unintended horizontal scrolling
