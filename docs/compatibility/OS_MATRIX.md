# Operating-System Compatibility Matrix

A platform is labelled supported only after a packaged artifact passes the listed tests on that actual OS or an appropriately licensed VM.

| Platform | Architecture | Target channel | Status |
|---|---|---|---|
| Windows 11 | x86-64 | Modern | Planned |
| Windows 10 | x86-64 | Modern | Planned |
| macOS 11+ | Apple Silicon | Modern | Planned |
| macOS 11+ | Intel | Modern | Planned |
| Windows 8.1 | x86-64 | Legacy | Investigation |
| Windows 7 SP1 | x86-64 | Legacy | Investigation |
| Windows Server 2012 R2 | x86-64 | Extended | Investigation |
| macOS 10.15 | Intel | Legacy | Investigation |
| macOS 10.14 | Intel | Legacy | Investigation |
| macOS 10.13 | Intel | Legacy | Investigation |
| macOS 10.11–10.12 | Intel | Extended | Not promised |

Unsupported by design:

- 32-bit Windows;
- Windows XP/Vista;
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

## Compatibility rule

Legacy support must not weaken HTTPS certificate validation, token storage, checksum verification or package validation.
