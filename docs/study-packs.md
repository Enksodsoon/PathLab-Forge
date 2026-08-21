# Study Pack authoring and publication

Forge authors immutable `pathlab.study-pack/1` packages for Viewer Study Mode. Publication requires explicit author, license, provenance, accepted static-DZI slide hashes, explicit answer keys, normalized spatial targets, and a completed faculty preview for the exact checksum.

The Study Packs workspace supports manual multiple-choice and spatial tasks plus bounded CSV import. Before saving, faculty must visit every projected learner task, inspect hints, explanations, and sources, review all six fixed action cards and all English/Thai reason strings, and attest keyboard/focus review. Any subsequent content change invalidates the preview checksum.

Forge stores only versioned JSON packages. Immediately before publication it re-reads Viewer capabilities for schema, byte, and task limits and then uses the scoped `study-packs:write` desktop connection. Viewer performs its own slide state, privacy, content-hash, and pack-contract checks. Forge does not package or run TRACE-SIM and does not make an AI activation decision.
