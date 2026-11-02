package com.molina.studio.terminal

import android.system.Os
import java.io.File

/** Operasi Unix untuk RootfsBackup di Android (lstat tidak mengikuti symlink). */
internal object AndroidRootfsPosix : RootfsPosix {
    override fun mode(f: File): Int = Os.lstat(f.path).st_mode and 0xFFF
    override fun chmod(f: File, mode: Int) = Os.chmod(f.path, mode)
    override fun symlink(link: File, target: String) = Os.symlink(target, link.path)
}
