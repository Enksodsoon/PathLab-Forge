# Direct OME release-candidate lineage

## Forge base

- Clean product base: `7f902d560e3adf6e86da55c6bb6e86638b3f23f5`.
- Direct-upload references: `87ccbada6692de4d8a6d0cea471499ffc07ded14`
  and `5be079cf24b2987e6f44f145b08d523b71c1face`.
- Mixed factor-2 reference: `7b06d776a914c5c57cc1c0b7462a591668c186e6`.
- Phase 1A reference: `f68c7d770a1899c153dd9bd829327de73c55659e`.

The release branch starts directly from the last pre-AI product commit. No mixed
commit was cherry-picked, merged, or rebased. Direct-upload behavior already on
the clean base was retained; factor-2 and SHA/profile behavior was reconstructed
as focused product edits.

## Viewer base

The coordinated Viewer branch starts from the clean native-JPEG commit
`900808148acf0379276fb3716ece41a02dcc06ae`. Persisted-SHA behavior from
`54fb4d22595d5da5b8f4b0fa1f89274aa0f85066` is reconstructed without its
intervening Classroom ancestry.

## Contamination rule

The final diff must contain no new AI, TRACE, ADAPT, teaching, research, or
Classroom production dependency. The final audit records path scans and ancestry.
