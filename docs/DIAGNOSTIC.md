# PyCharm "Analyzing project" hang — VCS root explosion deadlock

Diagnosed live on 2026-05-22 against PyCharm Community 2026.1 (build
PY-261.23567.174) running inside the PyCharm/desktop LXC (CT 1017) on
`proxmox01`. This document records the symptom, the evidence, the
code-level root cause traced into the IntelliJ Community source, the
applied configuration fix, and the upstream change that would
permanently eliminate the pathology.

---

## 1. Symptom

When opening the `rotek-apps` umbrella project at
`/media/srv-main-softdev/rotek-apps`, PyCharm displayed a progress
indicator labelled *"Analyzing project to enable smart features"*
(or sometimes *"Scanning files"*) that never completed. The IDE was
visually frozen; the project tool window, code-completion, and Find
Usages did not respond. Force-killing the IDE and restarting did not
help. Closing PyCharm via the window close button hung on *"Saving
application settings"* and *"Waiting for Scanning to complete"*.

The other two umbrella projects (`projects` at
`/media/srv-main-softdev/projects`, 114 mappings; `rnprivat`, 12
mappings) showed the same class of hang at different intensities.

The host filesystem (bindfs → ZFS), the network (JetBrains data-services
endpoint), and the JetBrains AI plugin were ruled out as causes through
earlier diagnostic passes. The remaining culprit was the project's VCS
configuration: `rotek-apps/.idea/vcs.xml` had grown to **167 nested git
repository mappings**.

---

## 2. Diagnostic methodology

PyCharm's bundled JBR ships `jcmd`, which can attach to the running PyCharm
PID and emit a full thread + coroutine dump. The dumps captured live are
the source of every observation in §3.

```
JBR=/home/srvadmin/.local/share/JetBrains/Toolbox/apps/pycharm-community/jbr/bin
PID=$(ps -eo pid,args | awk '$2 ~ /pycharm-community\/bin\/pycharm$/ \
                            && $2 !~ /\.sh$/ {print $1; exit}')
"$JBR/jcmd" "$PID" Thread.print -l > /tmp/pycharm-dump.txt
"$JBR/jcmd" "$PID" GC.heap_info
```

PyCharm itself also writes "freeze" dumps when its `PerformanceWatcher`
notices a coroutine dispatcher unresponsive for ≥5 s. They land in
`~/.cache/JetBrains/PyCharm2026.1/log/threadDumps-Dispatchers.IO-*/`
and were used to corroborate the live `jcmd` dumps.

The frozen-process CPU was measured via `/proc/PID/stat` (utime+stime in
clock ticks, multiplied by `1000 / getconf CLK_TCK`). `/proc/PID/schedstat`
**should not be used inside LXC on this host** — it returns near-zero
values regardless of actual CPU consumption (caveat in §8).

---

## 3. Live evidence

### 3.1 Heap is not the constraint

```
$ jcmd $PID GC.heap_info
garbage-first heap   total reserved 12288000K, committed 4562944K, used 2749576K
 region size 8192K, 249 young (2039808K), 23 survivors (188416K)
```

Heap is set to `-Xmx12000m`. Of 12 GiB max, **4.5 GiB committed, 2.7 GiB
used**. Adding more heap has zero effect on this class of hang — the JVM
isn't paging, isn't in a long GC, and the JNI thread count is normal.

### 3.2 Thread state summary

```
Threads BLOCKED:                                           0
Threads WAITING:                                          73
Threads TIMED_WAITING:                                    73
Threads RUNNABLE:                                         23
Threads parked on AbstractQueuedLongSynchronizer (rwlock): 132
Threads at checkAndUpdateRepositoryCollection:            70
```

Zero `BLOCKED` (no monitor contention). 132 threads parked on the
read-write-lock's queue, 70 of them at the same site inside
`VcsRepositoryManager.checkAndUpdateRepositoryCollection`. The lock
holder is **one** thread, and its stack tells the whole story.

### 3.3 The lock holder's stack (the kernel of the bug)

`DefaultDispatcher-worker-25`, native thread id 82810, JVM cpu time
910 ms accumulated, holds the `VcsRepositoryManager` write lock. Top
of stack:

```
"DefaultDispatcher-worker-25" daemon prio=5 nid=82810
   java.lang.Thread.State: TIMED_WAITING (parking)
    parking to wait for <0x5f94974e0> (kotlinx.coroutines.BlockingCoroutine)
    at jdk.internal.misc.Unsafe.park
    at java.util.concurrent.locks.LockSupport.parkNanos
    at kotlinx.coroutines.BlockingCoroutine.joinBlocking          (Builders.kt:146)
    at IntelliJCoroutines.runBlockingWithParallelismCompensation
    at IntelliJCoroutinesFacade.runBlockingWithParallelismCompensation (:35)
    at com.intellij.openapi.progress.CoroutinesKt.runBlockingCancellable (coroutines.kt:151)
    at com.intellij.openapi.progress.CoroutinesKt.runBlockingMaybeCancellable (coroutines.kt:215)
    at git4idea.repo.GitRepositoryImpl.<init>                     (GitRepositoryImpl.kt:81)
    at git4idea.repo.GitRepositoryImpl$Companion.createInstance   (GitRepositoryImpl.kt:268)
    at git4idea.repo.GitRepositoryCreator.createRepositoryIfValid (GitRepositoryCreator.java:19)
    at com.intellij.dvcs.repo.VcsRepositoryManager$Companion.tryCreateRepository
                                                          (VcsRepositoryManager.kt:106)
    at com.intellij.dvcs.repo.VcsRepositoryManager.findNewRoots   (VcsRepositoryManager.kt:408)
    at com.intellij.dvcs.repo.VcsRepositoryManager.checkAndUpdateRepositoryCollection$lambda$0
                                                          (VcsRepositoryManager.kt:373)
    at com.intellij.openapi.progress.util.BackgroundTaskUtil.runUnderDisposeAwareIndicator
    at com.intellij.dvcs.repo.VcsRepositoryManager.checkAndUpdateRepositoryCollection
                                                          (VcsRepositoryManager.kt:368)
    at com.intellij.dvcs.repo.VcsRepositoryManager.ensureUpToDate$lambda$0
                                                          (VcsRepositoryManager.kt:165)
    at com.intellij.util.Alarm$Request$schedule$1$1.invokeSuspend (Alarm.kt:430)
```

Read it bottom-up: a periodic `Alarm` fires `ensureUpToDate`, which
calls `checkAndUpdateRepositoryCollection`. At line 354 it acquires the
`MODIFY_LOCK` write lock. Still under the lock, it calls `findNewRoots`,
which iterates every candidate VCS root and calls `tryCreateRepository`
on each. `tryCreateRepository` dispatches through an extension point
to `GitRepositoryCreator`, which calls `GitRepositoryImpl.<init>`. The
constructor at line 81 does:

```kotlin
runBlockingMaybeCancellable { workingTreeHolder.updateState() }
```

— a synchronous blocking call from within the write critical section,
which parks on a child coroutine. That child:

```
"com.intellij.dvcs.repo.VcsRepositoryManager":BlockingCoroutine{Active}@6097a144,
  state: SUSPENDED [..., ComputationState(level=0,
                                          thisLevelLock=RWMutexIdeaImpl@157f1541,
                                          isParallelizedRead=false),
                    BlockingEventLoop]
    at git4idea.repo.GitWorkingTreeHolderImpl.updateState (GitWorkingTreeHolderImpl.kt:37)
    at git4idea.repo.GitRepositoryImpl$1.invokeSuspend    (GitRepositoryImpl.kt:82)
```

The coroutine context shows the IDE-wide `RWMutexIdeaImpl@157f1541`
read-write mutex (it was the only such instance referenced anywhere in
the dump, by 7 threads). Inside `GitWorkingTreeHolderImpl.updateState`
(line 38 of the IntelliJ source), the function takes its own
`Mutex updateLock` and then publishes synchronously to the project
message bus and runs a `Git.listWorktrees(repository)` subprocess.

### 3.4 The queue piled up behind it

70 other coroutines, each from a different VCS-aware feature in the IDE
(`GitAnnotationProvider.getCurrentRevision`, `ChangeListManagerImpl.actualUpdate`,
`GitLogProvider.getGitLogParameters`, `GitRecentCommitsProvider`,
`GitLogExperimentalProvider`, etc.) are parked on the same
`MODIFY_LOCK` write lock at line 354, waiting for worker-25 to release
it.

### 3.5 Why N=167 makes this catastrophic

`findNewRoots` is **O(unmapped-roots)** inside the lock. Each iteration
does a `runBlocking` on `GitWorkingTreeHolderImpl.updateState`, which
serially: takes `updateLock`, publishes events, and runs a `git`
subprocess (`git worktree list --porcelain`). For one new root that's
maybe 50–200 ms. For 167 it is *seconds-to-minutes* of held lock per
sweep. Every time the Alarm at `VcsRepositoryManager.kt:165` reschedules
itself (every project event, file change, or focus change), this whole
walk repeats.

The 70-deep waiter queue we observed is the steady-state result of
"the lock holder runs ~tens of seconds per refresh; refresh requests
arrive faster than they drain." That is the deadlock you saw — not a
classic two-lock cycle, but a pathological serialization with N=167
multiplier and a `runBlocking` inside the critical section.

---

## 4. Code-level root cause

Source (IntelliJ Community, `master` branch, current at time of writing):

- `platform/dvcs-impl/src/com/intellij/dvcs/repo/VcsRepositoryManager.kt`
  - `ensureUpToDate` (line 165): scheduled by `Alarm` on most VCS events.
  - `checkAndUpdateRepositoryCollection` (line 354): takes `MODIFY_LOCK.lock()`.
  - Lambda at lines 368–373: while `MODIFY_LOCK` is held, runs
    `findInvalidRoots` and `findNewRoots`.
  - `findNewRoots` (line 408): iterates candidate roots, calls
    `Companion.tryCreateRepository`.
  - `Companion.tryCreateRepository` (line 106): extension-point dispatch.
- `plugins/git4idea/src/git4idea/repo/GitRepositoryImpl.kt`
  - Constructor (line 81): `runBlockingMaybeCancellable { workingTreeHolder.updateState() }`
  - `Companion.createInstance` (line 268): instantiates plus installs listeners.
- `plugins/git4idea/src/git4idea/repo/GitWorkingTreeHolderImpl.kt`
  - `updateState` (line 37–60): `@RequiresBackgroundThread`, takes
    `updateLock` Mutex (line 38), updates a `MutableStateFlow`,
    synchronously publishes to the project message bus,
    `Git.listWorktrees(repository)` subprocess.

### 4.1 The three design problems

1. **Per-root work is inside the global write lock.** `findNewRoots` is
   not "build a list of new roots, release lock, instantiate them, take
   lock to swap in." It instantiates each `GitRepositoryImpl` while
   still holding `MODIFY_LOCK`. With N=167 you serialize N construction
   calls under the global lock.
2. **`runBlockingMaybeCancellable` inside a held lock.** The constructor
   `GitRepositoryImpl.<init>` at line 81 turns an asynchronous suspend
   function into a blocking call. The current dispatcher is
   `Dispatchers.Default` (saturated by the same kind of work) and the
   child coroutine can include `git` subprocess execution, message-bus
   sync-publish (re-entrant into project services), and IDE
   `RWMutexIdeaImpl` acquisition. Any of these can suspend for a long
   time — and during that time the global VCS lock is held.
3. **The Alarm-driven `ensureUpToDate` has no rate-limiting.** It is
   rescheduled on most VCS-relevant events. With N=167 it fires
   continuously, leaving the lock held effectively all the time.

### 4.2 What the upstream fix would look like

Three independent changes would eliminate this for any N:

- Move the repository-instantiation phase **out of** `MODIFY_LOCK`.
  Discover root paths under the lock, release it, instantiate
  `GitRepositoryImpl`s on a `Dispatchers.IO` scope in parallel, then
  re-acquire the lock for the final swap. (Roughly: a copy-on-write
  pattern around the repository map.)
- Replace the constructor's `runBlockingMaybeCancellable` with a
  suspending `init` step invoked by `createInstance`'s coroutine
  caller. The constructor would return an "uninitialized" repository
  and the caller would `await` its first state. No `runBlocking` while
  holding any lock.
- Coalesce `Alarm.ensureUpToDate` requests so concurrent or
  rapidly-arriving events trigger one refresh, not a backlog.

This is a non-trivial change touching code that many extension points
depend on. As of 2026-05-22, JetBrains has tickets for "VCS root
discovery is slow with many submodules" (IDEA-188998 and successors)
but no fix in 2026.1.

---

## 5. Applied fix

We cannot patch the IntelliJ source tree, but we can patch the running
JVM. Three approaches were considered:

- **(a)** Reduce N (the number of nested VCS mappings) to a single-digit
  number by trimming `.idea/vcs.xml`. *Tried, did not work — see §5.1.*
- **(b)** Eliminate the VCS subsystem entirely by disabling the Git4Idea
  plugin. *Works, but you lose the gutter / Changes view / branch UI
  inside PyCharm — see §5.2.*
- **(c)** Patch the constructor at JVM class-load time, via a
  `-javaagent` JAR that rewrites the one `INVOKESTATIC` that triggers
  the in-lock `runBlocking`. *This is the actual fix that is now in
  place — see §5.3.*

§5.1 is preserved because the failure mode is non-obvious and worth
documenting. §5.2 is preserved because it remains a valid fallback for
environments where loading a `-javaagent` is undesirable.

### 5.1 Why the first attempt (trim + `vcs=""`) did NOT work

The first attempt replaced the 167 mappings with a 4-line file:
umbrella as `Git` plus three "no-VCS" parent markers
(`$PROJECT_DIR$/lib`, `$PROJECT_DIR$/../projects`, `$PROJECT_DIR$/../rnprivat`).

This was based on the assumption that an explicit
`<mapping directory="X" vcs="" />` would suppress PyCharm's auto-detection
inside subtree `X`. **It does not.** PyCharm's mapping resolution uses
longest-path-match: a `vcs=""` mapping on parent `X` does not stop a
more-specific `vcs="Git"` mapping on child `X/sub` from being added by
the auto-detector. Within a few minutes of opening the project, the IDE
walked the trees under each `vcs=""` parent, found every nested `.git`,
and silently appended 166 fresh `vcs="Git"` lines. The §3 deadlock
returned in full.

The Registry overrides

```
vcs.root.detector.enabled       false
git.repository.root.detector.enabled  false
```

appended to `early-access-registry.txt` had no effect in 2026.1 — those
key names are not what the platform's `VcsRootProblemNotifier` /
`VcsRootScanner` actually read. Unknown keys are silently ignored, so
the entries are harmless, but they did not suppress anything.

### 5.2 Fallback workaround: disable Git4Idea (and Subversion) at the IDE level

This is the **previous** workaround used during diagnosis, retained
here as a documented fallback. It is no longer the active fix on this
machine — the agent in §5.3 is — but it remains useful when running a
`-javaagent` is not an option (e.g. corporate-managed IDEs that lock
`pycharm64.vmoptions`, or environments where the user simply does not
need Git UI inside PyCharm).

The idea is to remove the VCS subsystem from PyCharm entirely. With
Git4Idea disabled there is no `VcsRepositoryManager`, no `MODIFY_LOCK`,
no `findNewRoots`, no `runBlocking` in `GitRepositoryImpl.<init>` — the
entire code path documented in §3 and §4 simply does not exist in the
process. All `git` work must then be done from the terminal.

Append to `~/.config/JetBrains/PyCharm2026.1/disabled_plugins.txt`:

```
Git4Idea
Subversion
```

`Subversion` is included for symmetry — none of these trees use SVN,
but the same plugin would scan if SVN repos appeared.

With the Git plugin disabled, `.idea/vcs.xml` mappings have no effect,
but it is still worth blanking the file so that nothing repopulates the
list if the plugin is ever re-enabled. All three umbrella projects'
`vcs.xml` were reduced to:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project version="4">
  <component name="VcsDirectoryMappings">
    <!-- Git plugin disabled at IDE level - see disabled_plugins.txt -->
  </component>
</project>
```

The trade-off is loss of all in-IDE Git UI: gutter blame, Changes view,
commit dialog, log, branch widget, push, etc. For users who want any
of that, use §5.3 instead.

### 5.3 The actual fix: bytecode-rewriting `-javaagent`

The active fix is a small Java agent
([`src/VcsPatchAgent.java`](../src/VcsPatchAgent.java), packaged as
[`dist/pycharm-vcs-patch.jar`](../dist/pycharm-vcs-patch.jar)) loaded
into PyCharm's JVM via `-javaagent` at launch. It uses ASM 9 to rewrite
exactly one bytecode instruction inside one method of one class:

- **Target:** `git4idea.repo.GitRepositoryImpl.<init>`, the call site
  shown at line 81 of the constructor (the same line called out at the
  bottom of the stack in §3.3).
- **Operation:** locate the unique `INVOKESTATIC` of
  `com.intellij.openapi.progress.CoroutinesKt.runBlockingMaybeCancellable`
  inside that constructor; replace it with `POP; ACONST_NULL;`. The
  Kotlin suspend lambda that was being run synchronously is dropped on
  the floor, a `null` is left on the stack for the subsequent return,
  and the constructor returns in microseconds instead of seconds.
- **Scope:** no other call site is touched. The other
  `runBlockingMaybeCancellable` of the same suspend function (inside
  `GitRepository.update()` / `GitRepositoryImpl$update$1`) is left
  intact, because that path is called from outside `MODIFY_LOCK` and
  the synchronous behaviour there is correct.
- **Safety:** the transformer matches by exact `(opcode, owner, name)`.
  If JetBrains rewrites the constructor (renames the helper, moves it,
  inlines it), the transformer finds zero call sites, logs a WARNING,
  and becomes a no-op. PyCharm then runs stock. There is no failure
  mode in which PyCharm runs with a half-patched constructor.

The state that `updateState()` would have populated synchronously
(current branch, HEAD, staging info) is left in its default empty
state. The state-flow is populated lazily a few hundred milliseconds
later, the first time any caller of `GitRepository.update()` runs —
typically the same alarm that triggered the construction, just *after*
`MODIFY_LOCK` has been released. UI elements that subscribe to the
state-flow (gutter, branch widget, Changes view, log) update reactively
when state becomes available. UI elements that read it synchronously
during the gap see "no current branch" for a fraction of a second.

Install:

```
~/.config/JetBrains/PyCharm2026.1/agents/pycharm-vcs-patch.jar
~/.config/JetBrains/PyCharm2026.1/pycharm64.vmoptions  ← contains:
    -javaagent:/home/srvadmin/.config/JetBrains/PyCharm2026.1/agents/pycharm-vcs-patch.jar
```

At launch you should see in stdout / `idea.log`:

```
[VcsPatchAgent] premain: target=git4idea/repo/GitRepositoryImpl.<init>
                neutralizing com/intellij/openapi/progress/CoroutinesKt.runBlockingMaybeCancellable
[VcsPatchAgent] neutralizing com/intellij/openapi/progress/CoroutinesKt.runBlockingMaybeCancellable(...)
                in git4idea/repo/GitRepositoryImpl.<init> (call site #1)
```

To revert: remove the `-javaagent:` line from `pycharm64.vmoptions` and
restart. PyCharm's own JARs are byte-for-byte unchanged on disk; the
patch only lives in the in-memory class image for the duration of one
JVM session. See [`README.md`](../README.md) for the full installation
guide, build instructions, and design rationale.

### 5.4 PyCharm-side vmoptions in place

The following were applied earlier in the diagnostic session and remain
useful regardless of which fix above is active:

```
~/.config/JetBrains/PyCharm2026.1/pycharm64.vmoptions
  -Xmx12000m
  -Dide.http.client.connect.timeout=20000
  -Dide.http.client.read.timeout=60000
  -Didea.connection.timeout=20000
  -Didea.read.timeout=60000
  -Djava.net.preferIPv4Stack=true
  -Dperformance.watcher.unresponsive.threshold.ms=20000
```

These address an unrelated 11-s `RegionUrlMapper` fetch
(`data.services.jetbrains.com/products` returns 26 MB) and silence the
freeze-dump spam, but do *not* fix the VCS deadlock — only §5.3 (or as
a fallback, §5.2) does.

### 5.5 Junie / AI Assistant disabled

`~/.config/JetBrains/PyCharm2026.1/disabled_plugins.txt` includes
`org.jetbrains.junie`. This removes one source of the network-dependent
coroutine suspend during shutdown save. Unrelated to VCS deadlock but
mentioned for completeness.

---

## 6. Verification

Four measured states of the same `rotek-apps` project, listed
left-to-right in the order they were tried:

| Metric | Original (167 mappings) | Trim-only attempt | Git4Idea disabled (§5.2) | `-javaagent` installed (§5.3) |
|---|---|---|---|---|
| Mappings in `vcs.xml` | 167 | 170+ (auto-re-added) | 0 (plugin gone) | 167 (unchanged) |
| Git UI inside PyCharm | yes (broken) | yes (broken) | **no** | **yes, working** |
| Threads at `checkAndUpdateRepositoryCollection` | 70 | 72 | 0 | **0** |
| `git4idea.repo.*` thread refs | dozens | dozens | 0 | a few (in-flight, non-blocking) |
| `GitRepositoryImpl.<init>` in-flight | several | several | n/a | **0** |
| Coroutines in `Cancelling` state | 2-4 (stuck) | 2 | 0 | **0** |
| Perfwatcher "Thread pool exhaustion" | every ~5 s, indefinitely | every ~5 s | none in 100 s | **none in first 80 s** |
| `UnindexedFilesIndexer - Finished` for rotek-apps | never reached | 49 s | 5.07 s | **4.6 s** |
| `exit dumb mode [rotek-apps]` | never | never observed clean | clean | **3.0 s after launch** |
| UI thread state | parked in modal pump | parked in modal pump | normal | normal `EventQueue.getNextEvent` |
| Process RSS | ~5.0 GiB | 5.1 GiB | 1.9 GiB | ~2.0 GiB |
| Outcome | hard kill required | hard kill required | clean, no Git UI | **clean, full Git UI** |

The agent column was captured live against PyCharm Community 2026.1
(build PY-261.23567.174) opening `rotek-apps` with all 167 mappings
intact and the `-javaagent` line wired into `pycharm64.vmoptions`. The
Git-disabled column was captured live on 2026-05-22 12:42 against
PyCharm PID 109331, dump at `/tmp/pycharm-nogit-124413.txt`.

The agent achieves the same indexer / freeze-watcher / RSS numbers as
the plugin-disabled approach **while preserving every Git feature
inside PyCharm**, because the in-lock `runBlocking` is what was
pathological, not VCS auto-discovery itself.

---

## 7. Operational guidance for similar projects

With the §5.3 agent loaded the N=167 multiplier is no longer
pathological — constructors return in microseconds, `MODIFY_LOCK` is
held only as long as it takes to enumerate roots, and `findNewRoots`
completes essentially instantly even for very large umbrella projects.
Most of the advice below is therefore now optional; it is retained as
hygiene that still applies if the agent is unloaded, replaced, or no
longer matches a future IntelliJ version.

- For projects that group many independent repositories, **the agent
  removes the need to trim `vcs.xml`** — 167 mappings work fine.
  Trimming used to be a hard requirement; with the agent installed,
  keep whatever mappings you actually want to commit through PyCharm.
- If the agent is *not* installed (e.g. a managed IDE that locks
  `pycharm64.vmoptions`), the older advice applies: cap the per-project
  VCS-mapping count below ≈10; above ≈100 the §3 pathology manifests on
  every refresh.
- The auto-detector still runs even with the agent. Clicking "Add
  roots" on the "Unregistered VCS roots detected" notification still
  appends every nested `.git` to `vcs.xml`. This is no longer harmful
  to performance with the agent, but it can still clutter the mapping
  list — use "Configure" to add only the roots you actually use.
- If the project lives on a FUSE/bindfs mount, losing inotify makes
  every PyCharm restart do a full rescan, which is slow even with the
  agent. Mount with `actimeo` tuning or use a native filesystem for
  the project root.
- Verify the agent is active on every PyCharm launch by checking for
  the two `[VcsPatchAgent]` lines in `idea.log` (see §5.3). After a
  JetBrains IDE update, also run `javap -c -p` on the patched
  `GitRepositoryImpl` class image to confirm `<init>` no longer
  contains a `runBlockingMaybeCancellable` reference. If the second
  marker is missing or the WARNING `patch had no effect` is logged,
  JetBrains has changed the target — the agent becomes a safe no-op
  and PyCharm runs stock, at which point the §5.2 fallback (or
  rebuilding the agent against the new target) becomes relevant.

---

## 8. Caveat: `/proc/PID/schedstat` is unreliable inside this LXC

During the diagnostic phase, repeated samples of `/proc/PID/schedstat`
showed `Δcpu = 0 ms` even while the process was clearly consuming CPU.
Example after the fix:

```
$ cat /proc/87509/schedstat
5670549 0 3                              # 5.6 ms total run time tracked
$ awk '{print $14+$15+$16+$17}' /proc/87509/stat   # via utime+stime in ticks
77                                       # 770 ms over 5 s — actual CPU
```

`/proc/PID/schedstat` requires `CONFIG_SCHEDSTATS=y` in the kernel and
correct accounting per task — both are inconsistent in unprivileged or
lxcfs-virtualised containers. **Use `/proc/PID/stat` field 14+15
(`utime + stime` in clock ticks, divide by `getconf CLK_TCK`) instead**
for accurate CPU sampling inside the container. Or use `top`/`htop`,
which read from `/proc/PID/stat` internally.

This does not change the substance of any earlier diagnostic — the
hangs were real and corroborated by the perfwatcher logs, the live
thread-dump waiter queues, and the user-visible UI freeze — but the
*specific framing* "0 ms CPU = deadlocked" was over-confident. The
correct framing is "70 threads parked on the same write lock, lock
holder parked on a `runBlocking`-of-a-suspended-child, no forward
progress on the user-visible task."

---

## Appendix A — Reproducing the diagnostic

To repeat any of this against a live PyCharm process:

```bash
JBR=/home/srvadmin/.local/share/JetBrains/Toolbox/apps/pycharm-community/jbr/bin
PID=$(ps -eo pid,args | awk '$2 ~ /pycharm-community\/bin\/pycharm$/ \
                            && $2 !~ /\.sh$/ {print $1; exit}')

# Heap
"$JBR/jcmd" "$PID" GC.heap_info

# Full thread + coroutine dump
"$JBR/jcmd" "$PID" Thread.print -l > /tmp/dump.txt

# CPU sampling (avoid schedstat in this container)
t1=$(awk '{print $14+$15+$16+$17}' /proc/$PID/stat); sleep 5
t2=$(awk '{print $14+$15+$16+$17}' /proc/$PID/stat)
echo "CPU consumed: $(( (t2-t1) * 1000 / $(getconf CLK_TCK) )) ms in 5s"

# Find the VCS-lock holder
grep -B0 -A40 "checkAndUpdateRepositoryCollection" /tmp/dump.txt \
  | grep -v "AbstractQueuedLongSynchronizer.acquire" | head -60
```

PyCharm's own freeze dumps under
`~/.cache/JetBrains/PyCharm2026.1/log/threadDumps-Dispatchers.IO-*/`
contain the same information plus coroutine-context details
(`ComputationState`, `RWMutexIdeaImpl@…`) that `jcmd Thread.print` does
not include.

---

## Appendix B — Files modified by this fix

The active fix (§5.3 agent):

```
/home/srvadmin/.config/JetBrains/PyCharm2026.1/agents/pycharm-vcs-patch.jar
  (compiled from src/VcsPatchAgent.java; ASM 9 bundled)

/home/srvadmin/.config/JetBrains/PyCharm2026.1/pycharm64.vmoptions
  appended: -javaagent:/home/srvadmin/.config/JetBrains/PyCharm2026.1/agents/pycharm-vcs-patch.jar
```

No `.idea/vcs.xml` file needs to be modified for the agent fix. The
167 mappings in `rotek-apps/.idea/vcs.xml` can remain as-is.

Files that remain from the §5.2 fallback path used earlier in the
diagnostic — they are now optional and can be reverted at any time:

```
/home/srvadmin/.config/JetBrains/PyCharm2026.1/disabled_plugins.txt
  Git4Idea          ← remove this line if you want Git UI back with the agent
  Subversion        ← remove this line if you want SVN support back

/media/srv-main-softdev/rotek-apps/.idea/vcs.xml          (blanked during §5.2)
/media/srv-main-softdev/projects/.idea/vcs.xml             (blanked during §5.2)
/media/srv-main-softdev/rnprivat/.idea/vcs.xml             (blanked during §5.2)
```

Snapshots of the various states (kept for forensics or rollback):

```
/media/srv-main-softdev/rotek-apps/.idea/vcs.xml.bak                            (28-mapping trim from 2026-05-12)
/media/srv-main-softdev/rotek-apps/.idea/vcs.xml.bloated-20260522-121124.bak    (167-mapping pre-disable state)
/media/srv-main-softdev/rotek-apps/.idea/vcs.xml.before-disable-<timestamp>.bak (170+ re-bloated state)
/media/srv-main-softdev/projects/.idea/vcs.xml.before-disable-<timestamp>.bak
/media/srv-main-softdev/rnprivat/.idea/vcs.xml.before-disable-<timestamp>.bak
```

Obsolete-but-harmless artefacts from earlier failed attempts:

```
/home/srvadmin/.config/JetBrains/PyCharm2026.1/early-access-registry.txt
  contains: vcs.root.detector.enabled=false,
            git.repository.root.detector.enabled=false
  (neither key is read by 2026.1; entries can be deleted but cost nothing)
```

Pre-existing unrelated fixes that are still in place:

```
/home/srvadmin/.config/JetBrains/PyCharm2026.1/pycharm64.vmoptions
  -Xmx12000m
  -Dide.http.client.connect.timeout=20000
  -Dide.http.client.read.timeout=60000
  -Didea.connection.timeout=20000
  -Didea.read.timeout=60000
  -Djava.net.preferIPv4Stack=true
  -Dperformance.watcher.unresponsive.threshold.ms=20000

/home/srvadmin/.config/JetBrains/PyCharm2026.1/disabled_plugins.txt
  also contains: org.jetbrains.junie  (disables AI Assistant / Junie)
```

## Appendix C — Re-enabling Git UI after the §5.2 fallback

If you were using the §5.2 plugin-disable workaround and want to switch
to the §5.3 agent (recovering Git UI inside PyCharm):

1. Install the agent: place
   `dist/pycharm-vcs-patch.jar` at
   `~/.config/JetBrains/PyCharm2026.1/agents/pycharm-vcs-patch.jar`
   and add a `-javaagent:...` line to `pycharm64.vmoptions` pointing
   at it. The repo's `./install.sh` does both.
2. Remove the lines `Git4Idea` and `Subversion` from
   `~/.config/JetBrains/PyCharm2026.1/disabled_plugins.txt`.
3. Restore `.idea/vcs.xml` from one of the `.bak` snapshots listed in
   Appendix B, or simply leave it blank and let PyCharm's
   auto-detector repopulate on first open — with the agent loaded,
   neither approach causes a hang.
4. Start PyCharm. Confirm the two `[VcsPatchAgent]` lines appear in
   `idea.log` and that Git UI elements (branch widget, Changes view,
   gutter) work.

If you instead want to roll back the agent (return to the §5.2 plugin-
disabled state):

1. Remove the `-javaagent:...` line from `pycharm64.vmoptions`.
2. Re-add `Git4Idea` (and optionally `Subversion`) to
   `disabled_plugins.txt`.
3. Blank `.idea/vcs.xml` per §5.2.

To roll back to fully stock PyCharm (no agent, Git plugin enabled),
just remove the `-javaagent:...` line and restart. The on-disk JAR can
stay; it is inert without the vmoptions reference.
