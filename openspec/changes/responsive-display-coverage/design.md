# Design

## Context

Forge supplies stage and panel slots to the pinned shared Viewer shell. Forge owns open-state in `App.tsx`, while its CSS currently overrides a narrower grid rule with a wider fixed-overlay rule. The exact teaching preview owns a separate OpenSeadragon instance and maps click positions through the image item.

## Goals / Non-Goals

**Goals:** Align shell CSS and React panel state with the approved breakpoints, keep transient overlays predictable, correct keyboard and pointer location selection, and add rendered evidence for geometry and state retention.

**Non-Goals:** Redesigning the workspace, changing the shared shell package, adding user preference persistence across reloads, changing saved study data or APIs, or changing image-loader budgets.

## Decisions

- Keep the existing shell and stages mounted. Track the current layout mode and separate docked preferences in the app state/ref layer; reconcile only when crossing a breakpoint, not on every resize event.
- Make CSS media rules match the navigator and inspector thresholds. Use bounded viewport-relative overlays, internal panel scrolling, dynamic viewport sizing with fallbacks, and safe-area insets without changing desktop visual tokens.
- Derive teaching selection from the active image item's transformed coordinates and reject points outside the source image. Keep the normalized X/Y fields as a keyboard route to select the same location and use explicit increments.
- Add a small Playwright harness pinned to the approved version and route deterministic synthetic DZI tiles through the browser. Keep test assets synthetic and separate from private image data.
- Assert the two-job aggregate loader limit and the configured cache settings through the current app configuration. The three possible live viewers (workspace primary, overview, and teaching preview) each use a 32-tile `maxImageCacheCount`, totaling 96 configured entries. Record actual loaded cache counts diagnostically because OpenSeadragon's count is a soft eviction threshold; do not claim a hard actual-tile or byte-level memory ceiling. Use viewer identity and stable handler counts as lifecycle evidence.

## Risks / Trade-offs

- The shared shell implementation is a pinned package, so a Forge-only patch cannot repair package-owned behavior → keep changes to Forge slots and CSS, and verify computed rendered geometry.
- Headless browser screenshots can miss physical mobile-browser chrome and hardware input differences → report browser emulation separately from device checks.
- The 960px / 1200px transition may expose differences between explicit user panel preference and transient visibility → test transitions in both directions and preserve the docked preference.

## Migration Plan

No data migration is required. The change is client-side and can be rolled back by reverting the frontend patch; OpenSpec and browser-test artifacts remain in the branch for review.
