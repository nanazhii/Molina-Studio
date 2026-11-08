package com.molina.studio.terminal

import android.content.Context
import com.molina.core.common.MolinaPaths
import com.molina.studio.AppPaths
import java.io.File

/** Bind PRoot untuk folder aplikasi Molina (/sdcard/Molina), di samping folder kerja. */
internal object StorageBinds {
    /**
     * Daftar `--bind=host:guest` untuk akar aplikasi, atau kosong bila folder host belum bisa dibuat
     * (izin storage belum ada). Titik mount di rootfs HANYA dibuat saat bind dipasang, supaya skrip di guest
     * tidak mengira /sdcard/Molina ada padahal hanya folder kosong di dalam rootfs.
     */
    fun app(ctx: Context, rootfsDir: File): List<String> {
        val name = MolinaPaths.APP_FOLDER
        val host = AppPaths.get(ctx).appRoot
        val mounts = listOf(File(rootfsDir, "sdcard/$name"), File(rootfsDir, "storage/emulated/0/$name"))
        if (!host.isDirectory && !host.mkdirs()) {
            mounts.forEach { it.delete() } // hanya menghapus bila kosong: buang titik mount usang
            return emptyList()
        }
        mounts.forEach { it.mkdirs() }
        return listOf(
            "--bind=${host.absolutePath}:/sdcard/$name",
            "--bind=${host.absolutePath}:/storage/emulated/0/$name",
        )
    }
}
