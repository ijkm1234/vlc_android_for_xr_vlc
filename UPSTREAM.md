# Upstream Provenance

This repository is derived from VideoLAN VLC for Android.

- Upstream: https://code.videolan.org/videolan/vlc-android.git
- Base branch: master
- Base commit: 740c0685b9cf138db921d9a0f90c4b1a1e713299
- XR snapshot branch: xr-vlc-snapshot-2026-06-19

`buildsystem/compile.sh` clones the `v0.0.1` release tag from
<https://github.com/ijkm1234/libvlcjni_for_xr_vlc.git> and verifies that it
resolves to commit `1e0f2fa5114700381e61e90d796f7edf86a733da`. In formal
builds, an existing checkout must also be at that exact clean revision; `-b`
explicitly permits local development sources. The local `libvlcjni` checkout
is not tracked by this repository.

This repository is not an official VideoLAN distribution and is not
affiliated with or endorsed by VideoLAN.

XRVLC modifies the Android application integration, playback and media bridge,
XR surface mapping, subtitle selection, controller preferences, selected user
interface resources, and the reproducible AAR build flow. Comment-capable
files modified from the upstream base carry an in-file change notice dated
2026-08-16. Newly created XRVLC source files carry an SPDX license identifier.

The JSON resources `application/vlc-android/res/raw/authors.json` and
`application/vlc-android/res/raw/libraries.json` carry their change notices as
data because JSON does not support comments. Binary and third-party visual
assets are documented in `ASSETS.md`.

The four source-code links shown on the XRVLC About page intentionally point to
the repository home pages rather than individual tags:

- <https://github.com/ijkm1234/xr_vlc>
- <https://github.com/ijkm1234/vlc_android_for_xr_vlc>
- <https://github.com/ijkm1234/libvlcjni_for_xr_vlc>
- <https://github.com/ijkm1234/vlc_for_xr_vlc>

VLC and VLC media player are trademarks of VideoLAN.
