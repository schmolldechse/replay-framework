# Replay Framework: Paper 26.3 adapter

Paper plugin framework for server-side, packet-based recordings and per-viewer
playback, with a public Java API. User-facing documentation lives in `docs/`.
This file is the brief for adding a **Paper 26.3 adapter** next to the
existing 26.2 adapter: the working agreements, the facts already established,
the decisions and the plan. Keep its status section current.

## Working agreements

- **Ask before implementing.** Present a design or plan and wait for approval
  before changing code. Analysis, reading and experiments in a scratch
  directory need no approval.
- **Commit only when the user says so,** explicitly ("commit", "commit step
  N"). A "works" or "carry on" is feedback, not commit permission; ask.
- Never commit `.idea/` or the user's local change to
  `gradle/wrapper/gradle-wrapper.properties`. End commit messages with
  `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- **New behavior is tested in game by the user** before it is committed. Name
  exactly what to check (what to record, what to do during playback) and wait
  for the replay ID or the feedback. For an unchanged-behavior refactor,
  still ask for a check, on the versions the change touches.
- **Supported versions: Minecraft 26.2 and 26.3.** Greenfield: old replays may
  break; no migrations, no compatibility shims.
- Each step ends with a green full build (all tests, see Build) before it is
  offered for the in-game check.
- Record every decision below as `D<n>` with date and reason. Record status per
  step (what was done, replay IDs of the check, commit hash).
- Feature requests or bugs outside the current step become GitHub issues
  (`gh` is installed and authenticated; in PowerShell refresh `PATH` from
  `[Environment]::GetEnvironmentVariable` first). Ask before creating one.
  Team replay is already planned as issue #15, after this adapter.

## Design rules

- **Packets, not events.** Recordings consist of the packets the server builds.
  Prefer packet sources over Bukkit events; reconstruction from events is not a
  capture source.
- **One packet, one definition.** Every PLAY packet has its own definition class
  that decides its handling. Clientbound and serverbound are strictly separate
  (own base classes, catalogs, packages). See `docs/packet-definition-layout.md`.
- **Each adapter implements its own packets (D1).** Every version module has
  its own definition class for every packet of its version, its own hooks and
  its own tests; the 26.2 adapter keeps its implementations. Version-neutral
  logic without Minecraft classes belongs in `paper-common` where that keeps
  the adapters modular and avoids duplicated logic. No shared module with
  Minecraft classes.
- **Generic over player-specific.** Subjects, sections and states are modeled
  for entities and blocks too, not only for players.
- **Budgets are configurable,** never hard limits baked into code.
- **Never move the viewer automatically,** for example on a world change.
- **Out of scope:** boss bars and other polish of the example plugin, team
  replay (#15).

## Build

Gradle lives at
`C:/Users/christian/.gradle/wrapper/dists/gradle-9.9.0-bin/k7t1gqg1ujh3kgq8oiu7hqi4/gradle-9.9.0/bin/gradle`
(the repository has no wrapper JAR). From Git Bash:

```bash
export JAVA_TOOL_OPTIONS="-Djdk.net.unixdomain.tmpdir=C:/Users/christian/.gradle/tmp"
G=C:/Users/christian/.gradle/wrapper/dists/gradle-9.9.0-bin/k7t1gqg1ujh3kgq8oiu7hqi4/gradle-9.9.0/bin/gradle
"$G" build --continue -PminecraftVersion=26.2
```

- Builds need the sandbox disabled (Gradle daemon sockets). A full build takes
  about three minutes; run it with a long timeout.
- `-PminecraftVersion` only selects the server `runServer` starts
  (`replay-runtime-paper/build.gradle.kts`); the build compiles every adapter
  module. The test server runs from `replay-runtime-paper/run`.
- While the server runs, the runtime JAR is locked and
  `:replay-runtime-paper:jar` fails. That failure alone is expected; judge the
  build by the test results under `*/build/test-results/test/*.xml` (tests,
  failures, errors per module).
- Replays are under
  `replay-runtime-paper/run/plugins/ReplayFramework/replay-storage/replays/<id>`
  (`manifest.json`, `tracks/`); the server log is
  `replay-runtime-paper/run/logs/latest.log`. The catalog is PostgreSQL.
- Java 26 (Temurin) is installed. Source files use CRLF in the working copy;
  Git's LF/CRLF warnings are normal.

## Module map

| Module | Role |
|---|---|
| `replay-api` | public API for plugins |
| `replay-format` | tracks, segments, checkpoints, index, manifest |
| `replay-core` | recording, tracks, playback, catalog, metadata |
| `replay-adapter:api` | contract between core and a version adapter (`ReplayAdapter`, `PaperAdapterProvider`, role interfaces of `PlaybackBridge`, `CaptureContext`/`TrackRoute`) |
| `replay-adapter:paper-common` | version-independent Paper parts, no Minecraft server classes: definition base classes and catalogs, `CaptureContexts`, `ProtocolSnapshot`/`PaperPacketRegistry` (registry validation against an adapter's catalog), `ClientboundTap`/`ServerboundTap`, `ReplayChannelWriter`, `PaperCaptureBridge`, `PaperPlaybackBridge` and its queue and gate, `SceneModel`, `CheckpointAssembler`, Argus |
| `replay-adapter:paper-26_2` | the 26.2 adapter: packet definitions, codecs, eye, snapshots, and the hooks the shared bridges call |
| `replay-runtime-paper` | the Paper plugin that composes everything; `PaperAdapterSelector` picks the provider whose `minecraftVersion()` equals `Bukkit.getMinecraftVersion()` (ServiceLoader, `META-INF/services/...PaperAdapterProvider`) |
| `replay-example-plugin` | reference integration (commands, menus, viewer worlds) |

The hooks a version module implements (from the finished modularization):
`CaptureHooks`, `PlaybackHooks` (with `ViewerParts`: codec, live
classification, `ViewerRewriter`, `ViewResetter`, `ViewerScene`),
`SceneDecoder`, `SnapshotSource`, `CheckpointCodec`, `ActorEffects`. The
26.2 implementations are `Paper26_2CaptureHooks`, `Paper26_2PlaybackHooks`,
`Paper26_2ViewerScene`, `Paper26_2SceneDecoder`, `Paper26_2SnapshotProvider`,
`Paper26_2NativePacketCodec`; `Paper26_2AdapterProvider` wires them.
`docs/architecture.md` and `docs/adapters/paper-26.2.md` describe them.

## Facts established (analysis of 2026-11-25)

Sources: the vanilla data generator of both Paper server JARs
(`packets.json`, `registries.json`) and a `javap` signature diff of
`net.minecraft.network.protocol.game` (Minecraft 26.x is not obfuscated;
Paper runs Mojang-mapped, the adapter uses `MOJANG_PRODUCTION`). To
regenerate, in a fresh scratch directory per version:

```bash
java -DbundlerMainClass=net.minecraft.data.Main -jar \
  C:/Users/christian/.gradle/caches/run-task-jars/paper/jars/26.3/253.jar \
  --reports --output out
# the patched server JAR lands in versions/26.3/paper-26.3.jar (26.2: jars/26.2/135.jar)
javap -p -cp versions/26.3/paper-26.3.jar <class names>
```

Versions: `gradle/libs.versions.toml` already pins `paper263 =
"26.3.build.244-stable"` (dev bundle cached) and `paper-api263`; the run
cache also has Paper 26.3 build 253. Protocol version 776 (26.2, per the replay manifests) to 777
(26.3).

### Packet registries

| Phase / direction | 26.2 → 26.3 | Added | Removed | IDs moved |
|---|---|---|---|---|
| PLAY clientbound | 141 → 144 | `add_transient_block` (37), `post_effects` (83), `swing_animation` (123) | none | 104 |
| PLAY serverbound | 69 → 69 | `punch` (46) | `swing` (63 in 26.2) | 17 |
| CONFIGURATION clientbound | 20 → 21 | `post_effects` (10) | none | 10 |
| all other phases | unchanged | | | |

Because IDs move, every 26.2 class name `Id<DDD>...` and every `PACKET_ID`
constant (210 definition files) is 26.2-specific.

### Java API changes of packet classes

34 classes change their public API; for the rest the change is internal
(stream codecs instead of `FriendlyByteBuf` constructors), which does not
affect code that uses the packet objects, but may change the wire format;
the round-trip tests decide.

- **Movement:** new interface `MovementPacket` and types `VecDelta`,
  `PositionPath`, `core.PositionAndRotation`. `ClientboundMoveEntityPacket`
  exposes `getPositionDelta()` instead of `getXa/Ya/Za`, its `Pos`/`PosRot`
  constructors take a `VecDelta`; `ClientboundEntityPositionSyncPacket` holds
  a `PositionPath` plus rotations (no `PositionMoveRotation`);
  `Clientbound/ServerboundMoveVehiclePacket` hold a `PositionAndRotation`;
  `VecDeltaCodec` gains `tryEncode`. This hits the scene decoder (movement
  wire format) and the rewriter.
- **Swing:** `ClientboundAnimatePacket` loses `SWING_MAIN_HAND/OFF_HAND`; arm
  swings are the new `ClientboundSwingAnimationPacket`. Serverbound `swing`
  is replaced by `punch` (`ServerboundPunchPacket`). Affects actor effects
  and the serverbound definitions.
- **Records instead of classes** (getters renamed, e.g. `getX()` → `x()`):
  `ClientboundLevelChunkWithLightPacket`, `ClientboundLightUpdatePacket`,
  `ClientboundLightUpdatePacketData` (now constructible),
  `ClientboundLevelParticlesPacket` (also new `RandomizationType` and
  per-axis max speed: a wire change), `ClientboundOpenBookPacket`,
  `ClientboundOpenSignEditorPacket` and `ServerboundSignUpdatePacket`
  (front/back flag becomes `SignTextSlot`, lines a `List`),
  `ClientboundRemoveEntitiesPacket`, `ClientboundUpdateAdvancementsPacket`
  (`PositionedAdvancement`), `ServerboundCommandSuggestionPacket`,
  `ServerboundUseItemOnPacket`, `ServerboundUseItemPacket`,
  `ServerboundAcceptTeleportationPacket` (now also carries position and
  rotation).
- **Chunks:** `ClientboundLevelChunkPacketData` replaces
  `getBlockEntitiesTagsConsumer` with `forEachBlockEntityTag`, gains
  `isReady/setReady`; `BlockEntityInfo` gets public accessors.
- **Chat:** `ClientboundPlayerChatPacket` and `ServerboundChatPacket` use
  `Optional` for signature and unsigned content.
- **Spawn info:** `CommonPlayerSpawnInfo.previousGameType()` is an `Optional`
  (login and respawn packets).
- **Explosion:** `ClientboundExplodePacket` gains `playSound()` (wire change).
- `ServerboundPlayerActionPacket.Action` gains `CHANGE_DESTROY_DIRECTION`.

### Registries and entity data

- `entity_type` 158 → 161 (`cushion`, `poplar_boat`, `poplar_chest_boat`),
  `particle_type` 125 → 128, `sound_event` 1968 → 1991, `block` 1196 → 1286,
  `item` 1537 → 1658, `data_component_type` 111 → 122 (removes `map_color`,
  `swing_animation`); most protocol IDs move. `menu` and `mob_effect`
  unchanged.
- `EntityDataSerializers` gains `DYE_COLOR`.
- Consequence: no registry ID or entity data index may be hard-coded; read
  them from the live registries. Check the 26.2 code (scene decoder,
  checkpoint fixtures, tests) for literals.

## Sharing between the adapters

Options considered in phase 0: A (copy `paper-26_2` and adapt), B (shared
sources compiled twice), C (a shared module with Minecraft classes, compiled
against 26.2, holding the unchanged definitions). C was approved first and
withdrawn the same day (D1): it would have restructured the adapters. The
chosen way: each adapter implements every packet itself (A for the packet
implementations); abstraction happens in `paper-common`, which holds no
Minecraft classes.

## Plan

Each phase ends with a green full build, the in-game check by the user and a
commit on the user's word.

| Phase | Content | Check |
|---|---|---|
| 0 | Analysis: spike, hard-coded IDs, classification of the 26.2 definitions | done |
| 1 | `paper-common` extension (only Minecraft-free logic that 26.3 would otherwise duplicate; candidates below, each approved by the user) and neutral docs (D5); 26.2 packet implementations untouched, 26.2 behavior unchanged | in game on 26.2 |
| 2 | `replay-adapter:paper-26_3`: build file (`paperweight.paperDevBundle(libs.versions.paper263)`), `Paper26_3AdapterProvider` (`"26.3"`, service file), its own definition class for every 26.3 packet (taken over from 26.2 with 26.3 IDs and names, adapted where 26.3 differs; classification of the new packets proposed to the user first), its own hooks (capture, playback, scene decoder, snapshots, Argus, actor effects) and its own tests; runtime depends on both adapters | server starts on 26.3 (`-PminecraftVersion=26.3`), records and plays back, including arm swings, transient blocks, post effects |
| 3 | Parity and docs: `docs/adapters/paper-26.3.md` with protocol inventory, README; full regression on both versions (record with world change, containers, entities; seek; two viewers; shutdown during a recording) | in game on 26.2 and 26.3 |

The registry's completeness check (every live PLAY packet has exactly one
definition) means the 26.3 adapter cannot start without definitions for the
new packets, so they are part of phase 2, not a later phase.

## Decisions

- **D1 (2026-11-25, revised the same day), each adapter implements its own
  packets.** `paper-26_2` keeps its implementations unchanged. `paper-26_3`
  gets its own definition class for every 26.3 packet, its own hooks and its
  own tests. There is no shared module with Minecraft classes; `paper-common`
  may be extended with Minecraft-free abstractions where that keeps the
  adapters modular. Reason: the user's decision; the first approved shape
  (option C, `paper-mojang-common`) was withdrawn because it would have
  restructured the adapters.
- **D2 (2026-11-25, revised), naming per adapter.** Each adapter names its
  definitions by its own packet IDs (`Id<DDD><Name>PacketDefinition`, as
  `docs/packet-definition-layout.md` describes) and its catalog validates
  against its own live registry, as today. The phase 0 proposals (names by
  identifier, `add`/`replace`/`remove` catalogs) are withdrawn with option C.
- **D3 (2026-11-25), one artifact.** Both adapters ship in the one
  `replay-runtime-paper` plugin JAR; `PaperAdapterSelector` picks at runtime.
- **D4 (2026-11-25), no cross-version playback.** Replays play only on the
  Minecraft version they were recorded on (greenfield rule; the manifest pins
  adapter, protocol and registry fingerprint).
- **D5 (2026-11-25), neutral docs.** Shared docs (README, `docs/*.md`) name
  no single adapter: no version-specific classes, IDs or measurements; they
  describe contracts and link to `docs/adapters/`. Each adapter has its own
  document (`docs/adapters/paper-26.2.md`, `paper-26.3.md`) plus an index
  `docs/adapters/README.md`. Done together with phase 1.
- **D6 (2026-11-25, revised), own tests per adapter.** `paper-26_3` has its
  own complete test suite; there is no cross-version test run and no linkage
  check (nothing compiled against one version runs on the other).
- D7 (shared code rule for binary compatibility) is withdrawn with option C.

## Status

- Modularization phases 1 to 3 are done and committed (last commit before
  this brief: `cb8b4b1`); the previous development notes were removed in
  `058073c`.
- Phase 0: done 2026-11-25 (results below). No production code changed.
- Phase 1: approved 2026-11-25 and implemented (not committed):
  `paper-common` gained package `protocol` (`ProtocolSnapshot`,
  `ProtocolPacket`, `PaperPacketRegistry`) and
  `packet.clientbound.CaptureContexts` (moved from
  `Paper26_2CaptureContexts`); `Paper26_2PacketRegistry` is a thin factory
  (version `26.2`, codec prefix, 26.2 catalog); `Paper26_2ProtocolIntrospector`
  returns the shared snapshot. `Paper26_2PacketCodec`,
  `Paper26_2ReplayAdapter` and `Paper26_2AdapterProvider` stay: the adapter
  already extends `AbstractReplayAdapter`, the provider wires version types,
  and a further base would remove only a few lines. Docs neutral (D5): new
  `docs/adapters/README.md`; Argus measurements moved to
  `docs/adapters/paper-26.2.md`. Full build green 2026-11-25 (paper-26_2 1183, paper-common 105 tests,
  no failures). In-game check on 26.2 passed 2026-11-27: replay
  `d01d7054-2756-4908-8414-3a88f3221e31` (full scenario, about 20 minutes;
  from minute 16 buffering is very slow after about 18,480 TNT ignitions, a
  load observation outside this step) and `58a4c03b-7c61-48c5-8dbd-41df84492dd8`
  (server stopped during the recording; finalized with manifest). Registry
  fingerprint unchanged (`d0fc878c...`). Commit pending.

## Phase 1 plan (draft, 2026-11-25)

Goal: version-neutral logic that `paper-26_2` holds today and `paper-26_3`
would otherwise duplicate moves to `paper-common`, behind interfaces the
adapters implement; no packet definition, decoder or hook of `paper-26_2`
changes its implementation. Then the shared docs become neutral (D5).

Candidates (classes in `paper-26_2` without Minecraft imports), each to be
confirmed or dropped by the user:

| Class (lines) | What it does | Proposal |
|---|---|---|
| `Paper26_2PacketRegistry` (145) | validates a protocol snapshot against the catalog (version string, codec key prefix, every PLAY packet defined, IDs match) and builds the `PacketRegistry` | generic `PaperPacketRegistry` in `paper-common`, parameterized by version, codec key prefix and catalog lookup; each adapter keeps a thin factory |
| `Paper26_2CaptureContexts` (81) | places a capture context at a position, block, entity, chat text or channel | move to `paper-common` as is (no version content) |
| `Paper26_2PacketCodec` (36) | delegates encode/decode to the connection accessor | stays: it depends on the version's accessor; too small to abstract |
| `Paper26_2ReplayAdapter` (74), `Paper26_2AdapterProvider` (131) | composition root and wiring | stay per adapter; only check whether a shared base in `paper-common` removes real duplication |

Docs (D5): `README.md`, `docs/architecture.md`, `getting-started.md`,
`packet-definition-layout.md`, `recording-tracks.md`, `virtual-observer.md`,
`capture-source-policy.md` become version-neutral; new
`docs/adapters/README.md` (index, what an adapter implements, how to add a
version); `docs/adapters/paper-26.2.md` keeps the 26.2 specifics.

In-game check on 26.2 (unchanged behavior): record with a world change, mobs
and a player, block breaking/placing, containers, chat and particles/sounds;
play back, seek, two viewers; stop the server during a recording.

## Phase 0 results (2026-11-25)

Method (all in the scratchpad, no repository change): both server JARs
extracted (26.2 build 135, 26.3 build 244 = the pinned dev bundle); a
linkage checker (ClassFile API) resolved every Minecraft/CraftBukkit member
reference of the compiled 26.2 module against the 26.3 server; a normalized
`javap -c` diff of all 285 common protocol classes; and the unchanged 26.2
test suite, compiled against the 26.2 dev bundle, run on the 26.3 classpath.

- **Baseline:** 26.2 test suite on its own classpath: 1183/1183 green (the
  harness is valid).
- **As is on 26.3:** 626 of 1108 green; nearly all failures are packet IDs
  (definition constants and test literals) and the catalog's completeness
  check (three new clientbound packets).
- **Spike, IDs retargeted to 26.3** (definition `PACKET_ID`, test literals,
  `swing` renamed to `punch`, placeholder definitions for the three new
  clientbound packets), still compiled against 26.2: **998 of 1108 green**.
  This proves the central assumption of option C: code compiled against 26.2
  runs on 26.3 wherever it does not touch a changed API.
- **Bytecode diff:** 56 of 285 common protocol classes changed; most are
  `FriendlyByteBuf` constructors replaced by `StreamCodec.composite` (same
  wire). Because replays never cross versions (D4), wire changes matter only
  where our code parses raw bytes itself (11 definitions do).
- **Hard-coded IDs:** main code uses no registry ID or entity data index
  literals (all symbolic). The ID-carrying entity data serializers
  (`OPTIONAL_UNSIGNED_INT`: Frog, Player, FireworkRocket) are used by the
  same entities in 26.3; the new `Cushion` uses `DYE_COLOR` (no ID), so the
  `set_entity_data` rewrite stays valid. Tests hard-code packet IDs in about
  140 files (`PACKET_ID` literals, hex asserts, `expected: <127>`).

Classification of the 210 definitions of the 26.2 module:

| Class | Definitions | 26.3 consequence |
|---|---|---|
| unchanged | 197 | shareable as is |
| main code breaks on 26.3 | CB `entity_position_sync`, `explode`, `level_chunk_with_light`, `level_particles`, `light_update`, `move_entity_pos`, `move_entity_pos_rot`, `move_entity_rot` (raw wire parse: rotation bytes moved), `player_chat`, `remove_entities`; SB `player_action` (enum switch without default; new constant `CHANGE_DESTROY_DIRECTION` would throw `MatchException`), `use_item_on` | one variant per version |
| removed in 26.3 | SB `swing` | 26.2 only |
| new in 26.3 | CB `add_transient_block`, `post_effects`, `swing_animation`; SB `punch` | 26.3 only (phase 3) |

Shared definitions whose tests touch changed API or changed native decoder
behavior (definition shareable, test needs a version part):
`custom_chat_completions`, `move_vehicle`, `open_book`, `open_sign_editor`,
`respawn`/`login` (spawn info), `set_player_team`, `update_advancements`,
`update_tags`. Nine tests fail only because a test helper uses
`RegistryLayer.WORLDGEN` / `VanillaRegistries.createLookup` (removed or
changed): a shared test fixture fix.

Non-definition classes that break on 26.3: `Paper26_2SnapshotProvider`
(`ServerEntity` constructor takes `UpdateInterval` instead of `int`),
`Paper26_2SceneDecoder` (movement, chunk, remove entities),
`Paper26_2ViewResetter`, `ReplayEntityInteractions` (`use_item_on` record),
and `Paper26_2BlockStateDecoder`/`SubjectStateDecoder`/`SceneDecoder` call
`CraftItemStack.asBukkitCopy(ItemStack)`, which 26.3 removed; the
`asBukkitCopy(ItemInstance)` overload exists in both versions.

For the 26.3 adapter (D1) this classification says where its own
definitions and hooks must differ from the 26.2 ones; everything else is
taken over with 26.3 IDs and names.
