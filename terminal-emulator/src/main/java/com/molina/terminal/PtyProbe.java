package com.molina.terminal;

import android.os.Process;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.lang.reflect.Field;

/**
 * [DIAG-PTY] Pemeriksaan kesehatan JNI terminal: membuka satu PTY, menjalankan {@code /system/bin/sh -c "echo ..."},
 * membaca keluarannya dengan batas waktu, lalu membersihkan proses dan file descriptor. Jalur kode yang sama dengan
 * {@link TerminalSession} (createSubprocess, waitFor, close), tanpa emulator. Panggil di luar main thread.
 */
public final class PtyProbe {
    private static final String MARK = "molina-pty-ok";

    public static final class Result {
        public final boolean ok;
        public final String detail;

        Result(boolean ok, String detail) {
            this.ok = ok;
            this.detail = detail;
        }
    }

    private PtyProbe() { }

    private static long ms(long t0) {
        return (System.nanoTime() - t0) / 1000000L;
    }

    public static Result run(long timeoutMs) {
        final long t0 = System.nanoTime();
        int fd;
        int pid;
        try {
            int[] pids = new int[1];
            fd = JNI.createSubprocess("/system/bin/sh", "/",
                    new String[]{"sh", "-c", "echo " + MARK},
                    new String[]{"PATH=/system/bin", "TERM=dumb"},
                    pids, 24, 80);
            pid = pids[0];
        } catch (LinkageError e) {
            return new Result(false, "libmolina_pty tidak termuat (" + e.getClass().getSimpleName() + ")");
        } catch (RuntimeException e) {
            return new Result(false, "membuka PTY gagal: " + e.getClass().getSimpleName());
        }
        if (fd < 0 || pid <= 0) return new Result(false, "membuka PTY gagal (fd/pid tidak valid)");

        final StringBuilder out = new StringBuilder();
        Thread reader = null;
        boolean seen = false;
        boolean timedOut = false;
        try {
            final FileDescriptor wrapped = wrap(fd);
            reader = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        // Sengaja tidak ditutup: fd ditutup sekali lewat JNI.close di bawah.
                        FileInputStream in = new FileInputStream(wrapped);
                        byte[] buf = new byte[256];
                        int n;
                        while ((n = in.read(buf)) > 0) {
                            synchronized (out) {
                                out.append(new String(buf, 0, n, "UTF-8"));
                                if (out.indexOf(MARK) >= 0) return;
                            }
                        }
                    } catch (Exception ignored) {
                        // EOF/EIO saat shell selesai: normal.
                    }
                }
            }, "molina-pty-probe");
            reader.setDaemon(true);
            reader.start();
            try {
                reader.join(timeoutMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            timedOut = reader.isAlive();
            synchronized (out) {
                seen = out.indexOf(MARK) >= 0;
            }
        } catch (Exception e) {
            return cleanup(fd, pid, null, new Result(false, "membaca PTY gagal: " + e.getClass().getSimpleName()));
        }
        Result r;
        if (seen) r = new Result(true, "PTY dan /system/bin/sh berjalan (" + ms(t0) + " ms)");
        else if (timedOut) r = new Result(false, "shell tidak membalas dalam " + (timeoutMs / 1000) + " dtk (dihentikan)");
        else r = new Result(false, "shell keluar tanpa keluaran yang diharapkan");
        return cleanup(fd, pid, reader, r);
    }

    /** Matikan shell (aman walau sudah selesai: zombie belum di-reap), reap, tutup fd satu kali. */
    private static Result cleanup(int fd, int pid, Thread reader, Result r) {
        try {
            Process.killProcess(pid);
        } catch (RuntimeException ignored) { }
        try {
            JNI.waitFor(pid);
        } catch (RuntimeException ignored) { }
        if (reader != null) {
            try {
                reader.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        try {
            JNI.close(fd);
        } catch (RuntimeException ignored) { }
        return r;
    }

    private static FileDescriptor wrap(int fd) throws ReflectiveOperationException {
        FileDescriptor result = new FileDescriptor();
        Field f;
        try {
            f = FileDescriptor.class.getDeclaredField("descriptor");
        } catch (NoSuchFieldException e) {
            f = FileDescriptor.class.getDeclaredField("fd");
        }
        f.setAccessible(true);
        f.set(result, fd);
        return result;
    }
}
