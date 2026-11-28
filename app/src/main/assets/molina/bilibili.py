#!/usr/bin/env python3
import base64
import json
import os
import re
import shutil
import subprocess
import sys
import time
from html import unescape

import requests

BASE_URL = "https://www.bilibili.tv"
OUTPUT_DIR = "/sdcard/Molina/Scripts"
URL_FILE = os.path.join(OUTPUT_DIR, "last_url.txt")
TIMEOUT = 15
_WARMED = False

SESSION = requests.Session()
SESSION.headers.update({
    "User-Agent": (
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
        "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    ),
    "Accept-Language": "id-ID,id;q=0.9,en;q=0.8",
})


def request_json(url, params=None):
    r = SESSION.get(url, params=params, timeout=TIMEOUT)
    r.raise_for_status()
    return r.json()


def warm_up(force=False):
    global _WARMED
    if _WARMED and not force:
        return
    SESSION.headers["Referer"] = "https://www.bilibili.com/"
    try:
        SESSION.get("https://www.bilibili.com", timeout=TIMEOUT)
        _WARMED = True
    except requests.RequestException:
        pass


def api_get(url, params=None, retries=2):
    warm_up()
    data = {}
    for i in range(retries + 1):
        data = request_json(url, params)
        if data.get("code") == -412 and i < retries:
            time.sleep(1.5 * (i + 1))
            warm_up(True)
            continue
        break
    return data


def clean_title(text):
    return unescape(re.sub(r"<[^>]+>", "", text)).strip()


def search_videos(keyword, limit=20):
    data = api_get(
        "https://api.bilibili.com/x/web-interface/search/type",
        {
            "search_type": "video",
            "keyword": keyword,
            "page": 1,
            "page_size": min(limit, 50),
        },
    )
    if data.get("code") != 0:
        raise RuntimeError(f"API pencarian gagal: {data.get('message')}")
    results = data.get("data", {}).get("result", []) or []
    videos = []
    for it in results[:limit]:
        bvid = it.get("bvid")
        url = (
            f"https://www.bilibili.com/video/{bvid}"
            if bvid else it.get("arcurl", "")
        )
        if url:
            videos.append({
                "title": clean_title(it.get("title", "(Tanpa judul)")),
                "author": it.get("author", "Unknown"),
                "plays": it.get("play", 0),
                "duration": it.get("duration", ""),
                "url": url,
            })
    return videos


def get_popular(limit=20):
    data = api_get(
        "https://api.bilibili.com/x/web-interface/popular",
        {"ps": min(limit, 50), "pn": 1},
    )
    if data.get("code") != 0:
        raise RuntimeError(f"API populer gagal: {data.get('message')}")
    items = data.get("data", {}).get("list", []) or []
    videos = []
    for it in items[:limit]:
        bvid = it.get("bvid")
        if not bvid:
            continue
        videos.append({
            "title": clean_title(it.get("title", "(Tanpa judul)")),
            "author": (it.get("owner") or {}).get("name", "-"),
            "plays": (it.get("stat") or {}).get("view", "-"),
            "duration": it.get("duration", ""),
            "url": f"https://www.bilibili.com/video/{bvid}",
        })
    return videos


def extract_media(url):
    cmd = ["yt-dlp", "--no-warnings",
           "--no-playlist", "--get-url", url]
    r = subprocess.run(cmd, capture_output=True, text=True, timeout=90)
    if r.returncode != 0:
        raise RuntimeError(r.stderr.strip() or "Ekstraksi media gagal.")
    urls = [x.strip() for x in r.stdout.splitlines() if x.strip()]
    if not urls:
        raise RuntimeError("URL media tidak ditemukan.")
    return urls


def save_url_fallback(text):
    try:
        os.makedirs(OUTPUT_DIR, exist_ok=True)
        with open(URL_FILE, "w", encoding="utf-8") as f:
            f.write(text + "\n")
        print(f"URL disimpan ke: {URL_FILE}")
    except OSError as exc:
        print(f"Gagal menyimpan file: {exc}")
    try:
        b64 = base64.b64encode(text.encode()).decode()
        sys.stdout.write(f"\033]52;c;{b64}\a")
        sys.stdout.flush()
    except Exception:
        pass


def copy_text(text):
    cands = [
        ("wl-copy",),
        ("xclip", "-selection", "clipboard"),
        ("xsel", "--clipboard", "--input"),
        ("termux-clipboard-set",),
    ]
    for c in cands:
        if shutil.which(c[0]):
            try:
                subprocess.run(list(c), input=text, text=True, check=True,
                               stdout=subprocess.DEVNULL,
                               stderr=subprocess.DEVNULL)
                print("URL disalin ke clipboard.")
                return True
            except subprocess.SubprocessError:
                pass
    save_url_fallback(text)
    return False


def play_video(url):
    if not shutil.which("mpv"):
        raise RuntimeError("MPV tidak ditemukan.")
    subprocess.run(["mpv", "--really-quiet", url], check=False)


def print_videos(videos):
    if not videos:
        print("Tidak ada video ditemukan.")
        return
    for i, v in enumerate(videos, start=1):
        print(f"\n{i:2}. {v['title']}")
        print(f"    Kreator : {v.get('author', '-')}")
        print(f"    Ditonton: {v.get('plays', '-')}")
        print(f"    URL     : {v['url']}")


def choose_video(videos):
    if not videos:
        print("Tidak ada video ditemukan.")
        return None
    print_videos(videos)
    while True:
        c = input("\nNomor video (Enter = kembali): ").strip()
        if not c:
            return None
        if c.isdigit() and 1 <= int(c) <= len(videos):
            return videos[int(c) - 1]
        print("Nomor tidak valid.")


def video_actions(video):
    while True:
        print(f"\nVideo: {video['title']}")
        print("1. Tampilkan URL halaman")
        print("2. Ekstrak URL media")
        print("3. Putar dengan MPV")
        print("4. Salin URL halaman")
        print("5. Kembali")
        c = input("> ").strip()
        try:
            if c == "1":
                print(video["url"])
            elif c == "2":
                urls = extract_media(video["url"])
                for i, u in enumerate(urls, start=1):
                    print(f"\nURL media {i}:\n{u}")
                if len(urls) == 1:
                    copy_text(urls[0])
            elif c == "3":
                play_video(video["url"])
            elif c == "4":
                copy_text(video["url"])
            elif c == "5":
                return
            else:
                print("Pilihan tidak valid.")
        except (RuntimeError, requests.RequestException,
                subprocess.TimeoutExpired) as exc:
            print(f"Gagal: {exc}")


# --- MOLINA PATCH v2: filter + terjemah ---
from concurrent.futures import ThreadPoolExecutor

TRANSLATE = True
_TR_CACHE = {}
ORDERS = {
    "1": ("totalrank", "Paling relevan"),
    "2": ("click", "Paling banyak ditonton"),
    "3": ("pubdate", "Terbaru"),
    "4": ("dm", "Terbanyak komentar"),
    "5": ("stow", "Terbanyak favorit"),
}
DURATIONS = {
    "1": (0, "Semua durasi"),
    "2": (1, "Kurang dari 10 menit"),
    "3": (2, "10 - 30 menit"),
    "4": (3, "30 - 60 menit"),
    "5": (4, "Lebih dari 60 menit"),
}


def has_cjk(t):
    return re.search(r"[\u3040-\u30ff\u3400-\u9fff\uac00-\ud7af]", t) is not None


def translate_text(text):
    if text in _TR_CACHE:
        return _TR_CACHE[text]
    out = text
    try:
        r = requests.get(
            "https://translate.googleapis.com/translate_a/single",
            params={"client": "gtx", "sl": "auto", "tl": "id",
                    "dt": "t", "q": text},
            timeout=8,
        )
        r.raise_for_status()
        out = "".join(p[0] for p in r.json()[0] if p and p[0]) or text
    except Exception:
        pass
    _TR_CACHE[text] = out
    return out


def localize(videos):
    if not TRANSLATE:
        return videos
    todo = [v for v in videos if has_cjk(v["title"])]
    if not todo:
        return videos
    with ThreadPoolExecutor(max_workers=8) as ex:
        res = list(ex.map(translate_text, [v["title"] for v in todo]))
    for v, t in zip(todo, res):
        v["title_asli"] = v["title"]
        v["title"] = t
    return videos


def search_videos(keyword, limit=20, order="totalrank", duration=0):
    params = {
        "search_type": "video",
        "keyword": keyword,
        "page": 1,
        "page_size": min(limit, 50),
        "order": order,
    }
    if duration:
        params["duration"] = duration
    data = api_get(
        "https://api.bilibili.com/x/web-interface/search/type", params
    )
    if data.get("code") != 0:
        raise RuntimeError(f"API pencarian gagal: {data.get('message')}")
    results = data.get("data", {}).get("result", []) or []
    videos = []
    for it in results[:limit]:
        bvid = it.get("bvid")
        url = (f"https://www.bilibili.com/video/{bvid}"
               if bvid else it.get("arcurl", ""))
        if url:
            videos.append({
                "title": clean_title(it.get("title", "(Tanpa judul)")),
                "author": it.get("author", "Unknown"),
                "plays": it.get("play", 0),
                "duration": it.get("duration", ""),
                "url": url,
            })
    return localize(videos)


def print_videos(videos):
    if not videos:
        print("Tidak ada video ditemukan.")
        return
    for i, v in enumerate(videos, start=1):
        plays = v.get("plays", "-")
        if isinstance(plays, int):
            plays = f"{plays:,}".replace(",", ".")
        print(f"\n{i:2}. {v['title']}")
        print(f"    Kreator : {v.get('author', '-')}")
        print(f"    Ditonton: {plays}")
        print(f"    URL     : {v['url']}")


def ask_menu(title, options, default):
    print(f"\n{title}")
    for k, (_, label) in options.items():
        print(f"{k}. {label}")
    c = input(f"Pilih [{default}]: ").strip() or default
    return options.get(c, options[default])[0]


def main():
    global TRANSLATE
    while True:
        st = "AKTIF" if TRANSLATE else "MATI"
        print("\n=== BILIBILI CLI ===")
        print("1. Populer (home)")
        print("2. Cari video (filter urutan/durasi)")
        print(f"3. Terjemah judul ke Indonesia: {st}")
        print("4. Keluar")
        c = input("> ").strip()
        try:
            if c == "1":
                sel = choose_video(localize(get_popular(20)))
                if sel:
                    video_actions(sel)
            elif c == "2":
                kw = input("Kata kunci: ").strip()
                if not kw:
                    continue
                t = input("Jumlah hasil [20]: ").strip()
                n = max(1, min(int(t) if t.isdigit() else 20, 50))
                order = ask_menu("Urutkan:", ORDERS, "1")
                dur = ask_menu("Durasi:", DURATIONS, "1")
                print("Mencari...")
                sel = choose_video(search_videos(kw, n, order, dur))
                if sel:
                    video_actions(sel)
            elif c == "3":
                TRANSLATE = not TRANSLATE
                print("Terjemahan:", "AKTIF" if TRANSLATE else "MATI")
            elif c == "4":
                print("Selesai.")
                return
            else:
                print("Pilihan tidak valid.")
        except (RuntimeError, requests.RequestException,
                subprocess.TimeoutExpired, ValueError) as exc:
            print(f"Gagal: {exc}")


# --- MOLINA PATCH v3: durasi ---
def fmt_duration(d):
    if d in (None, "", "-"):
        return "-"
    try:
        if isinstance(d, (int, float)):
            s = int(d)
        else:
            s = 0
            for x in str(d).strip().split(":"):
                s = s * 60 + int(x)
    except ValueError:
        return str(d)
    h, rem = divmod(s, 3600)
    m, sec = divmod(rem, 60)
    if h:
        return f"{h}:{m:02d}:{sec:02d} (±{h} j {m} mnt)"
    return f"{m}:{sec:02d}"


def print_videos(videos):
    if not videos:
        print("Tidak ada video ditemukan.")
        return
    for i, v in enumerate(videos, start=1):
        plays = v.get("plays", "-")
        if isinstance(plays, int):
            plays = f"{plays:,}".replace(",", ".")
        print(f"\n{i:2}. {v['title']}")
        print(f"    Kreator : {v.get('author', '-')}")
        print(f"    Durasi  : {fmt_duration(v.get('duration'))}")
        print(f"    Ditonton: {plays}")
        print(f"    URL     : {v['url']}")


# --- MOLINA PATCH v4: penanda FILM + putar terakhir ---
FILM_MIN = 3600
ONLY_FILM = False


def dur_seconds(d):
    try:
        if isinstance(d, (int, float)):
            return int(d)
        s = 0
        for x in str(d).strip().split(":"):
            s = s * 60 + int(x)
        return s
    except (ValueError, TypeError):
        return 0


def is_film(v):
    return dur_seconds(v.get("duration")) >= FILM_MIN


def film_filter(videos, n):
    if ONLY_FILM:
        videos = [v for v in videos if is_film(v)]
    return videos[:n]


def print_videos(videos):
    if not videos:
        print("Tidak ada video ditemukan.")
        return
    for i, v in enumerate(videos, start=1):
        plays = v.get("plays", "-")
        if isinstance(plays, int):
            plays = f"{plays:,}".replace(",", ".")
        tag = "[FILM] " if is_film(v) else ""
        print(f"\n{i:2}. {tag}{v['title']}")
        print(f"    Kreator : {v.get('author', '-')}")
        print(f"    Durasi  : {fmt_duration(v.get('duration'))}")
        print(f"    Ditonton: {plays}")
        print(f"    URL     : {v['url']}")


def play_last():
    try:
        with open(URL_FILE, encoding="utf-8") as f:
            url = f.read().strip()
    except OSError:
        url = ""
    if not url:
        print("last_url.txt kosong. Salin URL dulu (opsi 4 di menu aksi).")
        return
    if not shutil.which("mpv"):
        raise RuntimeError("MPV tidak ditemukan.")
    print("Memutar:", url)
    subprocess.run(
        ["mpv", "--referrer=https://www.bilibili.com/", url],
        check=False,
    )


def main():
    global TRANSLATE, ONLY_FILM
    while True:
        tr = "AKTIF" if TRANSLATE else "MATI"
        fl = "AKTIF" if ONLY_FILM else "MATI"
        print("\n=== BILIBILI CLI ===")
        print("1. Populer (home)")
        print("2. Cari video (filter urutan/durasi)")
        print(f"3. Terjemah judul ke Indonesia: {tr}")
        print(f"4. Hanya film (>= 60 menit): {fl}")
        print("5. Putar URL terakhir (mpv)")
        print("6. Keluar")
        c = input("> ").strip()
        try:
            if c == "1":
                vids = localize(get_popular(50 if ONLY_FILM else 20))
                sel = choose_video(film_filter(vids, 20))
                if sel:
                    video_actions(sel)
            elif c == "2":
                kw = input("Kata kunci: ").strip()
                if not kw:
                    continue
                t = input("Jumlah hasil [20]: ").strip()
                n = max(1, min(int(t) if t.isdigit() else 20, 50))
                order = ask_menu("Urutkan:", ORDERS, "1")
                dur = ask_menu("Durasi:", DURATIONS, "1")
                print("Mencari...")
                vids = search_videos(kw, 50 if ONLY_FILM else n, order, dur)
                sel = choose_video(film_filter(vids, n))
                if sel:
                    video_actions(sel)
            elif c == "3":
                TRANSLATE = not TRANSLATE
                print("Terjemahan:", "AKTIF" if TRANSLATE else "MATI")
            elif c == "4":
                ONLY_FILM = not ONLY_FILM
                print("Hanya film:", "AKTIF" if ONLY_FILM else "MATI")
            elif c == "5":
                play_last()
            elif c == "6":
                print("Selesai.")
                return
            else:
                print("Pilihan tidak valid.")
        except (RuntimeError, requests.RequestException,
                subprocess.TimeoutExpired, ValueError) as exc:
            print(f"Gagal: {exc}")


# --- MOLINA PATCH v5: URL langsung + Referer ---
def referer_for(url):
    if "bilibili.tv" in url or "biliintl.com" in url:
        return "https://www.bilibili.tv/"
    return "https://www.bilibili.com/"


def video_info(url):
    cmd = ["yt-dlp", "--no-warnings", "--no-playlist", "-J", url]
    r = subprocess.run(cmd, capture_output=True, text=True, timeout=90)
    if r.returncode != 0:
        raise RuntimeError(r.stderr.strip() or "Gagal membaca info.")
    o = json.loads(r.stdout)
    views = o.get("view_count")
    return {
        "title": o.get("title") or "(Tanpa judul)",
        "author": o.get("uploader") or o.get("channel") or "-",
        "plays": views if views is not None else "-",
        "duration": o.get("duration") or "",
        "url": o.get("webpage_url") or url,
        "subs": sorted((o.get("subtitles") or {}).keys()),
    }


def open_url():
    raw = input("URL atau ID video: ").strip()
    if not raw:
        return
    if raw.isdigit():
        raw = f"https://www.bilibili.tv/id/video/{raw}"
    if not raw.startswith(("http://", "https://")):
        raise RuntimeError("URL harus diawali http/https.")
    print("Membaca info...")
    v = video_info(raw)
    localize([v])
    print_videos([v])
    if v["subs"]:
        print("    Subtitle:", ", ".join(v["subs"]))
    video_actions(v)


def play_last():
    try:
        url = open(URL_FILE, encoding="utf-8").read().strip()
    except OSError:
        url = ""
    if not url:
        print("last_url.txt kosong. Salin URL dulu.")
        return
    if not shutil.which("mpv"):
        raise RuntimeError("MPV tidak ditemukan.")
    print("Memutar:", url)
    subprocess.run(
        ["mpv", "--referrer=" + referer_for(url), url], check=False)


def video_actions(video):
    while True:
        print(f"\nVideo: {video['title']}")
        print("1. Tampilkan URL halaman")
        print("2. Ekstrak URL media (butuh Referer)")
        print("3. Putar dengan MPV")
        print("4. Salin URL halaman")
        print("5. Kembali")
        c = input("> ").strip()
        try:
            if c == "1":
                print(video["url"])
            elif c == "2":
                for i, u in enumerate(extract_media(video["url"]), 1):
                    print(f"\nURL media {i}:\n{u}")
                print("\nURL media butuh Referer:",
                      referer_for(video["url"]))
                print("Tanpa itu 403. Di Molina Player tempel URL HALAMAN.")
            elif c == "3":
                play_video(video["url"])
            elif c == "4":
                copy_text(video["url"])
            elif c == "5":
                return
            else:
                print("Pilihan tidak valid.")
        except (RuntimeError, requests.RequestException,
                subprocess.TimeoutExpired) as exc:
            print(f"Gagal: {exc}")


def main():
    global TRANSLATE, ONLY_FILM
    while True:
        tr = "AKTIF" if TRANSLATE else "MATI"
        fl = "AKTIF" if ONLY_FILM else "MATI"
        print("\n=== BILIBILI CLI ===")
        print("1. Populer (home)")
        print("2. Cari video (filter urutan/durasi)")
        print(f"3. Terjemah judul ke Indonesia: {tr}")
        print(f"4. Hanya film (>= 60 menit): {fl}")
        print("5. Putar URL terakhir (mpv)")
        print("6. Buka dari URL/ID (bilibili.tv/.com)")
        print("7. Keluar")
        c = input("> ").strip()
        try:
            if c == "1":
                vids = localize(get_popular(50 if ONLY_FILM else 20))
                sel = choose_video(film_filter(vids, 20))
                if sel:
                    video_actions(sel)
            elif c == "2":
                kw = input("Kata kunci: ").strip()
                if not kw:
                    continue
                t = input("Jumlah hasil [20]: ").strip()
                n = max(1, min(int(t) if t.isdigit() else 20, 50))
                order = ask_menu("Urutkan:", ORDERS, "1")
                dur = ask_menu("Durasi:", DURATIONS, "1")
                print("Mencari...")
                vids = search_videos(kw, 50 if ONLY_FILM else n, order, dur)
                sel = choose_video(film_filter(vids, n))
                if sel:
                    video_actions(sel)
            elif c == "3":
                TRANSLATE = not TRANSLATE
                print("Terjemahan:", "AKTIF" if TRANSLATE else "MATI")
            elif c == "4":
                ONLY_FILM = not ONLY_FILM
                print("Hanya film:", "AKTIF" if ONLY_FILM else "MATI")
            elif c == "5":
                play_last()
            elif c == "6":
                open_url()
            elif c == "7":
                print("Selesai.")
                return
            else:
                print("Pilihan tidak valid.")
        except (RuntimeError, requests.RequestException,
                subprocess.TimeoutExpired, ValueError) as exc:
            print(f"Gagal: {exc}")


# --- MOLINA PATCH v6: M3U pakai URL halaman + UA/Referer ---
UA = ("Mozilla/5.0 (Linux; Android 8.0.0) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
M3U_FILE = "/sdcard/Molina/Media/IPTV/bilibili.m3u"  # v6b: folder IPTV, otomatis terbaca Cinema


def is_cdn_url(u):
    m = re.match(r"https?://([^/:?#]+)", u.strip())
    h = m.group(1).lower() if m else ""
    return h.startswith("upos-") or h.endswith(".bilivideo.com") or h.endswith(".bilivideo.cn")


def add_to_m3u(video):
    url = video["url"].strip()
    if is_cdn_url(url):
        print("Ditolak: itu URL CDN (kedaluwarsa/403). Pakai URL halaman.")
        return False
    title = re.sub(r"[\r\n]+", " ", video.get("title", "")).strip() or url
    os.makedirs(os.path.dirname(M3U_FILE), exist_ok=True)
    cur = ""
    if os.path.isfile(M3U_FILE):
        cur = open(M3U_FILE, encoding="utf-8").read()
    if url in [x.strip() for x in cur.splitlines()]:
        print("Sudah ada di", M3U_FILE)
        return False
    with open(M3U_FILE, "a", encoding="utf-8") as f:
        if not cur.strip():
            f.write("#EXTM3U\n")
        elif not cur.endswith("\n"):
            f.write("\n")
        f.write(f"#EXTINF:-1,{title}\n{url}\n")
    print("Ditambahkan ke", M3U_FILE)
    return True


def play_last():
    try:
        url = open(URL_FILE, encoding="utf-8").read().strip()
    except OSError:
        url = ""
    if not url:
        print("last_url.txt kosong. Salin URL dulu.")
        return
    if not shutil.which("mpv"):
        raise RuntimeError("MPV tidak ditemukan.")
    if is_cdn_url(url):
        print("Peringatan: URL CDN sementara; bila 403, pakai URL halaman.")
    print("Memutar:", url)
    subprocess.run(["mpv", "--user-agent=" + UA,
                    "--referrer=" + referer_for(url), url], check=False)


def video_actions(video):
    while True:
        print(f"\nVideo: {video['title']}")
        print("1. Tampilkan URL halaman")
        print("2. Ekstrak URL media (info saja, sementara)")
        print("3. Putar dengan MPV")
        print("4. Salin URL halaman")
        print("5. Tambah ke M3U Molina (URL halaman)")
        print("6. Kembali")
        c = input("> ").strip()
        try:
            if c == "1":
                print(video["url"])
            elif c == "2":
                for i, u in enumerate(extract_media(video["url"]), 1):
                    print(f"\nURL media {i}:\n{u}")
                print("\nURL ini kedaluwarsa dan butuh UA + Referer:",
                      referer_for(video["url"]))
                print("JANGAN ditempel ke Molina Player/M3U (403). Pakai opsi 4/5.")
            elif c == "3":
                play_video(video["url"])
            elif c == "4":
                copy_text(video["url"])
            elif c == "5":
                add_to_m3u(video)
            elif c == "6":
                return
            else:
                print("Pilihan tidak valid.")
        except (RuntimeError, requests.RequestException,
                subprocess.TimeoutExpired) as exc:
            print(f"Gagal: {exc}")


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print("\nDihentikan.")

