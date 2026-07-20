# Prepared-Slide Contract

Do not invent the final JSON Schema in this repository before the server contract exists.

The canonical server acceptance contract will be:

```text
PathLab-Viewer/contracts/prepared-slide-v1.schema.json
```

After Viewer Task 1 is merged, copy the exact schema into this directory and add a compatibility test that compares its checksum or semantic version against the supported contract fixture.

Breaking changes create a new schema version.
