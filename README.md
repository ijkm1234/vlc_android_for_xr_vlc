# VLC for Android — XRVLC fork

This repository is an independent modified fork of the VideoLAN
[VLC for Android](https://code.videolan.org/videolan/vlc-android) project. It
is maintained for XRVLC and is not an official VideoLAN distribution. It is
not affiliated with or endorsed by VideoLAN.

The upstream source, base revision, XRVLC dependency revision, and change
scope are documented in [UPSTREAM.md](UPSTREAM.md).

This documentation was modified for XRVLC on 2026-08-16.

VLC on Android plays all the same files as the classical version of VLC, and features a media database
for Audio and Video files and stream.

- [Project Structure](#project-structure)
- [LibVLC](#libvlc)
- [License](#license)
- [Build](#build)
  - [Build Application](#build-application)
  - [Build LibVLC](#build-libvlc)
- [Contribute](#contribute)
  - [Pull requests](#pull-requests)
  - [Translations](#translations)
- [Issues and feature requests](#issues-and-feature-requests)
- [Support](#support)

## Project Structure

Here are the current folders of vlc-android project:

- extension-api : Application extensions SDK (not released yet)
- application : Android application source code, organized by modules.
- buildsystem : Build scripts, CI and maven publication configuration
- libvlc : LibVLC gradle module, VLC source code will be cloned in `vlc/` at root level.
- medialibrary : Medialibrary gradle module

## LibVLC

LibVLC is the Android library embedding VLC engine, which provides a lot of multimedia features, like:

- Play every media file formats, every codec and every streaming protocols
- Hardware and efficient decoding on every platform, up to 8K
- Network browsing for distant filesystems (SMB, FTP, SFTP, NFS...) and servers (UPnP, DLNA)
- Playback of Audio CD, DVD and Bluray with menu navigation
- Support for HDR, including tonemapping for SDR streams
- Audio passthrough with SPDIF and HDMI, including for Audio HD codecs, like DD+, TrueHD or DTS-HD
- Support for video and audio filters
- Support for 360 video and 3D audio playback, including Ambisonics
- Ability to cast and stream to distant renderers, like Chromecast and UPnP renderers.

And more.

![LibVLC stack](https://images.videolan.org/images/libvlc_stack.png)

The upstream LibVLC module can be used to power Android media players. The
XRVLC variant in this repository is built from source.

See the upstream
[LibVLC Android samples](https://code.videolan.org/videolan/libvlc-android-samples).

## License

VLC for Android application code is licensed under
[GPL-2.0-or-later](COPYING). Android libraries make the assembled application,
de facto, a GPLv3 application. Individual bundled components retain their own
licenses.

The XRVLC libvlcjni and LibVLC components are licensed under
[LGPL-2.1-or-later](https://github.com/ijkm1234/libvlcjni_for_xr_vlc/blob/v0.0.1/libvlc/COPYING.LIB),
except where an individual file states otherwise.

## Build

Native libraries are published on bintray. So you can:

- Build the application and get libraries via gradle dependencies (JVM build only)
- Build the whole app (LibVLC + Medialibrary + Application)
- Build LibVLC only, and get an .aar package

### Build Application

VLC-Android build relies on gradle build modes :

- `Release` & `Debug` will get LibVLC and Medialibrary from Bintray, and build application source code only.
- `SignedRelease` also, but it will allow you to sign application apk with a local keystore.
- `Dev` will build build LibVLC, Medialibrary, and then build the application with these binaries. (via build scripts only)

### Build LibVLC

You will need a recent Linux distribution to build VLC.
It should work with Windows 10, and macOS, but there is no official support for this.

#### Setup

See the upstream
[AndroidCompile wiki page](https://wiki.videolan.org/AndroidCompile/), especially
for build dependencies.

Here are the essential points:

On Debian/Ubuntu, install the required dependencies:
```bash
sudo apt install automake ant autopoint cmake build-essential libtool-bin \
    patch pkg-config protobuf-compiler ragel subversion unzip git \
    openjdk-8-jre openjdk-8-jdk flex python wget
```

Setup the build environment:
Set `$ANDROID_SDK` to point to your Android SDK directory
`export ANDROID_SDK=/path/to/android-sdk`

Set `$ANDROID_NDK` to point to your Android NDK directory
`export ANDROID_NDK=/path/to/android-ndk`

Then, you are ready to build!

#### Build

`buildsystem/compile.sh -l -a <ABI>`

ABI can be `arm`, `arm64`, `x86`, `x86_64` or `all` for a multi-abis build

You can do a library release build with `-r` argument

#### Medialibrary

Build Medialibrary with `-ml` instead of `-l`

## Contribute

VLC for Android and this XRVLC fork are libre and open source software.


### Pull requests

XRVLC-specific changes should be proposed to this repository. Changes intended
for upstream VLC for Android should be proposed to the
[VideoLAN repository](https://code.videolan.org/videolan/vlc-android/).

### Translations

You can help improving translations too by joining the [transifex vlc project](https://app.transifex.com/yaron/vlc-trans/dashboard/)

Translations merge requests are then generated from transifex work.

## Issues and feature requests

Report XRVLC-specific issues in this repository. The
[VideoLAN VLC for Android bugtracker](https://code.videolan.org/videolan/vlc-android/issues)
is for the upstream project.

## Support

- For usage support, use the in-app feedback option in the `About` screen
- XRVLC source and issues: https://github.com/ijkm1234/vlc_android_for_xr_vlc
- Upstream source: https://code.videolan.org/videolan/vlc-android
