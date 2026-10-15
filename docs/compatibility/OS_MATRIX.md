# Operating-System Compatibility Matrix

A platform is labelled supported only after a packaged artifact passes the listed tests on that actual OS or an appropriately licensed VM.

| Platform | Architecture | Target channel | Status |
|---|---|---|---|
| Windows 11 | x86-64 | Modern | Internal package validated; release gated |
| Windows 10 22H2 | x86-64 | 1.0 | Actual-machine acceptance pending |
| macOS 14+ | Apple Silicon | 1.0 | Build, signing and actual-machine acceptance pending |
| macOS 14+ | Intel | 1.0 | Build, signing and actual-machine acceptance pending |

Unsupported by design:

- 32-bit Windows;
- Windows XP/Vista;
- Windows versions before Windows 10 22H2;
- macOS versions before macOS 14;
- PowerPC or 32-bit Intel Macs.

## Required platform tests

1. launch without separately installing Java/Python/native tools;
2. open approved OME-TIFF;
3. open approved SVS;
4. discover complete VSI dataset;
5. reject incomplete VSI clearly;
6. create ten-item batch;
7. crop and 2× downsample;
8. export RGB OME-TIFF;
9. generate and preview DZI;
10. build `.plslide`;
11. resumably upload;
12. recover queue after restart.

## Release rule

Advertise only combinations qualified against the final signed artifact. An internal Windows 11 smoke test does not establish Windows 10 or macOS support. Earlier legacy investigation is historical and outside this release.
