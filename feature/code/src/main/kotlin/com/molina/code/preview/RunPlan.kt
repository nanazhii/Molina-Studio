package com.molina.code.preview

/**
 * Cara menjalankan satu skrip di Debian PRoot. [interpreter] adalah jalur DI DALAM guest,
 * [extension] dipakai untuk berkas sementara, [needsPython] memicu pemeriksaan python3.
 */
class RunPlan(
    val label: String,
    val interpreter: String,
    val extension: String,
    val needsPython: Boolean,
    /** [CODE-KTS] Perintah yang harus ada di guest (jalur absolut atau nama di PATH); kosong = tanpa prasyarat. */
    val requires: List<String> = emptyList(),
    /** [CODE-KTS] Pesan siap tampil bila ada prasyarat yang belum ada. */
    val installHint: String? = null
)

object RunPlans {
    private val SH = RunPlan("sh", "/bin/sh", "sh", false)
    private val BASH = RunPlan("bash", "/bin/bash", "sh", false)
    private val PYTHON = RunPlan("python3", "/usr/bin/python3", "py", true)
    // [CODE-KTS] peluncur kotlin dipetakan ke /usr/local/bin/kotlin
    private const val KOTLIN_HINT = "Kotlin belum siap di Debian. Di Terminal jalankan:\n" +
        "apt install -y default-jre-headless wget unzip\n" +
        "wget -O /tmp/kotlinc.zip https://github.com/JetBrains/kotlin/releases/download/v2.1.0/kotlin-compiler-2.1.0.zip\n" +
        "unzip -q -o /tmp/kotlinc.zip -d /opt\n" +
        "ln -sf /opt/kotlinc/bin/kotlin /usr/local/bin/kotlin"
    private val KOTLIN = RunPlan(
        "kotlin", "/usr/local/bin/kotlin", "kts", false,
        requires = listOf("/usr/local/bin/kotlin", "java"),
        installHint = KOTLIN_HINT,
    )

    /** Rencana dari nama berkas dan baris pertama; shebang menang atas ekstensi. Null = tak dikenal. */
    fun of(fileName: String?, firstLine: String?): RunPlan? {
        fromShebang(firstLine)?.let { return it }
        return when (fileName?.substringAfterLast('.', "")?.lowercase().orEmpty()) {
            "sh", "bash" -> BASH
            "py" -> PYTHON
            "kts" -> KOTLIN
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
            name == "kotlin" -> KOTLIN
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
