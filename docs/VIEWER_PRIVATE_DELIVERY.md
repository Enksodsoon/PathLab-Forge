# Private Viewer delivery

Forge uses one outbound HTTPS connection. Open **Connect to PathLab Viewer**, scan the QR code or open the prefilled approval link, confirm the matching device code, and leave Forge running. No inbound port, VPN, shared folder, or cloud-drive staging is required.

After a conversion is approved, **Deliver privately to Viewer** performs one flow:

1. Upload the verified `ome-dynamic-v1` artifact with server-authoritative offsets.
2. Verify the persisted Viewer SHA-256 and enable **Open private slide in Viewer**.
3. Package available local annotations, classifications, geometry measurements, and provenance into `pathlab-private-results/v1`.
4. Resume and apply the result bundle without retransmitting the image.

Delivery state is persisted in Forge SQLite. Transient transport failures retry for up to 30 minutes with capped backoff; repeated failures reduce the request size from 64 MiB to 16 MiB. Permanent identity, authorization, capacity, profile, conflict, and hash failures pause. Cancellation removes only incomplete Viewer staging and retains the verified local artifact.

New connections include `results:sync`. A credential created before this capability remains valid for image upload, but Forge asks for one reconnect before structured results can be delivered.

Production distributions must be built with an HTTPS `pathlab.forge.viewer.defaultOrigin`. Development continues to default to loopback and makes no startup request unless a persisted delivery is pending.

Out of scope: public sharing, publication, public APIs, parallel multipart upload, incomplete-OME viewing, cloud storage, and background updates.
