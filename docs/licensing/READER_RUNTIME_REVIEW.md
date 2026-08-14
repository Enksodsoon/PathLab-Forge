# Reader runtime redistribution gate

Forge discovers Bio-Formats and libvips capabilities at runtime. “Supported” is a
best-effort probe result, not certification of every vendor variant.

Release installers must not be produced until all of these are complete:

1. commercial/GPL redistribution review for the exact Bio-Formats 8.5.x and
   libvips builds;
2. signed upstream artifacts copied into an external, clean runtime root;
3. exact versions and SHA-256 hashes recorded in `reader-runtime.lock.properties`;
4. `redistribution.status=APPROVED` recorded after review; and
5. `verifyReaderRuntimeBundle` passes on Windows and macOS packaging hosts.

The repository does not contain reader binaries. Local development may use
`PATHLAB_FORGE_BFTOOLS`, `PATHLAB_FORGE_SDPC_RUNTIME`,
`PATHLAB_FORGE_ISYNTAX_RUNTIME`, and the existing libvips
discovery mechanism. This does not authorize redistribution. The SDPC adapter
must remain opt-in until the exact native decoder and its FFmpeg dependencies
have completed redistribution and notice review. Philips iSyntax uses the
independent BSD-2-Clause `libisyntax` decoder through the MIT `pyisyntax`
wrapper; exact wheels, Python runtime, transitive licenses and notices remain a
packaging gate even though local decoding does not require the Philips SDK.

Expected external layout:

```text
reader-runtime/
  bftools/bioformats_package.jar
  vips/bin/vips.exe       # Windows
  vips/bin/vips           # macOS
  sdpc/DecodeSdpcDll.dll  # optional Windows runtime after approval
  isyntax/isyntax/        # pyisyntax/libisyntax runtime after approval
```
