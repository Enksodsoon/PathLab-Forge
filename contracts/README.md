# Prepared-Slide Contract

Do not invent or privately fork the final JSON Schema before the public Viewer contract exists.

The canonical server acceptance contract will be:

```text
PathLab-Viewer/contracts/prepared-slide-v1.schema.json
```

After Viewer V1 is merged:

1. copy the exact schema into this directory;
2. record its schema version and source commit in a small machine-readable lock file;
3. add compatibility tests against the same canonical valid and invalid manifest fixtures;
4. fail the Forge build when its producer model no longer satisfies the pinned schema.

Package v1 is derivative-only:

```text
manifest.json
derivative/slide.dzi
derivative/slide_files/<level>/<column>_<row>.jpg
derivative/thumbnail.jpg
```

The standardized OME-TIFF remains local. Breaking changes create a new schema version; never silently redefine version 1.
