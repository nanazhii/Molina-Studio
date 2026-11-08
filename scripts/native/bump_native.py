#!/usr/bin/env python3
"""Naikkan pin pustaka native libmpv/libplayer sekali jalan [NATIVE-BUMP].

Pin ada di native/prebuilt.env (NATIVE_TAG + NATIVE_SHA256) dan menunjuk Release repo ini yang
dibuat workflow native-mpv.yml. Skrip ini: mengunduh tgz Release, MENGHITUNG SHA-256 sendiri,
mencocokkannya dengan SHA256SUMS Release, memeriksa isi tgz (hanya jniLibs/arm64-v8a/*.so, tanpa
symlink, tiga .so wajib ada), menampilkan diff, menulis ulang prebuilt.env, mencatat progres.md,
lalu menjalankan telemetry_gate + bundle_gate (gagal -> semua dikembalikan).

Catatan jujur: SHA256SUMS berasal dari Release yang sama, jadi pencocokan itu menangkap unduhan
rusak/tidak konsisten, BUKAN Release yang sengaja dipalsukan. Titik tinjau manusia = diff di PR.
Pin hash tarball sumber (native/pins.sha256) berubah hanya saat scripts/native_mpv.sh berubah;
bukan bagian dari alat ini.

Pemakaian:
  python3 scripts/native/bump_native.py                       # Release native-mpv-* terbaru (butuh gh)
  python3 scripts/native/bump_native.py native-mpv-abc1234-run7 [--dry-run]
  python3 scripts/native/bump_native.py TAG --tgz berkas.tgz --sums SHA256SUMS   # tanpa gh (uji/offline)
"""
import argparse, hashlib, json, os, re, subprocess, sys, tarfile, tempfile

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import pinlib
from pinlib import rd, die

ENV = "native/prebuilt.env"
PROGRES = "progres.md"
TGZ = "molina-native-arm64.tgz"
TAG_RX = re.compile(r"^native-mpv-([0-9a-f]{7,40})-run(\d+)$")
HEX_RX = re.compile(r"^[0-9a-f]{64}$")
MEMBER_RX = re.compile(r"^jniLibs/(arm64-v8a/([A-Za-z0-9_.+-]+\.so)?)?$")  # sama dengan scripts/fetch_native.sh
REQUIRED = ("libmpv.so", "libplayer.so", "libc++_shared.so")


def run_no(tag): return int(TAG_RX.match(tag).group(2))


def sha_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""): h.update(chunk)
    return h.hexdigest()


def inspect(tgz):
    """-> (sha256_tgz, [(nama, ukuran, sha256)]). Menolak isi yang melanggar aturan fetch_native.sh (lebih ketat: tanpa symlink)."""
    files = []
    with tarfile.open(tgz, "r:gz") as tf:
        for m in tf.getmembers():
            cand = m.name + ("/" if m.isdir() else "")
            if not MEMBER_RX.match(cand):
                die("isi tgz di luar jniLibs/arm64-v8a/*.so: %r" % m.name)
            if not (m.isfile() or m.isdir()):
                die("isi tgz bukan berkas/folder biasa (symlink/perangkat?): %r" % m.name)
            if m.isfile():
                data = tf.extractfile(m).read()
                files.append((os.path.basename(m.name), len(data), hashlib.sha256(data).hexdigest()))
    names = {n for n, _, _ in files}
    missing = [r for r in REQUIRED if r not in names]
    if missing: die("berkas wajib hilang di tgz: " + ", ".join(missing))
    return sha_file(tgz), sorted(files)


def check_sums(sums_text, sha):
    for line in sums_text.splitlines():
        parts = line.split()
        if len(parts) == 2 and parts[1].lstrip("*") == TGZ:
            if not HEX_RX.match(parts[0]): die("SHA256SUMS: hash tidak valid untuk " + TGZ)
            if parts[0] != sha: die("SHA-256 hasil hitung (%s...) != SHA256SUMS Release (%s...)" % (sha[:12], parts[0][:12]))
            return
    die("SHA256SUMS tidak memuat " + TGZ)


def repo_slug(arg):
    if arg: return arg
    if os.environ.get("GITHUB_REPOSITORY"): return os.environ["GITHUB_REPOSITORY"]
    r = subprocess.run(["git", "remote", "get-url", "origin"], cwd=pinlib.ROOT, capture_output=True, text=True)
    m = re.search(r"github\.com[:/]([^/\s]+/[^/\s]+?)(?:\.git)?\s*$", r.stdout)
    if not m: die("repo tidak diketahui; pakai --repo pemilik/nama")
    return m.group(1)


def gh(*args):
    r = subprocess.run(["gh", *args], capture_output=True, text=True)
    if r.returncode != 0: die("gh %s gagal: %s" % (args[0], r.stderr.strip()[:300]))
    return r.stdout


def latest_tag(repo):
    tags = [t["tagName"] for t in json.loads(gh("release", "list", "--repo", repo, "--limit", "50", "--json", "tagName"))]
    ok = [t for t in tags if TAG_RX.match(t)]
    if not ok: die("tidak ada Release native-mpv-* di " + repo)
    return max(ok, key=run_no)


def download(repo, tag, dest):
    gh("release", "download", tag, "--repo", repo, "--pattern", TGZ, "--pattern", "SHA256SUMS",
       "--pattern", "BUILD-INFO.txt", "--dir", dest, "--clobber")
    return (os.path.join(dest, TGZ), os.path.join(dest, "SHA256SUMS"),
            os.path.join(dest, "BUILD-INFO.txt") if os.path.exists(os.path.join(dest, "BUILD-INFO.txt")) else None)


def env_value(text, key):
    m = re.search(r"^%s=(\S*)\s*$" % key, text, re.M)
    return m.group(1) if m else None


def progres_entry(tag, old_tag, files):
    t = "[NATIVE-PIN %s]" % tag
    return t, (
        "\n## 2026-10-31 - Pin pustaka native dinaikkan ke %s %s\n"
        "- [x] chore(native): %s -> %s lewat scripts/native/bump_native.py; native/prebuilt.env diperbarui (SHA-256 dihitung dari tgz Release, dicocokkan dengan SHA256SUMS, isi tgz diperiksa: %d berkas .so)\n"
        "- [x] Dampak: Gradle, AndroidManifest, JNI mpv TIDAK disentuh oleh skrip; yang berubah hanya biner yang diambil fetch_native.sh saat build\n"
        "- [ ] Belum diuji di perangkat: putar video + cek logcat setelah APK baru; tunggu CI (fetch_native.sh memverifikasi ulang hash)\n"
        % (tag, t, old_tag or "(kosong)", tag, len(files)))


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("tag", nargs="?", default="", help="tag Release native-mpv-<commit>-run<N>; kosong = terbaru")
    ap.add_argument("--repo", default="")
    ap.add_argument("--tgz"); ap.add_argument("--sums"); ap.add_argument("--build-info")
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--allow-older", action="store_true")
    ap.add_argument("--skip-gates", action="store_true", help="hanya untuk uji")
    a = ap.parse_args()

    env = rd(ENV)
    old_tag, old_sha = env_value(env, "NATIVE_TAG"), env_value(env, "NATIVE_SHA256")
    if old_tag is None or old_sha is None: die("NATIVE_TAG/NATIVE_SHA256 tidak ditemukan di " + ENV)

    tag = a.tag
    tmp = tempfile.mkdtemp(prefix="bump_native_")
    if a.tgz:
        if not tag: die("mode --tgz wajib menyebut tag")
        if not a.sums: die("mode --tgz wajib menyertakan --sums (pencocokan SHA256SUMS tidak boleh dilewati)")
        tgz, sums, binfo = a.tgz, a.sums, a.build_info
    else:
        repo = repo_slug(a.repo)
        if not tag: tag = latest_tag(repo); print("Release terbaru:", tag)
        if not TAG_RX.match(tag): die("format tag salah: %r (harap native-mpv-<commit>-run<N>)" % tag)
        tgz, sums, binfo = download(repo, tag, tmp)
    if not TAG_RX.match(tag): die("format tag salah: %r (harap native-mpv-<commit>-run<N>)" % tag)

    if TAG_RX.match(old_tag or "") and run_no(tag) < run_no(old_tag) and not a.allow_older:
        die("%s lebih lama dari %s; tambahkan --allow-older bila disengaja" % (tag, old_tag))

    sha, files = inspect(tgz)
    with open(sums, encoding="utf-8") as f: check_sums(f.read(), sha)
    if tag == old_tag and sha != old_sha:
        die("TAG SAMA TAPI HASH BEDA (%s... -> %s...): Release tidak boleh diubah diam-diam; tinjau manual." % (old_sha[:12], sha[:12]))

    print("Pustaka native: %s -> %s" % (old_tag or "(kosong)", tag))
    print("SHA-256 tgz   : %s... -> %s..." % ((old_sha or "")[:16], sha[:16]))
    for n, size, h in files: print("  %-22s %9d B  %s..." % (n, size, h[:16]))
    if binfo and os.path.exists(binfo):
        print("BUILD-INFO (cuplikan):")
        for line in open(binfo, encoding="utf-8", errors="replace").read().splitlines()[:8]: print("  " + line[:120])

    new_env = re.sub(r"^NATIVE_TAG=.*$", "NATIVE_TAG=" + tag, env, count=1, flags=re.M)
    new_env = re.sub(r"^NATIVE_SHA256=.*$", "NATIVE_SHA256=" + sha, new_env, count=1, flags=re.M)
    ptag, entry = progres_entry(tag, old_tag, files)
    changes = {ENV: new_env, PROGRES: pinlib.with_entry(rd(PROGRES), ptag, entry)}

    pending = [k for k, v in changes.items() if rd(k) != v]
    if a.dry_run:
        print("[dry-run] akan menulis: " + ", ".join(pending) if pending else "[dry-run] tidak ada perubahan"); return
    written = pinlib.apply_changes(changes, a.skip_gates)
    if not written: print("Tidak ada perubahan: pin sudah di %s (idempotent)." % tag); return
    print("SELESAI: %s ditulis. Tinjau 'git diff', lalu commit." % ", ".join(written))
    print("Pesan commit: chore(native): naikkan pin ke %s" % tag)


if __name__ == "__main__":
    main()
