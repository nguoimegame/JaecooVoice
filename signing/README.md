# Signing lineage

`jaecoo.lineage` is JaecooApp's key rotation chain (old debug key -> release key). It holds public
certificates only. The release workflow signs the APK with the old key and the release key
(`apksigner sign --lineage`), so a device that has the old key still updates.
The release keystore lives in the repository secrets `JAECOO_RELEASE_KEYSTORE_BASE64` and
`JAECOO_RELEASE_KEYSTORE_PASSWORD`, never here. The plan is in JaecooApp `docs/release-signing.md`.
