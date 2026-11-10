# IntelliJ for Samsung DeX (APK)

One APK that contains a small Debian Linux, IntelliJ IDEA (Linux ARM64 build with its own
JetBrains Runtime) and a display server, and shows the IDE as a normal resizable DeX window.

## How it works

```
Android app (this project)
 ├─ LinuxService ── proot ──► Debian arm64 rootfs (unpacked from the APK on first launch)
 │                              ├─ Xvnc :1          (X display, resizes with the DeX window)
 │                              ├─ openbox          (window manager, IntelliJ maximized, no frame)
 │                              ├─ websockify/noVNC (127.0.0.1:6080 only)
 │                              └─ /opt/idea/bin/idea.sh
 └─ MainActivity ── WebView ──► http://127.0.0.1:6080/vnc.html (mouse + keyboard go straight to the IDE)
```

* **Why proot and not a VM:** Android only lets system apps start real virtual machines (AVF), and
  Samsung phones don't offer it at all. Emulating a whole kernel with QEMU would be far too slow for
  IntelliJ. proot runs Debian's programs directly on the phone's CPU using Android's own kernel, so
  the IDE runs at close to native speed.
* `proot` comes from the Termux project's Android builds (`scripts/fetch-proot.sh`).
* The rootfs is built with Docker (`scripts/build-rootfs.sh`) and stored in the APK as
  `assets/rootfs.tar.xz`, so nothing is downloaded on the phone.

## Getting the APK

The APK is built by GitHub Actions (workflow **Android APK (IntelliJ for Samsung DeX)**).
Open the latest run under the repository's *Actions* tab and download the `APKintellij-dex-apk`
artifact. To bundle a specific IntelliJ version, run the workflow manually and paste the
`.tar.gz` link for *Linux ARM64* from jetbrains.com into `idea_url`.

To build locally you need Docker with arm64 emulation, the Android SDK, `patchelf`, `jq` and `xz`:

```bash
cd android-app
./scripts/fetch-proot.sh
./scripts/build-rootfs.sh
./gradlew assembleRelease   # app/build/outputs/apk/release/app-release.apk
```

## Installing on the phone

1. Copy the APK to the phone and open it (allow "Install unknown apps" for your file manager).
   The APK is large (around 1 GB) because IntelliJ is inside it.
2. Start **IntelliJ for DeX**. The first launch unpacks Linux and IntelliJ, which takes a few minutes
   and about 3–4 GB of storage.
3. Allow notifications: the app keeps a "IntelliJ is running" notification so Android doesn't stop
   the IDE in the background. Use its **Stop** button to shut everything down.

Recommended phone settings:

* **Settings › Battery › Background usage limits**: add the app to *Never sleeping apps*.
* **Android 14+:** *Developer options › Disable child process restrictions* on. On Android 12–13 run
  `adb shell settings put global settings_enable_monitor_phantom_procs false` once. Without this,
  Android may kill IntelliJ's helper processes (Gradle, git, the compiler daemon).
* A phone with 8 GB of RAM or more. IntelliJ is given a 2 GB heap
  (`rootfs-overlay/opt/apkintellij/idea.vmoptions`).

## Using it in DeX

* Resize or maximize the window; the Linux display follows the window size.
* Mouse and physical keyboard go to IntelliJ. Press **Back twice** to send the app to the background.
* Right-click the desktop background (visible when IntelliJ is not maximized) for a terminal.
* Your phone's storage is at `/sdcard` inside Linux once the app has storage access; projects in
  `/root` live only inside the app and are removed by **Reinstall Linux** or uninstalling the app.
* If the IDE doesn't come up, the start screen's **Show log** button shows the Linux log.
