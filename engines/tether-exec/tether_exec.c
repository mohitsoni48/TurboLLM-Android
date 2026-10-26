// libtether_exec.so <daemon-pid> <program> [args...]
//
// Ties an engine to the app process, by the kernel. The daemon starts each engine as
// `libtether_exec.so <its own pid> <engine> <args...>` (TurboLLM's util/tether.ts). This arms
// PR_SET_PDEATHSIG(SIGKILL) and then execs the engine in place, so the engine keeps this process's
// pid, stdio and exit status, no extra process is left behind, and the kernel SIGKILLs the engine
// the moment the daemon's thread that started it ends: however the app process ends (Quit, a
// crash, Process.killProcess, the system killing the app). Without it an engine's only tie was
// system_server killing the app's cgroup after the app process had died.
//
// Why a separate executable: Node runs nothing in the child between fork and exec, and the death
// signal belongs to the process and is kept across execve(2) (not for a set-user-ID program, which
// no engine is), so a process that then becomes the engine has to arm it. It follows the thread
// that forked, not the process: libuv forks on the thread that calls spawn, which is the thread
// MainActivity starts node::Start on, and that thread lives exactly as long as the daemon.
//
// Named lib*.so because Android installs into nativeLibraryDir, the only place an app may execute
// files from, only the files under lib/<abi>/ that match lib*.so. Built for each ABI the APK ships
// by scripts/stage-tether-exec.mjs; BUILDING-ENGINES.md, "The engine tether".
//
// Exit status 127, without running the program, when:
//   - the arguments are wrong, or the daemon pid is not a number above 1;
//   - this process's parent is not that pid. The daemon process ended between the fork and the
//     prctl, so this process was already reparented out of it and no signal would ever come (or
//     the daemon passed another pid, which is a bug). This sees the process end, not the thread:
//     had only the forking thread ended, the kernel would have moved this process to another
//     thread of the daemon, getppid() would be unchanged, and the armed signal would come when
//     that thread ends instead. The daemon's thread ends only with the daemon, so the two agree;
//   - the exec fails (the message on stderr says why).
// If prctl itself fails, the program is started without the signal, with a message on stderr: the
// daemon's boot self-test then sees a program outlive its parent and uses its sh lifeline instead.
#include <errno.h>
#include <limits.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>
#include <unistd.h>

int main(int argc, char **argv) {
  if (argc < 3) {
    fputs("usage: libtether_exec.so <daemon-pid> <program> [args...]\n", stderr);
    return 127;
  }
  char *end = NULL;
  errno = 0;
  long parent = strtol(argv[1], &end, 10);
  if (errno != 0 || end == argv[1] || *end != '\0' || parent <= 1 || parent > INT_MAX) {
    fprintf(stderr, "libtether_exec.so: bad daemon pid '%s'; not starting %s\n", argv[1], argv[2]);
    return 127;
  }
  if (prctl(PR_SET_PDEATHSIG, SIGKILL) != 0) {
    fprintf(stderr, "libtether_exec.so: prctl(PR_SET_PDEATHSIG) failed: %s; starting %s without it\n",
            strerror(errno), argv[2]);
  } else if (getppid() != (pid_t)parent) {
    // Checked after the prctl: a daemon that ends from here on fires the signal.
    fprintf(stderr, "libtether_exec.so: the parent is %d, not the daemon %ld, which has exited; not starting %s\n",
            (int)getppid(), parent, argv[2]);
    return 127;
  }
  execvp(argv[2], argv + 2);
  fprintf(stderr, "libtether_exec.so: cannot run %s: %s\n", argv[2], strerror(errno));
  return 127;
}
