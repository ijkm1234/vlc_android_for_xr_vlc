# Upstream Provenance

This repository is derived from VideoLAN VLC for Android.

- Upstream: https://code.videolan.org/videolan/vlc-android.git
- Base branch: master
- Base commit: 740c0685b9cf138db921d9a0f90c4b1a1e713299
- XR snapshot branch: xr-vlc-snapshot-2026-06-19

`buildsystem/compile.sh` clones the `v0.0.1` release tag from
<https://github.com/ijkm1234/libvlcjni_for_xr_vlc.git> and verifies that it
resolves to commit `2d96eac4d95e16c3da1dffa109848e7605ca1cf9`. The local
`libvlcjni` checkout is not tracked by this repository.

This repository is not an official VideoLAN distribution and is not
affiliated with or endorsed by VideoLAN.
