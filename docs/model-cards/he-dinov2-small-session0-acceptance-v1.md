# DINOv2-small Session 0 acceptance pack

This manifest exists only to prove that the checksum-pinned DINOv2-small worker
can execute offline on the Windows `LocalService` P2000 host. It accepts only
non-identifying `acceptance-<hex>` jobs and remains `benchmark-only` and
`not-evaluable`.

Passing this pack proves runtime compatibility, CUDA 12.6 `sm_61` execution,
resource-envelope telemetry, durable GPU-lane processing, and signed evidence
packaging. It does not qualify H&E retrieval, activate a pilot model, validate
pathology quality, or support diagnostic or clinical claims.

The installed Python 3.12 base runtime is copied into the protected external
pack directory by `scripts/make-dinov2-runtime-portable.ps1`. The runtime
manifest records the portable base and `pyvenv.cfg` files so `LocalService`
does not depend on or receive access to a Windows user's Python installation.
