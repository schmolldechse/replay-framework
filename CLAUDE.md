# Replay Framework

Paper plugin framework for server-side, packet-based recordings and per-viewer
playback, with a public Java API. User-facing documentation lives in `docs/`;
this file holds the working agreements and the state of ongoing work.

## Working agreements

- **Ask before implementing.** Present a design or plan and wait for approval
  before changing code. Analysis and reading need no approval.
- **Commit only when the user says so.** Never commit `.idea/` or the user's
  local `gradle/wrapper/gradle-wrapper.properties` change. End commit messages
  with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- **New behavior is tested in game by the user** before it is committed. Name
  what to check and wait for the replay ID or the feedback.
- **Minecraft 26.2 only.** Greenfield: old replays may break; no migrations, no
  compatibility shims for removed adapters.

## Design rules

- **Packets, not events.** Recordings consist of the packets the server builds.
  Prefer packet sources over Bukkit events; reconstruction from events is not a
  capture source.
- **One packet, one definition.** Every PLAY packet has its own definition class
  that decides its handling. Clientbound and serverbound are strictly separate
  (own base classes, catalogs, packages).
- **Abstraction first.** Shared logic belongs in a shared module so a new
  Minecraft version only implements what really differs. Do not copy logic into
  a version adapter.
- **Generic over player-specific.** Subjects, sections and states are modeled
  for entities and blocks too, not only for players.
- **Budgets are configurable,** never hard limits baked into code.
- **Never move the viewer automatically,** for example on a world change.
- **Out of scope here:** boss bars and other polish of the example plugin; they
  belong to the planned rework of the example plugin into a production plugin.

## Build

Gradle lives at
`C:/Users/christian/.gradle/wrapper/dists/gradle-9.9.0-bin/k7t1gqg1ujh3kgq8oiu7hqi4/gradle-9.9.0/bin/gradle`
(the repository has no wrapper JAR). From Git Bash:

```bash
export JAVA_TOOL_OPTIONS="-Djdk.net.unixdomain.tmpdir=C:/Users/christian/.gradle/tmp"
"$G" build -PminecraftVersion=26.2
```

Builds need the sandbox disabled (Gradle daemon sockets). The test server runs
from `replay-runtime-paper/run`; while it runs, the runtime JAR is locked and
`:replay-runtime-paper:jar` fails. Replays are under
`replay-runtime-paper/run/plugins/ReplayFramework/replay-storage/replays/<id>`.

## Module map

| Module | Role |
|---|---|
| `replay-api` | public API for plugins |
| `replay-format` | tracks, segments, checkpoints, index, manifest |
| `replay-core` | recording, tracks, playback, catalog, metadata |
| `replay-adapter:api` | contract between core and a version adapter |
| `replay-adapter:paper-common` | version-independent Paper parts: Argus, definition base classes and catalogs, recipient copies and state; channel handlers, capture and playback bridges, checkpoint assembly |
| `replay-adapter:paper-26_2` | the 26.2 adapter: definitions, codecs, eye, snapshots, and the hooks the shared bridges call |
| `replay-runtime-paper` | the Paper plugin that composes everything |
| `replay-example-plugin` | reference integration (commands, menus, viewer worlds) |

## Modularization (phase 1 done)

Analysis from 2026-11-23. The weakness: logic that should be shared lives in
the 26.2 adapter, and the interfaces between layers grow with every feature
instead of being extension points.

### Findings

1. (Resolved in phase 1.) About 3,000 lines of the 26.2 adapter do not depend on the version:
   `Paper26_2CaptureBridge` (669 lines, 6 Minecraft references),
   `Paper26_2PlaybackBridge` (719, 2), `Paper26_2CheckpointEncoder` (548, 0),
   `ReplayViewerOutboundGate`, `PacketEventsReplayWriter`,
   `Paper26_2ReplayPacketRewriter`, `Paper26_2CaptureOrder`,
   `Paper26_2AuthoritativeStateIndex`. A second version would copy them.
2. (Resolved in phase 1.) `Paper26_2PlaybackBridge` and `Paper26_2CaptureBridge` are god classes.
3. (Resolved in phase 2, step 1.) `PlaybackBridge` (adapter API) has 19 default methods; every feature added one.
4. Scene tracking and state decoding mix version-independent rules with
   Minecraft packet decoding. (Open: `Paper26_2SceneEntityTracker`, 360 lines,
   still does both; carried over to phase 2.)
5. Track kinds are not an extension point: `BLOCK_STATE` needed about 12 edits.
6. `CaptureContext` has 11 fields mixing filtering, provenance and routing.
7. `RecordingCoordinator` (1,093 lines) and `RecordingSegmentAppender` (742)
   mix lifecycle, catalog transitions, Argus, checkpoints, routing, budget and
   failures.
8. `DefaultPlaybackService.prepareSession` is one block of about 130 lines.
9. Track opening exists twice in `DefaultPlaybackService`.
10. Dimension-change checkpoints are wired but never emitted (18 references).
11. Six compatibility or legacy constructors and paths remain.
12. `CaptureOrigin.PLAYER_VIEW` is carried but no recording accepts it.

### Plan

Each phase changes no behavior, ends with a green full build and an in-game
check by the user, and is committed only after approval.

| Phase | Content | Status |
|---|---|---|
| 1 | Shared Paper base: version-independent capture bridge, playback bridge (split into queue, pipeline, lifecycle, filter), checkpoint assembly; small per-version hooks (codec, connection access, snapshot source) | done |
| 2 | Adapter contract: `PlaybackBridge` split into role interfaces; `CaptureContext` routing as one `TrackRoute` type; carried over from phase 1: `ServerboundTap` and the shared scene model (finding 4) | open |
| 3 | Core: track kinds as an extension point; split recording and playback assembly; one track opener; remove dead paths (findings 10 to 12) | open |

Decisions made during the phases are recorded below.

### Decisions

**D1, 2026-11-23: PacketEvents is redundant and is removed in phase 1.**
PacketEvents is used in two classes only:

- `Paper26_2CaptureBridge` takes the final clientbound bytes of player
  connections from a PacketEvents post-task, and then decodes them back into
  the packet object (`decodeCapturedFrame`) so the definition can read it:
  Paper encodes, PacketEvents copies, we decode.
- `PacketEventsReplayWriter` writes replay packets from the PacketEvents encoder
  context, to skip PacketEvents listeners and the Paper encoder.

Both work with our own Netty handlers at fixed points of the server pipeline
(head to tail: `timeout, [decrypt], splitter, [decompress], decoder,
[bundler], [encrypt], prepender, [compress], encoder, [unbundler],
packet_handler`; PacketEvents adds its encoder head-side of `encoder`):

- a **clientbound tap** directly before `packet_handler`, the tail of the
  pipeline, sees every packet object before encoding, like the Argus eye
  does, and unbundles bundles itself. That position stays valid when the
  server swaps its encoder or unbundler during reconfiguration. The
  definition reads the object directly; the packet is encoded once, for the
  frame.
- a **write anchor** tail-side of `compress` (or `prepender`) writes replay
  bytes so that only compression, framing and encryption follow.

The adapter already depends on Paper internals through paperweight and uses
the live Minecraft codec, so PacketEvents adds nothing but an extra server
plugin that must support each Minecraft version before we can. Accepted
consequence: packets another plugin injects through PacketEvents as raw bytes
are no longer seen. Scene packets of that kind were never recorded (eyes see
objects before any PacketEvents rewrite), so this only affects player-only
packets such as sounds that a plugin sends to selected players.

**D2, 2026-11-23: the viewer gate classifies packet objects.**
`ReplayViewerOutboundGate` encodes every live packet with `inspectAndEncode`
just to learn its ID. It classifies the object by its definition instead.

**D3, 2026-11-23: the shared base lives in `paper-common`.**
No extra module: `paper-common` may use the Paper API and Netty (both
version-independent) but no Minecraft server classes. Version-specific work
goes through small hook interfaces implemented by the version module.

### Phase 1 design

Shared classes in `paper-common` (new package `adapter.paper.channel`,
`adapter.paper.capture`, `adapter.paper.playback`, `adapter.paper.checkpoint`):

| Shared class | Replaces | Role |
|---|---|---|
| `ClientboundTap` (done) | PacketEvents listener in `Paper26_2CaptureBridge` | per player channel, before `packet_handler`; hands packet objects to capture |
| `ServerboundTap` (carried over to phase 2) | `Paper26_2ServerboundReceiver` (81 lines) | per player channel, before `packet_handler` |
| `ReplayChannelWriter` (done) | `PacketEventsReplayWriter` | writes encoded replay packets from an anchor behind `compress` |
| `PaperCaptureBridge` (done) | `Paper26_2CaptureBridge` | taps, capture order, Argus publishing, definitions, failures |
| `CaptureOrder`, `CaptureStateIndex` (done) | `Paper26_2CaptureOrder`, `Paper26_2AuthoritativeStateIndex` | unchanged, moved |
| `PaperPlaybackBridge` (done) | `Paper26_2PlaybackBridge` | lifecycle, frame pipeline, world filter, queries |
| `ViewerOutboundQueue` (done) | part of `Paper26_2PlaybackBridge` | batching, per-turn limit, idle waiters, discard |
| `ViewerGate` (done) | `ReplayViewerOutboundGate` | classifies live packet objects (D2) |
| `CheckpointAssembler` (done) | `Paper26_2CheckpointEncoder` | ordering, validation and encoding of checkpoint packets |

Hooks the version module implements:

| Hook | Provides |
|---|---|
| `CaptureHooks` (done, `Paper26_2CaptureHooks`) | capture: registry, definitions, Argus platform, server tick, connection identity, unbundling, encoding of sent, observed and state packets, Paper's connection hook, the version's own handlers |
| `PlaybackHooks` (done, `Paper26_2PlaybackHooks`) | playback: the viewer's channel and the `ViewerParts` of a viewer: codec, live classification, `ViewerRewriter`, `ViewResetter`, `ViewerScene`, interaction handler |
| `SnapshotSource` (done, `Paper26_2SnapshotProvider`) | the native packets of a checkpoint for a coverage |
| `CheckpointCodec` (done, `Paper26_2NativePacketCodec`) | encoding of checkpoint scene and state packets |

Order of work, each step compiling and tested: (1) hooks and channel
handlers, PacketEvents removed; (2) shared capture bridge; (3) shared playback
bridge; (4) checkpoint assembler; (5) docs, full build, in-game check.

Status:
- Step 1 committed (`1a8492a`): `ClientboundTap`, `ReplayChannelWriter`,
  `PipelineNames` in `adapter.paper.channel`; PacketEvents removed.
- Step 2 implemented: `PaperCaptureBridge`, `CaptureHooks`, `CaptureOrder`,
  `CaptureStateIndex` in `adapter.paper.capture`; the 26.2 module keeps only
  `Paper26_2CaptureHooks` (180 lines instead of the 560-line bridge). The two
  constructors without a plugin were dead and are gone. Full build green,
  committed (`4bf611d`).
- Step 3 implemented: `PaperPlaybackBridge`, `ViewerOutboundQueue`,
  `ViewerGate` and the interfaces `PlaybackHooks`, `ViewerParts`,
  `ViewerRewriter`, `ViewResetter`, `ViewerScene` in `adapter.paper.playback`.
  The 26.2 module keeps `Paper26_2PlaybackHooks` and `Paper26_2ViewerScene`
  (310 lines) instead of the 720-line bridge; rewriter, resetter, trackers and
  decoders implement the interfaces. Checked in game (replay `aab662ec`),
  committed (`7613d5f`).
- Step 4 implemented: `CheckpointAssembler`, `CheckpointSnapshot`,
  `PacketBlueprint`, `EncodedPacket` and the hooks `SnapshotSource`,
  `CheckpointCodec` in `adapter.paper.checkpoint`. `PacketCheckpointFamily`
  carries the assembly order; the encoder's duplicate enum, the three family
  filters and `Paper26_2CheckpointEncoder` are gone. Checked in game (replay
  `2b16a81f`), committed (`389e0c0`).
- Step 5: docs describe the shared base (architecture, version contract,
  adapter doc, README); dead accessor methods from before the shared bridges
  (`installPlaybackGate`, `decodeCapturedFrame`, `uninstallHandler`,
  `uninstallHandlerAndWait`) and the unused `EntityPosition.blockX/Y/Z` are
  gone. Checked in game (replay `5badacb0`), committed.

Phase 2 status:
- Step 1 implemented: `PlaybackBridge` extends the roles `ViewerStream`,
  `ViewerWorld`, `SceneQueries`, `StateDecoder`, `ViewerInteractions` (package
  `adapter.playback`) and keeps only lifecycle; no defaults remain.
  `PacketScheduler` and `SeekEngine` take `ViewerStream`, `StateTracks` takes
  `StateDecoder`, `SceneTracks` takes `SceneQueries`. Test stubs extend
  `StubPlaybackBridge` from the test fixtures of `replay-adapter:api`. Checked
  in game, committed.

Fixes found while testing phase 1, outside the plan: viewer worlds without
blocks (`d0c3bc9`), checkpoint-only tracks accepted by the finalizer
(`cae1d7f`), relative movement replayed during seeks (`165d817`).
