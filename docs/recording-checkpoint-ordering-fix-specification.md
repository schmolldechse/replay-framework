# Recording checkpoint ordering fix specification

## Status

Implemented on branch `codex/fix-recording-checkpoint-ordering` after explicit
approval.

## Problem statement

Starting a recording on Paper 26.3 can fail while the initial checkpoint is
assembled with:

```text
IllegalArgumentException: Comparison method violates its general contract!
```

The same defect can also terminate a recording immediately after its start.
The session is then no longer returned by `RecordingService.active(...)`, so a
subsequent `record stop` command correctly reports that the recording session
is not active even when the supplied recording-session UUID is valid.

The supplied replay `0989c86f-7541-42b2-8035-f939502411d1` confirms this
sequence. Its persisted state is `FAILED` with failure code `ADAPTER_ERROR` and
the recording-writer cause `IllegalArgumentException`. It entered `RECORDING`
at `10:58:06.747715+01` and failed at `10:58:06.845478+01`, approximately 98 ms
later. The related session ID is
`f09a26ba-bcf2-4b95-94a4-6ab8e61036a6`.

## Root cause

`Paper26CheckpointEncoder` and `Paper26_3CheckpointEncoder` use the same
target-key comparison algorithm when ordering checkpoint packet blueprints.
The algorithm compares two keys numerically only when both complete suffixes
can be parsed as comma-separated integers. Otherwise, it falls back to a text
comparison.

Entity checkpoint state contains both forms:

- base entity state such as `entity:2` and `entity:10`;
- qualified state such as `entity:2:attributes` and
  `entity:10:attributes`.

The comparison strategy therefore depends on the particular pair being
compared. For example, numeric comparison considers `entity:2` smaller than
`entity:10`, while comparisons involving a qualified key use textual order.
This permits cycles and violates comparator transitivity. Java's TimSort
detects such a cycle for sufficiently large real-world snapshots and throws
the reported exception.

The implementation is duplicated in both version-specific Paper adapters, so
Paper 26.2 has the same latent defect even though the observed exception came
from Paper 26.3.

## Proposed implementation

### 1. Define a total target-key order in each Paper adapter

Update the private checkpoint ordering logic in:

- `Paper26CheckpointEncoder`;
- `Paper26_3CheckpointEncoder`.

Each target key will be normalized into one immutable comparison shape before
two values are compared. The comparison shape will contain:

1. the key prefix before the first colon, such as `entity`, `block`, or
   `chunk`;
2. whether the primary value is a numeric tuple or text;
3. the parsed numeric tuple when present;
4. the optional qualifier following the numeric tuple, such as `attributes`,
   `leash`, or `effect`;
5. the original key as a final deterministic tie-breaker.

The comparator will always compare the same fields in the same order. It will
not switch between numeric and textual semantics based on the other operand.
For entity keys this produces the intended grouping:

```text
entity:2
entity:2:attributes
entity:10
entity:10:attributes
```

Comma-separated block and chunk coordinates, including negative values, will
retain numeric ordering. Non-numeric keys will retain deterministic textual
ordering.

The helper remains private to each adapter. Target-key structure is an
adapter-internal checkpoint concern and must not become a new public API or a
cross-version contract. Keeping the implementations version-specific also
allows either adapter to evolve independently if its packet or state identity
requirements diverge.

### 2. Preserve the existing recording lifecycle

No change is planned for `RecordingService.active(...)`, recording-session
IDs, replay IDs, or the `record stop` argument.

The stop command already parses the full recording-session UUID and looks it
up through the correct service. The reported session was absent because the
writer had already failed and the coordinator intentionally removes terminal
failed sessions from its active-session registry. Preventing the checkpoint
ordering failure keeps the session active until an explicit or budget-driven
stop and resolves the observed stop symptom without weakening lifecycle
invariants or allowing failed sessions to be stopped as if they were active.

### 3. Add adapter-specific regression tests

Extend the checkpoint fixture tests for Paper 26.2 and Paper 26.3 with the same
version-local scenario:

- build an entity-metadata snapshot containing shuffled base entity keys and
  qualified attribute keys for single- and multi-digit entity IDs;
- encode the checkpoint through the real adapter encoder boundary;
- assert that encoding completes without a comparator exception;
- capture and assert the exact stable target-key order;
- repeat with the same logical snapshot order to demonstrate deterministic
  output.

The tests belong to the adapter modules because the failure is caused by
adapter-specific checkpoint identities. No generic core behavior changes, so
no new generic core test is required for this fix.

## Files expected to change

- `replay-adapter/paper-26_2/src/main/java/.../checkpoint/Paper26CheckpointEncoder.java`
- `replay-adapter/paper-26_2/src/test/java/.../Paper26CheckpointFixtureTest.java`
- `replay-adapter/paper-26_3/src/main/java/.../checkpoint/Paper26_3CheckpointEncoder.java`
- `replay-adapter/paper-26_3/src/test/java/.../Paper26_3CheckpointFixtureTest.java`
- this specification, updated to record the completed validation

No replay-format, database-schema, configuration, command syntax, or public
API changes are required.

## Version-control plan

Before implementation, fetch the remote repository and verify that local
`master` exactly matches `origin/master`. Create the dedicated branch
`codex/fix-recording-checkpoint-ordering` from that commit. Keep the production
fix and its regression tests in one focused semantic commit, for example:

```text
fix: enforce total checkpoint target ordering
```

## Validation plan

1. Run the Paper 26.2 adapter tests.
2. Run the Paper 26.3 adapter tests.
3. Run the generic core tests to detect recording-lifecycle regressions.
4. Run the repository test/build task if the environment permits it.
5. Build the runtime and Example plugin artifacts.
6. Start a Paper 26.3 development server, start a recording in a world with
   multiple entities, keep it active beyond the 30-second checkpoint interval,
   and stop it with its recording-session UUID.
7. Verify that no comparator exception occurs, the session remains active
   until stopped, the replay reaches `AVAILABLE`, and checkpoint/segment
   artifacts are published.

If the local environment prevents a real server smoke test, that limitation
will be reported explicitly rather than replacing it with a weaker claim.

## Expected impact and compatibility

- Recording startup and periodic/dimension-change checkpoint generation become
  deterministic for mixed base and qualified state keys.
- The same latent failure is removed from both supported Paper adapters.
- Existing replay artifacts remain readable because neither the artifact
  format nor packet payloads change; only checkpoint frame ordering is made
  well-defined.
- Public APIs, configuration, command syntax, and recording identifier
  semantics remain unchanged.

## Validation result

- Local `master` and `origin/master` were both verified at
  `13fab17beb345218281f1ed524c5b8dd3b07c73a` before the implementation branch
  was created.
- The changed production sources and checkpoint fixture tests compile directly
  against their respective Paper 26.2 and Paper 26.3 user-development bundles.
- `Paper26CheckpointFixtureTest`: 4 of 4 tests successful.
- `Paper26_3CheckpointFixtureTest`: 4 of 4 tests successful.
- The repository Gradle test invocation was attempted, but Gradle could not
  establish its required local loopback connection in the Codex execution
  environment and stopped before running project tasks. The two affected test
  classes were therefore executed through JUnit Platform directly.
- A live Paper server smoke test was not performed from this environment
  because the blocked Gradle invocation could not produce and deploy fresh
  runtime artifacts. The real failure record was inspected read-only and
  confirmed the lifecycle diagnosis described above.
