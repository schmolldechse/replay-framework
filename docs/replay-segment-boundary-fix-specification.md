# Replay Segment Boundary Fix Specification

## Status

This document records the analysis and approved implementation for a replay-open failure observed with replay `ca7af9d5-7ef8-46e2-b67a-a98c3f61c9cd`. The implementation is present on branch `codex/fix-replay-segment-boundaries` and remains subject to review and integration.

## Problem statement

Opening the replay creates the isolated viewer world, then immediately tears it down. The example plugin reports `Replay could not be opened. Please try again.` and logs only a `SegmentDecodeException` for the viewer lifecycle operation.

The failure is not caused by a damaged segment download or a stale cache entry. It is caused by the playback service deriving a physical segment boundary from an index entry that represents a checkpoint anchor rather than a packet frame.

## Verified evidence

The stored artifacts and the cached first segment were inspected without modifying them.

- All four stored segment artifacts decode successfully with `ReplaySegmentReader`.
- The cached first segment has the same length and SHA-256 hash as its stored source and also decodes successfully.
- Segment zero declares the physical frame interval `38,203,500 ns` through `30,037,996,600 ns` in its header.
- The last real frame seek point for segment zero is at `30,037,996,600 ns` with frame offset `7,550,624`.
- A later index point exists at `30,086,881,600 ns`, checkpoint ordinal `1`, segment ordinal `0`, and frame offset `7,550,672`.
- Offset `7,550,672` is the sealed uncompressed length of segment zero. The point therefore addresses the boundary after the segment's final frame; it does not address a frame contained by the segment.
- The first frame in segment one starts later at `30,111,627,600 ns`.
- Segments one through three have matching derived and encoded physical boundaries. Only segment zero is rejected.

The current failure path is:

1. `RecordingSegmentAppender` seals segment zero during rotation.
2. A subsequent checkpoint is written before segment one receives its first frame.
3. The checkpoint anchor correctly references the sealed end of segment zero so seeking can apply the checkpoint before the next delta.
4. `DefaultPlaybackService.buildSegmentRefs` groups every seek point by segment and treats the maximum timestamp as the physical segment end.
5. The checkpoint anchor extends the derived end of segment zero beyond its last frame.
6. `PlaybackBuffer.validateDecoded` compares that derived end with the encoded segment header and rejects the otherwise valid segment.
7. `ExampleViewerEnvironment` logs only the outer exception class, hiding the expected and actual boundaries and the nested cause.

Viewer-world creation and immediate unload are consequences of the failed playback-open lifecycle and its cleanup, not a world-generation defect.

## Existing contracts

The proposed change preserves the existing format and playback contracts.

- A `SeekPoint` can represent either a packet-frame location or a checkpoint anchor.
- The first seek point for a checkpoint ordinal is its checkpoint anchor. `ReplayIndex.seekCheckpointFloor` already relies on this ordering and returns that first point.
- A segment header describes the physical timestamps of frames encoded in that segment.
- A `SegmentRef` supplied to the buffer represents the same physical frame interval and is checked against the decoded header.
- Quiet time before the first frame, between segments, and after the last frame is logical playback coverage. `PlaybackBuffer.loadedCoverage` already expands loaded physical segments across those quiet intervals.

The physical integrity check in `PlaybackBuffer` should therefore remain strict. Relaxing it would conceal genuinely inconsistent artifacts and would mix logical coverage with encoded frame boundaries.

## Proposed changes

### 1. Derive segment boundaries only from frame seek points

Update `DefaultPlaybackService.buildSegmentRefs` to classify the first index point for every checkpoint ordinal as a checkpoint anchor. Exclude those anchors when calculating the minimum and maximum physical timestamp for each segment.

The classification must be performed from the globally ordered index before points are grouped by segment. This handles checkpoint anchors that intentionally reference the end of the previous sealed segment while the first post-checkpoint delta belongs to the next segment.

The initial `(0 ns, checkpoint 0, segment 0, offset 0)` bootstrap point becomes a normal instance of the same rule instead of requiring a one-off equality check.

Every manifest segment must still have at least one remaining frame point. Missing frame points, unknown segment ordinals, or unordered physical intervals remain corruption errors.

### 2. Preserve physical bounds for the final segment

Use the last frame timestamp as the final segment's physical end instead of replacing it with `manifest.durationNanos()`.

This is required for the existing strict header comparison when a valid recording contains quiet time after its final packet. Buffering already treats a loaded final segment as covering the quiet tail through the replay duration, so this change does not shorten logical playback availability.

### 3. Retain strict decoded-segment validation

Keep adapter-ID and exact physical-bound checks in `PlaybackBuffer.validateDecoded`. Improve the boundary mismatch message to include the segment ordinal and both expected and decoded start/end timestamps. This makes a real artifact mismatch actionable without reducing validation strength.

### 4. Preserve nested failures in example-plugin diagnostics

Change the example viewer lifecycle warning to attach the unwrapped throwable to the log record. The user-facing message remains concise, while the server console receives the full cause chain and stack trace.

This diagnostic adjustment is limited to failed viewer operations and does not change successful playback behavior or public APIs.

## Affected components

- `replay-core`: physical `SegmentRef` construction and its regression coverage.
- `replay-core`: decoded-boundary diagnostic detail.
- `replay-example-plugin`: viewer-operation error logging.
- `docs`: this analysis and implementation specification.

No adapter implementation, packet capture path, storage provider, database schema, manifest schema, replay format revision, or public API needs to change.

## Compatibility

The change is reader-side compatible with existing revision-three recordings.

- The reported replay can be opened without rewriting its manifest, index, checkpoints, or segments.
- Newly recorded replays keep the same index semantics and wire format.
- Existing valid recordings with checkpoint anchors in active segments remain valid.
- Existing valid recordings with a checkpoint anchor at a sealed segment boundary become readable.
- Existing valid recordings with a quiet tail no longer fail strict final-segment validation.
- Truly corrupt segment headers, hashes, adapter IDs, and frame intervals continue to fail.

No artifact migration or cache deletion is required. A valid cached segment remains usable after the fix.

## Test plan

Add focused tests at the layer where the incorrect interpretation occurs.

1. Construct a multi-segment replay index in which a new checkpoint anchor references the sealed end of segment zero and the first delta for that checkpoint belongs to segment one. Opening the replay and loading segment zero must succeed.
2. Assert that the derived physical interval for segment zero ends at its last frame, not at the later checkpoint-anchor timestamp.
3. Cover a checkpoint anchor inside an active segment and confirm that surrounding frame points still determine the segment bounds.
4. Cover a final segment whose last frame precedes the manifest duration and confirm that it decodes successfully while logical buffer coverage still reaches the replay duration.
5. Retain or add a negative case in which the header differs from the frame-derived interval; loading must still fail rather than silently accepting corruption.
6. Verify that a viewer-operation failure logs the nested throwable, or isolate the logging format behind a testable helper if direct plugin logging cannot be tested without disproportionate setup.

Run the relevant module suites first, followed by the repository's complete Gradle test suite. A local Paper 26.3 smoke test should then open the reported replay, reach the viewer controls, and begin playback without deleting or regenerating the replay artifacts.

## Implementation sequence after approval

1. Fetch the remote repository and verify that local `master` still matches `origin/master`.
2. Rebase or recreate `codex/fix-replay-segment-boundaries` from that verified base if the remote changed after this analysis.
3. Add the failing regression fixtures and confirm that they reproduce the boundary rejection.
4. Implement frame-only physical-bound derivation and final-segment handling.
5. Improve boundary mismatch and example viewer diagnostics.
6. Run focused and full tests, then perform the Paper 26.3 replay-open smoke test when the required local server dependencies are available.
7. Review the final diff for task-only changes and commit in logically separated, semantically prefixed English commits.

## Expected outcome

The reported replay opens using its existing artifacts. Checkpoint anchors continue to support exact seek reconstruction, but they no longer redefine the physical contents of a segment. Buffering retains quiet-interval behavior, strict corruption checks remain active, and future viewer-open failures expose their actual nested cause in the server log.
