#include <errno.h>
#include <libproc.h>
#include <poll.h>
#include <signal.h>
#include <spawn.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/wait.h>
#include <unistd.h>

extern int responsibility_spawnattrs_setdisclaim(posix_spawnattr_t *attrs, uint32_t flags);
extern char **environ;

static pid_t helperProcessId = 0;

static void stopHelperAndExit(int signalNumber) {
    (void)signalNumber;
    if (helperProcessId > 0) {
        kill(helperProcessId, SIGTERM);
    }
    _exit(0);
}

static void writeAll(int fileDescriptor, const char *bytes, ssize_t byteCount) {
    ssize_t bytesWritten = 0;
    while (bytesWritten < byteCount) {
        ssize_t wroteNow = write(fileDescriptor, bytes + bytesWritten, (size_t)(byteCount - bytesWritten));
        if (wroteNow < 0) {
            return;
        }
        bytesWritten += wroteNow;
    }
}

int main(int argc, char **argv) {
    if (argc < 2) {
        fprintf(stderr, "Missing the dictation helper path.\n");
        return 2;
    }
    const char *helperPath = argv[1];
    int parentToHelper[2];
    int helperToParent[2];
    if (pipe(parentToHelper) != 0 || pipe(helperToParent) != 0) {
        perror("pipe");
        return 1;
    }

    posix_spawn_file_actions_t fileActions;
    posix_spawn_file_actions_init(&fileActions);
    posix_spawn_file_actions_adddup2(&fileActions, parentToHelper[0], STDIN_FILENO);
    posix_spawn_file_actions_adddup2(&fileActions, helperToParent[1], STDOUT_FILENO);
    posix_spawn_file_actions_addclose(&fileActions, parentToHelper[1]);
    posix_spawn_file_actions_addclose(&fileActions, helperToParent[0]);

    posix_spawnattr_t spawnAttributes;
    posix_spawnattr_init(&spawnAttributes);
    /* Only the packaged Rvoice.app should own the microphone and Accessibility permissions. When Rvoice runs from a
       terminal or an IDE (IntelliJ, cmux), the helper is cut loose so macOS asks about Rvoice, not that app. */
    int parentIsMacApp = 0;
    pid_t ancestorProcessId = getppid();
    for (int hop = 0; hop < 8 && ancestorProcessId > 1; hop++) {
        char ancestorPath[PROC_PIDPATHINFO_MAXSIZE];
        int pathLength = proc_pidpath(ancestorProcessId, ancestorPath, sizeof(ancestorPath));
        if (pathLength > 0 && strstr(ancestorPath, "/Rvoice.app/Contents/") != NULL) {
            parentIsMacApp = 1;
            break;
        }
        struct proc_bsdinfo processInfo;
        int infoSize = proc_pidinfo(ancestorProcessId, PROC_PIDTBSDINFO, 0, &processInfo, sizeof(processInfo));
        if (infoSize <= 0) {
            break;
        }
        ancestorProcessId = processInfo.pbi_ppid;
    }
    if (!parentIsMacApp && responsibility_spawnattrs_setdisclaim(&spawnAttributes, 1) != 0) {
        fprintf(stderr, "Could not separate the helper from the app that launched it.\n");
        return 1;
    }

    int forwardedArgumentCount = argc - 2;
    if (forwardedArgumentCount < 0) {
        forwardedArgumentCount = 0;
    }
    char **helperArguments = calloc((size_t)forwardedArgumentCount + 2, sizeof(char *));
    if (helperArguments == NULL) {
        fprintf(stderr, "Could not allocate arguments for the helper.\n");
        return 1;
    }
    helperArguments[0] = (char *)helperPath;
    for (int argumentIndex = 0; argumentIndex < forwardedArgumentCount; argumentIndex++) {
        helperArguments[argumentIndex + 1] = argv[argumentIndex + 2];
    }
    int spawnResult = posix_spawn(
        &helperProcessId,
        helperPath,
        &fileActions,
        &spawnAttributes,
        helperArguments,
        environ
    );
    posix_spawn_file_actions_destroy(&fileActions);
    posix_spawnattr_destroy(&spawnAttributes);
    close(parentToHelper[0]);
    close(helperToParent[1]);
    if (spawnResult != 0) {
        fprintf(stderr, "Could not start the dictation helper: %s\n", strerror(spawnResult));
        return 1;
    }

    signal(SIGTERM, stopHelperAndExit);
    signal(SIGINT, stopHelperAndExit);

    struct pollfd streams[2];
    streams[0].fd = STDIN_FILENO;
    streams[0].events = POLLIN;
    streams[1].fd = helperToParent[0];
    streams[1].events = POLLIN;
    char buffer[4096];
    for (;;) {
        int pollResult = poll(streams, 2, -1);
        if (pollResult < 0) {
            if (errno == EINTR) {
                continue;
            }
            break;
        }
        if (streams[0].revents & (POLLIN | POLLHUP | POLLERR)) {
            ssize_t bytesRead = read(STDIN_FILENO, buffer, sizeof(buffer));
            if (bytesRead <= 0) {
                close(parentToHelper[1]);
                streams[0].fd = -1;
            } else {
                writeAll(parentToHelper[1], buffer, bytesRead);
            }
        }
        if (streams[1].revents & (POLLIN | POLLHUP | POLLERR)) {
            ssize_t bytesRead = read(helperToParent[0], buffer, sizeof(buffer));
            if (bytesRead <= 0) {
                break;
            }
            writeAll(STDOUT_FILENO, buffer, bytesRead);
        }
    }
    kill(helperProcessId, SIGTERM);
    waitpid(helperProcessId, NULL, 0);
    return 0;
}
