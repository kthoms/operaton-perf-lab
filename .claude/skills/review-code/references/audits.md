# Step 6 audits — main-thread, same turn as reviewer dispatch

Each audit is a **trigger** (decided from the changed-file list) and a **procedure** you run yourself with Read/Grep, synchronously, in the turn that dispatches the reviewers. They are deliberately not delegated: they catch what reviewers who focus on the diff window systematically miss, and they must be done before synthesis.

Every CRITICAL raised here needs a `VERIFIED:` line (file:line + snippet, docs URL + section, or script output), exactly like a reviewer's.

---

## Built-in 1 — Test quality (ALWAYS RUNS)

**1a — Inline stubs.** For each changed test file: does it import the function under test from source, or define its own copy inline? A test that tests its own arithmetic passes forever regardless of the real code. → WARNING with file:line.

**1b — Silent ignore (schema ↔ runtime).** For each new or changed field in any schema or type in the diff: find where runtime code reads it. Defined but never read → CRITICAL ("users can configure this and it does nothing"). Also the reverse: runtime stopped reading a field the schema still accepts. High-risk shapes: filter/condition/rule fields; cadence/interval/proration fields; new optional API body fields (does the service pass them through, does storage persist them); new migration columns no query selects.

**1c — Adversarial coverage, three rounds.**
- Round 1: categorize every test case in changed test files as HAPPY PATH or ADVERSARIAL (error, invalid input, auth rejection, empty/null/zero, partial failure, concurrency, boundary, malformed, duplicate, timeout, rollback). Over 80% happy-path → WARNING naming the missing categories.
- Round 2: for each changed non-test file, list the adversarial cases that SHOULD exist and don't — inputs that break assumptions, dependencies failing mid-operation, what a malicious tenant could do, two requests racing, exactly-at-threshold boundaries. Each → WARNING.
- Round 3: combinations and cascades — concurrent + partial failure + retry; step 2 failing after step 1 committed; cache stale after a failed write. SUGGESTION unless it involves data corruption or financial accuracy, then WARNING.

---

## Built-in 2 — Real E2E coverage (ALWAYS RUNS; feeds Step 10)

**Trigger:** any changed file matching `CRITICAL_GLOBS`. No match → record the exemption category and reason, done.

For each matching file: identify what it exposes (a route and its methods, a service function, a handler). Search your E2E directories for a spec that exercises it at a **real boundary** — real HTTP through the real handler, a real browser, a real datastore. Then:
- No real spec → CRITICAL.
- Spec covers only the happy path → CRITICAL (needs auth rejection, validation failure, error responses, read-back after mutation).
- A file under the E2E directory that mocks the datastore or imports the handler in-process → CRITICAL ("integration test mislabeled as E2E"). Mechanical check: grep E2E dirs for your mocking framework's mock calls and for direct client imports paired with a mock.
- Spec exists but has not been EXECUTED in-session against this tree with green output → CRITICAL. Existence is not execution.

Print a table, one line per file, then hand the result to Step 10.

---

## Your audits — extension point

### Benchmark integrity

**Trigger:** the diff touches `jobs/`, `idle/`, `analysis/`, `results/` (under `runner/src/main/java/io/github/kthoms/perflab/`) or `PerfLabHomeGuard`.

Source: README ("First real start", "When jobs start") and the invariant in `CLAUDE.md`.

1. **Clean-main and idle path** — read every changed path that starts or compiles a benchmark. Can it run from a checkout that is not clean local `main`, or while the machine is not idle? Yes → CRITICAL.
2. **Baseline contamination** — can a run with `source = 'commit'` reach a baseline or a regression comparison? Yes → CRITICAL.
3. **Detector thresholds** — a changed noise band or per-KPI absolute floor without a test pinning the new value → WARNING.

```
BENCHMARK INTEGRITY AUDIT:
- Clean-main / idle path: OK
- Baseline contamination: OK
- Detector thresholds: OK
```
