# Direct OME interactive report

Open `report.html` in a current browser. It is a self-contained, read-only report with
interactive source affordances, metric cards, charts, and an exact fidelity table. It
does not require Forge, Viewer, a CDN, or a sidecar data service.

## Source of truth

- `../ome-direct-rc-metrics.json` contains the governed aggregate evidence.
- `source.sql` is the DuckDB transformation used to form the bounded report datasets.
- `artifact.json` is the canonical report manifest and reviewed snapshot.
- `report.html` is generated from that artifact by the Data Analytics portable report
  builder; it does not introduce another chart runtime into Forge.

## Verification

- Canonical artifact validation: passed.
- Exact embedded-payload and structural verification: passed.
- Content inventory: 17 blocks, 3 charts, 4 metric cards, and 1 table.
- Browser rendering: the enhanced reader and charts render successfully.
- Remaining shared-reader issue: on Windows, the packaged sticky header uses `100vw`,
  which creates a scrollbar-width horizontal overflow when the report is long enough
  to require a vertical scrollbar. The builder's strict overflow check remains red for
  that reason. No product pipeline or evidence data is affected.
