# Dependency and Redistribution Review

Complete before public source release or installer distribution.

| Component | Exact version | License | Linked or bundled | Redistribution files/notices | Decision |
|---|---|---|---|---|---|
| Java runtime |  |  |  |  | Pending |
| JavaFX |  |  |  |  | Pending |
| QuPath libraries/distribution |  |  |  |  | Pending |
| Bio-Formats | 8.5.0 | GPL-2.0-or-later/commercial | Contained child JVM | Exact approved license/notice required | Pending |
| OpenSlide |  |  |  |  | Pending |
| libvips | 8.18.2 | LGPL-2.1-or-later plus codecs | Contained native child processes | Exact build and transitive notices required | Pending |
| JPEG/TIFF codecs |  |  |  |  | Pending |
| SQLite JDBC |  |  |  |  | Pending |
| Apache POI | 5.5.1 | Apache-2.0 | Linked | LICENSE and NOTICE | Pending |
| Sqray SDPC decoder and FFmpeg DLLs | Local runtime | Mixed/verify exact artifacts | Optional external runtime | License and notices required | Pending; do not redistribute |
| libisyntax | pyisyntax 0.1.6 wheel | BSD-2-Clause | Optional contained child runtime | LICENSE required | Pending packaged-platform review |
| pyisyntax | 0.1.6 | MIT | Optional contained child runtime | LICENSE required | Pending packaged-platform review |
| NumPy/Pillow/CFFI | 2.5.2 / 12.3.0 / 2.1.1 | Permissive; verify exact wheels | Optional contained child runtime | Licenses and notices required | Pending |
| Python runtime | 3.12.13 local proof | PSF | Optional contained child runtime | PSF license and notices | Pending |
| tus client |  |  |  |  | Pending |

Do not assume that because a component is open source, it can be bundled under any application license.
