# Playback Segment Residency Fix Specification

## Status

This document specifies the approved correction for missing live-playback
frames after a segment has been prefetched. The implementation is contained in
the `codex/fix-playback-segment-residency` branch.

## Problem statement

Replay `0d9973cf-4e5d-4a97-952c-3807eee74ace` has a duration of
84.008 seconds. During normal-speed playback, the visible scene stops advancing
at approximately 44 seconds, resumes by jumping to the state around 60 seconds,
and later stops advancing again. The playback clock continues to progress while
the scene is static.

The behavior is caused by inconsistent decoded-buffer bookkeeping. A complete
segment is decoded and registered as loaded. Time-based memory retention then
removes frames from the future portion of that segment, but the segment remains
registered as completely loaded. Later frame-range requests therefore do not
reload the segment and return an incomplete range. When the next segment starts,
its first available state packet produces the visible jump.

## Evidence from the reported replay

The stored recording is complete and structurally valid:

| Artifact | Physical frame interval | Frame count | Uncompressed bytes |
| --- | ---: | ---: | ---: |
| Segment 0 | 0.0598892 s to 30.0451021 s | 26,409 | 9,226,769 |
| Segment 1 | 30.0630033 s to 60.0236800 s | 26,845 | 9,590,436 |
| Segment 2 | 60.0678708 s to 84.0083638 s | 28,115 | 8,950,474 |

The three segments contain 81,369 frames. The replay index contains 81,372
points, which is consistent with those frames plus three checkpoint anchors.
All segment headers and frame ordering validate successfully.

Paper 26.3 packet ID `35` (`entity_position_sync`) occurs 34,401 times. The
recorded player entity with ID `44` has 1,638 position frames from 0.061 seconds
through 82.497 seconds. In the reported problem range, each complete second from
44 through 67 contains 19 to 21 position frames for that player. The stored
coordinates continue to change throughout the visible freeze. This rules out a
capture interruption, adapter packet-encoding gap, or damaged recording as the
cause.

The current buffer sequence explains the observed boundary:

1. At the beginning, segment 0 is decoded for the initial 30-second preload.
2. At a later prefetch refresh, segment 1 is decoded in full because it overlaps
   the forward preload window.
3. `MemoryPacketBuffer.retain(...)` removes individual frames later than the
   current retention upper bound. If the refresh occurs near 15 seconds, this
   removes the portion of segment 1 after approximately 45 seconds.
4. `PlaybackBuffer.loadedSegments` still contains segment ordinal `1` because
   `forgetOutsideRetainedWindow(...)` removes an ordinal only when the complete
   segment lies outside the retention window.
5. `framesBetween(...)` considers segment 1 loaded and reads only the surviving
   frames. The scheduler advances normally but has no frames to emit for the
   missing tail.
6. Segment 2 begins at 60.067 seconds. Its first entity-state packets are still
   present and move the replay entities directly to their state at that time.

The precise start of a missing interval can vary with the first prefetch refresh
position. This accounts for the later freeze being observed around 67 seconds
rather than at one fixed recording timestamp.

## Existing invariants involved

`PlaybackBuffer` currently treats membership in `loadedSegments` as proof that
every decoded frame from that physical segment is resident in
`MemoryPacketBuffer`. `areLoaded(...)`, logical coverage calculation, progress
reporting, and frame-range loading all depend on that interpretation.

`MemoryPacketBuffer`, however, currently owns frames individually. Its time
retention and memory-budget eviction can remove an arbitrary subset of a located
segment. No notification is returned to `PlaybackBuffer`, so the two components
can disagree about segment residency.

A segment must therefore be treated as the atomic residency unit whenever its
frames have a known segment ordinal. A segment is either fully resident and may
be marked loaded, or it is absent and must be eligible for reloading. Frames
without a segment location, used by lower-level callers and tests, may retain
their existing individual-frame behavior.

## Proposed solution

### 1. Add segment-aware memory residency

Extend `MemoryPacketBuffer` with explicit support for complete located segments:

- Adding a segment replaces any earlier frames for the same ordinal atomically.
  This prevents duplicates if a segment is reloaded.
- Located frames are retained and evicted as a complete segment, never as an
  arbitrary time slice.
- A decoded segment whose accounted size alone exceeds the configured memory
  budget fails with the existing capacity error instead of being accepted only
  partially.
- Budget eviction removes the complete located segment farthest from the current
  playback position. Existing individual eviction remains available for frames
  added without a segment ordinal.
- The buffer exposes the segment ordinals that remain fully resident, or returns
  the ordinals evicted by a mutation, so the owner can synchronize its state.

This keeps the current byte budget meaningful while preserving the stronger
correctness requirement that a loaded segment is complete.

### 2. Retain overlapping segments atomically

Change `PlaybackBuffer.ensureAvailable(...)` so the requested time window is
translated to segment ordinals using the verified segment references. Every
loaded segment that overlaps the retained-behind or scaled preload-ahead window
remains intact, including frames slightly outside the exact time bounds. A
segment is removed only when its complete logical interval no longer overlaps
the retained window or when the memory budget evicts it.

After every add or retention operation, synchronize `loadedSegments` and
`loadedDiskBytes` with the complete segment set reported by the memory buffer.
`areLoaded(...)` must never return true for a partially present or evicted
segment.

The existing physical-versus-logical boundary rules remain unchanged:

- Physical frame bounds continue to come from segment headers and frame index
  points.
- Logical quiet time between adjacent segments and after the final segment
  continues to be represented by `loadedCoverage(...)`.
- Checkpoint anchors remain separate from segment frame bounds.

### 3. Preserve scheduler and adapter responsibilities

No changes are required in `PacketScheduler`, the Paper 26.2 or Paper 26.3
adapters, the replay packet format, checkpoint encoding, recording capture, or
identity rewriting. Once `framesBetween(...)` receives complete resident data,
the scheduler's cursor and ordered emission behavior are already appropriate.

This separation is important: the recording proves that packet capture and the
Paper 26.3 `entity_position_sync` representation are functioning continuously.
Changing adapter capture would mask the buffer defect and introduce unrelated
risk.

## Affected components

| Component | Planned change |
| --- | --- |
| `replay-core/.../cache/MemoryPacketBuffer.java` | Preserve atomic residency for frames with a segment ordinal; report or expose complete resident ordinals; reject a segment that cannot fit by itself. |
| `replay-core/.../buffer/PlaybackBuffer.java` | Retain whole overlapping segments and keep loaded-segment/disk-byte bookkeeping synchronized with memory residency. |
| `replay-core/.../PlaybackBufferCacheTest.java` | Add regression coverage for prefetched segment tails and segment-level budget eviction. |
| `docs/playback.md` | Document that decoded segment residency is atomic and that configured memory must accommodate an individual recording segment. |

No public API signature needs to change. The new residency operations can remain
internal to `replay-core`.

## Test strategy

### Core regression test

Create at least three short physical segments whose duration is longer than the
preload refresh step. Then:

1. Load the initial segment at position zero.
2. Advance to a position whose preload window intersects the next segment but
   ends inside that segment.
3. Run retention.
4. Request frames from the later tail of the same prefetched segment.
5. Assert that every expected frame is returned in order and that the storage
   fetch count does not indicate a corrective duplicate fetch while the segment
   is resident.

This test must fail against the current implementation by observing an empty or
truncated tail.

### Memory-budget tests

- Verify that budget pressure evicts complete located segments rather than
  individual frames within a segment.
- Verify that an evicted ordinal is no longer considered loaded and can be
  fetched and decoded again when playback approaches it.
- Verify that a single decoded segment larger than `memoryBudgetBytes` fails
  explicitly without leaving partial frames or loaded bookkeeping behind.
- Preserve the existing individual-frame retention test for unlocated frames.

### Existing suite

Run the replay-core playback and cache tests, followed by the complete project
test suite if the local Gradle environment permits it. The reported artifact
should also be inspected after the change to confirm that frame requests across
44 to 60 seconds and the later segment tail contain the same continuous player
position frames already present on disk.

### Manual server verification

Build the current Paper runtime and example plugin, replay the existing
84-second artifact at normal speed, and confirm:

- player and entity motion remains continuous across approximately 44, 60, and
  67 seconds;
- the playback clock and visible scene remain synchronized;
- no buffering loop or adapter failure appears in the server log;
- seeking before and after each 30-second segment boundary still reconstructs
  the expected scene.

## Compatibility and impact

- Existing replay artifacts remain compatible and require no migration.
- Recording output, manifest revision, index encoding, and checkpoint files do
  not change.
- Both Paper adapters benefit because buffering is implemented in shared core
  code.
- Memory may retain a small amount beyond the exact time window because an
  overlapping segment remains complete. The configured byte budget continues
  to cap residency by evicting complete segments.
- A configuration whose memory budget is smaller than one decoded segment will
  now fail clearly instead of producing silent gaps. With the current defaults,
  `maxSegmentBytes` is 64 MiB and `memoryBudgetBytes` is 128 MiB, so one normal
  segment fits with room for frame accounting overhead except for the documented
  one-frame recording overhang. The implementation must include that overhead
  in its capacity check.

## Out of scope

- Changing the 30-second segment or checkpoint intervals.
- Reducing capture frequency or filtering valid entity movement packets.
- Modifying Paper packet IDs or version-specific packet construction.
- Altering playback speed, seek semantics, observer isolation, or UI controls.
- Rewriting or repairing the reported replay artifact, which is already valid.

## Acceptance criteria

The change is complete when all of the following are true:

1. A segment marked loaded is always fully resident in the decoded memory
   buffer.
2. Prefetch retention cannot remove only the future tail of a loaded segment.
3. Frame requests later in a prefetched segment return all recorded frames.
4. Memory-budget eviction and loaded-segment bookkeeping remain consistent.
5. The reported 84-second replay advances continuously through both observed
   freeze intervals.
6. Existing seek, logical quiet-time, cache integrity, and neutral-observer
   tests continue to pass.

## Implementation validation

The focused `PlaybackBufferCacheTest` suite passes all 16 tests, including the
new prefetched-tail, whole-segment eviction, reload-after-eviction, and
oversized-segment cases. An
isolated run of the adjacent playback suites passes 30 of 31 tests. The sole
failure is the pre-existing
`preparedTimelineUsesIncrementalForwardSeekWithoutSecondReset` assertion, which
expects one reset and receives two on the unchanged `master` implementation.

The normal Gradle test task could not be executed in the local environment
because Gradle fails while establishing its loopback connection. The changed
main sources and selected test sources compile successfully with Java 26, and
the isolated JUnit execution uses the project's resolved JUnit dependencies.
