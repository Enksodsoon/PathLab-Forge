# Security and Privacy

## Data boundary

Original proprietary WSI stays on the local workstation by default. Viewer-only upload contains only manifest, thumbnail and sanitized DZI/JPEG assets.

## Credentials

Release builds must use Windows Credential Manager or macOS Keychain. Plaintext token files are prohibited.

## Packages

All `.plslide` packages are treated as untrusted by PathLab Viewer. Forge must still validate locally before upload and calculate SHA-256.

## Diagnostics

Logs must avoid credentials and should redact or replace sensitive source names in shareable diagnostic bundles.

## Reporting

Do not place patient data or credentials in public issues. Use a private report channel.
