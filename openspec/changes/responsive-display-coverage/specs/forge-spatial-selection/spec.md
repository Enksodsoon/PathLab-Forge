# Spec Delta

## Purpose

Defines how faculty preview locations are selected and normalized against the exact teaching image so that viewport geometry cannot shift authored coordinates.

## ADDED Requirements

### Requirement: Teaching locations use displayed image coordinates
Pointer selection MUST be transformed through the loaded teaching image and normalized by its source dimensions; clicks in letterbox or canvas gutters MUST NOT change the selected location.

#### Scenario: Select a visible landmark in a non-square image
- **WHEN** a faculty user selects a visible landmark in a non-square synthetic teaching image
- **THEN** the normalized location matches the source-image coordinate within one source pixel and clicks in the surrounding gutters are ignored

### Requirement: Spatial coordinates are keyboard adjustable
Faculty preview MUST expose bounded normalized X and Y values with 0.01 increments; when either value is focused, Arrow keys MUST adjust it by 0.01 and Shift+Arrow MUST adjust it by 0.1, clamped to the image edges.

#### Scenario: Adjust coordinates without pointer input
- **WHEN** a faculty user presses Arrow or Shift+Arrow while an X or Y field is focused
- **THEN** only that coordinate changes by the declared increment and Enter or Space does not synthesize a new canvas selection

### Requirement: Image-coordinate behavior survives viewport transforms
Teaching image coordinate selection MUST remain correct after pan, zoom, resize, and orientation changes, and image dimensions MUST remain tied to the exact associated artifact.

#### Scenario: Reposition the teaching viewport
- **WHEN** a faculty user pans or zooms, rotates the viewport, then selects the same rendered landmark
- **THEN** the resulting normalized location is the same within one source-image pixel
