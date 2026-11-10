package dev.apkintellij

import android.content.Context
import java.io.File

/** Where the bundled Linux userspace lives on the device, and how proot is started. */
class Linux(private val context: Context) {
  val rootfs = File(context.filesDir, "rootfs")
  val fakeProc = File(context.filesDir, "fake-proc")
  val prootTmp = File(context.filesDir, "proot-tmp")
  val log = File(context.filesDir, "linux.log")
  private val marker = File(rootfs, ".apkintellij-rootfs")
  private val nativeLibDir = File(context.applicationInfo.nativeLibraryDir)

  /** The rootfs build id shipped inside this APK, written by scripts/build-rootfs.sh. */
  fun bundledVersion(): String =
    context.assets.open(ROOTFS_VERSION_ASSET).bufferedReader().use { it.readText().trim() }

  fun installedVersion(): String? = if (marker.isFile) marker.readText().trim() else null

  fun isInstalled(): Boolean = installedVersion() == bundledVersion()

  fun markInstalled(version: String) = marker.writeText(version)

  /**
   * The command that boots the rootfs and runs /opt/apkintellij/start.sh, which starts the
   * VNC server, the window manager, the noVNC bridge on [VIEWER_PORT] and IntelliJ.
   */
  fun command(width: Int, height: Int): List<String> {
    val cmd = mutableListOf(
      File(nativeLibDir, "libproot.so").path,
      "--kill-on-exit",
      "--link2symlink",
      "--sysvipc",
      "-L",
      "--kernel-release=6.2.1-PRoot-APKintellij",
      "-0",
      "-r", rootfs.path,
      "-b", "/dev",
      "-b", "/proc",
      "-b", "/sys",
      "-b", "/dev/urandom:/dev/random",
      "-b", "${File(rootfs, "tmp").path}:/dev/shm",
    )
    // Android hides parts of /proc from apps; the JVM and IntelliJ read these files.
    for (name in FAKE_PROC_FILES.keys) {
      if (!File("/proc/$name").canRead()) cmd += listOf("-b", "${File(fakeProc, name).path}:/proc/$name")
    }
    val sdcard = File("/storage/emulated/0")
    if (sdcard.canRead()) cmd += listOf("-b", "${sdcard.path}:/sdcard")
    cmd += listOf(
      "-w", "/root",
      "/usr/bin/env", "-i",
      "HOME=/root",
      "USER=root",
      "LANG=C.UTF-8",
      "TERM=xterm-256color",
      "TMPDIR=/tmp",
      "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
      "VNC_GEOMETRY=${width}x$height",
      "VIEWER_PORT=$VIEWER_PORT",
      "/bin/bash", "/opt/apkintellij/start.sh",
    )
    return cmd
  }

  fun environment(): Map<String, String> = mapOf(
    "LD_LIBRARY_PATH" to nativeLibDir.path,
    "PROOT_LOADER" to File(nativeLibDir, "libproot-loader.so").path,
    "PROOT_TMP_DIR" to prootTmp.path,
  )

  companion object {
    const val ROOTFS_ASSET = "rootfs.tar.xz"
    const val ROOTFS_VERSION_ASSET = "rootfs.version"
    const val VIEWER_PORT = 6080

    /** Stand-ins for /proc files Android denies to apps (same idea as proot-distro). */
    val FAKE_PROC_FILES = mapOf(
      "loadavg" to "0.12 0.07 0.02 2/165 765\n",
      "stat" to """
        cpu  1957 0 2877 93280 262 342 254 87 0 0
        cpu0 31 0 226 12027 82 10 4 9 0 0
        ctxt 140223
        btime 1680020856
        processes 772
        procs_running 2
        procs_blocked 0
        softirq 75663 0 5903 6 25375 10774 0 243 11685 0 21677
      """.trimIndent() + "\n",
      "uptime" to "124.08 932.80\n",
      "version" to "Linux version 6.2.1-PRoot-APKintellij (proot@apkintellij) #1 SMP PREEMPT\n",
      "vmstat" to "nr_free_pages 1743136\nnr_zone_inactive_anon 179281\nnr_zone_active_anon 7183\n",
    )
  }
}
