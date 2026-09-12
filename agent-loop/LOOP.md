# VeriTransit Agent Work Loop

A two-agent development loop that works the repo's issue tracker autonomously:

- **Worker** — picks up open issues (highest severity first), implements the fix
  on a branch, opens a PR, and posts evidence.
- **Critic / advisor** — independently verifies every claim the worker makes,
  critiques the implementation, files new evidence-backed issues, and approves
  or rejects PRs.

Neither agent is trusted on its own. The loop is designed so that a wrong claim
("tests pass", "this handles the overage case") is caught by the other agent
before it reaches `main`.

## Ground rules (both agents)

1. **No hallucinations.** Never claim a build/test/device result you did not
   observe. Every claim in a PR, comment, or issue must carry evidence: the
   command run and its actual output (path to a log or a pasted excerpt).
   If you cannot verify something, say exactly that.
2. **No invented APIs.** Before calling a third-party API (GenieX SDK, QNN,
   Android), confirm it exists — decompile the artifact (`javap -classpath …`)
   or find it in the dependency sources — and cite how you verified it.
3. **No hardcoding.** Production code must not embed values that a model, the
   SDK metadata, or the manifest should supply. Constants belong in tests as
   *expected* values, never in product code as *substituted* ones.
4. **Cite file:line** for every criticism. A critique without a location and a
   repro/reasoning is noise, not review.
5. **Scope discipline.** One issue per branch/PR. Never mix unrelated cleanup
   into a fix. Never touch files another agent has claimed (check for
   `in-progress` labels / branch locks below).

## Worker protocol

1. `gh issue list --label agent-loop --state open` — pick the highest severity
   (`severity:high` > `severity:medium` > `severity:low`) that has neither
   `in-progress` nor `blocked`, and that has no open PR yet (search PRs for
   `(#<n>)` in the title).
2. Claim it: `gh issue edit <n> --add-label in-progress` and comment with the
   branch name (`fix/issue-<n>-<slug>`).
3. Branch from current `origin/main`. Read the code before changing it. Write
   the change **and** the test that proves it (JVM unit tests preferred —
   on-device tests self-skip without hardware).
4. Verify before opening the PR:
   ```bash
   GRADLE_USER_HOME="$PWD/.gradle-home" \
     JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
     ./gradlew :app:compileDebugKotlin :app:testDebugUnitTest \
               :app:compileDebugAndroidTestKotlin :telegram-bot:test
   ```
   Paste the tail of the run (BUILD SUCCESSFUL/FAILED + test counts) into the
   PR description. A red build means: fix it, or do not open the PR.
5. Open the PR titled `… (#<n>)`, referencing the issue. Do **not** merge.
6. If the issue cannot proceed (needs a Snapdragon device, credentials, a
   product decision), label it `blocked`, comment with what exactly is missing,
   and move to the next issue. Never fake progress.

## Critic protocol

Run after the worker in each cycle. For each open PR authored by the worker:

1. **Verify the claims.** Re-run the build/test command yourself. If the PR
   says "tests pass", the critic's own run is the only acceptable evidence.
2. **Review the diff against the issue's acceptance criteria** (each issue
   lists them). Check specifically for:
   - hardcoded values replacing model/runtime-supplied data,
   - invented SDK/API usage,
   - claims not backed by the diff,
   - regressions in the demo path (the app must stay usable without the model),
   - JSON/enum contract breaks between the app and `telegram-bot/`.
3. **Verdict:** `gh pr review --approve --label` with `critic:approved`, or
   `--request-changes` with `critic:changes-requested` plus per-comment
   `file:line` evidence. Approve only when you would stake the issue's
   acceptance criteria on it.
4. **File new issues** for anything found that is out of the PR's scope:
   `gh issue create --label agent-loop,severity:<high|medium|low>` with
   observed-behaviour, expected-behaviour, and evidence sections.
5. Hypothesis check: when the worker's reasoning says "X works because Y",
   construct the cheapest test that would fail if Y were false, and run it.

## Merge rule

A PR may be merged (squash) only when: the critic has approved it
(`critic:approved`), the worker's own verification run was green, and the PR
does not touch files claimed by an in-flight branch. The merger re-runs the
fast unit-test command before merging. If a device-dependent claim is involved,
note it in the issue — device runs are performed when hardware is available,
never assumed.

## Issue hygiene

- Every issue carries `severity:high|medium|low` and (for loop work)
  `agent-loop`.
- `in-progress` / `blocked` / `critic:*` labels are workflow state; keep them
  current. A closed issue's PR must reference it with `Fixes #<n>`.
- New findings from review, field logs, or model behaviour go through
  `gh issue create` — not TODO comments in code.
