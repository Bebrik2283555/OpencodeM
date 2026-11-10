#include <jni.h>
#include <pty.h>
#include <unistd.h>
#include <signal.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <fcntl.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>

JNIEXPORT jintArray JNICALL Java_com_opencode_engine_NativePty_open(JNIEnv *env, jobject self,
        jstring shell, jstring directory, jobjectArray variables) {
    (void) self;
    const char *raw = (*env)->GetStringUTFChars(env, shell, 0);
    if (!raw) return NULL;
    char *file = strdup(raw);
    (*env)->ReleaseStringUTFChars(env, shell, raw);
    raw = (*env)->GetStringUTFChars(env, directory, 0);
    if (!raw) { free(file); return NULL; }
    char *cwd = strdup(raw);
    (*env)->ReleaseStringUTFChars(env, directory, raw);
    jsize count = (*env)->GetArrayLength(env, variables);
    char **values = calloc((size_t) count + 1, sizeof(char *));
    if (!file || !cwd || !values) goto failure;
    for (jsize i = 0; i < count; i++) {
        jstring value = (*env)->GetObjectArrayElement(env, variables, i);
        raw = (*env)->GetStringUTFChars(env, value, 0);
        if (!raw) { (*env)->DeleteLocalRef(env, value); goto failure; }
        values[i] = strdup(raw);
        (*env)->ReleaseStringUTFChars(env, value, raw);
        (*env)->DeleteLocalRef(env, value);
        if (!values[i]) goto failure;
    }
    int master = -1;
    struct winsize size = { .ws_row = 24, .ws_col = 80 };
    pid_t pid = forkpty(&master, NULL, NULL, &size);
    if (pid == 0) {
        if (chdir(cwd) != 0) _exit(126);
        char *args[] = { file, "-i", NULL };
        execve(file, args, values);
        _exit(127);
    }
    if (pid < 0) goto failure;
    fcntl(master, F_SETFD, FD_CLOEXEC);
    for (jsize i = 0; i < count; i++) free(values[i]);
    free(values); free(cwd); free(file);
    jint result[] = { master, pid };
    jintArray out = (*env)->NewIntArray(env, 2);
    if (!out) { kill(-pid, SIGKILL); close(master); waitpid(pid, NULL, 0); return NULL; }
    (*env)->SetIntArrayRegion(env, out, 0, 2, result);
    return out;
failure:
    if (values) { for (jsize i = 0; i < count; i++) free(values[i]); free(values); }
    free(cwd); free(file);
    if (!(*env)->ExceptionCheck(env)) (*env)->ThrowNew(env, (*env)->FindClass(env, "java/io/IOException"), "Could not create Android PTY");
    return NULL;
}

JNIEXPORT void JNICALL Java_com_opencode_engine_NativePty_resize(JNIEnv *env, jobject self, jint fd, jint cols, jint rows) {
    (void) env; (void) self;
    if (cols < 1 || cols > 1000 || rows < 1 || rows > 1000) return;
    struct winsize size = { .ws_row = (unsigned short) rows, .ws_col = (unsigned short) cols };
    ioctl(fd, TIOCSWINSZ, &size);
}

JNIEXPORT jint JNICALL Java_com_opencode_engine_NativePty_wait(JNIEnv *env, jobject self, jint pid) {
    (void) env; (void) self;
    int status;
    while (waitpid(pid, &status, 0) < 0) if (errno != EINTR) return -1;
    return WIFEXITED(status) ? WEXITSTATUS(status) : 128 + WTERMSIG(status);
}

JNIEXPORT void JNICALL Java_com_opencode_engine_NativePty_kill(JNIEnv *env, jobject self, jint pid) {
    (void) env; (void) self;
    if (pid > 0) kill(-pid, SIGKILL);
}
