# Detection table — what changed, or what the plan names → reviewers

Read by `review-code` Step 2 (against a changed-file list) and by `review-plan` Step 3 (against the topics, components, and paths a plan names). **One table, two readers, one file** — it lives here, in `review-code/references/`; review-plan reads it by relative path and declares review-code a required companion for that reason. `bootstrap-agents` writes it from its roster survey.

Rows are `signal → reviewer agent(s)`. The left column is anything decidable from a file list OR from plan text: a path glob, a directory, a component or subsystem name, a topic keyword. Multiple rows may match; every match becomes a lens, and review-code Step 2 (`bin/reviewer-fold.sh`) decides how many agents carry them: matched lenses are folded into fewer agents under `MAX_REVIEWERS`, never dropped.

## Rows for this repo

| If the change touches (paths in a diff, or topics a plan names)… | Add reviewer |
|---|---|
| `runner/src/main/java/io/github/kthoms/perflab/analysis/**`, `runner/src/main/java/io/github/kthoms/perflab/results/**`, `runner/src/test/**` (regression detection, results, any test file) | `test-engineer` |
| `runner/src/main/java/io/github/kthoms/perflab/jobs/**` (job executors, scheduling) | `jobs-expert` |
| `runner/src/main/java/io/github/kthoms/perflab/idle/**` (idle gate) | `idle-gate-expert` |
| `runner/src/main/resources/db/migration/**` (Flyway migrations) | `database-migration-engineer` |

`code-reviewer` has no row: it is the general reviewer that runs when no row matches.

## Rules

- **Always-on reviewer:** the zero-context reviewer is added by the skill in every mode, not by this table, and is not counted when the skill decides between isolated and collaborative review.
- **Overlapping rows are intended.** A handler file can match the API, infra, performance, and observability rows; each is a different lens and each is kept. More lenses than `MAX_REVIEWERS` allows fold into shared agents (each agent carries every profile folded into it); don't dedupe the matches, and don't narrow a row only to save agents — the cap does that. A large or high-stakes diff is the case for raising `MAX_REVIEWERS` for that run: the roughly 2× blocking-finding yield in Design Principle 4 came from an uncapped run of about ten reviewers, and each agent added is worth its tokens only when its expected findings are.
- **The test reviewer row is load-bearing.** It must match every `CRITICAL_GLOBS` path, because that reviewer writes and runs the real E2E tests review-code Step 10 gates on. A `CRITICAL_GLOBS` entry with no reviewer row behind it is a gate that can never pass.
- **Zero domain matches is a valid outcome** (a docs-only or scripts-only diff). The skill then runs one general reviewer, isolated. Do not add a catch-all row to avoid that case.
- **A reviewer named here must exist** as an agent definition your harness can spawn. `bootstrap-agents` verifies this; if you edit by hand, check it.
