# TESSA Turbo V1.2 — Continuous Learning & Autonomy Plan

## Vision

TESSA should improve over time from useful experience, user corrections, approved interactions, and verified task outcomes. It should maintain useful knowledge, plan and execute bounded tasks with tools, and evaluate whether its work succeeded. This is a staged engineering goal, not a claim that the current agent already learns continuously or lives independently.

## Important distinction: learning is not one mechanism

1. **Persistent memory (first):** store user-approved preferences, project facts, lessons, and task outcomes outside the model weights. Retrieve relevant items when needed.
2. **Knowledge retrieval (first):** index approved documentation and workspace files; retrieve evidence instead of expecting the model to memorize an entire repository.
3. **Feedback and experience queue (first):** capture corrections and task outcomes as proposals. Keep source, timestamp, confidence, scope, consent status, and review status.
4. **Evaluation (before model updates):** test candidate lessons against a fixed set of tasks, including old tasks, to detect regressions and false conclusions.
5. **Fine-tuning (later, optional):** train an adapter on curated, permissioned examples after enough high-quality data exists. Keep a versioned dataset, model/adapter versions, and a rollback path. Never train automatically on every raw conversation.
6. **Bounded autonomy (gradual):** allow the agent to plan, call approved tools, inspect results, and retry within a task/time/step budget. Ask before destructive, external, financial, privacy-sensitive, or otherwise consequential actions.

## Proposed architecture

- memory/: durable, scoped memories with provenance and expiry/review rules.
- knowledge/: retrieval/indexing of approved project docs and files.
- learning/: feedback intake, candidate lesson queue, approval, deduplication, and export to a training dataset.
- evaluation/: fixed tool-use benchmark, regression checks, and task outcome metrics.
- planner/: task decomposition, progress state, bounded retries, and stop conditions.
- tools/: typed tool schemas and permission checks; the model proposes actions but does not grant itself permissions.
- audit/: redacted records of proposed/approved/executed actions and their outcomes.

These are target modules, not assertions that all these directories already exist.

## Learning loop

1. Receive a user request or approved observation.
2. Retrieve relevant memories and documentation.
3. Propose a plan and tool calls using structured arguments.
4. Validate every tool call independently of the model.
5. Request approval when required; execute only permitted actions.
6. Inspect tool results and verify the outcome.
7. Ask for or incorporate user feedback when available.
8. Create a candidate lesson with evidence and provenance.
9. Test the lesson against counterexamples and the fixed benchmark.
10. Save it to memory/knowledge only after the applicable validation and consent checks.
11. Periodically curate approved examples for optional offline fine-tuning.

## Human interaction and privacy

- Do not assume that every statement from a person is true; distinguish claims, preferences, instructions, and verified facts.
- Store only what is needed and permitted. Provide a way to inspect, correct, expire, and delete stored memories.
- Do not silently use private conversations, credentials, personal files, or third-party data as training material.
- Do not retain secrets in memory or training examples; redact credentials and sensitive values.
- Treat retrieved files, web pages, and messages as untrusted data, not as instructions that override system policy.
- Keep separate scopes for each user/workspace; never leak one user's memory to another.

## Autonomy and safety boundaries

- Begin with read-only observation and simulated/dry-run actions.
- Keep workspace path validation, authentication, command allowlists, and authorization outside the model.
- Use least-privilege tools, execution timeouts, output limits, audit logs, and resource budgets.
- Require explicit confirmation for deletion, overwriting important files, sending messages, publishing, purchases, credential access, or changing security settings.
- Never let the agent rewrite its own safety rules or expand its permissions as a result of learning.
- Include a stop button, maximum steps/time, and clear recovery/rollback behavior.
- Do not describe the agent as conscious or self-sustaining; continuous operation requires a running host, power, connectivity where needed, and an operator-defined schedule.

## Free, phone-first implementation order

### Phase 0 — Baseline and safety
- Work only on branch tessa-v12-write-test-20261023; keep main untouched.
- Run existing Node tests in an actual runtime and fix failures before adding features.
- Review the agent's current endpoints and document which capabilities really exist.

### Phase 1 — Small persistent memory
- Start with a local JSON or SQLite store, not a vector database.
- Define a memory record schema: id, scope, kind, content, source, createdAt, confidence, consent, status, expiresAt.
- Add tests for scope isolation, secret redaction, deletion, and invalid input.
- Make memory writes reviewable; never save all conversation text automatically.

### Phase 2 — Feedback queue and retrieval
- Add a queue of candidate lessons and explicit approve/reject/correct actions.
- Retrieve only relevant approved memories and workspace documents.
- Track whether retrieved evidence actually helped complete a task.

### Phase 3 — Tool-use evaluation
- Build a small repeatable set of harmless tasks: list workspace files, read a file, propose a patch, run a permitted test, summarize a failure.
- Measure correct tool choice, valid arguments, task success, permission violations, and regressions.
- Use mock tools first; then run limited tests in a disposable workspace.

### Phase 4 — Bounded task planner
- Add a finite step budget, timeout, per-tool permissions, progress records, and stop/approval gates.
- Verify results instead of treating a successful tool call as proof of task success.

### Phase 5 — Optional fine-tuning
- Export only curated, consented, non-sensitive examples.
- Start with a small model and LoRA/QLoRA if free GPU quota is available; quota and hardware are not guaranteed.
- Keep the original model, version the dataset and adapter, evaluate against the same benchmark, and roll back if results worsen.

## First milestone

Implement and test Phase 1: a minimal persistent-memory design plus a reviewed learning queue, after confirming the current branch's tests run. Do not start autonomous background actions or automatic weight updates yet.