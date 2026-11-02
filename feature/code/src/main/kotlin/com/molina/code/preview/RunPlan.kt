package com.molina.code.preview

/**
 * Cara menjalankan satu skrip di Debian PRoot. [interpreter] adalah jalur DI DALAM guest,
 * [extension] dipakai untuk berkas sementara, [needsPython] memicu pemeriksaan python3.
 */
class RunPlan(
    val label: String,
    val interpreter: String,
    val extension: String,
    val needsPython: Boolean
)

object RunPlans {
    private val SH = RunPlan("sh", "/bin/sh", "sh", false)
    private val BASH = RunPlan("bash", "/bin/bash", "sh", false)
    private val PYTHON = RunPlan("python3", "/usr/bin/python3", "py", true)

    /** Rencana dari nama berkas dan baris pertama; shebang menang atas ekstensi. Null = tak dikenal. */
    fun of(fileName: String?, firstLine: String?): RunPlan? {
        fromShebang(firstLine)?.let { return it }
        return when (fileName?.substringAfterLast('.', "")?.lowercase().orEmpty()) {
            "sh", "bash" -> BASH
            "py" -> PYTHON
            else -> null
        }
    }

    /** Urai `#!/bin/sh`, `#!/usr/bin/env python3`, dst. Hanya interpreter yang dikenal yang diterima. */
    fun fromShebang(firstLine: String?): RunPlan? {
        val line = firstLine?.trim() ?: return null
        if (!line.startsWith("#!")) return null
        val tokens = line.removePrefix("#!").trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null
        var name = tokens[0].substringAfterLast('/')
        if (name == "env") {
            name = tokens.drop(1).firstOrNull { !it.startsWith("-") && '=' !in it }
                ?.substringAfterLast('/') ?: return null
        }
        return when {
            name == "sh" || name == "dash" -> SH
            name == "bash" -> BASH
            name == "python" || name == "python3" || name.matches(Regex("python3\\.\\d+")) -> PYTHON
            else -> null
        }
    }
}

/** Pemetaan jalur Android ke jalur guest (hanya yang di-bind ke PRoot oleh Terminal yang terlihat). */
object GuestPaths {
    /**
     * Jalur guest untuk [hostPath], atau null bila tidak terlihat di guest. Default hanya folder
     * kerja [workspace] dan [extraRoots] (folder setingkat, mis. Molina) yang di-bind; [fullAccess] membuka seluruh storage.
     */
    fun of(
        hostPath: String,
        storageRoot: String,
        workspace: String,
        fullAccess: Boolean,
        extraRoots: List<String> = emptyList(),
    ): String? {
        val root = storageRoot.trimEnd('/')
        if (hostPath != root && !hostPath.startsWith("$root/")) return null
        val rel = hostPath.removePrefix(root)
        if (rel.split('/').any { it == ".." }) return null
        if (fullAccess) return "/sdcard$rel"
        val visible = (listOf(workspace) + extraRoots).any { rel == "/$it" || rel.startsWith("/$it/") }
        return if (visible) "/sdcard$rel" else null
    }
}
