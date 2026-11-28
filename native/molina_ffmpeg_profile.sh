# Profil FFmpeg Molina: PERMUKAAN SERANGAN SEMPIT (daftar putih) + pengerasan binari.
# Di-source oleh buildscripts/scripts/ffmpeg.sh (disisipkan scripts/native_mpv.sh) dan menghasilkan
# array molina_ffmpeg_args yang ditambahkan SETELAH flag upstream.
#
# Komponen yang tidak ada di daftar TIDAK dikompilasi sama sekali, jadi kodenya tidak ada di .so
# dan tidak bisa dieksploitasi lewat berkas atau stream jahat. Menambah format: edit daftar di
# bawah, catat di progres.md, jalankan workflow native-mpv.yml, lalu pin ulang native/prebuilt.env.
# Setelah build, scripts/ffmpeg_components_audit.sh memeriksa hasil configure yang sebenarnya.

# Protokol: hanya yang dipakai Molina (UrlPolicy mengizinkan http, https, rtmp, rtmps, rtsp, berkas lokal).
# httpproxy = tunnel CONNECT https lewat Molina Shield. crypto = AES-128 HLS. rtp+udp = isi rtsp.
molina_protocols=(file http https tcp tls crypto data httpproxy rtmp rtmps rtp udp)

# Demuxer: kontainer media umum, streaming, subtitle teks, dan gambar sampul/animasi.
molina_demuxers=(
  # kontainer video/audio
  matroska mov avi asf flv live_flv mpegts mpegps mpegvideo ogg wav mp3 flac aac ac3 eac3 dts dtshd truehd
  aiff caf ape wv tta dsf amr amrnb amrwb mpc mpc8 rm
  # bitstream mentah
  h264 hevc obu av1 ivf m4v mjpeg mpjpeg
  # streaming
  hls dash rtsp sdp
  # subtitle
  ass srt webvtt microdvd subviewer subviewer1 jacosub mpl2 mpsub pjs realtext sami stl vplayer aqtitle lrc sup
  # gambar
  image2 image2pipe image_jpeg_pipe image_png_pipe image_webp_pipe image_gif_pipe image_bmp_pipe gif apng webp_anim
)

molina_ffmpeg_args=(
  --disable-protocols --disable-demuxers
  --enable-protocol="$(IFS=,; echo "${molina_protocols[*]}")"
  --enable-demuxer="$(IFS=,; echo "${molina_demuxers[*]}")"
  # pengerasan: canary tumpukan, fortify, RELRO penuh, tumpukan tidak bisa dieksekusi
  "--extra-cflags=-fstack-protector-strong -D_FORTIFY_SOURCE=2"
  "--extra-ldflags=-Wl,-z,relro,-z,now,-z,noexecstack"
)
