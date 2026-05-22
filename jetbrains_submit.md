# Submitting this issue / fix to JetBrains

YouTrack is JetBrains' official issue tracker — the right venue for this.
Three options, from easiest to most invasive.

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

Before filing new, search YouTrack for `"VCS root" submodule deadlock`
and `"checkAndUpdateRepositoryCollection"`. Known candidate:

- **IDEA-188998** — *"VCS root discovery is slow with many
  submodules"*. Same neighbourhood; could be the same bug viewed
  from a different angle. Adding our diagnostic to an existing
  ticket is usually higher-signal than a duplicate file. Reference
  our doc and the live thread dump.

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
