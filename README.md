# pycharm_vcs_patch

A small Java agent that fixes the JetBrains IntelliJ Platform VCS
deadlock during PyCharm's initial project scan on projects containing
many nested git repositories. The agent is small (≈80 lines of source,
~130 KB packaged), surgical (modifies exactly one INVOKESTATIC inside
one method of one class), and self-contained (ASM bundled in the JAR).

Tested live against **PyCharm Community 2026.1** (build PY-261.23567.174)
opening an umbrella project with 167 nested git repositories.

Full diagnostic write-up: [`docs/DIAGNOSTIC.md`](docs/DIAGNOSTIC.md).
How to submit upstream: [`jetbrains_submit.md`](jetbrains_submit.md).

---

## What this is

A `-javaagent` JAR that, before any IntelliJ code runs:

- **Locates exactly one `INVOKESTATIC` instruction** in the bytecode of
  `git4idea.repo.GitRepositoryImpl.<init>` — the call to
  `com.intellij.openapi.progress.CoroutinesKt.runBlockingMaybeCancellable`
  that wraps `workingTreeHolder.updateState()`.
- **Replaces** that two-instruction sequence with `POP; ACONST_NULL;`,
  i.e. discard the Kotlin suspend lambda without running it and push
  null for the subsequent stack pop. The constructor returns in
  microseconds instead of waiting for a `git` subprocess + message-bus
  publish + IDE read-write-mutex acquisition under
  `VcsRepositoryManager.MODIFY_LOCK`.
- No other call site is touched. The same helper is still used from
  `GitRepository.update()` and elsewhere — those paths are not called
  under the global VCS lock and the synchronous behaviour there is the
  intended behaviour.

## Why this is the right surgical target

Stack trace of the deadlock (captured live with `jcmd Thread.print` —
see [`docs/DIAGNOSTIC.md`](docs/DIAGNOSTIC.md) §3):

```
VcsRepositoryManager.ensureUpToDate$lambda$0          (VcsRepositoryManager.kt:165)
  → checkAndUpdateRepositoryCollection                (VcsRepositoryManager.kt:368)   ← MODIFY_LOCK held from here
    → findNewRoots                                    (VcsRepositoryManager.kt:408)
      → tryCreateRepository                           (VcsRepositoryManager.kt:106)
        → GitRepositoryCreator.createRepositoryIfValid(GitRepositoryCreator.java:19)
          → GitRepositoryImpl.createInstance          (GitRepositoryImpl.kt:268)
            → GitRepositoryImpl.<init>                (GitRepositoryImpl.kt:81)
              → runBlockingMaybeCancellable           (coroutines.kt:215)    ← *** THE BLOCKING CALL ***
                → joinBlocking → LockSupport.parkNanos
              ↑
              ↑ suspended on:
              └── GitWorkingTreeHolderImpl.updateState (GitWorkingTreeHolderImpl.kt:37)
                   ↳ withLock(updateLock)
                   ↳ messageBus.syncPublisher().on*()
                   ↳ Git.listWorktrees(repository)    ← `git` subprocess
```

The deadlock is *not* in one specific method's logic. It is in the
fact that `checkAndUpdateRepositoryCollection` holds `MODIFY_LOCK`
while calling `findNewRoots`, which constructs `GitRepositoryImpl`s,
whose constructors do `runBlockingMaybeCancellable { updateState() }`.

For N nested git repos, the constructor is called N times under one
lock. Each call adds 100-500 ms of lock-hold time (its
`runBlockingMaybeCancellable` waits for a coroutine that runs
`messageBus.syncPublisher`, IDE read-mutex acquisition, and a `git
listWorktrees` subprocess). For N=167 the total lock-hold is
1-2 minutes; while held, ~70 other VCS-aware features queue and the
IDE is effectively frozen.

**The patch removes that 100-500 ms per construction.** The
`workingTreeHolder` is left in its initial empty state, exactly as it
would be after a stock IntelliJ refresh that fails. Real state is
populated the next time anything triggers `update()` (the same suspend
function, called from outside any global lock) — gutter colours,
Changes view, log refresher all subscribe to the state-flow and
update reactively when it becomes non-empty.

## Lifecycle

```
t=0     JVM launches with -javaagent:.../pycharm-vcs-patch.jar
        ─ premain logs: "premain: target=git4idea/repo/GitRepositoryImpl.<init>
                         neutralizing com/intellij/openapi/progress/CoroutinesKt.runBlockingMaybeCancellable"
        ─ registers a ClassFileTransformer

t=class-load
        When the JVM goes to define git4idea.repo.GitRepositoryImpl, our
        transformer is invoked. It walks the bytecode of <init>; for the
        single INVOKESTATIC of runBlockingMaybeCancellable, it emits
        POP + ACONST_NULL instead.
        ─ logs: "neutralizing com/intellij/openapi/progress/CoroutinesKt.runBlockingMaybeCancellable
                 in git4idea/repo/GitRepositoryImpl.<init> (call site #1)"

t=∞     Patch is permanent for the lifetime of the JVM. No restore
        logic, no DumbService watching, no class redefinition gymnastics.
        Every constructor call from here on runs the patched body.
        The auto-discovery loop in findNewRoots still works — it just
        completes in milliseconds instead of minutes, because each
        GitRepositoryImpl.<init> no longer blocks.
```

The agent JAR on disk is byte-for-byte unchanged after install.
PyCharm's own JARs are byte-for-byte unchanged on disk — the patch
exists only in JVM memory. Removing the `-javaagent:` line from
`pycharm64.vmoptions` fully reverts to stock behaviour on the next
launch.

## What this does NOT change

- Existing mappings in `.idea/vcs.xml` work exactly as before —
  gutter, blame, Changes view, commit UI, push, log, etc.
- Auto-discovery still happens. `VcsRepositoryManager.findNewRoots`
  still runs every alarm cycle and finds every nested `.git` it
  should find. The difference is only that it doesn't block while
  doing so.
- The patched constructor's other dependencies (the staging area
  holder, untracked files holder, merge conflicts holder, etc.) are
  all initialized normally. We strip exactly the `runBlocking
  updateState()` part.
- `GitRepository.update()` and the user-initiated refresh path are
  untouched — their `runBlockingMaybeCancellable` calls are at
  different bytecode locations in different methods.

## What this might subtly change

- Immediately after a fresh `GitRepositoryImpl` is constructed, its
  `workingTreeHolder` reports the default empty state instead of
  whatever `updateState()` would have populated. If any IDE feature
  reads the state synchronously between construction and the first
  background `update()`, it sees: no current branch, no current HEAD,
  empty staging info. In practice the IDE handles this transient
  state gracefully — features either subscribe to the state-flow
  (and update when populated) or tolerate empty state. The same
  transient state exists after any normal VCS refresh fails or
  retries.
- The user-visible *first paint* of VCS-aware UI elements (e.g. the
  branch name in the status bar) may take a few extra milliseconds
  because it can only fill in after the asynchronous first
  `update()` completes. This is invisible in practice — the IDE was
  going to be busy with indexing during that window anyway.

## Files in this directory

```
pycharm_vcs_patch/
├── README.md               this file
├── CLAUDE.md               context for AI assistants
├── jetbrains_submit.md     how/where to submit this upstream
├── build.sh                rebuild the JAR from source
├── install.sh              install JAR + wire vmoptions
├── src/
│   └── VcsPatchAgent.java  the agent source (single file, ~80 lines)
└── dist/
    └── pycharm-vcs-patch.jar  compiled, packaged agent JAR
```

## Quick start (already installed on this machine)

The agent is wired in at:

```
~/.config/JetBrains/PyCharm2026.1/agents/pycharm-vcs-patch.jar
~/.config/JetBrains/PyCharm2026.1/pycharm64.vmoptions    ← contains:
    -javaagent:/home/srvadmin/.config/JetBrains/PyCharm2026.1/agents/pycharm-vcs-patch.jar
```

Restart PyCharm. Watch the markers in PyCharm stdout or `idea.log`:

```
[VcsPatchAgent] premain: target=git4idea/repo/GitRepositoryImpl.<init> neutralizing …
[VcsPatchAgent] neutralizing com/intellij/openapi/progress/CoroutinesKt.runBlockingMaybeCancellable(...) in git4idea/repo/GitRepositoryImpl.<init> (call site #1)
```

The second line appears only when the JVM first goes to load
`GitRepositoryImpl` — typically within seconds of project open.

## Build from source

```
./build.sh    # uses PyCharm's bundled JBR javac and ASM from the Junie plugin
```

## Install on a fresh machine

```
./build.sh && ./install.sh
```

Both scripts are idempotent. Restart PyCharm to activate.

## Uninstall

Remove the `-javaagent:` line from `pycharm64.vmoptions`, restart
PyCharm. The on-disk install is byte-for-byte unchanged; the patch
exists only as an in-memory transform of one method during a session.

## Verification

Live measurements on `rotek-apps` (167 nested git repos):

| Metric | Stock PyCharm | Agent installed |
|---|---|---|
| `UnindexedFilesIndexer - Finished` | never (or many minutes) | **4.6 s** |
| `exit dumb mode [rotek-apps]` | never | **3.0 s after launch** |
| Perfwatcher freezes in first 80 s | many | **0** |
| `checkAndUpdateRepositoryCollection` waiters in steady state | 70+ | **0** |
| `GitRepositoryImpl.<init>` in-flight | several | **0** |
| Process RSS | 5+ GiB | 2.0 GiB |
| UI responsive | no | yes |

## Compatibility

- Built against IntelliJ Platform 261.x. The targeted call
  (`runBlockingMaybeCancellable` inside `GitRepositoryImpl.<init>`)
  has been stable in this form across recent IDEA / git4idea
  versions, but if JetBrains rewrites the constructor body, the
  agent's transformer simply finds zero call sites and logs a
  WARNING (`patch had no effect`). No risk of breakage — it just
  becomes a no-op and PyCharm runs stock.
- Works on any JBR ≥ 17 (uses standard `Instrumentation` and ASM 9).

## See also

- [`docs/DIAGNOSTIC.md`](docs/DIAGNOSTIC.md) — full diagnostic
  write-up including `jcmd` evidence, code-level root cause traced
  into IntelliJ Community source with line numbers, before/after
  measurements, and the upstream-fix recommendation.
- [`jetbrains_submit.md`](jetbrains_submit.md) — how to submit this
  issue / fix to JetBrains via YouTrack.
- [`ai-stance.md`](ai-stance.md) — our general position on AI in
  software: what we use it for, what we don't, and what we think
  the better questions are.
- [`ai-transparency.md`](ai-transparency.md) — specific accounting
  for *this* repository: what AI did, what the human did, how
  verification was performed, and how to audit / rebuild from
  source if you don't want to trust the pre-built JAR.
- [`CLAUDE.md`](CLAUDE.md) — context for AI assistants (Claude Code
  and similar) entering this repo.
