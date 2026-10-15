# Spec Delta

## Purpose

Defines the bounded image-loading behavior Forge must retain while responsive layout changes resize the active viewer.

## ADDED Requirements

### Requirement: Responsive changes retain the Forge image-loader budget
The Forge viewer MUST keep no more than two active image-loader jobs, and repeated display changes MUST NOT create duplicate viewers or accumulating event handlers.

#### Scenario: Repeated alternating resize
- **WHEN** the same open image is resized between narrow and wide layouts for 20 alternating cycles
- **THEN** one workspace viewer remains attached, no duplicate handlers are added, and active loader jobs stay within two

### Requirement: Forge viewers use bounded configured tile caches
The workspace primary, overview, and teaching preview viewers MUST each configure `maxImageCacheCount` to 32, for an aggregate configured limit of 96. Actual loaded counts are diagnostic because this OpenSeadragon setting is a soft eviction threshold, not a hard cache or byte-level memory ceiling.

#### Scenario: Workspace and teaching preview load concurrently
- **WHEN** a populated workspace with its primary and overview viewers opens a teaching preview and all three viewers request synthetic tiles
- **THEN** active image-loader jobs stay within two, configured cache limits total 96 across the three live viewers, and actual loaded tile counts are recorded diagnostically
