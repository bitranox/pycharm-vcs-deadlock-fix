# CLAUDE.md — pycharm_vcs_patch

Context for AI assistants (Claude Code) entering this directory.

## What this directory is

A self-contained Java agent that patches one specific `INVOKESTATIC`
inside `git4idea.repo.GitRepositoryImpl.<init>` — the call to
`com.intellij.openapi.progress.CoroutinesKt.runBlockingMaybeCancellable`
that wraps `workingTreeHolder.updateState()` — by replacing it with
`POP; ACONST_NULL;`. This defuses the deadlock that occurs when
`VcsRepositoryManager.checkAndUpdateRepositoryCollection` calls the
constructor N times under the global `MODIFY_LOCK` for a project with
N nested git repos.

It is **not** a JetBrains-supported plugin; it is a userland
workaround that operates by bytecode rewriting at JVM class-load
time. The patch is permanent for the JVM session — there is no
restore logic because the patched constructor is correct on its own
(the `updateState()` it skipped runs lazily later via the same
suspend function from `GitRepository.update()`, the existing alarm
path, or any other refresh trigger — all outside the global VCS
lock).

Earlier iterations of this agent (preserved in git history of this
directory) targeted `VcsRepositoryManager.findNewRoots` and used
batched rate-limiting + DumbService-triggered restore. Those
approaches work but trade one long IDE freeze for many shorter ones
because the lock-hold time during each batch is still significant.
The constructor patch removes the cost entirely.

See `README.md` for the user-facing guide.

## Architectural orientation

```
src/VcsPatchAgent.java            single-file Java source for the agent
dist/pycharm-vcs-patch.jar        compiled, packaged JAR (premain agent)
build.sh                          rebuild dist/*.jar from src/*
install.sh                        copy JAR to ~/.config/.../agents/ and
                                  wire it into pycharm64.vmoptions
README.md                         user docs
CLAUDE.md                         this file
```

The agent uses ASM 9 for bytecode rewriting. ASM is bundled into the
agent JAR itself (no runtime classpath dependency on PyCharm's own
ASM). The JAR is built with the JBR's bundled `javac`:

```
~/.local/share/JetBrains/Toolbox/apps/pycharm-community/jbr/bin/javac
```

ASM source is unpacked from PyCharm's Junie plugin location:

```
~/.local/share/JetBrains/PyCharm2026.1/ej/lib/asm-9.6.jar
```

## Related files outside this directory

- **Full diagnostic write-up** — symptom, live `jcmd` evidence,
  code-level root cause traced into IntelliJ Community source with
  line numbers, before/after metrics, and what the upstream JetBrains
  fix would look like:
  - `../Docs/pycharm_vcs_deadlock.md`

- **Mount-layer document** — bindfs vs kernel-bind + idmap discussion
  for the LXC container. Unrelated to the VCS deadlock at the code
  level, but they sometimes interact (FUSE mount → no inotify → every
  startup does a heavy scan → exacerbates whatever the VCS subsystem
  is doing):
  - `../Docs/mount_options.md`

- **PyCharm vmoptions** — the live IDE config; the agent is wired in
  here. If you remove the `-javaagent:` line, the patch is fully
  disabled.
  - `~/.config/JetBrains/PyCharm2026.1/pycharm64.vmoptions`

- **PyCharm logs** — search for `[VcsPatchAgent]` to see what the
  agent did at startup. Markers logged are: `premain:`, `patching`,
  `captured Class object:`, `restore:`.
  - `~/.cache/JetBrains/PyCharm2026.1/log/idea.log`

## Persistent memory references

Claude's auto-memory for the user (`srvadmin`) lives at
`~/.claude/projects/-home-srvadmin/memory/MEMORY.md` and indexes
individual memory files. Relevant entries that may exist:

- A reference memory pointing to *this* directory should be present
  there under a slug like `pycharm-vcs-patch-location`. If it is, it
  is the canonical place to look up where this work-around lives.
- If the memory references this directory but the entry is missing
  there, add it back with frontmatter type=`reference`.

When the user asks about PyCharm "Analyzing project to enable smart
features" hangs, or PyCharm freezing during indexing on big multi-repo
projects, prefer:

1. Pointing at this directory (and `../Docs/pycharm_vcs_deadlock.md`
   for the full analysis) **before** suggesting any new fix.
2. Checking whether the `-javaagent:` line in `pycharm64.vmoptions`
   is still present and the JAR exists on disk.
3. Grepping `idea.log` for `[VcsPatchAgent]` to confirm the agent
   loaded. Expected markers each session:
     - `premain: target=git4idea/repo/GitRepositoryImpl.<init> neutralizing …`
     - `neutralizing com/intellij/openapi/progress/CoroutinesKt.runBlockingMaybeCancellable(…) in git4idea/repo/GitRepositoryImpl.<init> (call site #1)`

If the agent's WARNING fires (`no runBlockingMaybeCancellable call
found in target class`), JetBrains has changed the constructor; the
agent needs updating. See `src/VcsPatchAgent.java` constants
`NEUTRALIZE_OWNER` / `NEUTRALIZE_NAME` / `TARGET_INTERNAL`.

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

CPU sampling that actually works inside this LXC (don't use
`/proc/PID/schedstat` — it returns near-zero regardless of activity):

```bash
HZ=$(getconf CLK_TCK)
t1=$(awk '{print $14+$15+$16+$17}' /proc/$PID/stat); sleep 5
t2=$(awk '{print $14+$15+$16+$17}' /proc/$PID/stat)
echo "CPU consumed: $(( (t2-t1) * 1000 / HZ )) ms in 5s"
```

Detect the deadlock pattern in a dump:

```bash
DUMP=/tmp/dump.txt
echo "VCS waiters:    $(grep -c checkAndUpdateRepositoryCollection "$DUMP")"
echo "ctors in flight:$(grep -c 'GitRepositoryImpl.<init>' "$DUMP")"
echo "cancelling:     $(grep -c '{Cancelling}' "$DUMP")"
```

If `VCS waiters >= ~10` and CPU is near-idle, you are looking at the
same pathology this patch addresses. Either the patch isn't loaded
(check `idea.log`), it was restored prematurely (rare), or there's a
distinct bug.

## Don't accidentally break this

- Do **not** add `Git4Idea` or `Subversion` to `disabled_plugins.txt`.
  Earlier diagnostic sessions tried that as a workaround; it works but
  removes the entire Git UI. The agent makes that unnecessary.
- Do **not** put `<mapping … vcs="">` on parents in `.idea/vcs.xml`
  expecting them to suppress auto-detection — they don't. Longest-path
  match means more-specific Git mappings still get added.
- Do **not** put `vcs.root.detector.enabled=false` in
  `early-access-registry.txt` — that key name is not read by 2026.1.
- The right way to stop a particular repo being tracked is to
  remove its `<mapping>` line from `vcs.xml` (PyCharm closed), or use
  *Settings → Version Control → Directory Mappings* in the UI.

## Don't accidentally break the agent

- The transformer targets the **first** INVOKESTATIC of
  `runBlockingMaybeCancellable` inside `<init>`. If JetBrains adds
  another such call to the constructor before the existing one, we
  would neutralize it instead — likely incorrect. Read the byte-code
  via `javap -c -p` after a Toolbox update; the patched method should
  contain no `runBlockingMaybeCancellable` references in `<init>`
  (other methods of GitRepositoryImpl are fine).
- The other call site (in `update()` / `GitRepositoryImpl$update$1`)
  is intentionally **not** patched. That path is called from outside
  any global lock and the synchronous behaviour is desired —
  user-initiated refresh should block until state is fresh.
- The agent intercepts only by exact `(opcode, owner, name)` match. A
  rename of the helper or a move to a different class would cause the
  agent to find zero call sites and log a WARNING. The agent then
  becomes a no-op and PyCharm runs stock — failure mode is safe.
