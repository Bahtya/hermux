/*
 * LD_PRELOAD path rewrite library for renamed Termux packages.
 *
 * Intercepts filesystem calls and rewrites paths starting with
 * /data/data/com.termux/ to /data/data/com.hermux/.
 *
 * This fixes ALL binaries with compiled-in old paths at once,
 * eliminating the need for per-tool workarounds (APT_CONFIG,
 * dpkg conf overrides, TERMINFO, etc.).
 *
 * Build: ndk-build produces libpath_rewrite.so
 * Usage: LD_PRELOAD=$PREFIX/lib/libpath_rewrite.so
 */

#define _GNU_SOURCE
#include <dlfcn.h>
#include <string.h>
#include <limits.h>
#include <dirent.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/xattr.h>
#include <fcntl.h>
#include <unistd.h>
#include <stdarg.h>
#include <stdlib.h>
#include <stdio.h>
#include <errno.h>
#include <utime.h>
#include <sys/time.h>
#include <sys/statfs.h>
#include <sys/statvfs.h>
#include <sys/inotify.h>
#include <linux/stat.h>

#define OLD_PREFIX  "/data/data/com.termux"
#define NEW_PREFIX  "/data/data/com.hermux"
#define OLD_LEN     21
#define NEW_LEN     21

/* Two thread-local buffers so functions that take two path arguments
 * (rename, link, symlink, etc.) don't clobber each other's result. */
static __thread char g_buf[PATH_MAX];
static __thread char g_buf2[PATH_MAX];

static const char *rewrite_to(const char *p, char *buf) {
    if (!p || strncmp(p, OLD_PREFIX, OLD_LEN) != 0) return p;
    size_t rest = strlen(p + OLD_LEN);
    if (NEW_LEN + rest >= PATH_MAX) return p;
    memcpy(buf, NEW_PREFIX, NEW_LEN);
    memcpy(buf + NEW_LEN, p + OLD_LEN, rest + 1);
    return buf;
}

static const char *rewrite(const char *p) {
    return rewrite_to(p, g_buf);
}

static const char *rewrite2(const char *p) {
    return rewrite_to(p, g_buf2);
}

/* Resolve a dlsym pointer once and cache it for all future calls. */
#define RESOLVE(type, name) \
    static type real; \
    if (!real) real = (type)dlsym(RTLD_NEXT, name)

/* --- execve shebang patching --- */

/* Clean up stale temp files from previous execve calls.
 * Runs once per thread (via __thread guard). */
static void cleanup_stale_temps(void) {
    static __thread int cleaned = 0;
    if (cleaned) return;
    cleaned = 1;

    const char *tmpdir = getenv("TMPDIR");
    if (!tmpdir || tmpdir[0] == '\0')
        tmpdir = NEW_PREFIX "/files/usr/tmp";

    DIR *d = opendir(tmpdir);
    if (!d) return;
    struct dirent *ent;
    char p[PATH_MAX];
    while ((ent = readdir(d)) != NULL) {
        if (strncmp(ent->d_name, ".rw_", 4) == 0) {
            snprintf(p, sizeof(p), "%s/%s", tmpdir, ent->d_name);
            unlink(p);
        }
    }
    closedir(d);
}

/*
 * Check if the file at 'path' is a script whose shebang contains OLD_PREFIX.
 * If so, create a temp file with the shebang patched and return its path.
 * Returns NULL if no patching needed or on failure.
 * Caller must not free the returned pointer (points to thread-local buffer).
 */
static const char *patch_shebang_if_needed(const char *path) {
    static __thread char tmpbuf[PATH_MAX];

    cleanup_stale_temps();

    int fd = open(path, O_RDONLY);
    if (fd < 0) return NULL;

    char head[512];
    ssize_t n = read(fd, head, sizeof(head));
    close(fd);

    if (n < 2 || head[0] != '#' || head[1] != '!') return NULL;

    /* Check if shebang line contains OLD_PREFIX */
    int found = 0;
    for (int i = 0; i <= n - OLD_LEN; i++) {
        if (head[i] == '\n') break;
        if (memcmp(head + i, OLD_PREFIX, OLD_LEN) == 0) {
            found = 1;
            break;
        }
    }
    if (!found) return NULL;

    /* Patch OLD_PREFIX → NEW_PREFIX in the first chunk */
    char patched[512];
    int plen = 0;
    for (int i = 0; i < n; ) {
        if (i <= n - OLD_LEN && memcmp(head + i, OLD_PREFIX, OLD_LEN) == 0) {
            memcpy(patched + plen, NEW_PREFIX, NEW_LEN);
            plen += NEW_LEN;
            i += OLD_LEN;
        } else {
            patched[plen++] = head[i++];
        }
    }

    /* Create temp file */
    const char *tmpdir = getenv("TMPDIR");
    if (!tmpdir || tmpdir[0] == '\0')
        tmpdir = NEW_PREFIX "/files/usr/tmp";

    snprintf(tmpbuf, sizeof(tmpbuf), "%s/.rw_XXXXXX", tmpdir);
    int tmpfd = mkstemp(tmpbuf);
    if (tmpfd < 0) return NULL;

    /* Write patched first chunk */
    if (write(tmpfd, patched, plen) != plen) {
        close(tmpfd);
        unlink(tmpbuf);
        return NULL;
    }

    /* Copy the rest of the file unchanged.
     * Body references to old paths are handled by recursive execve
     * interception and other intercepted filesystem calls. */
    fd = open(path, O_RDONLY);
    if (fd >= 0) {
        /* Skip the bytes we already wrote */
        char skipbuf[512];
        read(fd, skipbuf, n);

        char copybuf[4096];
        ssize_t r;
        while ((r = read(fd, copybuf, sizeof(copybuf))) > 0) {
            write(tmpfd, copybuf, r);
        }
        close(fd);
    }

    close(tmpfd);
    chmod(tmpbuf, 0700);
    return tmpbuf;
}

int execve(const char *pathname, char *const argv[], char *const envp[]) {
    RESOLVE(int (*)(const char *, char *const [], char *const []), "execve");
    if (!real_execve) { errno = ENOSYS; return -1; }

    const char *rewritten = rewrite(pathname);

    /* Check if the target file is a script with a shebang needing patching */
    const char *patched = patch_shebang_if_needed(rewritten);
    if (patched) {
        int ret = real_execve(patched, argv, envp);
        int saved = errno;
        unlink(patched);
        errno = saved;
        return ret;
    }

    return real_execve(rewritten, argv, envp);
}

int execveat(int dirfd, const char *pathname, char *const argv[],
             char *const envp[], int flags) {
    RESOLVE(int (*)(int, const char *, char *const [], char *const [], int), "execveat");
    if (!real_execveat) { errno = ENOSYS; return -1; }

    if (pathname[0] == '/' || dirfd == AT_FDCWD) {
        const char *rewritten = rewrite(pathname);

        const char *patched = patch_shebang_if_needed(rewritten);
        if (patched) {
            int ret = real_execveat(dirfd, patched, argv, envp, flags);
            int saved = errno;
            unlink(patched);
            errno = saved;
            return ret;
        }

        return real_execveat(dirfd, rewritten, argv, envp, flags);
    }

    return real_execveat(dirfd, pathname, argv, envp, flags);
}

/* --- Intercepted functions --- */

DIR *opendir(const char *name) {
    RESOLVE(DIR *(*)(const char *), "opendir");
    if (!real) return NULL;
    return real(rewrite(name));
}

int stat(const char *p, struct stat *s) {
    RESOLVE(int (*)(const char *, struct stat *), "stat");
    if (!real) return -1;
    return real(rewrite(p), s);
}

int lstat(const char *p, struct stat *s) {
    RESOLVE(int (*)(const char *, struct stat *), "lstat");
    if (!real) return -1;
    return real(rewrite(p), s);
}

int fstatat(int fd, const char *p, struct stat *s, int flags) {
    RESOLVE(int (*)(int, const char *, struct stat *, int), "fstatat");
    if (!real) return -1;
    return real(fd, rewrite(p), s, flags);
}

int access(const char *p, int m) {
    RESOLVE(int (*)(const char *, int), "access");
    if (!real) return -1;
    return real(rewrite(p), m);
}

int faccessat(int fd, const char *p, int m, int flags) {
    RESOLVE(int (*)(int, const char *, int, int), "faccessat");
    if (!real) return -1;
    return real(fd, rewrite(p), m, flags);
}

ssize_t readlink(const char *p, char *b, size_t s) {
    RESOLVE(ssize_t (*)(const char *, char *, size_t), "readlink");
    if (!real) return -1;
    return real(rewrite(p), b, s);
}

ssize_t readlinkat(int fd, const char *p, char *b, size_t s) {
    RESOLVE(ssize_t (*)(int, const char *, char *, size_t), "readlinkat");
    if (!real) return -1;
    return real(fd, rewrite(p), b, s);
}

int open(const char *p, int f, ...) {
    mode_t m = 0;
    if (f & (O_CREAT | O_TMPFILE)) {
        va_list a;
        va_start(a, f);
        m = (mode_t)va_arg(a, int);
        va_end(a);
    }
    RESOLVE(int (*)(const char *, int, ...), "open");
    if (!real) return -1;
    return real(rewrite(p), f, m);
}

int open64(const char *p, int f, ...) {
    mode_t m = 0;
    if (f & (O_CREAT | O_TMPFILE)) {
        va_list a;
        va_start(a, f);
        m = (mode_t)va_arg(a, int);
        va_end(a);
    }
    RESOLVE(int (*)(const char *, int, ...), "open64");
    if (!real) return -1;
    return real(rewrite(p), f, m);
}

int openat(int fd, const char *p, int f, ...) {
    mode_t m = 0;
    if (f & (O_CREAT | O_TMPFILE)) {
        va_list a;
        va_start(a, f);
        m = (mode_t)va_arg(a, int);
        va_end(a);
    }
    RESOLVE(int (*)(int, const char *, int, ...), "openat");
    if (!real) return -1;
    return real(fd, rewrite(p), f, m);
}

int creat(const char *p, mode_t m) {
    RESOLVE(int (*)(const char *, mode_t), "creat");
    if (!real) return -1;
    return real(rewrite(p), m);
}

int rename(const char *o, const char *n) {
    RESOLVE(int (*)(const char *, const char *), "rename");
    if (!real) return -1;
    return real(rewrite(o), rewrite2(n));
}

int renameat(int oldfd, const char *o, int newfd, const char *n) {
    RESOLVE(int (*)(int, const char *, int, const char *), "renameat");
    if (!real) return -1;
    return real(oldfd, rewrite(o), newfd, rewrite2(n));
}

int unlink(const char *p) {
    RESOLVE(int (*)(const char *), "unlink");
    if (!real) return -1;
    return real(rewrite(p));
}

int unlinkat(int fd, const char *p, int flags) {
    RESOLVE(int (*)(int, const char *, int), "unlinkat");
    if (!real) return -1;
    return real(fd, rewrite(p), flags);
}

int mkdir(const char *p, mode_t m) {
    RESOLVE(int (*)(const char *, mode_t), "mkdir");
    if (!real) return -1;
    return real(rewrite(p), m);
}

int mkdirat(int fd, const char *p, mode_t m) {
    RESOLVE(int (*)(int, const char *, mode_t), "mkdirat");
    if (!real) return -1;
    return real(fd, rewrite(p), m);
}

int chmod(const char *p, mode_t m) {
    RESOLVE(int (*)(const char *, mode_t), "chmod");
    if (!real) return -1;
    return real(rewrite(p), m);
}

int chown(const char *p, uid_t u, gid_t g) {
    RESOLVE(int (*)(const char *, uid_t, gid_t), "chown");
    if (!real) return -1;
    return real(rewrite(p), u, g);
}

int lchown(const char *p, uid_t u, gid_t g) {
    RESOLVE(int (*)(const char *, uid_t, gid_t), "lchown");
    if (!real) return -1;
    return real(rewrite(p), u, g);
}

int truncate(const char *p, off_t l) {
    RESOLVE(int (*)(const char *, off_t), "truncate");
    if (!real) return -1;
    return real(rewrite(p), l);
}

int link(const char *o, const char *n) {
    RESOLVE(int (*)(const char *, const char *), "link");
    if (!real) return -1;
    return real(rewrite(o), rewrite2(n));
}

int symlink(const char *o, const char *n) {
    RESOLVE(int (*)(const char *, const char *), "symlink");
    if (!real) return -1;
    return real(rewrite(o), rewrite2(n));
}

char *realpath(const char *p, char *r) {
    RESOLVE(char *(*)(const char *, char *), "realpath");
    if (!real) return NULL;
    return real(rewrite(p), r);
}

FILE *fopen(const char *p, const char *m) {
    RESOLVE(FILE *(*)(const char *, const char *), "fopen");
    if (!real) return NULL;
    return real(rewrite(p), m);
}

FILE *fopen64(const char *p, const char *m) {
    RESOLVE(FILE *(*)(const char *, const char *), "fopen64");
    if (!real) return NULL;
    return real(rewrite(p), m);
}

FILE *freopen(const char *p, const char *m, FILE *s) {
    RESOLVE(FILE *(*)(const char *, const char *, FILE *), "freopen");
    if (!real) return NULL;
    return real(rewrite(p), m, s);
}

/* statx requires Linux 4.11+ / API 28+. On older devices dlsym returns NULL
 * and the call is safely skipped. */
int statx(int dirfd, const char *p, int flags, unsigned mask, struct statx *s) {
    RESOLVE(int (*)(int, const char *, int, unsigned, struct statx *), "statx");
    if (!real) return -1;
    return real(dirfd, rewrite(p), flags, mask, s);
}

int rmdir(const char *p) {
    RESOLVE(int (*)(const char *), "rmdir");
    if (!real) return -1;
    return real(rewrite(p));
}

int remove(const char *p) {
    RESOLVE(int (*)(const char *), "remove");
    if (!real) return -1;
    return real(rewrite(p));
}

/* --- Timestamp syscalls (needed by dpkg) --- */

int utime(const char *p, const struct utimbuf *t) {
    RESOLVE(int (*)(const char *, const struct utimbuf *), "utime");
    if (!real) return -1;
    return real(rewrite(p), t);
}

int utimes(const char *p, const struct timeval t[2]) {
    RESOLVE(int (*)(const char *, const struct timeval [2]), "utimes");
    if (!real) return -1;
    return real(rewrite(p), t);
}

int utimensat(int fd, const char *p, const struct timespec t[2], int flags) {
    RESOLVE(int (*)(int, const char *, const struct timespec [2], int), "utimensat");
    if (!real) return -1;
    if (p && (p[0] == '/' || fd == AT_FDCWD))
        return real(fd, rewrite(p), t, flags);
    return real(fd, p, t, flags);
}

/* --- *at variants --- */

int fchmodat(int fd, const char *p, mode_t m, int flags) {
    RESOLVE(int (*)(int, const char *, mode_t, int), "fchmodat");
    if (!real) return -1;
    return real(fd, rewrite(p), m, flags);
}

int fchownat(int fd, const char *p, uid_t u, gid_t g, int flags) {
    RESOLVE(int (*)(int, const char *, uid_t, gid_t, int), "fchownat");
    if (!real) return -1;
    return real(fd, rewrite(p), u, g, flags);
}

int renameat2(int oldfd, const char *o, int newfd, const char *n, unsigned flags) {
    RESOLVE(int (*)(int, const char *, int, const char *, unsigned), "renameat2");
    if (!real) return -1;
    return real(oldfd, rewrite(o), newfd, rewrite2(n), flags);
}

int linkat(int oldfd, const char *o, int newfd, const char *n, int flags) {
    RESOLVE(int (*)(int, const char *, int, const char *, int), "linkat");
    if (!real) return -1;
    return real(oldfd, rewrite(o), newfd, rewrite2(n), flags);
}

int symlinkat(const char *o, int fd, const char *n) {
    RESOLVE(int (*)(const char *, int, const char *), "symlinkat");
    if (!real) return -1;
    return real(rewrite(o), fd, rewrite2(n));
}

int mknod(const char *p, mode_t m, dev_t d) {
    RESOLVE(int (*)(const char *, mode_t, dev_t), "mknod");
    if (!real) return -1;
    return real(rewrite(p), m, d);
}

int mknodat(int fd, const char *p, mode_t m, dev_t d) {
    RESOLVE(int (*)(int, const char *, mode_t, dev_t), "mknodat");
    if (!real) return -1;
    return real(fd, rewrite(p), m, d);
}

/* --- Filesystem info --- */

int statfs(const char *p, struct statfs *s) {
    RESOLVE(int (*)(const char *, struct statfs *), "statfs");
    if (!real) return -1;
    return real(rewrite(p), s);
}

int statvfs(const char *p, struct statvfs *s) {
    RESOLVE(int (*)(const char *, struct statvfs *), "statvfs");
    if (!real) return -1;
    return real(rewrite(p), s);
}

int truncate64(const char *p, off64_t l) {
    RESOLVE(int (*)(const char *, off64_t), "truncate64");
    if (!real) return -1;
    return real(rewrite(p), l);
}

/* --- 64-bit / large file variants --- */

FILE *freopen64(const char *p, const char *m, FILE *s) {
    RESOLVE(FILE *(*)(const char *, const char *, FILE *), "freopen64");
    if (!real) return NULL;
    return real(rewrite(p), m, s);
}

/* --- Directory --- */

int chdir(const char *p) {
    RESOLVE(int (*)(const char *), "chdir");
    if (!real) return -1;
    return real(rewrite(p));
}

/* --- Path resolution --- */

char *canonicalize_file_name(const char *p) {
    RESOLVE(char *(*)(const char *), "canonicalize_file_name");
    if (!real) return NULL;
    return real(rewrite(p));
}

/* --- Extended attributes --- */

ssize_t getxattr(const char *p, const char *n, void *v, size_t s) {
    RESOLVE(ssize_t (*)(const char *, const char *, void *, size_t), "getxattr");
    if (!real) return -1;
    return real(rewrite(p), n, v, s);
}

ssize_t lgetxattr(const char *p, const char *n, void *v, size_t s) {
    RESOLVE(ssize_t (*)(const char *, const char *, void *, size_t), "lgetxattr");
    if (!real) return -1;
    return real(rewrite(p), n, v, s);
}

int setxattr(const char *p, const char *n, const void *v, size_t s, int f) {
    RESOLVE(int (*)(const char *, const char *, const void *, size_t, int), "setxattr");
    if (!real) return -1;
    return real(rewrite(p), n, v, s, f);
}

int lsetxattr(const char *p, const char *n, const void *v, size_t s, int f) {
    RESOLVE(int (*)(const char *, const char *, const void *, size_t, int), "lsetxattr");
    if (!real) return -1;
    return real(rewrite(p), n, v, s, f);
}

ssize_t listxattr(const char *p, char *l, size_t s) {
    RESOLVE(ssize_t (*)(const char *, char *, size_t), "listxattr");
    if (!real) return -1;
    return real(rewrite(p), l, s);
}

ssize_t llistxattr(const char *p, char *l, size_t s) {
    RESOLVE(ssize_t (*)(const char *, char *, size_t), "llistxattr");
    if (!real) return -1;
    return real(rewrite(p), l, s);
}

int removexattr(const char *p, const char *n) {
    RESOLVE(int (*)(const char *, const char *), "removexattr");
    if (!real) return -1;
    return real(rewrite(p), n);
}

int lremovexattr(const char *p, const char *n) {
    RESOLVE(int (*)(const char *, const char *), "lremovexattr");
    if (!real) return -1;
    return real(rewrite(p), n);
}

/* --- FIFO --- */

int mkfifo(const char *p, mode_t m) {
    RESOLVE(int (*)(const char *, mode_t), "mkfifo");
    if (!real) return -1;
    return real(rewrite(p), m);
}

int mkfifoat(int fd, const char *p, mode_t m) {
    RESOLVE(int (*)(int, const char *, mode_t), "mkfifoat");
    if (!real) return -1;
    return real(fd, rewrite(p), m);
}

/* --- File monitoring --- */

int inotify_add_watch(int fd, const char *p, uint32_t m) {
    RESOLVE(int (*)(int, const char *, uint32_t), "inotify_add_watch");
    if (!real) return -1;
    return real(fd, rewrite(p), m);
}

/* --- Access (GNU variant) --- */

int euidaccess(const char *p, int m) {
    RESOLVE(int (*)(const char *, int), "euidaccess");
    if (!real) return -1;
    return real(rewrite(p), m);
}

/* --- Diagnostics --- */

/* Use syscall to avoid calling our own intercepted open() during init */
#include <sys/syscall.h>

static void write_diag(const char *msg) {
    int fd = (int)syscall(__NR_openat, AT_FDCWD, "/sdcard/hermux-pathrewrite.log",
                          O_WRONLY | O_CREAT | O_APPEND, 0666);
    if (fd >= 0) {
        syscall(__NR_write, fd, msg, strlen(msg));
        syscall(__NR_close, fd);
    }
}

__attribute__((constructor))
static void pathrewrite_init(void) {
    char buf[512];

    /* Test that dlsym works */
    void *real_open = dlsym(RTLD_NEXT, "open");
    void *real_stat = dlsym(RTLD_NEXT, "stat");

    snprintf(buf, sizeof(buf),
        "path_rewrite loaded: dlsym(open)=%p dlsym(stat)=%p\n",
        real_open, real_stat);
    write_diag(buf);

    /* Test TLS buffer access */
    const char *test = rewrite("/data/data/com.termux/files/usr/bin/bash");
    snprintf(buf, sizeof(buf),
        "TLS test: rewrite result=%s\n", test ? test : "(null)");
    write_diag(buf);
}
