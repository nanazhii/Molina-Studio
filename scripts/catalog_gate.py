#!/usr/bin/env python3
"""Gerbang version catalog [CATALOG]: versi dependensi eksternal hanya hidup di gradle/libs.versions.toml.
1. Koordinat Maven literal di build.gradle.kts dilarang (kecuali NewPipeExtractor, dipin scripts/newpipe/bump_pin.py).
2. Alias libs.* di build file harus ada di catalog, dan version.ref harus menunjuk kunci [versions] yang ada.
3. CatalogVersions.kt (tampilan Komponen bawaan) harus sinkron dengan catalog.
4. [STANDALONE] Proyek Gradle mandiri (settings.gradle.kts selain root) yang memakai alias libs.* wajib meng-import
   katalog root lewat versionCatalogs { from(files("<path>/gradle/libs.versions.toml")) } dan path-nya harus valid.
5. [REPOMODE] Setiap settings.gradle.kts (root dan mandiri) wajib repositoriesMode FAIL_ON_PROJECT_REPOS, dan JitPack
   hanya boleh lewat exclusiveContent yang dibatasi ke grup com.github.*.
Alias yang tidak dipakai hanya PERINGATAN."""
import os, pathlib, re, subprocess, sys, tomllib

ROOT = pathlib.Path(__file__).resolve().parent.parent
DEPCFG = (r"(?:implementation|api|compileOnly|runtimeOnly|testImplementation|testRuntimeOnly|androidTestImplementation|"
          r"debugImplementation|releaseImplementation|ksp|kapt|annotationProcessor|coreLibraryDesugaring|lintChecks)")
LITERAL = re.compile(DEPCFG + r'\(\s*(?:platform\(\s*)?"([^"]*:[^"]*)"')
ALIAS = re.compile(r"\blibs\.([A-Za-z0-9_.]+)")
COMMENT = re.compile(r"(?m)(?<!:)//.*$")
ALLOW = {("engine/newpipe/build.gradle.kts", "com.github.TeamNewPipe:NewPipeExtractor:$newpipeVersion")}

cat = tomllib.loads((ROOT / "gradle" / "libs.versions.toml").read_text(encoding="utf-8"))
libs, vers = cat.get("libraries", {}), cat.get("versions", {})
errs, warns = [], []

keys = {}
for alias, spec in libs.items():
    k = alias.replace("-", ".").replace("_", ".")
    if k in keys:
        errs.append("alias ganda setelah normalisasi aksesor: %s dan %s" % (keys[k], alias))
    keys[k] = alias
    v = spec.get("version") if isinstance(spec, dict) else None
    ref = v.get("ref") if isinstance(v, dict) else None
    if ref and ref not in vers:
        errs.append("alias %s memakai version.ref '%s' yang tidak ada di [versions]" % (alias, ref))

files = []
for d, dirs, names in os.walk(ROOT):
    dirs[:] = [x for x in dirs if x not in (".git", ".gradle", "build", "node_modules", ".idea")]
    if "build.gradle.kts" in names:
        files.append(pathlib.Path(d) / "build.gradle.kts")

used = set()
for f in sorted(files):
    rel = f.relative_to(ROOT).as_posix()
    text = COMMENT.sub("", f.read_text(encoding="utf-8"))
    for m in LITERAL.finditer(text):
        if (rel, m.group(1)) not in ALLOW:
            errs.append("%s: koordinat literal \"%s\" dilarang; pindahkan ke gradle/libs.versions.toml" % (rel, m.group(1)))
    for m in ALIAS.finditer(text):
        parts = m.group(1).split(".")
        if parts[0] in ("versions", "plugins", "bundles"):
            continue
        hit = next((".".join(parts[:i]) for i in range(len(parts), 0, -1) if ".".join(parts[:i]) in keys), None)
        if hit is None:
            errs.append("%s: alias libs.%s tidak ada di catalog" % (rel, m.group(1)))
        else:
            used.add(hit)

# [STANDALONE] proyek Gradle mandiri harus meng-import katalog root jika memakai alias libs.*
ROOT_TOML = (ROOT / "gradle" / "libs.versions.toml").resolve()
IMPORT = re.compile(r'from\(\s*files\(\s*"([^"]+)"\s*\)\s*\)')
for st in sorted(ROOT.rglob("settings.gradle.kts")):
    if st.parent == ROOT or any(x in st.parts for x in (".git", ".gradle", "build", "node_modules", ".idea")):
        continue
    rel = st.relative_to(ROOT).as_posix()
    bf = st.parent / "build.gradle.kts"
    uses = bf.exists() and ALIAS.search(COMMENT.sub("", bf.read_text(encoding="utf-8"))) is not None
    stext = COMMENT.sub("", st.read_text(encoding="utf-8"))
    paths = [(st.parent / m.group(1)).resolve() for m in IMPORT.finditer(stext) if m.group(1).endswith("libs.versions.toml")]
    if "versionCatalogs" in stext and paths:
        if ROOT_TOML not in paths:
            errs.append("%s: versionCatalogs tidak menunjuk gradle/libs.versions.toml root" % rel)
    elif uses:
        errs.append("%s: proyek mandiri memakai alias libs.* tetapi tidak meng-import katalog root; tambahkan versionCatalogs { create(\"libs\") { from(files(\"<path>/gradle/libs.versions.toml\")) } }" % rel)

# [REPOMODE] kebijakan repositori seragam di semua settings.gradle.kts (root dan proyek mandiri)
for st in sorted(ROOT.rglob("settings.gradle.kts")):
    if any(x in st.parts for x in (".git", ".gradle", "build", "node_modules", ".idea")):
        continue
    rel = st.relative_to(ROOT).as_posix()
    stext = COMMENT.sub("", st.read_text(encoding="utf-8"))
    if "repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)" not in stext:
        errs.append("%s: wajib repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)" % rel)
    for m in re.finditer(r"jitpack\.io", stext):
        i = stext[:m.start()].rfind("exclusiveContent")
        tail = stext[m.end():m.end() + 300]
        scoped = ('includeGroup("com.github.' in tail) or ('includeGroupByRegex("com\\\\.github' in tail)
        if i < 0 or m.start() - i > 200 or not scoped:
            errs.append("%s: JitPack hanya boleh lewat exclusiveContent dengan filter grup com.github.*" % rel)

for k, alias in keys.items():
    if k not in used:
        warns.append("alias tidak dipakai modul mana pun: %s" % alias)

gen = subprocess.run([sys.executable, str(ROOT / "scripts" / "gen_catalog_versions.py"), "--check"], capture_output=True, text=True)
if gen.returncode != 0:
    errs.append((gen.stderr or gen.stdout).strip().removeprefix("GAGAL: "))

for w in warns:
    print("PERINGATAN:", w)
if errs:
    for e in errs:
        print("GAGAL:", e)
    print("CATALOG GATE GAGAL (%d alias, %d build file)" % (len(libs), len(files)))
    sys.exit(1)
print("CATALOG GATE OK (%d alias, %d build file)" % (len(libs), len(files)))
