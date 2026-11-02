# AI-Assisted Workflow

> **Status: PLANNED WORKFLOW.** This document describes how AI assistance is intended to be
> used. The findings sections are intentionally empty placeholders; they must be filled with
> real observations as work happens. Nothing below should be read as a record of reviews or
> results that have taken place, unless it is explicitly dated and attributed.

## 1. Principles

- **Human-led.** A human engineer owns every decision: scope, architecture, what is merged,
  and what is claimed to work.
- **AI is implementation assistance, not the final engineering authority.** AI agents draft
  documents, code, and tests; they do not approve their own work.
- **Evidence over assertion.** AI output is trusted only to the extent it is verified by tests
  that a human has seen run, or by human review.
- **Small steps.** AI tasks are small and reviewable (see `AGENTS.md` §6). Each produces a
  report of changed files, tests actually executed, assumptions, and Git operations.

## 2. Workflow per task

1. **Define the task (human).** One phase or sub-phase from `docs/implementation-plan.md`,
   with acceptance criteria and the expected verification checkpoint.
2. **Implement (AI-assisted).** The agent works on a feature branch, following `AGENTS.md`.
3. **Report (AI).** Files changed with reasons, exact test commands and actual results,
   assumptions, open questions, Git operations.
4. **Review (human).** Read the diff, not just the summary. Specifically check:
   - Transaction boundaries (no payment call inside a transaction).
   - Lock ordering and all-or-nothing hold behavior.
   - Database constraints actually present in migrations.
   - Tests assert meaningful outcomes (DB state, not just HTTP status) and could actually fail.
   - No secrets, no scope creep, no undocumented dependencies.
5. **Verify (human).** Re-run the test commands locally; for UI phases, exercise the flow manually.
6. **Decide (human).** Request changes, or merge the PR into `main` (humans only).
7. **Record.** Log notable review findings and AI mistakes in §4 and §5 below.

## 3. What AI is and is not used for

| Used for | Not used for |
|---|---|
| Drafting planning documents and ADRs | Final architecture decisions |
| Generating boilerplate, DTOs, migrations, tests | Deciding that tests are sufficient |
| Proposing concurrency test designs | Declaring code correct without test evidence |
| Explaining unfamiliar APIs | Merging, approving, or pushing to `main` |
| Suggesting edge cases and failure modes | Inventing results, benchmarks, or reviews |

## 4. Review findings

_Placeholder — to be filled in with actual findings from human review of AI-produced work.
Do not pre-populate._

| Date | Phase / PR | Finding | Action taken |
|---|---|---|---|
| | | | |

## 5. AI mistakes observed

_Placeholder — to be filled in with actual mistakes made by AI assistance (e.g. incorrect
code, false claims, scope creep) and how they were caught. Do not pre-populate._

| Date | Phase / PR | Mistake | How it was caught | Correction |
|---|---|---|---|---|
| | | | | |

## 6. Reflections

_Placeholder — to be written at the end of the assessment, based on the entries above._
