#!/usr/bin/env python3
"""Gerbang rahasia Molina: gagalkan CI bila repo memuat berkas terlarang, pola token/kunci, atau workflow
yang membocorkan secret ke GITHUB_ENV. Mode bawaan memeriksa berkas ter-track; --history memeriksa seluruh riwayat.
Ubah daftar di bawah SECARA SADAR dan catat di progres.md."""
import fnmatch, os, re, subprocess, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
HISTORY = "--history" in sys.argv

FORBIDDEN_PATHS = [
    "*.jks", "*.keystore", "*.p12", "*.pfx", "*.key", ".env", ".env.*", "*/.env", "*/.env.*",
    "local.properties", "progres.md", "docs/BLUEPRINT.md", "docs/blueprint-code-editor.md", "docs/HANDOFF.md",
    "docs/UJI_PERANGKAT.md", "docs/SIGNING.md", "docs/lsp_probe.txt", "docs/rootfs_audit.txt",
    "molina-keys/*", "terminal-logs/*", "*.bak", "*.orig", "*.rej", "*.patch",
]
# Pola berupa regex agar berkas ini sendiri tidak memuat token utuh.
SECRET_PATTERNS = {
    "token GitHub": r"gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{20,}",
    "kunci AWS": r"AKIA[0-9A-Z]{16}",
    "kunci API Google": r"AIza[0-9A-Za-z_-]{30,}",
    "token Slack": r"xox[bap]-[0-9A-Za-z-]{10,}",
    "kunci privat": r"-----BEGIN [A-Z ]*PRIVATE KEY",
    "kredensial di URL git": r"https?://[^/\s\"'@:]+:[^/\s\"'@]+@(?:github\.com|gitlab\.com|bitbucket\.org)",
    "password signing tertanam": r"(?:storePassword|keyPassword)\s*=\s*\"[^\"$]{3,}\"",
}
SECRET_RX = {k: re.compile(v) for k, v in SECRET_PATTERNS.items()}
SKIP_CONTENT_EXT = (".so", ".png", ".jpg", ".jpeg", ".webp", ".gif", ".jar", ".zip", ".tgz", ".gz",
                    ".ttf", ".otf", ".woff", ".woff2", ".ico", ".pem")
# Fixture uji palsu (bukan rahasia asli) yang sudah terlanjur ada di riwayat commit: (berkas, jenis temuan).
HISTORY_FIXTURE_ALLOW = {("scripts/app-tests/run_git_cred_guard.sh", "kredensial di URL git")}
# Commit lama yang terlanjur memuat progres.md (satu baris catatan progres, tanpa rahasia; sudah dilepas di 2876d7b).
# Daftar tetap: commit baru yang memuat berkas terlarang tetap menggagalkan gerbang.
HISTORY_PATH_ALLOW = {("progres.md", s) for s in ("1496c40", "90d308f", "9178c79", "b09ac4a", "bdd7253")}
ENV_SECRETISH = re.compile(r"(PASSWORD|TOKEN|SECRET|PRIVATE)", re.I)

def git(*a):
    return subprocess.run(["git", "-C", ROOT] + list(a), capture_output=True, text=True, errors="replace").stdout

def forbidden(path):
    return any(fnmatch.fnmatch(path, p) for p in FORBIDDEN_PATHS)

def scan_workflow(path, text, problems):
    if "pull_request_target" in text:
        problems.append("%s: memakai pull_request_target (berisiko untuk fork)" % path)
    for i, line in enumerate(text.splitlines(), 1):
        if "GITHUB_ENV" in line and "$" in line and ENV_SECRETISH.search(line):
            problems.append("%s:%d: menulis nilai rahasia ke GITHUB_ENV (kirim lewat env: pada langkah yang butuh)" % (path, i))

problems = []
if HISTORY:
    for sha in git("rev-list", "--all").split():
        for path in git("ls-tree", "-r", "--name-only", sha).splitlines():
            if forbidden(path) and (path, sha[:7]) not in HISTORY_PATH_ALLOW:
                problems.append("riwayat %s: berkas terlarang pernah ada: %s" % (sha[:7], path))
    cur = ""; cur_file = ""
    for line in git("log", "--all", "-p", "-U0", "--no-color", "--format=commit %h").splitlines():
        if line.startswith("commit "):
            cur = line.split()[1]
        elif line.startswith("+++ "):
            cur_file = line[6:] if line.startswith("+++ b/") else ""
        elif line.startswith("+"):
            for name, rx in SECRET_RX.items():
                if (cur_file, name) in HISTORY_FIXTURE_ALLOW: continue
                if rx.search(line): problems.append("riwayat %s: %s" % (cur, name))
else:
    for path in git("ls-files").splitlines():
        if forbidden(path):
            problems.append("berkas terlarang ter-track: %s" % path); continue
        if path.endswith(SKIP_CONTENT_EXT): continue
        try:
            with open(os.path.join(ROOT, path), encoding="utf-8", errors="replace") as f: text = f.read()
        except OSError:
            continue
        for name, rx in SECRET_RX.items():
            if rx.search(text): problems.append("%s: %s" % (path, name))
        if path.startswith(".github/workflows/") and path.endswith((".yml", ".yaml")):
            scan_workflow(path, text, problems)

if problems:
    print("GERBANG RAHASIA GAGAL:")
    for x in sorted(set(problems)): print("  - " + x)
    sys.exit(1)
if HISTORY:
    print("Gerbang rahasia lulus (riwayat): tanpa berkas terlarang atau pola token/kunci di seluruh commit.")
else:
    print("Gerbang rahasia lulus (berkas ter-track): tanpa berkas terlarang, token/kunci, atau kebocoran GITHUB_ENV.")
