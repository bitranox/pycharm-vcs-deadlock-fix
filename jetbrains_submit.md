# Submitting this issue / fix to JetBrains

YouTrack is JetBrains' official issue tracker — the right venue for this.
Three options, from easiest to most invasive.

**As of 2026-05, two open tickets already describe this deadlock** — see §2
for the IDs and drafted comments. Prefer commenting on an existing ticket
over filing a new one; duplicates get closed and lose the diagnostic detail.

The root cause is two-pronged and both prongs should be mentioned in any
ticket:

- `git4idea.repo.GitRepositoryImpl.<init>` performs a synchronous
  `runBlockingMaybeCancellable { workingTreeHolder.updateState() }` (at
  `GitRepositoryImpl.kt:81`) — a blocking wait on a suspend function
  that itself acquires further locks and runs a `git` subprocess.
- `com.intellij.dvcs.repo.VcsRepositoryManager.checkAndUpdateRepositoryCollection`
  (at `VcsRepositoryManager.kt:368`) calls that constructor N times
  while holding the global `MODIFY_LOCK` write lock.

Each call individually is bearable; the combination is catastrophic
for any non-trivial multi-repo project.

## 1. File a new ticket

URL: <https://youtrack.jetbrains.com/newIssue?project=IDEA>

- Project: **IntelliJ IDEA** (the platform; VCS code lives there,
  not in PyCharm).
- Subsystem: **VCS / Git** (or **Version Control. Git**, exact label
  varies).
- Title suggestion:
  *"GitRepositoryImpl.<init> calls runBlockingMaybeCancellable while VcsRepositoryManager.MODIFY_LOCK is held; deadlocks IDE on multi-repo projects"*.

Attach as evidence:

- Full diagnostic write-up:
  `proxmox01/Docs/pycharm_vcs_deadlock.md`
- Live `jcmd Thread.print` dump showing the lock chain
  (`/tmp/pycharm-deadlock-120808.txt` from the diagnostic session).
- This directory `pycharm_vcs_patch/` — both as proof-of-concept that
  removing the blocking call from `<init>` resolves the deadlock, and
  as a workaround users can install today.

The key data points they need:

1. **Thread dump showing the lock chain.** `DefaultDispatcher-worker-25`
   parked in `LockSupport.parkNanos` inside `joinBlocking` →
   `runBlockingMaybeCancellable` → `GitRepositoryImpl.<init>:81` →
   `createInstance` → `tryCreateRepository` → `findNewRoots` →
   `checkAndUpdateRepositoryCollection` (which held `MODIFY_LOCK` at
   line 368). The 70+ waiting threads in the same dump are queued on
   the lock, doing nothing.
2. **IntelliJ Community source line numbers** for the offending path:
   - `platform/dvcs-impl/src/com/intellij/dvcs/repo/VcsRepositoryManager.kt`
     lines 165 (`ensureUpToDate`), 354 (`MODIFY_LOCK.lock()`), 368-373
     (the lambda invoked under the lock), 408 (`findNewRoots`).
   - `plugins/git4idea/src/git4idea/repo/GitRepositoryImpl.kt` line 81
     (the `runBlockingMaybeCancellable { workingTreeHolder.updateState() }`).
   - `plugins/git4idea/src/git4idea/repo/GitWorkingTreeHolderImpl.kt`
     line 37 (`@RequiresBackgroundThread` + the `withLock(updateLock)`
     + `messageBus.syncPublisher` + `Git.listWorktrees` chain the
     constructor's `runBlocking` waits on).
3. **Reproducer**: any project containing N ≥ 100 nested `.git`
   directories where `.idea/vcs.xml` lacks mappings for them, OR
   where the user clicks *"Add roots"* on the "Unregistered VCS
   roots detected" popup. PyCharm hangs on *"Analyzing project to
   enable smart features"* for many minutes. With N ≈ 167 (our test
   case) the lock is held continuously; the IDE never becomes
   responsive without external intervention.
4. **Workaround proof-of-concept** (this repo): a `-javaagent` that
   neutralizes the single INVOKESTATIC of
   `runBlockingMaybeCancellable` inside
   `git4idea/repo/GitRepositoryImpl.<init>` — replacing it with
   `POP; ACONST_NULL;`. Measured outcome on the same 167-repo
   project: indexing completes in ~5 s, dumb mode exits in ~3 s,
   zero perfwatcher freezes, no lock waiters, all VCS features
   continue to work normally because `updateState()` runs lazily via
   other paths.

## 2. Comment on an existing ticket

Two confirmed open tickets match (verified 2026-05 via the YouTrack
public API):

- **[IJPL-244177](https://youtrack.jetbrains.com/issue/IJPL-244177)** —
  *"Git4Idea deadlock with 80+ repos"*. State: Submitted, unassigned.
  Reported against PyCharm 2026.1.1; reporter calls it a regression
  from 2025.3. Description names every component this agent targets:
  `VcsRepositoryManager` write lock held by `GitRepositoryImpl.<init>`
  at `GitRepositoryImpl.kt:81` blocked inside `runBlockingMaybeCancellable
  → runBlockingCancellable → runBlocking`, with dozens of dispatcher
  workers queued on `checkAndUpdateRepositoryCollection` at
  `VcsRepositoryManager.kt:354`. Reporter's workaround is
  `-Dkotlinx.coroutines.io.parallelism=256` (mask, not fix).
- **[IJPL-244426](https://youtrack.jetbrains.com/issue/IJPL-244426)** —
  *"Editor freezes on every keystroke at AsyncCompletion.startThread in
  2026.1.1 (thread starvation by VcsRepositoryManager.checkAndUpdateRepositoryCollection)"*.
  State: Submitted, unassigned. Different surface symptom (per-keystroke
  EDT freeze on an `AsyncCompletion` semaphore + concurrent
  `RegionUrlMapper` HTTP timeouts) but the title attributes it to the
  same `Dispatchers.IO` starvation. Plausibly a downstream manifestation
  of IJPL-244177 once the pool is wedged.

The drafted comments below are kept here so they version-control with
the repo; copy/paste into YouTrack's comment box (it accepts markdown).

### Draft comment for IJPL-244177

```markdown
Confirming the same diagnosis from a separate reproducer (PyCharm Community
2025.x on Linux, project with ~167 nested `.git` directories). Thread dump
shows exactly the chain described in the OP:

- One `DefaultDispatcher-worker-N` parked in `LockSupport.parkNanos` inside
  `joinBlocking` → `runBlockingMaybeCancellable` → `GitRepositoryImpl.<init>`
  at `GitRepositoryImpl.kt:81` (the `workingTreeHolder.updateState()` call),
  holding `VcsRepositoryManager.MODIFY_LOCK`.
- 70+ other dispatcher workers `WAITING` on that same lock inside
  `VcsRepositoryManager.checkAndUpdateRepositoryCollection`
  (`VcsRepositoryManager.kt:354`).
- The coroutine the lock-holder is blocking on cannot be scheduled because
  the entire pool is queued on the lock it holds — classic resource deadlock.
  `Thread pool exhaustion: Dispatchers.IO is not responding for 5000 ms`
  every ~5 s for the rest of the session.

The `-Dkotlinx.coroutines.io.parallelism=256` workaround masks the deadlock
by giving the pool enough headroom that the unblocking coroutine can sneak
onto a free worker before all workers are queued. It is fragile: any project
large enough to queue 256+ workers on the lock brings the deadlock back. The
fix should remove the cycle, not enlarge the pool.

### Root cause is in the constructor, not the manager

`GitRepositoryImpl.<init>` synchronously bridges into a suspend function
while the caller (`checkAndUpdateRepositoryCollection`) holds a global write
lock. Construction is N × that bridge cost while the lock is held, and the
suspend function itself wants a worker — the deadlock is structural.

### Minimal fix (one line)

`plugins/git4idea/src/git4idea/repo/GitRepositoryImpl.kt` around line 81 —
drop the `runBlockingMaybeCancellable { workingTreeHolder.updateState() }`
call from the constructor. The first real `updateState()` then happens
lazily via `GitRepository.update()` / the existing alarm path / any later
refresh trigger — all of which already run *outside* `MODIFY_LOCK` on their
own dispatcher. State consumers (gutter, annotations, log) subscribe to the
state-flow and update reactively, so the transient empty state at
end-of-ctor is handled the same way it's handled after any refresh.

### Defence in depth

Independently, `VcsRepositoryManager.checkAndUpdateRepositoryCollection`
should construct the new `GitRepositoryImpl` instances **outside**
`MODIFY_LOCK` and only take the lock for the atomic map swap. That removes
the O(N) blocking work from inside the lock regardless of what individual
constructors do.

### Proof-of-concept workaround

I have a userland `-javaagent` that performs exactly the constructor change
above by ASM-rewriting the offending `INVOKESTATIC` at JVM class-load time
(replaces it with `POP; ACONST_NULL;`). Same 167-repo reproducer: indexing
completes in ~5 s, dumb mode exits in ~3 s, zero perfwatcher freezes, no
lock waiters, all VCS features (gutter, annotations, log, branch ops,
push/pull) continue to work normally.

Source, build script, install script:
<https://github.com/bitranox/pycharm-vcs-deadlock-fix>

Possibly the same root cause as IJPL-244426 (different surface symptom,
same attribution in the title).
```

### Draft comment for IJPL-244426

```markdown
The title attributes this to "thread starvation by
`VcsRepositoryManager.checkAndUpdateRepositoryCollection`", which is
consistent with the unresolved IJPL-244177 ("Git4Idea deadlock with 80+
repos"). If that is in fact the root cause here, the
EDT-on-`AsyncCompletion`-semaphore freeze is a downstream symptom:
`Dispatchers.IO` is wedged because one worker holds
`VcsRepositoryManager.MODIFY_LOCK` while parked inside
`GitRepositoryImpl.<init>` → `runBlockingMaybeCancellable` (at
`GitRepositoryImpl.kt:81`), and every other worker is queued on the same
lock. With the pool exhausted, anything that schedules onto it starves —
including completion contributors and `RegionUrlMapper`'s `HttpClient`
call. That would explain the otherwise-puzzling combination of an
idle-looking pool plus the "Thread pool exhaustion: Dispatchers.IO is not
responding for 5000 ms" log spam plus the `HttpConnectTimeoutException`
against a host the JBR can otherwise reach in <1 s.

### Quick way to confirm or rule out

At the moment of freeze, capture a thread dump
(`jcmd <pid> Thread.print -l`) and check:

\`\`\`bash
grep -c checkAndUpdateRepositoryCollection dump.txt   # waiters on MODIFY_LOCK
grep -c 'GitRepositoryImpl.<init>'         dump.txt   # ctors in flight
\`\`\`

If `waiters >= ~10` and one thread is parked in `joinBlocking` inside
`GitRepositoryImpl.<init>:81`, this is IJPL-244177 manifesting as a UI
freeze rather than as a startup hang. If neither pattern is present, the
`AsyncCompletion` semaphore lost-wakeup is an independent bug and this
ticket should stay separate.

### If it is the same root cause

The fix is to remove the
`runBlockingMaybeCancellable { workingTreeHolder.updateState() }` call
from `GitRepositoryImpl.<init>` — first state population then happens
lazily via `GitRepository.update()` / the existing alarm path, which run
outside `MODIFY_LOCK` on their own dispatcher.

### Workaround for reproduction / triage

A userland `-javaagent` that performs that single bytecode change at
class-load time (ASM rewrite of one `INVOKESTATIC` to `POP; ACONST_NULL;`):
<https://github.com/bitranox/pycharm-vcs-deadlock-fix>

If installing it makes this ticket's freezes disappear, that's strong
evidence the root cause is shared with IJPL-244177. If freezes persist
after install (and the agent logs `neutralizing ... call site #1` in
`idea.log`), the `AsyncCompletion` semaphore issue is independent and
the ticket should stay open as its own thing.
```

## 3. Code PR to intellij-community

Repository: <https://github.com/JetBrains/intellij-community>

There are two independent code changes that would each, on their
own, fix the user-visible symptom. JetBrains may want to apply
both; the simpler one is sufficient.

### 3a. Minimal fix — remove the blocking call from the constructor

File: `plugins/git4idea/src/git4idea/repo/GitRepositoryImpl.kt`
(around line 81).

```kotlin
// before:
init {
    // … initialize holders …
    runBlockingMaybeCancellable {
        workingTreeHolder.updateState()
    }
}

// after:
init {
    // … initialize holders …
    // First state population happens lazily via workingTreeHolder.update()
    // which is invoked from GitRepository.update() and from the existing
    // alarm-driven refresh path. Both run outside any global VCS lock.
}
```

This is exactly what the agent in this directory does at runtime, just
expressed in source. The constructor returns immediately; the
`workingTreeHolder._state` starts at its default empty value;
features that consume the state subscribe to the StateFlow and
react when it becomes non-empty. The first asynchronous update
happens almost immediately after the constructor returns, but on
its own dispatcher with no global lock held.

Risk surface: anything that synchronously reads
`workingTreeHolder.state` immediately after `<init>` (without
subscribing) gets an empty value. We have not seen this manifest
in measurement; the IDE handles transient empty state from refresh
already.

### 3b. Defence in depth — move construction out of MODIFY_LOCK

File: `platform/dvcs-impl/src/com/intellij/dvcs/repo/VcsRepositoryManager.kt`
(around lines 354-408).

```kotlin
// Before: per-repo construction happens inside the write lock.
fun checkAndUpdateRepositoryCollection() {
    MODIFY_LOCK.withLock {
        val invalid = findInvalidRoots(repositoryListSnapshot.values)
        // …
        repositoryListSnapshot.putAll(findNewRoots(…))   // ← O(N) blocking inside lock
    }
}

// After: copy-on-write swap. Discover candidates under the lock
// (cheap), construct outside the lock (parallel-safe), swap atomically.
fun checkAndUpdateRepositoryCollection() {
    val candidates: Set<VirtualFile> = MODIFY_LOCK.withLock {
        buildCandidateListFromSnapshot()    // cheap, reads metadata only
    }
    val newRepos: Map<VirtualFile, Repository> =
        candidates.map { tryCreateRepository(it) }
                  .filterNotNull()
                  .toMap()
    MODIFY_LOCK.withLock {
        repositoryMap.putAll(newRepos)      // atomic swap, fast
        // …
    }
}
```

This removes the pathological O(N) work from inside the lock
regardless of whether 3a is also applied. Even if individual
constructors keep their `runBlocking`, they run in parallel on
`Dispatchers.IO` instead of serialised under one write lock.

### Submission process

- PRs require signing the JetBrains Contributor License Agreement.
  They almost always want a corresponding YouTrack ticket first;
  link it in the PR description.
- The 3a patch is essentially one removed line plus a comment; it
  is a much easier PR to land than 3b.

## What to make clear in the report

The userland agent in this directory (`pycharm_vcs_patch/`) is a
**proof-of-concept and workaround**. It rewrites
`GitRepositoryImpl.<init>`'s bytecode at JVM class-load time to skip
exactly the call described in §3a. It is not something JetBrains
would (or should) ship — but it is concrete demonstration that the
proposed code change resolves the symptom completely, with no
observed regression. Reference it in the ticket as evidence; don't
ask JetBrains to accept it.

If JetBrains applies §3a (the trivial fix) the agent becomes
unnecessary and can be uninstalled by removing one line from
`pycharm64.vmoptions`.
