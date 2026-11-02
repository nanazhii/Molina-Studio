#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

#define MOLINA_UNUSED(x) x __attribute__((__unused__))
#ifdef __APPLE__
# define LACKS_PTSNAME_R
#endif

static int throw_runtime_exception(JNIEnv* env, char const* message)
{
    /* [PTY-HARDEN] FindClass/ThrowNew tidak boleh dipanggil saat exception sudah pending (mis. OOM). */
    if ((*env)->ExceptionCheck(env)) return -1;
    jclass exClass = (*env)->FindClass(env, "java/lang/RuntimeException");
    if (exClass) (*env)->ThrowNew(env, exClass, message);
    return -1;
}

/* [PTY-HARDEN] Bebaskan array string hasil strdup yang diakhiri NULL (aman untuk NULL). */
static void free_strv(char** v)
{
    if (!v) return;
    for (char** t = v; *t; ++t) free(*t);
    free(v);
}

static int create_subprocess(JNIEnv* env,
        char const* cmd,
        char const* cwd,
        char* const argv[],
        char** envp,
        int* pProcessId,
        jint rows,
        jint columns)
{
    int ptm = open("/dev/ptmx", O_RDWR | O_CLOEXEC);
    if (ptm < 0) return throw_runtime_exception(env, "Cannot open /dev/ptmx");

#ifdef LACKS_PTSNAME_R
    char* devname;
#else
    char devname[64];
#endif
    if (grantpt(ptm) || unlockpt(ptm) ||
#ifdef LACKS_PTSNAME_R
            (devname = ptsname(ptm)) == NULL
#else
            ptsname_r(ptm, devname, sizeof(devname))
#endif
       ) {
        close(ptm);
        return throw_runtime_exception(env, "Cannot grantpt()/unlockpt()/ptsname_r() on /dev/ptmx");
    }

    // Enable UTF-8 mode and disable flow control to prevent Ctrl+S from locking up the display.
    struct termios tios;
    tcgetattr(ptm, &tios);
    tios.c_iflag |= IUTF8;
    tios.c_iflag &= ~(IXON | IXOFF);
    tcsetattr(ptm, TCSANOW, &tios);

    /** Set initial winsize. */
    struct winsize sz = { .ws_row = (unsigned short) rows, .ws_col = (unsigned short) columns };
    ioctl(ptm, TIOCSWINSZ, &sz);

    pid_t pid = fork();
    if (pid < 0) {
        close(ptm);
        return throw_runtime_exception(env, "Fork failed");
    } else if (pid > 0) {
        *pProcessId = (int) pid;
        return ptm;
    } else {
        // Clear signals which the Android java process may have blocked:
        sigset_t signals_to_unblock;
        sigfillset(&signals_to_unblock);
        sigprocmask(SIG_UNBLOCK, &signals_to_unblock, 0);

        close(ptm);
        setsid();

        int pts = open(devname, O_RDWR);
        if (pts < 0) exit(-1);

        dup2(pts, 0);
        dup2(pts, 1);
        dup2(pts, 2);

        DIR* self_dir = opendir("/proc/self/fd");
        if (self_dir != NULL) {
            int self_dir_fd = dirfd(self_dir);
            struct dirent* entry;
            while ((entry = readdir(self_dir)) != NULL) {
                int fd = atoi(entry->d_name);
                if (fd > 2 && fd != self_dir_fd) close(fd);
            }
            closedir(self_dir);
        }

        clearenv();
        if (envp) for (; *envp; ++envp) putenv(*envp);

        if (chdir(cwd) != 0) {
            char* error_message;
            // No need to free asprintf()-allocated memory since doing execvp() or exit() below.
            if (asprintf(&error_message, "chdir(\"%s\")", cwd) == -1) error_message = "chdir()";
            perror(error_message);
            fflush(stderr);
        }
        execvp(cmd, argv);
        // Show terminal output about failing exec() call:
        char* error_message;
        if (asprintf(&error_message, "exec(\"%s\")", cmd) == -1) error_message = "exec()";
        perror(error_message);
        _exit(1);
    }
}

JNIEXPORT jint JNICALL Java_com_molina_terminal_JNI_createSubprocess(
        JNIEnv* env,
        jclass MOLINA_UNUSED(clazz),
        jstring cmd,
        jstring cwd,
        jobjectArray args,
        jobjectArray envVars,
        jintArray processIdArray,
        jint rows,
        jint columns)
{
    jsize size = args ? (*env)->GetArrayLength(env, args) : 0;
    char** argv = NULL;
    if (size > 0) {
        argv = (char**) calloc((size + 1), sizeof(char*));
        if (!argv) return throw_runtime_exception(env, "Couldn't allocate argv array");
        for (int i = 0; i < size; ++i) {
            jstring arg_java_string = (jstring) (*env)->GetObjectArrayElement(env, args, i);
            char const* arg_utf8 = arg_java_string ? (*env)->GetStringUTFChars(env, arg_java_string, NULL) : NULL;
            if (!arg_utf8) { free_strv(argv); return throw_runtime_exception(env, "GetStringUTFChars() failed for argv"); }
            argv[i] = strdup(arg_utf8);
            (*env)->ReleaseStringUTFChars(env, arg_java_string, arg_utf8);
        }
        argv[size] = NULL;
    }

    size = envVars ? (*env)->GetArrayLength(env, envVars) : 0;
    char** envp = NULL;
    if (size > 0) {
        envp = (char**) calloc((size + 1), sizeof(char *));
        if (!envp) { free_strv(argv); return throw_runtime_exception(env, "malloc() for envp array failed"); }
        for (int i = 0; i < size; ++i) {
            jstring env_java_string = (jstring) (*env)->GetObjectArrayElement(env, envVars, i);
            char const* env_utf8 = env_java_string ? (*env)->GetStringUTFChars(env, env_java_string, 0) : NULL;
            if (!env_utf8) { free_strv(argv); free_strv(envp); return throw_runtime_exception(env, "GetStringUTFChars() failed for env"); }
            envp[i] = strdup(env_utf8);
            (*env)->ReleaseStringUTFChars(env, env_java_string, env_utf8);
        }
        envp[size] = NULL;
    }

    int procId = 0;
    char const* cmd_cwd = cwd ? (*env)->GetStringUTFChars(env, cwd, NULL) : NULL;
    char const* cmd_utf8 = cmd ? (*env)->GetStringUTFChars(env, cmd, NULL) : NULL;
    if (!cmd_cwd || !cmd_utf8) {
        if (cmd_cwd) (*env)->ReleaseStringUTFChars(env, cwd, cmd_cwd);
        if (cmd_utf8) (*env)->ReleaseStringUTFChars(env, cmd, cmd_utf8);
        free_strv(argv);
        free_strv(envp);
        return throw_runtime_exception(env, "cmd/cwd null or GetStringUTFChars() failed");
    }
    int ptm = create_subprocess(env, cmd_utf8, cmd_cwd, argv, envp, &procId, rows, columns);
    (*env)->ReleaseStringUTFChars(env, cmd, cmd_utf8);
    (*env)->ReleaseStringUTFChars(env, cwd, cmd_cwd);
    free_strv(argv);
    free_strv(envp);
    /* [PTY-HARDEN] Gagal (ptmx/fork): exception sudah pending, jangan lanjut ke GetPrimitiveArrayCritical. */
    if (ptm < 0 || (*env)->ExceptionCheck(env)) return -1;

    int* pProcId = (int*) (*env)->GetPrimitiveArrayCritical(env, processIdArray, NULL);
    if (!pProcId) return throw_runtime_exception(env, "JNI call GetPrimitiveArrayCritical(processIdArray, &isCopy) failed");

    *pProcId = procId;
    (*env)->ReleasePrimitiveArrayCritical(env, processIdArray, pProcId, 0);

    return ptm;
}

JNIEXPORT void JNICALL Java_com_molina_terminal_JNI_setPtyWindowSize(JNIEnv* MOLINA_UNUSED(env), jclass MOLINA_UNUSED(clazz), jint fd, jint rows, jint cols)
{
    struct winsize sz = { .ws_row = (unsigned short) rows, .ws_col = (unsigned short) cols };
    ioctl(fd, TIOCSWINSZ, &sz);
}

JNIEXPORT void JNICALL Java_com_molina_terminal_JNI_setPtyUTF8Mode(JNIEnv* MOLINA_UNUSED(env), jclass MOLINA_UNUSED(clazz), jint fd)
{
    struct termios tios;
    tcgetattr(fd, &tios);
    if ((tios.c_iflag & IUTF8) == 0) {
        tios.c_iflag |= IUTF8;
        tcsetattr(fd, TCSANOW, &tios);
    }
}

JNIEXPORT jint JNICALL Java_com_molina_terminal_JNI_waitFor(JNIEnv* MOLINA_UNUSED(env), jclass MOLINA_UNUSED(clazz), jint pid)
{
    int status = 0;
    pid_t r;
    do { r = waitpid(pid, &status, 0); } while (r < 0 && errno == EINTR);
    if (r < 0) return 0; /* [PTY-HARDEN] sudah di-reap / tidak ada: jangan baca status yang tidak valid */
    if (WIFEXITED(status)) {
        return WEXITSTATUS(status);
    } else if (WIFSIGNALED(status)) {
        return -WTERMSIG(status);
    } else {
        // Should never happen - waitpid(2) says "One of the first three macros will evaluate to a non-zero (true) value".
        return 0;
    }
}

JNIEXPORT void JNICALL Java_com_molina_terminal_JNI_close(JNIEnv* MOLINA_UNUSED(env), jclass MOLINA_UNUSED(clazz), jint fileDescriptor)
{
    close(fileDescriptor);
}
