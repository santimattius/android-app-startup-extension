Objective: Correct the reviewed findings from PRs #64–#67 on the current branch only.
Branch: `feat/sample-u5-scenarios`. Delivery strategy: `feature-branch-chain` (current diff 776 authored lines, including automatic formatting).
Test runner: Gradle wrapper through the managed `gradle_run.py` workflow.

## Specs

1. **S1 — Requested outcome.** "corrige los findings". Assumption: “los findings” refers to every medium and low finding reported for PRs #64–#67 in this conversation, applied only to the current branch as explicitly selected.
2. **S2 — Observability.** The late-collector result must contain only the current bounded replay snapshot, polling must stop below `STARTED`, and nullable failure messages must not render as `null`.
3. **S3 — Demo execution safety.** UI-triggered demo cases must execute serially, reject duplicate taps while running, preserve coroutine cancellation, and test both manifest-backed eager `true` and on-demand/dependency `false` results.
4. **S4 — Failure example accuracy.** The advanced failure case must describe and test the serialized on-demand behavior it actually exercises, without claiming concurrent sibling isolation.
5. **S5 — Waiting and instrumentation ownership.** UI lifecycle cancellation must not reach the singleton startup wait operations, and dispatcher instrumentation must run transactionally and restore the captured prior settings automatically.
6. **S6 — Verification.** Focused tests, the complete app unit-test suite, and lint must pass. The user explicitly accepted parent-run proof because the independent verifier's isolated Gradle setup was blocked by its harness; do not claim independent runtime execution.

## Tasks

- **T1** — S2 — delegated — done — Commit `bcc426a`. Replay snapshot, lifecycle-gated polling, and nullable error formatting; covered by focused app tests. Risk: none.
- **T2** — S3 — inline — done — Commit `42b7338`. Shared runner owns observable active state, atomically rejects duplicates across recreation, and preserves cancellation; manifest-backed eager flags covered. Risk: item 4 (cross-recreation concurrency and cancellation).
- **T3** — S4 — inline — done — Commit `42b7338`. Failure case now demonstrates reporting plus a later serialized request. Risk: none.
- **T4** — S5 — inline — done — Commit `1b169a6`. Startup waits shield singleton bookkeeping from UI cancellation; instrumentation transaction restores captured settings on success, failure, and cancellation. Risk: item 4 (global wait and configuration cancellation boundaries).
- **T5** — S6 — inline + delegated verifier — done — Parent focused/full tests and lint passed; independent structural review found one blocker that was corrected, and the user accepted parent-run proof after independent Gradle execution remained unavailable.

## Log

- **L1** — User request: "corrige los findings"
- **L2** — User selected: "Corregir solo la rama actual". Existing PR branches will not be rewritten or published.
- **L3** — Engram mirror pending: the available Engram server rejected the required runtime contract with HTTP 404.
- **L4** — RED evidence: missing `DemoCaseRunner`; missing `withDispatcherInstrumentation`; and manifest eager flags initially remained false until the real provider was created.
- **L5** — GREEN evidence: focused regression group passed; `:app:testDebugUnitTest` passed; `:app:lintDebug` passed through managed Gradle workflow `765a2bc014a7d6cdfb04e442b81fce8d`.
- **L6** — LSP evidence unavailable: the configured Kotlin LSP uses Kotlin 2.1 metadata support against this project's Kotlin 2.3.20 and does not resolve the Android/Compose classpath; Gradle compilation, tests, and lint are authoritative.
- **L7** — User selected delivery: "Cadena desde la rama feature/tracker (recomendado)".
- **L8** — Independent verifier found one S3 blocker: composition-local running state accepts and queues a duplicate after recreation. Its isolated Gradle setup was rejected by the harness, so independent runtime verification is unavailable and must not be retried automatically.
- **L9** — Correction: active-case ownership moved into `DemoCaseRunner.shared` with atomic rejection and a lifecycle-observable `StateFlow`; added cancellation restoration coverage for instrumentation.
- **L10** — Post-correction evidence: focused correction tests passed with `--rerun-tasks`; complete `:app:testDebugUnitTest --rerun-tasks` passed; `:app:lintDebug --rerun-tasks` passed. Managed workflow finished and removed wrapper-owned logs.
- **L11** — Feature-chain slice 1: `bcc426a` (`fix(app): report replay snapshots accurately`), covering T1/S2.
- **L12** — Feature-chain slice 2: `1b169a6` (`fix(app): protect startup wait and instrumentation lifecycles`), covering T4/S5. Independent verifier unavailable; parent focused/full tests and lint passed.
- **L13** — Feature-chain slice 3: `42b7338` (`fix(app): serialize demo case execution safely`), covering T2/S3 and T3/S4. Independent verifier blocker corrected; parent focused/full tests and lint passed.
- **L14** — User decision: "Aceptar la evidencia parental". Independent Gradle execution remains unavailable and is not claimed.
