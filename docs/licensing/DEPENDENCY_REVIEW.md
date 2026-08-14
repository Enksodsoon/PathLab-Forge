# Dependency and Redistribution Review

Complete before public source release or installer distribution.

| Component | Exact version | License | Linked or bundled | Redistribution files/notices | Decision |
|---|---|---|---|---|---|
| Java runtime |  |  |  |  | Pending |
| JavaFX |  |  |  |  | Pending |
| QuPath libraries/distribution |  |  |  |  | Pending |
| Bio-Formats |  |  |  |  | Pending |
| OpenSlide |  |  |  |  | Pending |
| libvips |  |  |  |  | Pending |
| JPEG/TIFF codecs |  |  |  |  | Pending |
| SQLite JDBC |  |  |  |  | Pending |
| Apache POI | 5.5.1 | Apache-2.0 | Linked | LICENSE and NOTICE | Pending |
| Sqray SDPC decoder and FFmpeg DLLs | Local runtime | Mixed/verify exact artifacts | Optional external runtime | License and notices required | Pending; do not redistribute |
| libisyntax | pyisyntax 0.1.6 wheel | BSD-2-Clause | Optional contained child runtime | LICENSE required | Pending packaged-platform review |
| pyisyntax | 0.1.6 | MIT | Optional contained child runtime | LICENSE required | Pending packaged-platform review |
| NumPy/Pillow/CFFI | Pinned by runtime lock | Permissive; verify exact wheels | Optional contained child runtime | Licenses and notices required | Pending |
| Python runtime | 3.12 local proof | PSF | Optional contained child runtime | PSF license and notices | Pending |
| tus client |  |  |  |  | Pending |

Do not assume that because a component is open source, it can be bundled under any application license.
