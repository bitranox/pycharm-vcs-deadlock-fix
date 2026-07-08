# CLAUDE.md - pycharm-vcs-deadlock-fix

Context for AI assistants (Claude Code and similar) entering this
repository.

## What this repository is

A self-contained Java agent that patches one specific `INVOKESTATIC`
inside `git4idea.repo.GitRepositoryImpl.<init>` - the call to
`com.intellij.openapi.progress.CoroutinesKt.runBlockingMaybeCancellable`
that wraps `workingTreeHolder.updateState()` - by replacing it with
`POP; ACONST_NULL;`. This defuses the deadlock that occurs when
`VcsRepositoryManager.checkAndUpdateRepositoryCollection` calls the
constructor N times under the global `MODIFY_LOCK` for a project with
N nested git repositories.

It is **not** a JetBrains-supported plugin; it is a userland
workaround that operates by bytecode rewriting at JVM class-load
time. The patch is permanent for the JVM session - there is no
restore logic, because the patched constructor is correct on its own.
The `updateState()` it skipped runs lazily later via the same suspend
function from `GitRepository.update()`, the existing alarm path, or
any other refresh trigger - all outside the global VCS lock.

Earlier iterations of this agent (preserved in git history) targeted
`VcsRepositoryManager.findNewRoots` and used batched rate-limiting +
DumbService-triggered restore. Those approaches work but trade one
long IDE freeze for many shorter ones, because the lock-hold time
during each batch is still significant. The constructor patch removes
the cost entirely.

See [`README.md`](README.md) for the user-facing guide.

> **Status (2026-07-08): fixed upstream in PyCharm 2026.1.3
> (`PY-261.25134.203`).** JetBrains fixed the deadlock in
> `git4idea.repo.GitWorkingTreeHolderImpl` (commit `3acd4218`): `updateState()`
> is now `@RequiresBackgroundThread` and no longer switches to `Dispatchers.IO`
> internally, so the constructor's `runBlockingMaybeCancellable` no longer
> exhausts the coroutine thread pool under `MODIFY_LOCK`. Important for future
> diagnosis: the target call still EXISTS in `GitRepositoryImpl.<init>` on fixed
> builds, so do NOT conclude "still broken" from its presence - check whether
> `GitWorkingTreeHolderImpl.updateState()` still has an internal
> `Dispatchers.IO`/`withContext` switch (present = unfixed, agent still useful;
> absent = fixed, agent redundant). PR JetBrains/intellij-community#3518 was
> declined 2026-05-26 as already fixed; YouTrack IJPL-244177 resolved. The agent
> has been disabled and uninstalled on lxc-pydev (repo kept). It stays relevant
> only for older builds that still hop dispatchers inside `updateState()`.

## Architectural orientation

```
src/VcsPatchAgent.java       single-file Java source for the agent
dist/pycharm-vcs-patch.jar   compiled, packaged JAR (premain agent)
build.sh                     rebuild dist/*.jar from src/*
install.sh                   copy JAR to PyCharm's config dir and
                             wire it into pycharm64.vmoptions
README.md                    user docs
docs/DIAGNOSTIC.md           full diagnostic write-up (case study)
jetbrains_submit.md          how to submit this issue / fix upstream
CLAUDE.md                    this file
LICENSE                      Apache-2.0 (this project)
NOTICE                       bundled ASM (BSD-3-Clause) attribution
```

The agent uses ASM 9 for bytecode rewriting. ASM is bundled into the
agent JAR itself, so the agent has no runtime classpath dependency on
PyCharm's own ASM. `build.sh` builds the JAR using PyCharm's bundled
JBR `javac` and an ASM JAR found anywhere under `~/.local/share/JetBrains`.

## Expected log markers each PyCharm session

In PyCharm stdout, or in
`~/.cache/JetBrains/PyCharmXXXX.X/log/idea.log` (search for
`VcsPatchAgent`):

```
[VcsPatchAgent] premain: target=git4idea/repo/GitRepositoryImpl.<init>
                neutralizing com/intellij/openapi/progress/CoroutinesKt.runBlockingMaybeCancellable
[VcsPatchAgent] neutralizing com/intellij/openapi/progress/CoroutinesKt.runBlockingMaybeCancellable(...)
                in git4idea/repo/GitRepositoryImpl.<init> (call site #1)
```

The first marker is logged unconditionally at premain. The second is
logged only when the JVM first goes to load
`git4idea.repo.GitRepositoryImpl` - typically within seconds of
project open.

If the agent logs a WARNING saying *"no runBlockingMaybeCancellable
call found in target class"*, JetBrains has changed the constructor;
the agent becomes a safe no-op and PyCharm runs stock. The agent's
target identifiers are constants in `src/VcsPatchAgent.java`:
`TARGET_INTERNAL`, `NEUTRALIZE_OWNER`, `NEUTRALIZE_NAME`.

## Useful diagnostic recipes

Find the live PyCharm PID:

```bash
ps -eo pid,args | awk '$2 ~ /pycharm-community\/bin\/pycharm$/ && $2 !~ /\.sh$/ {print $1; exit}'
```

Take a thread dump (the JBR ships `jcmd`):

```bash
JBR=~/.local/share/JetBrains/Toolbox/apps/pycharm-community/jbr/bin
"$JBR/jcmd" "$PID" Thread.print -l > /tmp/dump.txt
```

CPU sampling that works inside LXC containers where `schedstat` is
unreliable:

```bash
HZ=$(getconf CLK_TCK)
t1=$(awk '{print $14+$15+$16+$17}' /proc/$PID/stat); sleep 5
t2=$(awk '{print $14+$15+$16+$17}' /proc/$PID/stat)
echo "CPU consumed: $(( (t2-t1) * 1000 / HZ )) ms in 5s"
```

Detect the deadlock pattern in a dump:

```bash
DUMP=/tmp/dump.txt
echo "VCS waiters:     $(grep -c checkAndUpdateRepositoryCollection "$DUMP")"
echo "ctors in flight: $(grep -c 'GitRepositoryImpl.<init>' "$DUMP")"
echo "cancelling:      $(grep -c '{Cancelling}' "$DUMP")"
```

If `VCS waiters >= ~10` and CPU is near-idle, you are looking at the
same pathology this patch addresses. Either the patch isn't loaded
(check `idea.log` for `VcsPatchAgent` lines), or the target
constructor changed (`patch had no effect` WARNING), or there's a
distinct bug.

## Surrounding context the patch is NOT for

If you see PyCharm freezes in the same `VcsRepositoryManager` area
with characteristics *different* from this one, the agent is unlikely
to help. Other things that surface as "PyCharm hangs on indexing":

- Slow filesystem (CIFS / NFS / FUSE bindfs without `actimeo` tuning).
  Symptom: high disk I/O during indexing, indexing eventually
  completes (just slowly). This patch does nothing about that - fix
  the storage. The diagnostic doc has a paragraph on this.
- `data.services.jetbrains.com` slow / unreachable. Symptom:
  `RegionUrlMapper - Failed to fetch regional URL mappings`. Fix
  with `-Dide.http.client.read.timeout=60000`,
  `-Djava.net.preferIPv4Stack=true` in `pycharm64.vmoptions`.
- AI Assistant / Junie plugin hung on a remote analytics call.
  Symptom: `JcpAnalyticsClient` in shutdown thread dump. Fix by
  disabling those plugins in `disabled_plugins.txt`.

This agent is specifically for the case where **many GitRepositoryImpl
constructors execute serially under `MODIFY_LOCK`**, which the live
thread dump will show clearly.

## Don't accidentally break the agent

- The transformer targets the **first** INVOKESTATIC of
  `runBlockingMaybeCancellable` inside `<init>`. If JetBrains adds
  another such call to the constructor before the existing one, we
  would neutralize that one - likely incorrect. After a JetBrains
  update, verify with `javap -c -p` on the patched class: `<init>`
  should contain zero `runBlockingMaybeCancellable` references (other
  methods of `GitRepositoryImpl` are fine).
- The other call site (in `update()` / `GitRepositoryImpl$update$1`)
  is intentionally **not** patched. That path is called from outside
  any global lock and the synchronous behaviour is desired -
  user-initiated refresh should block until state is fresh.
- The agent intercepts only by exact `(opcode, owner, name)` match. A
  rename of the helper or a move to a different class causes the
  agent to find zero call sites and log a WARNING. The agent then
  becomes a no-op and PyCharm runs stock - failure mode is safe.

## Code-style hints if extending

- Single source file under `src/`, package `dev.bx.pycharm`.
- ASM only at agent-load time, not at agent-runtime once the
  transformer has fired. Avoid pulling in additional dependencies; the
  agent should remain small and self-contained for trust reasons.
- Don't add restore-via-`redefineClasses` logic unless absolutely
  necessary. The JVM disallows adding or removing methods via
  redefineClasses, which trapped earlier iterations of this agent.
  The current "patch and stay patched" design avoids that pitfall
  entirely.
