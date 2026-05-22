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

## 5. Applied configuration fix

We cannot patch IntelliJ. The N=167 multiplier in §3.5 can be defused
by either (a) reducing N to a single-digit number, or (b) eliminating
the entire VCS subsystem so the multiplier is structurally absent.
**(a) was tried and failed; (b) works and is what is in place.**

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

### 5.2 The working fix: disable Git4Idea (and Subversion) at the IDE level

The reliable cure is to remove the VCS subsystem from PyCharm entirely.
With Git4Idea disabled there is no `VcsRepositoryManager`, no
`MODIFY_LOCK`, no `findNewRoots`, no `runBlocking` in
`GitRepositoryImpl.<init>` — the entire code path documented in §3 and
§4 simply does not exist in the process. All `git` work must be done
from the terminal, which is how this project was being used in practice
anyway.

Appended to `~/.config/JetBrains/PyCharm2026.1/disabled_plugins.txt`:

```
Git4Idea
Subversion
```

`Subversion` is included for symmetry — none of these trees use SVN,
but the same plugin would scan if SVN repos appeared.

### 5.3 vcs.xml files reduced to empty comments

Once the Git plugin is disabled the mappings have no effect, but the
files are cleaned out so that if the plugin is ever re-enabled in
future, nothing immediately repopulates them. All three umbrella
projects:

```
/media/srv-main-softdev/rotek-apps/.idea/vcs.xml
/media/srv-main-softdev/projects/.idea/vcs.xml
/media/srv-main-softdev/rnprivat/.idea/vcs.xml
```

were reduced to:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project version="4">
  <component name="VcsDirectoryMappings">
    <!-- Git plugin disabled at IDE level - see disabled_plugins.txt -->
  </component>
</project>
```

Each previous state is preserved as `.idea/vcs.xml.before-disable-<timestamp>.bak`
plus the original `.bak` from 2026-05-12.

### 5.3 PyCharm-side vmoptions already in place

For completeness, these were applied earlier in this session and remain
relevant:

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
freeze-dump spam, but do *not* fix the VCS deadlock — only the §5.1 + §5.2
configuration changes do.

### 5.4 Junie / AI Assistant disabled

`~/.config/JetBrains/PyCharm2026.1/disabled_plugins.txt` includes
`org.jetbrains.junie`. This removes one source of the network-dependent
coroutine suspend during shutdown save. Unrelated to VCS deadlock but
mentioned for completeness.

---

## 6. Verification

Three measured states of the same `rotek-apps` project:

| Metric | Original (167 mappings, Git enabled) | Trim-only attempt (re-bloated to 170+) | After Git4Idea disabled |
|---|---|---|---|
| Mappings in `vcs.xml` | 167 | 170+ (auto-re-added) | 0 (Git plugin gone) |
| Threads at `checkAndUpdateRepositoryCollection` | 70 | 72 | **0** |
| `git4idea.repo.*` thread refs | dozens | dozens | **0** |
| `Coroutines in Cancelling state` | 2-4 (stuck) | 2 | **0** |
| Perfwatcher "Thread pool exhaustion" rate | every ~5 s, indefinitely | every ~5 s | **none in 100 s** |
| `UnindexedFilesIndexer - Finished` for rotek-apps | never reached | 49 s | **5.07 s** |
| UI thread state | parked in modal pump | parked in modal pump | normal `EventQueue.getNextEvent` |
| Process RSS | ~5.0 GiB | 5.1 GiB | **1.9 GiB** |
| CPU pattern (8 s sample) | 0 forward progress | 0 forward progress | 35 s indexer burst, then idle <1 s |
| Outcome | hard kill required | hard kill required | **clean, usable** |

The Git-disabled column was captured live on 2026-05-22 12:42 against
PyCharm PID 109331, dump at `/tmp/pycharm-nogit-124413.txt`.

---

## 7. Operational guidance for similar projects

- **Cap the per-project VCS-mapping count below ≈10.** Above ≈30 the
  IDE becomes visibly sluggish; above ≈100 the pathology in §3
  manifests on every refresh.
- For umbrella projects that group many independent repositories,
  prefer one of these patterns:
  - Single Git mapping on the umbrella, `<mapping ... vcs="" />` on the
    parents of every nested-repo subtree (the §5.1 pattern).
  - One PyCharm project per active repository, switched via
    *File → Open Recent*.
  - One project with multiple Content Roots (Settings → Project
    Structure) but `vcs=""` on everything except the active root.
- **Do not click "Add roots" on the "Unregistered VCS roots detected"
  notification.** It will silently re-bloat `vcs.xml`. Click
  "Configure" and add only what you actually need, or "Ignore" all.
  Or just leave the auto-detector disabled (§5.2).
- If the project lives on a FUSE/bindfs mount, see
  `mount_options.md` — losing inotify makes every PyCharm restart do a
  full rescan, which interacts badly with VCS re-detection.

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

The working fix:

```
/home/srvadmin/.config/JetBrains/PyCharm2026.1/disabled_plugins.txt
  appended: Git4Idea
  appended: Subversion

/media/srv-main-softdev/rotek-apps/.idea/vcs.xml          (mappings removed)
/media/srv-main-softdev/projects/.idea/vcs.xml             (mappings removed)
/media/srv-main-softdev/rnprivat/.idea/vcs.xml             (mappings removed)
```

Snapshots of the various states (kept for forensics or rollback):

```
/media/srv-main-softdev/rotek-apps/.idea/vcs.xml.bak                       (28-mapping trim from 2026-05-12)
/media/srv-main-softdev/rotek-apps/.idea/vcs.xml.bloated-20260522-121124.bak  (167-mapping pre-disable state)
/media/srv-main-softdev/rotek-apps/.idea/vcs.xml.before-disable-<timestamp>.bak  (170+ re-bloated state)
/media/srv-main-softdev/projects/.idea/vcs.xml.before-disable-<timestamp>.bak
/media/srv-main-softdev/rnprivat/.idea/vcs.xml.before-disable-<timestamp>.bak
```

The earlier failed attempts left these artefacts that are now obsolete
but harmless:

```
/home/srvadmin/.config/JetBrains/PyCharm2026.1/early-access-registry.txt
  contains: vcs.root.detector.enabled=false,
            git.repository.root.detector.enabled=false
  (neither key is read by 2026.1; entries can be deleted but cost nothing)
```

Pre-existing unrelated fixes from earlier in the same diagnostic session
that are still in place:

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

## Appendix C — Re-enabling Git later, safely

If at some point Git UI inside PyCharm becomes desirable again:

1. Remove the lines `Git4Idea` and `Subversion` from
   `~/.config/JetBrains/PyCharm2026.1/disabled_plugins.txt`.
2. Before restarting PyCharm, populate `.idea/vcs.xml` with only the
   few mappings you actually need — typically just the project's own
   root: `<mapping directory="$PROJECT_DIR$" vcs="Git" />`.
3. Open the project. When the "Unregistered VCS roots detected"
   notification appears (it will), **do not click "Add roots"**. Click
   "Configure", review the list, and add only the roots you intend to
   actively commit through PyCharm.
4. If you forget step 3 and the file balloons again, the recovery is
   to kill PyCharm, re-trim `vcs.xml`, and try again. Keep N below ≈10
   permanently.

If "I want gutter blame and Changes view but only for repo X" is the
goal, the cleanest pattern is **one PyCharm project per active repo**,
opened individually via *File → Open Recent*. Umbrella projects above
~10 nested repos remain a footgun in 2026.1.
