# progres.md — Molina-Studio (log estafet)

Dokumen ini ada di repo agar pekerjaan bisa dilanjutkan oleh sesi/AI lain tanpa kehilangan konteks.
Panduan kerja lengkap: HANDOFF.md. Perubahan file ini saja: commit `docs: update progres.md [skip ci]`.

## Tujuan modular (alasan semua langkah ini)
Aplikasi akan terus membesar. Agar mudah dikelola dan dirawat jangka panjang:
- Logic tidak bercampur atau tersebar; satu tanggung jawab = satu modul.
- Antar modul tidak saling bergantung; yang dipakai bersama lewat kontrak (core:api) atau core terpusat.
- Perbaikan di satu modul tidak menyentuh modul lain.
- Ukuran berhasil: modul fitur + core/* disalin ke proyek kosong tetap ter-build dan jalan.
Penjaga otomatis (CI): module_gate, split_package_gate, offline_gate, api_surface_gate, pkg_graph (ratchet), dup_gate (ratchet).

## Peta modul saat ini
core: api (kontrak + LogContract), common, exec, files, log, net, preview, security, storage, ui.
feature: code, mpv, preview. engine: newpipe. Lain: app, vault, terminal-emulator, terminal-view.

## Riwayat
- [x] Fase A: :core:files (FileTypes, NaturalOrder, DirOrder, FolderKinds).
- [ ] Fase B (menunggu CI): :core:net, LogContract ke :core:api, api_surface_gate.

## Utang / catatan
- :core:common masih api(:core:net) sampai Fase C (GuardedStreamResolver, StreamResolver, YtdlpParse).
- Ratchet saat ini: pkg_graph 12 siklus, dup_gate 6/91.

## Berikutnya
- [ ] Fase C: :core:stream (StreamResolver*, StreamQuality, PlaylistPlan, ResumeMark, TvGuide) dan :core:download (Download*, Ytdlp*, BatchPlan, Search*, FolderName, SevenZipCommand).
- [ ] Sisa :core:common kecil (MolinaPaths, ExitHooks, CrashContext, LogRedact, MiniJson, ...); lalu :core:log tidak lagi bergantung ke common.
- [ ] Putus siklus app (terminal<->cinema via MediaGateway, settings via SettingsSection), pecah paket app jadi modul.

<!-- fase-c -->
## 2026-11-20: Fase C (:core:json, :core:stream, :core:download)
- [x] :core:json (MiniJson, JsonCursor); :core:stream (16 berkas: StreamResolver*, StreamQuality, StreamAudioLang, Playlist*, OriginalTitles, RelativeTime, Search*, Ytdlp{Parse,Search,Fetch}, YtdlUrls); :core:download (13 berkas: Download*, BatchPlan, YtdlpCommand, SevenZipCommand, PasswordPolicy).
- [x] :core:common melepas api(:core:net); stream & download masuk FORBIDDEN_PROJECTS offline_gate.
- Keputusan: ResumeMark, TvGuide, FolderName tetap di :core:common (dipakai rak media/ChannelTidy; dipindah akan membuat siklus).
- Berikutnya: pecah sisa :core:common (rak media, sistem, intent), lalu :core:log tidak bergantung ke common.
