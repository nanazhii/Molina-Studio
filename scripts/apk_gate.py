#!/usr/bin/env python3
"""[APK-GATE] Verifikasi APK HASIL BUILD (bukan source): izin, komponen exported, pustaka native, host URL di dex.
Fail-closed: bila format keluaran aapt2 tidak dikenali, gerbang GAGAL (bukan lulus diam-diam).
Pakai: apk_gate.py <apk> [--aapt2 PATH] [--update-baseline]"""
import hashlib, re, subprocess, sys, zipfile, os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BASELINE = os.path.join(ROOT, "scripts", "apk_hosts.baseline")
PERM_OK = {
    "android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE",
    "android.permission.READ_EXTERNAL_STORAGE", "android.permission.WRITE_EXTERNAL_STORAGE",
    "android.permission.FOREGROUND_SERVICE", "android.permission.FOREGROUND_SERVICE_DATA_SYNC",
    "android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK", "android.permission.WAKE_LOCK",
    "com.molina.vault.permission.IMPORT",
}
PERM_NOTICE = {"android.permission.RECEIVE_BOOT_COMPLETED"}  # lazimnya dari work-runtime; periksa di run pertama
EXPORTED_OK = {"com.molina.studio.MainActivity", "com.molina.cinema.MPVActivity"}
LIB_DIR = "lib/arm64-v8a/"
LIB_PINNED = {}  # nama -> sha256, diisi dari scripts/proot dan scripts/sevenzip
# Pustaka native tanpa pin per berkas, dikelompokkan menurut asal. Nama baru di APK = GAGAL sampai ditinjau dan didaftarkan.
LIB_PREBUILT = {  # tgz di native/prebuilt.env (SHA-256 tgz dipin; isi diperiksa scripts/fetch_native.sh)
    "libmpv.so", "libplayer.so", "libc++_shared.so", "libavcodec.so", "libavdevice.so", "libavfilter.so",
    "libavformat.so", "libavutil.so", "libswresample.so", "libswscale.so",
}
LIB_BUILT = {"libmolina_pty.so"}  # dibangun NDK dari terminal-emulator/src/main/jni/Android.mk
LIB_ANDROIDX = {"libandroidx.graphics.path.so", "libdatastore_shared_counter.so"}  # AndroidX transitif (Maven)
LIB_OTHER_OK = LIB_PREBUILT | LIB_BUILT | LIB_ANDROIDX
HOST_RE = re.compile(rb"https?://([A-Za-z0-9.-]{3,253})")


def pins():
    out = {}
    for rel, names in (("scripts/proot/pins.sha256", None), ("scripts/sevenzip/pins.sha256", {"lib7zz.so"})):
        p = os.path.join(ROOT, rel)
        if not os.path.isfile(p): continue
        for ln in open(p, encoding="utf-8"):
            ln = ln.strip()
            if ln and not ln.startswith("#"):
                h, _, n = ln.partition(" ")
                if names is None or n.strip() in names: out[n.strip()] = h
    return out


def badging_perms(text):
    return set(re.findall(r"uses-permission(?:-sdk-23)?: name='([^']+)'", text))


def exported_components(xmltree):
    """Kembalikan (daftar komponen exported, jumlah komponen terbaca). Format aapt2 dump xmltree."""
    comps, cur, n = [], None, 0
    for ln in xmltree.splitlines():
        m = re.match(r"\s*E: (activity|activity-alias|service|receiver|provider) ", ln)
        if m:
            cur = {"name": None, "exp": None, "filter": False, "perm": False}; comps.append(cur); n += 1; continue
        if cur is None: continue
        if re.match(r"\s*E: intent-filter", ln): cur["filter"] = True
        if ":name(" in ln and cur["name"] is None:
            mm = re.search(r'="([^"]+)"', ln)
            if mm: cur["name"] = mm.group(1)
        if ":exported(" in ln: cur["exp"] = ln.strip().endswith("=true") or "=(type 0x12)0xffffffff" in ln
        if ":permission(" in ln: cur["perm"] = True
    # komponen dengan intent-filter dan tanpa atribut exported dianggap exported pada targetSdk < 31
    res = [c for c in comps if c["exp"] or (c["exp"] is None and c["filter"])]
    return res, n


def main():
    a = sys.argv[1:]
    if not a: print(__doc__); return 2
    apk = a[0]; upd = "--update-baseline" in a
    aapt2 = a[a.index("--aapt2") + 1] if "--aapt2" in a else "aapt2"
    err, note = [], []
    run = lambda *c: subprocess.run([aapt2, "dump", *c], capture_output=True, text=True, check=True).stdout
    perms = badging_perms(run("badging", apk))
    if not perms: err.append("izin tidak terbaca dari badging (format tidak dikenali)")
    for p in sorted(perms):
        if p in PERM_OK: continue
        if p.endswith(".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION") or p in PERM_NOTICE: note.append("izin transitif: " + p)
        else: err.append("IZIN TAK TERDAFTAR: " + p)
    exp, n = exported_components(run("xmltree", "--file", "AndroidManifest.xml", apk))
    if n == 0: err.append("komponen manifest tidak terbaca (format xmltree tidak dikenali)")
    for c in exp:
        nm = c["name"] or "?"
        if nm in EXPORTED_OK: continue
        if nm.startswith("androidx.") and c["perm"]: note.append("exported androidx berizin: " + nm)
        else: err.append("KOMPONEN EXPORTED TAK TERDAFTAR: " + nm)
    pin = pins()
    with zipfile.ZipFile(apk) as z:
        for i in z.infolist():
            if i.filename.endswith(".so") and not i.filename.startswith(LIB_DIR): err.append("pustaka native di luar arm64-v8a: " + i.filename)
            if i.filename.startswith(LIB_DIR) and i.filename.endswith(".so"):
                nm = i.filename[len(LIB_DIR):]; h = hashlib.sha256(z.read(i)).hexdigest()
                if nm in pin and pin[nm] != h:
                    src = os.path.join(ROOT, "app", "src", "main", "jniLibs", "arm64-v8a", nm); info = ""
                    if os.path.isfile(src):  # diagnosis: bedakan "biner repo salah" dari "AGP mengubah biner"
                        sh = hashlib.sha256(open(src, "rb").read()).hexdigest()
                        info = " (sumber repo %s pin; ukuran APK %d B, sumber %d B)" % ("SAMA dengan" if sh == pin[nm] else "BEDA dari", i.file_size, os.path.getsize(src))
                    err.append("SHA-256 APK != pin: " + nm + info)
                elif nm not in pin and nm not in LIB_OTHER_OK: err.append("PUSTAKA NATIVE TAK TERDAFTAR: " + nm)
            if i.filename.endswith((".apk", ".dex", ".jar")) and not re.match(r"classes\d*\.dex$", i.filename):
                err.append("berkas kode tersembunyi: " + i.filename)
        for nm in pin:
            if (LIB_DIR + nm) not in z.namelist() and nm.startswith("lib"): err.append("pin tanpa pustaka di APK: " + nm)
        hosts = set()
        for i in z.infolist():
            if re.match(r"classes\d*\.dex$", i.filename):
                hosts |= {m.decode().lower().rstrip(".") for m in HOST_RE.findall(z.read(i))}
    if upd:
        open(BASELINE, "w", encoding="utf-8").write("\n".join(sorted(hosts)) + "\n"); print("baseline host ditulis: %d" % len(hosts))
    elif os.path.isfile(BASELINE):
        base = {l.strip() for l in open(BASELINE, encoding="utf-8") if l.strip()}
        for h in sorted(hosts - base): err.append("HOST BARU DI DEX (tidak ada di baseline): " + h)
    else: err.append("scripts/apk_hosts.baseline tidak ada; jalankan apk_gate.py <apk> --update-baseline di lokal, tinjau isinya, lalu commit")
    for x in note: print("CATATAN:", x)
    for x in err: print("GAGAL:", x)
    print("APK GATE %s (%d izin, %d exported, %d host dex)" % ("GAGAL" if err else "OK", len(perms), len(exp), len(hosts)))
    return 1 if err else 0


if __name__ == "__main__": sys.exit(main())
