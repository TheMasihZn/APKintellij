package dev.apkintellij

import android.content.Context
import android.system.Os
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

/**
 * Unpacks the Debian rootfs (with IntelliJ already inside /opt/idea) that ships in the APK's
 * assets into the app's private storage. Runs once per rootfs version.
 */
class Installer(private val context: Context, private val linux: Linux) {

  /** [progress] receives a value in 0..1 and a short status line. */
  fun install(progress: (Float, String) -> Unit) {
    val version = linux.bundledVersion()
    progress(0f, "Preparing Linux…")
    deleteRecursively(linux.rootfs)
    linux.rootfs.mkdirs()

    val total = try {
      context.assets.openFd(Linux.ROOTFS_ASSET).use { it.length }
    } catch (e: IOException) {
      -1L
    }

    val dirModes = mutableListOf<Pair<File, Int>>()
    val hardLinks = mutableListOf<Pair<File, File>>()
    var lastReport = 0L

    context.assets.open(Linux.ROOTFS_ASSET).use { raw ->
      val counting = CountingInputStream(raw.buffered(1 shl 20))
      TarArchiveInputStream(XZCompressorInputStream(counting)).use { tar ->
        while (true) {
          val entry = tar.nextEntry ?: break
          val target = resolve(entry.name) ?: continue
          when {
            entry.isDirectory -> {
              target.mkdirs()
              dirModes += target to entry.mode
            }
            entry.isSymbolicLink -> {
              target.parentFile?.mkdirs()
              target.delete()
              Os.symlink(entry.linkName, target.path)
            }
            entry.isLink -> {
              val source = resolve(entry.linkName)
              if (source != null) hardLinks += source to target
            }
            entry.isFile -> writeFile(tar, entry, target)
            else -> Unit // device nodes, fifos: proot binds the host /dev instead
          }
          if (total > 0 && counting.count - lastReport > (4 shl 20)) {
            lastReport = counting.count
            progress(counting.count.toFloat() / total, "Unpacking Linux and IntelliJ… ${entry.name.take(60)}")
          }
        }
      }
    }

    // Android does not let apps create hard links, so hard-linked files become copies.
    for ((source, target) in hardLinks) {
      if (source.isFile) {
        target.parentFile?.mkdirs()
        source.copyTo(target, overwrite = true)
        Os.chmod(target.path, Os.stat(source.path).st_mode and 0x1ff)
      }
    }
    // Directory modes go last so read-only dirs don't block their own extraction.
    for ((dir, mode) in dirModes.asReversed()) {
      runCatching { Os.chmod(dir.path, (mode and 0x1ff) or 0x1c0) }
    }

    progress(1f, "Finishing setup…")
    finishSetup()
    linux.markInstalled(version)
  }

  private fun writeFile(tar: TarArchiveInputStream, entry: TarArchiveEntry, target: File) {
    target.parentFile?.mkdirs()
    if (target.exists() || isSymlink(target)) target.delete()
    target.outputStream().use { tar.copyTo(it, 1 shl 16) }
    // Owner must keep read/write access: proot runs everything as our single app uid.
    Os.chmod(target.path, (entry.mode and 0x1ff) or 0x180)
  }

  private fun finishSetup() {
    val root = linux.rootfs
    File(root, "tmp").apply { mkdirs(); Os.chmod(path, 0x1ff) }
    File(root, "root").mkdirs()
    linux.prootTmp.mkdirs()
    linux.fakeProc.mkdirs()
    for ((name, content) in Linux.FAKE_PROC_FILES) File(linux.fakeProc, name).writeText(content)
    // Docker leaves these as bind-mount placeholders; give Linux working network config.
    replaceFile(File(root, "etc/resolv.conf"), "nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
    replaceFile(File(root, "etc/hosts"), "127.0.0.1 localhost\n::1 localhost ip6-localhost ip6-loopback\n")
    replaceFile(File(root, "etc/hostname"), "apkintellij\n")
  }

  private fun replaceFile(file: File, content: String) {
    file.parentFile?.mkdirs()
    if (file.exists() || isSymlink(file)) file.delete()
    file.writeText(content)
  }

  /** Maps a tar path onto the rootfs, refusing anything that would escape it. */
  private fun resolve(name: String): File? {
    val clean = name.removePrefix("./").trimStart('/').trimEnd('/')
    if (clean.isEmpty()) return null
    if (clean.split('/').any { it == ".." }) return null
    return File(linux.rootfs, clean)
  }

  private fun isSymlink(file: File): Boolean =
    runCatching { android.system.OsConstants.S_ISLNK(Os.lstat(file.path).st_mode) }.getOrDefault(false)

  private fun deleteRecursively(file: File) {
    if (!file.exists() && !isSymlink(file)) return
    if (file.isDirectory && !isSymlink(file)) {
      runCatching { Os.chmod(file.path, 0x1c0) }
      file.listFiles()?.forEach { deleteRecursively(it) }
    }
    file.delete()
  }

  private class CountingInputStream(input: InputStream) : FilterInputStream(input) {
    var count = 0L
      private set

    override fun read(): Int = super.read().also { if (it >= 0) count++ }

    override fun read(b: ByteArray, off: Int, len: Int): Int =
      super.read(b, off, len).also { if (it > 0) count += it }

    override fun skip(n: Long): Long = super.skip(n).also { count += it }
  }
}
