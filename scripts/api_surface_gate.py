#!/usr/bin/env python3
"""Gerbang permukaan API Molina-Studio: simbol PUBLIK modul kontrak (core/api, core/files) dibekukan
dalam snapshot scripts/api_surface/*.txt. Perubahan kontrak harus sengaja: ubah kode, jalankan
`python3 scripts/api_surface_gate.py --update`, lalu commit snapshot bersama perubahan kodenya.

Cara kerja: pemindai teks Kotlin (tanpa kompilator). Mencatat deklarasi class/interface/object/fun/val/var/
typealias yang tidak private/internal, dengan jalur tipe pembungkusnya, tanpa badan fungsi. Entri enum ikut
dicatat. Batas: isi badan, nilai awal properti, dan visibilitas lewat anotasi tidak dipantau.
Exit 1 bila snapshot berbeda atau tidak ada."""
import difflib
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
SNAP = ROOT / "scripts" / "api_surface"
# modul -> berkas snapshot. Tambah modul kontrak lain di sini secara sadar.
MODULES = {"core/api": "core_api.txt", "core/files": "core_files.txt"}

MODS = (r"public|protected|data|sealed|enum|abstract|open|fun|const|override|suspend|inline|operator|infix|"
        r"lateinit|annotation|value|companion|tailrec|external|expect|actual")
DECL = re.compile(r"^(?P<mods>(?:(?:" + MODS + r")\s+)*)(?P<kw>class|interface|object|fun|val|var|typealias)\b(?P<rest>.*)$")
ANNOT = re.compile(r"^(?:@[\w.]+(?:\([^)]*\))?\s*)+")
OPEN_MAP = {"{": "\u27e6", "}": "\u27e7", "(": "\u2985", ")": "\u2986", "=": "\u2254"}


def clean(src):
    """Buang komentar; isi string dinetralkan agar kurung/kurawal di dalamnya tidak mengacaukan pemindaian."""
    out, i, n = [], 0, len(src)
    while i < n:
        c = src[i]
        if src.startswith("/*", i):
            j = src.find("*/", i + 2)
            i = n if j < 0 else j + 2
            out.append(" ")
        elif src.startswith("//", i):
            j = src.find("\n", i)
            i = n if j < 0 else j
        elif src.startswith('"""', i):
            j = src.find('"""', i + 3)
            j = n if j < 0 else j
            body = src[i + 3:j]
            out.append('"""' + "".join(OPEN_MAP.get(ch, ch) for ch in body).replace("\n", " ") + '"""')
            i = min(n, j + 3)
        elif c == '"' or c == "'":
            j = i + 1
            while j < n and src[j] != c and src[j] != "\n":
                j += 2 if src[j] == "\\" else 1
            body = src[i + 1:j]
            out.append(c + "".join(OPEN_MAP.get(ch, ch) for ch in body) + c)
            i = j + 1
        else:
            out.append(c)
            i += 1
    return "".join(out)


def logical_lines(text):
    buf, depth = "", 0
    for raw in text.split("\n"):
        line = raw.strip()
        if not line and not buf:
            continue
        buf = (buf + " " + line).strip() if buf else line
        depth += line.count("(") - line.count(")")
        if depth <= 0:
            yield buf
            buf, depth = "", 0
    if buf:
        yield buf


def cut(rest):
    depth = 0
    for i, ch in enumerate(rest):
        if ch in "(<[":
            depth += 1
        elif ch in ")>]":
            depth -= 1
        elif depth <= 0 and (ch == "{" or ch == "="):
            return rest[:i].strip()
    return rest.strip()


def scan(path):
    text = clean(path.read_text(encoding="utf-8", errors="ignore"))
    pk = re.search(r"^package\s+([\w.]+)", text, re.M)
    pkg = pk.group(1) if pk else ""
    scopes = []  # tiap scope: dict(kind="type"|"code", name, enum, entries, done)
    out = []

    def visible():
        return all(s["kind"] == "type" for s in scopes)

    def path_of():
        return ".".join(s["name"] for s in scopes if s["name"])

    def emit(sig):
        out.append(f"{pkg}{'.' + path_of() if path_of() else ''}: {sig}")

    for line in logical_lines(text):
        if line.startswith(("package ", "import ")):
            continue
        body = ANNOT.sub("", line).strip()
        top = scopes[-1] if scopes else None
        if top and top["kind"] == "type" and top["enum"] and not top["done"] and visible():
            if not DECL.match(body) and not body.startswith("}"):
                semi = body.find(";")
                part = body if semi < 0 else body[:semi]
                top["entries"].append(part.strip())
                if semi >= 0:
                    top["done"] = True
                body = "" if semi < 0 else body[semi + 1:].strip()
                line = body
            elif DECL.match(body):
                top["done"] = True
        m = DECL.match(body) if body else None
        decl_type = None
        if m and visible():
            mods = " ".join(m.group("mods").split())
            kw = m.group("kw")
            rest = cut(m.group("rest"))
            if kw in ("class", "interface", "object"):
                nm = re.match(r"\s*([A-Za-z_]\w*)", rest)
                name = nm.group(1) if nm else ("Companion" if "companion" in mods.split() else "")
                decl_type = {"kind": "type", "name": name, "enum": "enum" in mods.split(), "entries": [], "done": False}
            emit((mods + " " if mods else "") + kw + " " + rest if rest else (mods + " " if mods else "") + kw)
            if decl_type and decl_type["enum"] and "{" in line and line.rstrip().endswith("}"):
                inner = line[line.index("{") + 1:line.rindex("}")].split(";")[0]
                scopes.append(decl_type)
                emit("enum entries " + " ".join(inner.split()))
                scopes.pop()
                decl_type = None
        elif m:
            kw = m.group("kw")
            if kw in ("class", "interface", "object"):
                decl_type = None
        first_open = True
        for ch in (line if body else ""):
            if ch == "{":
                if first_open and decl_type is not None:
                    scopes.append(decl_type)
                else:
                    scopes.append({"kind": "code", "name": "", "enum": False, "entries": [], "done": True})
                first_open = False
            elif ch == "}":
                if scopes:
                    s = scopes.pop()
                    if s["kind"] == "type" and s["enum"] and s["entries"]:
                        scopes.append(s)
                        emit("enum entries " + " ".join(" ".join(s["entries"]).split()))
                        scopes.pop()
        # Enum yang ditutup tanpa ';' sudah dicatat di atas; tandai agar tak dobel.
    return out


def surface(module):
    lines = []
    base = ROOT / module
    for p in sorted(base.rglob("*.kt")):
        if "build" in p.relative_to(base).parts:
            continue
        lines += scan(p)
    return sorted(set(lines))


def main():
    update = "--update" in sys.argv
    bad = 0
    SNAP.mkdir(parents=True, exist_ok=True)
    for module, fname in MODULES.items():
        if not (ROOT / module).is_dir():
            print(f"API SURFACE GAGAL: modul {module} tidak ditemukan (perbarui MODULES secara sadar)")
            bad += 1
            continue
        now = "\n".join(surface(module)) + "\n"
        snap = SNAP / fname
        if update:
            if not snap.exists() or snap.read_text(encoding="utf-8") != now:
                snap.write_text(now, encoding="utf-8")
                print(f"[ok] snapshot {fname} diperbarui ({now.count(chr(10))} simbol)")
            else:
                print(f"[skip] snapshot {fname} sudah mutakhir")
            continue
        if not snap.exists():
            print(f"API SURFACE GAGAL: {fname} belum ada. Jalankan: python3 scripts/api_surface_gate.py --update")
            bad += 1
            continue
        old = snap.read_text(encoding="utf-8")
        if old != now:
            bad += 1
            print(f"API SURFACE GAGAL: kontrak {module} berubah tanpa pembaruan snapshot:")
            for d in difflib.unified_diff(old.splitlines(), now.splitlines(), "snapshot", "kode", lineterm="", n=0):
                if not d.startswith(("---", "+++", "@@")):
                    print("  " + d)
            print("Bila perubahan disengaja: python3 scripts/api_surface_gate.py --update, lalu commit snapshot.")
    if bad:
        sys.exit(1)
    if not update:
        print("API SURFACE GATE OK (" + ", ".join(MODULES) + ")")


if __name__ == "__main__":
    main()
