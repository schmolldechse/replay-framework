# Replay Fidelity and Seek Consistency Specification

## Status

This document is an implementation proposal. It does not authorize or contain
the production changes described below. Implementation starts only after the
proposal has been approved explicitly.

The analysis was performed against `master` at
`f8b45a4 feat: add safe replay observer placement (#4)`. At the start of the
analysis, local `master` and `origin/master` pointed to the same commit. The
untracked `.idea/` directory is user-owned and is not part of this work.

## Problem statement

The following problems have been observed during Paper 26.2 and Paper 26.3
playback:

1. Recorded players can be displayed with generated names such as
   `rd57673176f953cf` instead of their recorded profile name.
2. A recorded player can remain stationary even though that player moved during
   the recording.
3. Chat received during recording is presented to the observer again during
   playback.
4. Forward and backward seeking can visibly pause while the client view is
   reconstructed.
5. The reconstructed scene can differ from the recorded state, particularly
   after repeated seeks.

The intended result is a deterministic, neutral replay view:

- replay entities retain their recorded presentation identity;
- entity state is recorded independently of the presence of packet recipients;
- the observer does not receive recorded connection-local chat;
- seeking converges on the same scene for the same timestamp;
- short forward seeks avoid unnecessary full client-view resets;
- Paper 26.2 and Paper 26.3 keep the common adapter abstraction while retaining
  their version-specific packet codecs and tests.

## Existing architecture

The relevant flow is currently:

```text
Paper/PacketEvents clientbound callback
        |
        v
version-specific CaptureBridge
        |
        v
CaptureRouter (fan-out to every matching recording session)
        |
        v
session packet queue -> segment/checkpoint artifacts
        |
        v
PlaybackBuffer -> SeekEngine/PacketScheduler
        |
        v
version-specific PlaybackBridge -> observer connection
```

The architecture already provides useful boundaries that must be preserved:

- the core does not depend on CraftBukkit or NMS;
- each Paper adapter owns native packet discovery, encoding, decoding, and
  rewriting;
- recording sessions own independent queues, budgets, scopes, and artifacts;
- checkpoints contain native clientbound initialization frames;
- `SeekPoint` already stores the checkpoint ordinal, segment ordinal, and
  uncompressed frame offset.

The proposed work extends these boundaries. It does not introduce a separate
verification module and does not move version-specific behavior into the core.

## Findings and root causes

### 1. Generated player names are used as visible profile names

Both `ReplayIdentityMap` implementations reserve the live observer's name. If a
recorded profile has that name, `mapName` replaces it with a deterministic
16-character generated value. `rewritePlayerInfo` then writes that value into
the replay `GameProfile`. A player's in-world nametag is derived from that
profile name, so the generated collision-avoidance identifier becomes visible.

UUID and entity-ID isolation are required because the replay entity must not
collide with the live observer. Replacing the visible profile name is not
required. The replay player-info entry is already hidden from the tab list and
its signed chat session is already removed.

### 2. Packet-recipient capture cannot reliably record entity movement

The Paper capture bridges observe outbound PacketEvents callbacks. One neutral
recording stream is therefore assembled from packets sent to real connections.
This has two correctness problems:

- a player normally does not receive an entity movement packet for that same
  player, so self-movement can be absent from the recording;
- one entity movement broadcast can be observed once for every tracking
  recipient, so relative movement can be duplicated in the neutral stream.

The existing `Paper26SyntheticStateCollector` and
`Paper26_3SyntheticStateCollector` provide in-memory deduplication seams, but no
production component creates a collector or feeds it Paper state changes. The
current adapter documentation consequently describes synthetic state behavior
that is not active in production.

Paper's `EntityMoveEvent` is not a complete replacement: it applies to living
entities but explicitly excludes players, which require `PlayerMoveEvent`.
Vehicles and other non-living moving entities need additional sources. A set of
event-specific listeners alone would therefore remain incomplete and would be
easy to diverge between adapters.

### 3. Recorded chat is intentionally rewritten and presented

`player_chat`, `system_chat`, and `disguised_chat` are currently configurable
packet families. Normal system chat is passed through. Player chat is converted
to unsigned decorated system chat so that an invalid recorded signature is not
reused. Tests currently assert this presentation behavior.

This is incompatible with a neutral observer. The chat was addressed to the
recording connection, not to a later replay viewer. Chat may still be retained
in the artifact when `CapturePolicy.includeChat()` permits it, but it must not
be emitted to a playback observer.

### 4. Every seek performs a complete reset and frame-by-frame reconstruction

`SeekEngine.seekTo` currently performs the following work for every seek:

1. find the floor checkpoint;
2. load the complete checkpoint;
3. load every delta between the checkpoint time and the target;
4. reset the observer view;
5. enqueue every checkpoint and delta frame individually;
6. wait until the outbound queue becomes idle.

The Paper bridges drain at most 128 outbound operations per event-loop turn and
flush each turn. This is safe for the connection, but a ten-second forward seek
still resets chunks and entities and replays all historical frames from the
checkpoint. It also re-emits transient history such as animations, particles,
sounds, and currently chat even though those packets are not part of the state
at the target timestamp.

### 5. Checkpoints and deltas do not share an exact temporal boundary

There are two independent consistency defects.

First, a periodic checkpoint request is created after a delta frame has been
written. The snapshot itself is scheduled on the Paper main thread and can be
taken later, especially when scoped chunks are preloaded. Nevertheless, its
frames are stamped with the earlier requested elapsed time and server tick. The
checkpoint can therefore contain state from the future relative to its index
timestamp.

Second, the checkpoint seek point stores an exact segment ordinal and byte
offset, but playback ignores both values. Deltas are selected by an inclusive
elapsed-time range beginning at the checkpoint timestamp. A delta at that same
timestamp can be applied even when it is already represented by the checkpoint.
Relative movement makes this especially visible because applying a delta twice
changes the result.

## Proposed design

### A. Separate protocol identity from presentation identity

The version-specific player-info rewriters will continue to remap:

- recorded UUIDs to session-local replay UUIDs;
- recorded entity IDs to session-local replay entity IDs;
- identity-bearing references that must follow those mapped values.

They will preserve:

- the recorded `GameProfile` name exactly;
- the recorded profile properties used for skins;
- an explicitly recorded display name when one exists.

Replay player-info entries remain unlisted and their remote chat session remains
empty. This prevents tab-list pollution and signature reuse without changing
the visible nametag. Objective and team identifiers may remain session-scoped;
they are internal protocol identifiers rather than the player's presentation
name.

`ReplayIdentityMap.mapName` and the reserved visible-name state will be removed
from both adapters unless another reachable packet family demonstrably requires
them. Call sites that represent score holders or profile owners must be reviewed
individually: visible player names are preserved, while internal objective/team
names continue to use their dedicated mappings.

### B. Introduce a recording-session capture handle

Global recipient packet capture is useful for transient world events, but it is
not an authoritative source for persistent entity state. The adapter API will
gain a version-neutral session lifecycle port, conceptually:

```java
interface CaptureBridge {
    CaptureSession openSession(
            RecordingScope scope,
            PacketSink sessionSink,
            FailureHandler failureHandler);

    interface CaptureSession extends AutoCloseable {
        CompletionStage<CheckpointCapture> initialCheckpoint();
        CompletionStage<Void> requestCheckpoint(CheckpointKind kind);
        void close();
    }
}
```

The final names and return types may be adjusted to the project's conventions,
but the following properties are required:

- the handle belongs to one recording session;
- synthetic frames go to a sink bound to that session, not through global
  fan-out;
- the handle is opened after scope resolution and closed on every manual,
  budget, failure, and shutdown terminal path;
- Paper objects never cross the adapter boundary;
- all world access and native entity inspection run on the Paper main thread;
- core queueing and storage remain asynchronous.

`CaptureRouter` will expose a targeted sink wrapper that performs the same
registry validation and policy checks as the global path but delivers only to
the bound `CaptureSink`. This avoids cross-contamination when recording scopes
overlap.

### C. Make Paper entity state independent of real recipients

Each Paper adapter will provide a version-specific entity-state sampler behind
the new session handle. A single Paper main-thread task runs once per server
tick while at least one recording session is active. It reads a union of the
active, already-resolved scopes once and fans immutable state observations into
the matching session collectors.

The sampler will use loaded chunks only for `LOADED_ONLY`. For bounded
`PRELOAD_SCOPE` recordings, it will use the chunks already prepared for the
session. Region bounds are applied to the actual entity position, not to a
packet recipient's position. Whole-world scopes use the loaded entity
collection of the selected worlds and never cause extra chunk loads.

For each entity, the session-local state index tracks at least:

- native entity ID and UUID;
- entity type and world;
- position, body rotation, head rotation, and on-ground state;
- velocity;
- player profile name and profile properties;
- entity metadata;
- equipment and active effects for living entities;
- passengers and vehicle relationships.

The version-specific encoder produces ordinary registered clientbound packets:

- a full spawn bundle when an entity enters the scope;
- absolute position synchronization for movement and rotation changes;
- head-rotation and velocity updates when those fields change;
- changed metadata, equipment, effects, and passenger state;
- entity removal when an entity leaves the scope or is removed.

Absolute movement is deliberately preferred over accumulated relative movement
for the authoritative sampled stream. It is deterministic after dropped frames,
checkpoint boundaries, and seek reconstruction.

The existing synthetic collectors should be reused or narrowed into the
session-local differential index rather than retaining a second unused
abstraction. Their production wiring and lifecycle must become explicit.

For packet families made authoritative by the sampler, the recipient-derived
copies are not written to recording sessions. This includes entity lifecycle,
movement, pose, velocity, metadata, equipment, effects, passengers, and player
profile updates covered by the sampler. Transient effects such as animation,
sound, particle, and damage-event presentation continue to come from outbound
capture. This prevents the same relative movement from being recorded once per
tracking recipient.

This change fixes newly created recordings. Missing movement cannot be
reconstructed retroactively from an existing artifact that never captured it.

### D. Treat recorded chat as connection-local during playback

The shared neutral-observer policy will classify the following packet families
as viewer-local:

- `minecraft:player_chat`;
- `minecraft:system_chat`;
- `minecraft:disguised_chat`;
- the already-local chat deletion and chat UI families.

They will be dropped before native payload rewriting. The conversion of player
chat into unsigned system chat will be removed because it becomes unreachable.

Capture policy remains unchanged. A developer may still retain chat in the
recording artifact for auditing, metadata extraction, or a future explicit
non-viewer presentation API. Ordinary playback never sends it to the observer.

### E. Establish an ordered checkpoint barrier

Checkpoint creation will be coordinated by the session capture handle rather
than requested by the segment writer with an assumed timestamp.

At the moment a checkpoint snapshot is detached on the Paper main thread, the
adapter will create an ordered capture point containing:

- the actual monotonic capture timestamp;
- the actual Paper server tick;
- a sequence allocated by the same allocator as captured frames;
- the immutable native checkpoint frames.

The checkpoint barrier and captured frames enter the session stream through one
serialized ordering path. Consequently:

- frames ordered before the barrier are represented before the checkpoint;
- the checkpoint represents state at the barrier;
- frames ordered after the barrier are the only deltas applied after it;
- chunk preloading may delay the checkpoint, but cannot backdate it.

The initial checkpoint uses the same concept as the recording epoch. The
session handle is armed first, the main-thread snapshot and epoch barrier are
created atomically, packets before the epoch are discarded, and packets after
it are buffered for activation. If repository activation fails, the handle and
buffer are discarded. This removes the current gap between an initial snapshot
and active packet admission.

The core writer will store the checkpoint's actual rebased elapsed time and the
exclusive resume boundary. It must no longer require an encoder result to match
an earlier provisional request timestamp.

### F. Use the stored segment offset as the seek boundary

The playback data API will add a boundary-aware delta read operation. Given a
checkpoint `SeekPoint`, it will:

- start in `seekPoint.segmentOrdinal()` at
  `seekPoint.frameOffset()` as an exclusive checkpoint boundary;
- continue through later segments;
- stop after the target position, using tick and sequence ordering for frames
  that share a timestamp.

Segment decoding must retain or derive the uncompressed frame-start offset.
Time-only `framesBetween(checkpointTime, target)` will no longer be used for
checkpoint reconstruction.

The existing index fields are sufficient, so no replay container layout change
is required. Adapter format revision 3 can remain readable. Old recordings
benefit from the exclusive boundary where their index is valid, although an
already mistimed old snapshot or movement that was never captured cannot be
repaired.

### G. Add reconstruction-aware playback emission

The playback adapter boundary will distinguish two emission modes:

```text
LIVE             normal timeline playback
RECONSTRUCTION   checkpoint/seek state reconstruction
```

During reconstruction, adapters emit durable state only. Ephemeral and
connection-local frames are skipped. Normal playback continues to emit allowed
ephemeral world events at their recorded time.

The bridge will accept bounded frame batches. A Paper adapter turns one batch
into bounded channel operations, checks the current playback generation, and
flushes once per event-loop turn. The existing event-loop fairness limit stays
in place; this proposal does not replace it with an unbounded write loop.

### H. Optimize eligible forward seeks incrementally

A forward seek may avoid a view reset when all of these conditions hold:

- the current client scene was produced by a completed checkpoint
  reconstruction or uninterrupted playback;
- the target is later than the current cursor;
- the current and target positions use the same floor checkpoint lineage;
- no structural reset packet or dimension checkpoint lies between them;
- no newer seek/playback generation has superseded the operation.

For such a seek, only durable deltas in `(currentPosition, targetPosition]` are
emitted in reconstruction mode. The current chunks and entities remain in the
client view.

Backward seeks, cross-dimension seeks, invalid/partially applied operations, and
targets outside the safe lineage use a full checkpoint reset. This conservative
fallback is preferred over a faster but ambiguous scene.

If a seek is superseded, its queued batch is invalidated by generation before a
later operation is applied. A partially applied operation marks the current
scene invalid, forcing the next operation through a full checkpoint rebuild.

## Component changes

### Adapter API

Expected changes:

- extend `CaptureBridge` with an explicit per-recording capture lifecycle;
- introduce immutable checkpoint capture/barrier values without Paper types;
- extend `PlaybackBridge` with emission mode and bounded batch support;
- document which identity fields are remapped and which presentation fields are
  preserved;
- keep backward-compatible default methods only where they cannot hide a
  missing production implementation.

### Replay core

Expected changes:

- `RecordingCoordinator` opens and closes the adapter capture handle together
  with each session;
- `DefaultRecordingSession` owns the handle and closes it idempotently on every
  terminal path;
- `CaptureRouter` provides validated targeted delivery;
- `SessionPacketQueue`/the appender accepts ordered packet and checkpoint
  barrier entries;
- `RecordingSegmentAppender` writes actual checkpoint capture points and
  exclusive seek boundaries;
- `PlaybackData` reads deltas by `SeekPoint` segment/offset boundary;
- `SeekEngine` supports reconstruction emission and conservative incremental
  forward seeking;
- `PlaybackTimeline` tracks whether the current client scene is valid and which
  checkpoint lineage produced it;
- `PacketScheduler` continues to use live emission mode.

### Paper 26.2 adapter

Expected changes:

- preserve recorded player profile names in player-info rewriting;
- add a Paper 26.2 main-thread entity sampler and native delta encoder;
- wire the Paper 26.2 synthetic state collector into recording sessions;
- create actual-time checkpoint barriers in the 26.2 snapshot path;
- classify all recorded chat presentation as viewer-local;
- implement reconstruction filtering and bounded batch writes using the 26.2
  packet registry and NMS types;
- update the 26.2 adapter documentation with the exact affected packet
  families and behavior.

### Paper 26.3 adapter

Expected changes mirror 26.2 but remain implemented against the Paper 26.3 NMS
packet types and codecs. Shared logic belongs in the adapter API/core only when
it is independent of NMS. No 26.2 NMS class or assumption may be reused by the
26.3 adapter merely because its current shape happens to match.

The 26.3 adapter documentation must list its own packet families and native
differences rather than refer to 26.2 as a template.

### Documentation

The following English documentation will be updated after implementation:

- `docs/playback.md`: neutral chat behavior, reconstruction semantics, and seek
  fallback rules;
- `docs/recording.md`: recipient-independent entity state capture and the
  checkpoint barrier;
- `docs/adapters/paper-26.2.md`: exact 26.2 entity, identity, chat, and seek
  packet behavior;
- `docs/adapters/paper-26.3.md`: exact 26.3 entity, identity, chat, and seek
  packet behavior.

## Test strategy

### Generic core tests

Add or extend tests for:

1. capture handles open after scope resolution and close exactly once on manual
   stop, budget stop, initialization failure, writer failure, and shutdown;
2. a targeted synthetic packet reaches only its owning session when scopes
   overlap;
3. a checkpoint uses its actual capture point rather than its request time;
4. frames before and after a same-tick barrier are included exactly once;
5. delta loading starts at the stored segment ordinal/frame offset;
6. repeated seeks to the same target produce the same modeled scene;
7. forward seek uses the incremental path only under the stated invariants;
8. backward, cross-lineage, structural, and invalid-scene seeks perform a full
   reset;
9. reconstruction suppresses ephemeral frames while normal playback emits
   them;
10. superseded seeks cannot leave a later operation with an apparently valid
    partial scene.

The scene-convergence fixture should model absolute entity position, entity
presence, block state, metadata, and passenger relationships rather than merely
asserting the order in which bridge methods were invoked.

### Paper 26.2 adapter tests

Add adapter-specific tests for:

- a replay profile keeps the recorded name even when it equals the observer's
  live name;
- UUID and entity ID are still remapped, tab-list visibility remains false, and
  the chat session remains absent;
- a moving player produces absolute movement without any tracking recipient;
- repeated recipient movement packets do not duplicate authoritative movement;
- entity enter/leave, rotation, velocity, metadata, equipment, effects, and
  passenger changes encode to registered 26.2 packets;
- all three chat presentation families are dropped;
- durable/ephemeral reconstruction classification matches the 26.2 registry;
- a delayed main-thread snapshot reports its actual 26.2 capture point.

### Paper 26.3 adapter tests

Add the equivalent tests using the 26.3 registry, packet classes, codecs, and
fixtures. The test suite must not inherit expected NMS values from the 26.2
adapter.

### Paper API scheduling tests

Use `org.mockbukkit.mockbukkit` where it can verify plugin lifecycle, scheduler,
scope, and Paper/Bukkit event integration without a real server. Native packet
encoding remains covered by the existing paperweight adapter fixtures because
MockBukkit is not a substitute for 26.2/26.3 NMS codec tests.

No `replay-verification` module or repository-level `verification/` directory is
introduced. A real `runServer` smoke test remains optional final validation,
not the primary automated test mechanism.

## Acceptance criteria

The change is complete only when all of the following are true for Paper 26.2
and Paper 26.3:

- a recorded player named `PlayerA` is displayed as `PlayerA`, including when
  `PlayerA` is the observer;
- the replay entity still has a session-local UUID/entity ID and cannot mutate
  the observer's live identity or tab-list/chat state;
- player movement is captured when no other player tracks the moving player;
- the same movement broadcast to multiple recipients is applied once;
- recorded player, system, and disguised chat is never presented to the
  observer;
- seeking to timestamp `T` repeatedly yields the same entity/block scene;
- a frame on a checkpoint boundary is neither omitted nor applied twice;
- a delayed/preloaded checkpoint carries the time at which its state was
  actually captured;
- an eligible `+10s` seek does not reset and resend the full checkpoint;
- a backward or structurally unsafe seek still uses a deterministic full reset;
- transient history is not replayed as a burst during reconstruction;
- adapter-specific and generic tests pass, and the adapter documentation matches
  the implemented packet policy.

## Compatibility and migration

No public recording request or playback-control method needs to be removed.
The new capture-session and playback-emission contracts are adapter service
provider interfaces and require both bundled adapters to be updated together.

The replay container and packet payload format do not need to change. The
existing seek-point segment/offset fields become authoritative. Adapter format
revision 3 should remain supported because the native packet payloads remain
decodable. This preserves playback of existing artifacts, with these limits:

- a missing movement packet in an old recording cannot be generated later;
- a checkpoint that was already captured at the wrong effective time cannot be
  made historically exact;
- visible-name, neutral-chat, boundary selection, and reconstruction filtering
  improvements can still apply to old artifacts.

If implementation reveals that a native packet representation must change, the
affected adapter format revision must be incremented only after documenting a
compatibility strategy. A revision bump must not be used merely to hide an old
recording-quality defect.

## Performance and operational impact

The entity sampler adds main-thread work once per tick while recordings are
active. It is bounded by loaded entities in the union of active scopes and
shares one world scan across overlapping sessions. Native packet encoding and
artifact writes use detached values outside the main thread where safe.

Metrics should be added to existing diagnostics for sampled entities, emitted
state deltas, suppressed recipient duplicates, checkpoint capture latency,
full seek rebuilds, incremental seeks, and reconstruction frames skipped. No
new external monitoring dependency is required.

Incremental forward seek reduces outbound chunk/entity resets and should remove
the common short hitch. Full rebuilds remain intentionally more expensive but
become deterministic and avoid replaying transient history.

## Risks and mitigations

- **Main-thread sampling cost:** scan only selected loaded worlds/chunks, share
  the union scan, emit deltas only, and measure the work in existing
  diagnostics.
- **Incomplete native state coverage:** define the authoritative packet family
  list separately in each adapter and test every family against its discovered
  registry.
- **Mixed synthetic and recipient-derived state:** suppress recipient-derived
  packets only for families covered by the sampler; tests must reject duplicate
  relative movement.
- **Seek cancellation during a batch:** generation-check each bounded batch and
  invalidate the scene after partial application.
- **Old artifact limitations:** keep them readable but document that absent
  historical data cannot be recovered.
- **Paper version drift:** keep NMS codecs and expected packet inventories in
  their respective 26.2 and 26.3 modules.

## Proposed implementation sequence and branches

Each independent change will be implemented from an up-to-date `master` in a
separate branch, in this order:

1. `codex/fix-replay-profile-names`
2. `codex/fix-neutral-replay-chat`
3. `codex/fix-deterministic-entity-state-capture`
4. `codex/fix-checkpoint-seek-consistency`
5. `codex/perf-incremental-replay-seeking`

The checkpoint consistency work must precede incremental seeking because the
optimization relies on an exact scene lineage and exclusive delta boundary.
Before each implementation branch is created, local `master` will be fetched
and compared with `origin/master` without overwriting user changes. Commits will
be scoped, semantic, and written in English. The untracked `.idea/` directory
will remain untouched.

## Explicit non-goals

- presenting recorded chat through the normal observer connection;
- reconstructing movement that is absent from an existing artifact;
- replacing PacketEvents for transient packet capture;
- adding a `replay-verification` module or `verification/` directory;
- changing viewer placement or the safe observer-position API;
- unrelated adapter refactoring, formatting, or dependency upgrades.
