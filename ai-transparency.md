# AI transparency

This repository was developed with substantial assistance from
**Claude** (Anthropic's AI, model "Claude Opus 4.7"), invoked via the
Claude Code CLI. This document describes honestly what the AI did and
what the human (the repository owner, [@bitranox](https://github.com/bitranox))
did, so anyone evaluating the code can decide for themselves how much
trust to extend.

## What the AI did

- **Diagnosis.** Read `jcmd Thread.print` output live against a stuck
  PyCharm process, identified the lock holder
  (`DefaultDispatcher-worker-25` parked in `LockSupport.parkNanos`
  inside `runBlockingMaybeCancellable`), traced the call chain back
  to `VcsRepositoryManager.checkAndUpdateRepositoryCollection`, and
  cross-referenced it against the IntelliJ Community source on
  GitHub to pinpoint the exact line numbers (`VcsRepositoryManager.kt:354`,
  `GitRepositoryImpl.kt:81`, `GitWorkingTreeHolderImpl.kt:37`).
- **Authoring.** Wrote the Java source of the agent
  (`src/VcsPatchAgent.java`), including the ASM bytecode-rewrite
  visitor that replaces `INVOKESTATIC ...runBlockingMaybeCancellable`
  with `POP; ACONST_NULL;` inside `GitRepositoryImpl.<init>`.
- **Documentation.** Drafted the README, this file, the
  CLAUDE.md context file, the `docs/DIAGNOSTIC.md` write-up with
  before/after measurements, the `jetbrains_submit.md` upstream-fix
  proposal, the `LICENSE`, the `NOTICE` file for bundled ASM, and the
  `build.sh` / `install.sh` scripts.
- **Iteration.** Built and tested multiple agent designs over the
  course of one debugging session, including approaches that did NOT
  work (rate-limited batching with restore via
  `Instrumentation.redefineClasses`, which failed due to the JVM rule
  that redefinition cannot add or remove methods). Those failed
  attempts informed the final, simpler design — and are documented
  in `CLAUDE.md` and git history so future readers don't repeat them.

## What the human did

- **Provided the failing system.** A live PyCharm install on a real
  project with 167 nested git repositories that reproduced the
  deadlock — the canonical test case throughout development.
- **Ran and observed.** Every PyCharm restart, every agent rebuild,
  every `jcmd` thread dump, every live activity sample was executed
  on the human's machine. The AI proposed; the human gave the
  go-ahead, watched the results, and reported what the UI actually
  did (which sometimes diverged from what metrics suggested — e.g.,
  the "it hangs again" feedback that drove the move from
  `findNewRoots` batching to the deeper constructor patch).
- **Made the architectural calls.** Decisions to keep vs. revert
  workarounds, to switch from time-based to event-driven restore,
  to drop the restore logic entirely once the constructor patch
  proved sufficient, to escalate from `findNewRoots` to
  `GitRepositoryImpl.<init>` — all of those were the human steering
  the AI.
- **Pushed the code.** All `git commit` and `git push` operations
  happened with the human's authority (the AI executes commands in
  the human's shell session). The author of record on each commit
  is the human; the human is fully responsible for what is published
  under their GitHub account.

## Verification

The agent was tested live, not just by analysis. Numbers in
`README.md` and `docs/DIAGNOSTIC.md` come from actual
`/proc/PID/stat` CPU samples, actual `jcmd Thread.print` dumps,
and actual `idea.log` markers from running PyCharm processes during
the session. Specifically:

| Before agent | After agent |
|---|---|
| `UnindexedFilesIndexer - Finished`: never reached | 4.6–5.9 s |
| `exit dumb mode [rotek-apps]`: never | ~3 s after launch |
| Perfwatcher freezes in first 80 s: many | 0 |
| `checkAndUpdateRepositoryCollection` lock waiters: 70+ | 0 |
| UI thread state: parked in modal pump | normal event-pump |
| Process RSS: 5+ GiB | ~2 GiB |

These are reproducible with the same agent + a project with many
nested git repositories on PyCharm Community 2026.1.

## What you can do to trust it

The agent is small enough to audit end-to-end:

- `src/VcsPatchAgent.java` is one file, ~200 lines including
  generous comments. The bytecode-emit block is the only
  non-trivial section; the rest is a standard ASM `ClassVisitor`.
- The packaged `dist/pycharm-vcs-patch.jar` bundles ASM 9.6
  **unmodified** from the copy that ships inside JetBrains' own Junie
  plugin (`~/.local/share/JetBrains/PyCharmXXXX.X/ej/lib/asm-9.6.jar`).
  You can verify by extracting both JARs and `diff`-ing the
  `org/objectweb/asm/...` directories.
- `build.sh` rebuilds the JAR from source using only PyCharm's own
  bundled JBR `javac` and the same ASM JAR. If you don't trust the
  pre-built JAR, run `./build.sh && ./install.sh` and use the JAR
  you just built.
- `install.sh` only writes to `~/.config/JetBrains/PyCharmXXXX.X/`:
  copies the JAR into `agents/` and appends one `-javaagent:` line
  to `pycharm64.vmoptions`. Nothing else on the system is touched.
- PyCharm's own installation on disk is **never modified**. The
  bytecode change exists only in JVM memory, and only after the
  agent fires.
- Uninstall: delete one line from `pycharm64.vmoptions` and restart.

## What this isn't

- Not a JetBrains product. JetBrains has not endorsed or reviewed
  this. The `jetbrains_submit.md` file documents the path to
  reporting the underlying bug upstream so a real JetBrains fix can
  ship and this agent can be retired.
- Not a security tool. The agent has no network code, no telemetry,
  and no persistence beyond the configured JVM flag. It does exactly
  one thing to one method in one class.
- Not a substitute for understanding what your IDE is doing. If you
  install it, read at least the README and skim `src/VcsPatchAgent.java`
  so you know which line of bytecode is being changed and why.

## License and attribution

The Apache-2.0 LICENSE applies to the human's contribution. AI-generated
text and code embedded in this repository are released under the same
license as the rest of the work, in line with Anthropic's usage terms
which place ownership of model outputs with the user.

If you build on this work, attribution to the human's GitHub username
is appreciated but not required by the license.
