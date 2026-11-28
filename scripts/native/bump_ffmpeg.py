#!/usr/bin/env python3
"""Naikkan pin ffmpeg CLI statis (FFmpeg Lite) sekali jalan [FFMPEG-BUMP].

Pin ada di TIGA tempat yang HARUS sama (dijaga scripts/bundle_gate.py):
  native/ffmpeg-cli.env                                       FFMPEG_CLI_TAG + FFMPEG_CLI_SHA256
  app/src/main/java/com/molina/studio/packages/ToolStore.kt   FFMPEG_LITE_TAG + FFMPEG_LITE_SHA256
Skrip ini: mengunduh tar.xz Release ffmpeg-cli-*, MENGHITUNG SHA-256 sendiri, mencocokkannya dengan
SHA256SUMS Release, memeriksa isi (hanya ffmpeg dan ffprobe, berkas biasa, ELF arm64, tanpa symlink),
menampilkan diff, menulis ulang ketiga berkas + progres.md, lalu menjalankan telemetry_gate +
bundle_gate (gagal -> semua dikembalikan).

Catatan jujur: SHA256SUMS berasal dari Release yang sama, jadi pencocokan itu menangkap unduhan
rusak/tidak konsisten, BUKAN Release yang sengaja dipalsukan. Titik tinjau manusia = diff sebelum commit.

Pemakaian:
  python3 scripts/native/bump_ffmpeg.py                        # Release ffmpeg-cli-* terbaru (butuh gh)
  python3 scripts/native/bump_ffmpeg.py ffmpeg-cli-abc1234-run7 [--dry-run]
  python3 scripts/native/bump_ffmpeg.py TAG --tarxz berkas.tar.xz --sums SHA256SUMS   # tanpa gh (uji/offline)
"""
import argparse, datetime, hashlib, json, os, re, subprocess, sys, tarfile, tempfile

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import pinlib
from pinlib import rd, wr, p, die

ENV = "native/ffmpeg-cli.env"
KT = "app/src/main/java/com/molina/studio/packages/ToolStore.kt"
PROGRES = "progres.md"
ASSET = "ffmpeg-molina-arm64.tar.xz"
TAG_RX = re.compile(r"^ffmpeg-cli-([0-9a-f]{7,40})-run(\d+)$")
HEX_RX = re.compile(r"^[0-9a-f]{64}$")
EXPECTED = ("ffmpeg", "ffprobe")
KT_TAG_RX = re.compile(r'(FFMPEG_LITE_TAG = )"[^"]*"')
KT_SHA_RX = re.compile(r'(FFMPEG_LITE_SHA256 = )"[^"]*"')


def run_no(tag): return int(TAG_RX.match(tag).group(2))


def sha_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""): h.update(chunk)
    return h.hexdigest()


def is_elf_arm64(head):
    """ELF 64-bit little-endian, e_machine = EM_AARCH64 (183 = 0xB7) di offset 18."""
    return len(head) >= 20 and head[:4] == b"\x7fELF" and head[4] == 2 and head[5] == 1 and head[18:20] == b"\xb7\x00"


def inspect(tarxz):
    """-> (sha256_arsip, [(nama, ukuran, sha256)]). Hanya ffmpeg + ffprobe, berkas biasa, ELF arm64."""
    files = []
    with tarfile.open(tarxz, "r:xz") as tf:
        for m in tf.getmembers():
            if m.name not in EXPECTED: die("isi arsip di luar {ffmpeg, ffprobe}: %r" % m.name)
            if not m.isfile(): die("isi arsip bukan berkas biasa (symlink/folder/perangkat?): %r" % m.name)
            data = tf.extractfile(m).read()
            if not is_elf_arm64(data): die("%s bukan ELF arm64 (aarch64)" % m.name)
            files.append((m.name, len(data), hashlib.sha256(data).hexdigest()))
    names = [n for n, _, _ in files]
    if sorted(names) != sorted(EXPECTED) or len(names) != len(set(names)):
        die("arsip wajib berisi tepat: " + ", ".join(EXPECTED))
    return sha_file(tarxz), sorted(files)


def check_sums(sums_text, sha):
    for line in sums_text.splitlines():
        parts = line.split()
        if len(parts) == 2 and parts[1].lstrip("*") == ASSET:
            if not HEX_RX.match(parts[0]): die("SHA256SUMS: hash tidak valid untuk " + ASSET)
            if parts[0] != sha: die("SHA-256 hasil hitung (%s...) != SHA256SUMS Release (%s...)" % (sha[:12], parts[0][:12]))
            return
    die("SHA256SUMS tidak memuat " + ASSET)


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
    if not ok: die("tidak ada Release ffmpeg-cli-* di " + repo)
    return max(ok, key=run_no)


def download(repo, tag, dest):
    gh("release", "download", tag, "--repo", repo, "--pattern", ASSET, "--pattern", "SHA256SUMS",
       "--pattern", "BUILD-INFO.txt", "--dir", dest, "--clobber")
    bi = os.path.join(dest, "BUILD-INFO.txt")
    return os.path.join(dest, ASSET), os.path.join(dest, "SHA256SUMS"), bi if os.path.exists(bi) else None


def env_value(text, key):
    m = re.search(r"^%s=(\S*)\s*$" % key, text, re.M)
    return m.group(1) if m else None


def kt_value(text, name):
    m = re.search(r'%s = "([^"]*)"' % name, text)
    return m.group(1) if m else None


def progres_entry(tag, old_tag, files):
    t = "[FFMPEG-PIN %s]" % tag
    return t, (
        "\n## %s - Pin ffmpeg-cli dinaikkan ke %s %s\n"
        "- [x] chore(ffmpeg): %s -> %s lewat scripts/native/bump_ffmpeg.py; native/ffmpeg-cli.env dan ToolStore.kt disamakan "
        "(SHA-256 dihitung dari tar.xz Release, dicocokkan dengan SHA256SUMS, isi diperiksa: %s, ELF arm64)\n"
        "- [x] Dampak: Gradle, AndroidManifest, JNI mpv TIDAK disentuh; yang berubah hanya pin biner FFmpeg Lite di ToolStore\n"
        "- [ ] Belum diuji di perangkat: pasang FFmpeg Lite dari Setelan > Paket > Alat dan jalankan ffmpeg -version; tunggu CI\n"
        % (datetime.date.today().isoformat(), tag, t, old_tag or "(kosong)", tag, ", ".join(n for n, _, _ in files)))


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("tag", nargs="?", default="", help="tag Release ffmpeg-cli-<commit>-run<N>; kosong = terbaru")
    ap.add_argument("--repo", default="")
    ap.add_argument("--tarxz"); ap.add_argument("--sums"); ap.add_argument("--build-info")
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--allow-older", action="store_true")
    ap.add_argument("--skip-gates", action="store_true", help="hanya untuk uji")
    a = ap.parse_args()

    env, kt = rd(ENV), rd(KT)
    old_tag, old_sha = env_value(env, "FFMPEG_CLI_TAG"), env_value(env, "FFMPEG_CLI_SHA256")
    if old_tag is None or old_sha is None: die("FFMPEG_CLI_TAG/FFMPEG_CLI_SHA256 tidak ditemukan di " + ENV)
    if len(KT_TAG_RX.findall(kt)) != 1 or len(KT_SHA_RX.findall(kt)) != 1:
        die("FFMPEG_LITE_TAG/FFMPEG_LITE_SHA256 harus muncul tepat satu kali di " + KT)
    if (kt_value(kt, "FFMPEG_LITE_TAG"), kt_value(kt, "FFMPEG_LITE_SHA256")) != (old_tag, old_sha):
        print("PERINGATAN: pin ToolStore.kt sebelumnya TIDAK sama dengan %s; keduanya akan disamakan." % ENV)

    tag = a.tag
    tmp = tempfile.mkdtemp(prefix="bump_ffmpeg_")
    if a.tarxz:
        if not tag: die("mode --tarxz wajib menyebut tag")
        if not a.sums: die("mode --tarxz wajib menyertakan --sums (pencocokan SHA256SUMS tidak boleh dilewati)")
        arc, sums, binfo = a.tarxz, a.sums, a.build_info
    else:
        repo = repo_slug(a.repo)
        if not tag: tag = latest_tag(repo); print("Release terbaru:", tag)
        if not TAG_RX.match(tag): die("format tag salah: %r (harap ffmpeg-cli-<commit>-run<N>)" % tag)
        arc, sums, binfo = download(repo, tag, tmp)
    if not TAG_RX.match(tag): die("format tag salah: %r (harap ffmpeg-cli-<commit>-run<N>)" % tag)

    if TAG_RX.match(old_tag or "") and run_no(tag) < run_no(old_tag) and not a.allow_older:
        die("%s lebih lama dari %s; tambahkan --allow-older bila disengaja" % (tag, old_tag))

    sha, files = inspect(arc)
    with open(sums, encoding="utf-8") as f: check_sums(f.read(), sha)
    if tag == old_tag and sha != old_sha:
        die("TAG SAMA TAPI HASH BEDA (%s... -> %s...): Release tidak boleh diubah diam-diam; tinjau manual." % (old_sha[:12], sha[:12]))

    print("ffmpeg-cli   : %s -> %s" % (old_tag or "(kosong)", tag))
    print("SHA-256      : %s... -> %s..." % ((old_sha or "")[:16], sha[:16]))
    for n, size, h in files: print("  %-10s %10d B  %s..." % (n, size, h[:16]))
    if binfo and os.path.exists(binfo):
        print("BUILD-INFO (cuplikan):")
        for line in open(binfo, encoding="utf-8", errors="replace").read().splitlines()[:8]: print("  " + line[:120])

    new_env = re.sub(r"^FFMPEG_CLI_TAG=.*$", "FFMPEG_CLI_TAG=" + tag, env, count=1, flags=re.M)
    new_env = re.sub(r"^FFMPEG_CLI_SHA256=.*$", "FFMPEG_CLI_SHA256=" + sha, new_env, count=1, flags=re.M)
    new_kt = KT_TAG_RX.sub(lambda m: '%s"%s"' % (m.group(1), tag), kt, count=1)
    new_kt = KT_SHA_RX.sub(lambda m: '%s"%s"' % (m.group(1), sha), new_kt, count=1)

    have_progres = os.path.isfile(p(PROGRES))  # progres.md di-.gitignore: bisa tidak ada di checkout CI/bersih
    cur_progres = rd(PROGRES) if have_progres else "# Progres Molina-Studio\n"
    ptag, entry = progres_entry(tag, old_tag, files)
    changes = {ENV: new_env, KT: new_kt, PROGRES: pinlib.with_entry(cur_progres, ptag, entry)}

    pending = [k for k, v in changes.items() if (rd(k) if os.path.isfile(p(k)) else None) != v]
    if a.dry_run:
        print("[dry-run] akan menulis: " + ", ".join(pending) if pending else "[dry-run] tidak ada perubahan"); return
    if not have_progres: wr(PROGRES, cur_progres)  # apply_changes membutuhkan berkas ada untuk cadangan rollback
    written = pinlib.apply_changes(changes, a.skip_gates)
    if not written: print("Tidak ada perubahan: pin sudah di %s (idempotent)." % tag); return
    print("SELESAI: %s ditulis. Tinjau 'git diff', lalu commit." % ", ".join(written))
    print("Pesan commit: chore(ffmpeg): naikkan pin ke %s" % tag)


if __name__ == "__main__":
    main()
