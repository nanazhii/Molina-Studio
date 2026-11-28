#!/usr/bin/env python3
"""Molina Bilibili CLI.

Sumber bawaan: Bilibili Global (bilibili.tv/id, katalog Indonesia).
Sumber cadangan: bilibili.com (China), ganti lewat menu 3.
"""
import base64
import json
import os
import re
import shutil
import subprocess
import sys
import time
from concurrent.futures import ThreadPoolExecutor
from html import unescape

import requests

OUTPUT_DIR = "/sdcard/Molina/Scripts"
URL_FILE = os.path.join(OUTPUT_DIR, "last_url.txt")
DEBUG_FILE = os.path.join(OUTPUT_DIR, "last_search.json")
M3U_FILE = "/sdcard/Molina/Media/IPTV/bilibili.m3u"  # folder IPTV: otomatis terbaca Cinema
M3U_GROUP = "Bilibili"
TIMEOUT = 15

# --- Sumber Global (.tv, Indonesia) ---
TV_ID = "https://www.bilibili.tv/id"
TV_SEARCH = "https://api.bilibili.tv/intl/gateway/web/v2/search_v2"
TV_PARAMS = {"s_locale": "id_ID", "platform": "web"}
POPULAR_KW = "anime"  # endpoint beranda .tv belum terverifikasi: Populer = pencarian kata kunci ini

# --- Sumber China (.com) ---
CN_SEARCH = "https://api.bilibili.com/x/web-interface/search/type"
CN_POPULAR = "https://api.bilibili.com/x/web-interface/popular"

UA = ("Mozilla/5.0 (Linux; Android 8.0.0) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
FILM_MIN = 3600

SOURCE = "tv"        # "tv" = bilibili.tv/id | "com" = bilibili.com
TRANSLATE = False    # hanya berguna untuk sumber .com
ONLY_FILM = False
_WARMED = False
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

SESSION = requests.Session()
SESSION.headers.update({"User-Agent": UA, "Accept-Language": "id-ID,id;q=0.9,en;q=0.8"})


# ---------------------------------------------------------------- umum
def clean_title(text):
    return unescape(re.sub(r"<[^>]+>", "", text)).strip()


def referer_for(url):
    if "bilibili.tv" in url or "biliintl.com" in url:
        return "https://www.bilibili.tv/"
    return "https://www.bilibili.com/"


def is_cdn_url(u):
    m = re.match(r"https?://([^/:?#]+)", u.strip())
    h = m.group(1).lower() if m else ""
    return h.startswith("upos-") or h.endswith(".bilivideo.com") or h.endswith(".bilivideo.cn")


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


def fmt_duration(d):
    if d in (None, "", "-"):
        return "-"
    s = dur_seconds(d)
    if s == 0:
        return str(d)
    h, rem = divmod(s, 3600)
    m, sec = divmod(rem, 60)
    if h:
        return f"{h}:{m:02d}:{sec:02d} (±{h} j {m} mnt)"
    return f"{m}:{sec:02d}"


def is_film(v):
    return dur_seconds(v.get("duration")) >= FILM_MIN


def film_filter(videos, n):
    if ONLY_FILM:
        videos = [v for v in videos if is_film(v)]
    return videos[:n]


# ------------------------------------------------------- sumber China (.com)
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
        r = SESSION.get(url, params=params, timeout=TIMEOUT)
        r.raise_for_status()
        data = r.json()
        if data.get("code") == -412 and i < retries:
            time.sleep(1.5 * (i + 1))
            warm_up(True)
            continue
        break
    return data


def search_cn(keyword, limit=20, order="totalrank", duration=0):
    params = {"search_type": "video", "keyword": keyword, "page": 1,
              "page_size": min(limit, 50), "order": order}
    if duration:
        params["duration"] = duration
    data = api_get(CN_SEARCH, params)
    if data.get("code") != 0:
        raise RuntimeError(f"API pencarian .com gagal: {data.get('message')}")
    videos = []
    for it in (data.get("data", {}).get("result", []) or [])[:limit]:
        bvid = it.get("bvid")
        url = f"https://www.bilibili.com/video/{bvid}" if bvid else it.get("arcurl", "")
        if url:
            videos.append({
                "title": clean_title(it.get("title", "(Tanpa judul)")),
                "author": it.get("author", "Unknown"),
                "plays": it.get("play", 0),
                "duration": it.get("duration", ""),
                "url": url,
                "cover": ("https:" + it["pic"]) if str(it.get("pic", "")).startswith("//") else "",
            })
    return videos


def popular_cn(limit=20):
    data = api_get(CN_POPULAR, {"ps": min(limit, 50), "pn": 1})
    if data.get("code") != 0:
        raise RuntimeError(f"API populer .com gagal: {data.get('message')}")
    videos = []
    for it in (data.get("data", {}).get("list", []) or [])[:limit]:
        bvid = it.get("bvid")
        if bvid:
            videos.append({
                "title": clean_title(it.get("title", "(Tanpa judul)")),
                "author": (it.get("owner") or {}).get("name", "-"),
                "plays": (it.get("stat") or {}).get("view", "-"),
                "duration": it.get("duration", ""),
                "url": f"https://www.bilibili.com/video/{bvid}",
                "cover": it.get("pic", "") if str(it.get("pic", "")).startswith("https://") else "",
            })
    return videos


def has_cjk(t):
    return re.search(r"[\u3040-\u30ff\u3400-\u9fff\uac00-\ud7af]", t) is not None


def translate_text(text):
    if text in _TR_CACHE:
        return _TR_CACHE[text]
    out = text
    try:
        r = requests.get(
            "https://translate.googleapis.com/translate_a/single",
            params={"client": "gtx", "sl": "auto", "tl": "id", "dt": "t", "q": text},
            timeout=8)
        r.raise_for_status()
        out = "".join(p[0] for p in r.json()[0] if p and p[0]) or text
    except Exception:
        pass
    _TR_CACHE[text] = out
    return out


def localize(videos):
    if not TRANSLATE or SOURCE != "com":
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


# ------------------------------------------------------ sumber Global (.tv)
def _walk(o):
    if isinstance(o, dict):
        yield o
        for v in o.values():
            yield from _walk(v)
    elif isinstance(o, list):
        for v in o:
            yield from _walk(v)


def _tv_item(d):
    title = d.get("title")
    if not isinstance(title, str) or not title.strip():
        return None
    aid, sid = d.get("aid"), d.get("season_id")
    if aid and str(aid).isdigit():
        url = f"{TV_ID}/video/{aid}"
    elif sid and str(sid).isdigit():
        url = f"{TV_ID}/play/{sid}"
    else:
        return None
    au = d.get("author")
    if isinstance(au, dict):
        au = au.get("nickname") or au.get("name")
    return {
        "title": clean_title(title),
        "author": au or "-",
        "plays": d.get("view") or "-",
        "duration": d.get("duration") or "",
        "url": url,
        "cover": d.get("cover") or "",
    }


def search_tv(keyword, limit=20):
    seen, out = set(), []
    for pn in range(1, 4):
        r = SESSION.get(
            TV_SEARCH,
            params=dict(TV_PARAMS, keyword=keyword, pn=pn, ps=20),
            headers={"Referer": "https://www.bilibili.tv/"},
            timeout=TIMEOUT)
        r.raise_for_status()
        data = r.json()
        if pn == 1:
            try:
                os.makedirs(OUTPUT_DIR, exist_ok=True)
                with open(DEBUG_FILE, "w", encoding="utf-8") as f:
                    json.dump(data, f, ensure_ascii=False, indent=1)
            except OSError:
                pass
        if data.get("code") != 0:
            if pn == 1:
                raise RuntimeError(f"API .tv gagal: {data.get('message')}")
            break
        added = 0
        for d in _walk(data.get("data") or {}):
            it = _tv_item(d)
            if it and it["url"] not in seen:
                seen.add(it["url"])
                out.append(it)
                added += 1
        if len(out) >= limit or not added:
            break
    if not out:
        raise RuntimeError("Tidak ada hasil untuk kata kunci itu.")
    return out[:limit]


# ------------------------------------------------------------ pencarian
def search_videos(keyword, limit=20, order="totalrank", duration=0):
    if SOURCE == "tv":
        return search_tv(keyword, limit)
    return localize(search_cn(keyword, limit, order, duration))


def get_popular(limit=20):
    if SOURCE == "tv":
        return search_tv(POPULAR_KW, limit)
    return localize(popular_cn(limit))


# ------------------------------------------------- yt-dlp, salin, mpv, M3U
def extract_media(url):
    cmd = ["yt-dlp", "--no-warnings", "--no-playlist", "--get-url", url]
    r = subprocess.run(cmd, capture_output=True, text=True, timeout=90)
    if r.returncode != 0:
        raise RuntimeError(r.stderr.strip() or "Ekstraksi media gagal.")
    urls = [x.strip() for x in r.stdout.splitlines() if x.strip()]
    if not urls:
        raise RuntimeError("URL media tidak ditemukan.")
    return urls


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
        "cover": o.get("thumbnail") or "",
        "subs": sorted((o.get("subtitles") or {}).keys()),
    }


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
    cands = [("wl-copy",), ("xclip", "-selection", "clipboard"),
             ("xsel", "--clipboard", "--input"), ("termux-clipboard-set",)]
    for c in cands:
        if shutil.which(c[0]):
            try:
                subprocess.run(list(c), input=text, text=True, check=True,
                               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                print("URL disalin ke clipboard.")
                save_url_fallback(text)  # tetap simpan agar "Putar URL terakhir" berfungsi
                return True
            except subprocess.SubprocessError:
                pass
    save_url_fallback(text)
    return False


def mpv_play(url):
    if not shutil.which("mpv"):
        raise RuntimeError("MPV tidak ditemukan.")
    if is_cdn_url(url):
        print("Peringatan: URL CDN sementara; bila 403, pakai URL halaman.")
    subprocess.run(["mpv", "--user-agent=" + UA,
                    "--referrer=" + referer_for(url), url], check=False)


def play_video(url):
    mpv_play(url)


def play_last():
    try:
        url = open(URL_FILE, encoding="utf-8").read().strip()
    except OSError:
        url = ""
    if not url:
        print("last_url.txt kosong. Salin URL dulu.")
        return
    print("Memutar:", url)
    mpv_play(url)


def add_to_m3u(video):
    url = video["url"].strip()
    if is_cdn_url(url):
        print("Ditolak: URL CDN (kedaluwarsa/403). Pakai URL halaman.")
        return False
    title = re.sub(r"[\r\n]+", " ", video.get("title", "")).strip() or url
    cover = (video.get("cover") or "").strip()
    attrs = f' group-title="{M3U_GROUP}"'
    if cover.startswith("https://") and '"' not in cover:
        attrs = f' tvg-logo="{cover}"' + attrs
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
        f.write(f"#EXTINF:-1{attrs},{title}\n{url}\n")
    print("Ditambahkan ke", M3U_FILE)
    return True


# ------------------------------------------------------------------ UI
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


def ask_menu(title, options, default):
    print(f"\n{title}")
    for k, (_, label) in options.items():
        print(f"{k}. {label}")
    c = input(f"Pilih [{default}]: ").strip() or default
    return options.get(c, options[default])[0]


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


def open_url():
    raw = input("URL atau ID video: ").strip()
    if not raw:
        return
    if raw.isdigit():
        raw = f"{TV_ID}/video/{raw}"
    if not raw.startswith(("http://", "https://")):
        raise RuntimeError("URL harus diawali http/https.")
    print("Membaca info...")
    v = video_info(raw)
    localize([v])
    print_videos([v])
    if v["subs"]:
        print("    Subtitle:", ", ".join(v["subs"]))
    video_actions(v)


def main():
    global TRANSLATE, ONLY_FILM, SOURCE
    while True:
        src = "GLOBAL .tv (Indonesia)" if SOURCE == "tv" else "CHINA .com"
        tr = "AKTIF" if TRANSLATE else "MATI"
        fl = "AKTIF" if ONLY_FILM else "MATI"
        print("\n=== BILIBILI CLI ===")
        print(f"Sumber: {src}")
        print("1. Populer")
        print("2. Cari video")
        print("3. Ganti sumber (.tv / .com)")
        print(f"4. Terjemah judul (hanya .com): {tr}")
        print(f"5. Hanya film (>= 60 menit): {fl}")
        print("6. Putar URL terakhir (mpv)")
        print("7. Buka dari URL/ID")
        print("8. Keluar")
        c = input("> ").strip()
        try:
            if c == "1":
                vids = get_popular(50 if ONLY_FILM else 20)
                sel = choose_video(film_filter(vids, 20))
                if sel:
                    video_actions(sel)
            elif c == "2":
                kw = input("Kata kunci: ").strip()
                if not kw:
                    continue
                t = input("Jumlah hasil [20]: ").strip()
                n = max(1, min(int(t) if t.isdigit() else 20, 50))
                order, dur = "totalrank", 0
                if SOURCE == "com":
                    order = ask_menu("Urutkan:", ORDERS, "1")
                    dur = ask_menu("Durasi:", DURATIONS, "1")
                print("Mencari...")
                vids = search_videos(kw, 50 if ONLY_FILM else n, order, dur)
                sel = choose_video(film_filter(vids, n))
                if sel:
                    video_actions(sel)
            elif c == "3":
                SOURCE = "com" if SOURCE == "tv" else "tv"
                print("Sumber:", SOURCE)
            elif c == "4":
                TRANSLATE = not TRANSLATE
                print("Terjemahan:", "AKTIF" if TRANSLATE else "MATI")
            elif c == "5":
                ONLY_FILM = not ONLY_FILM
                print("Hanya film:", "AKTIF" if ONLY_FILM else "MATI")
            elif c == "6":
                play_last()
            elif c == "7":
                open_url()
            elif c == "8":
                print("Selesai.")
                return
            else:
                print("Pilihan tidak valid.")
        except (RuntimeError, requests.RequestException,
                subprocess.TimeoutExpired, ValueError) as exc:
            print(f"Gagal: {exc}")


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print("\nDihentikan.")
