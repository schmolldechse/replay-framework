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
| `replay-adapter:paper-26_3` | the 26.3 adapter, its own implementation of every 26.3 packet and hook (taken over from 26.2 and adapted, see `docs/adapters/paper-26.3.md`) |
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

Versions: `gradle/libs.versions.toml` pins `paper263 =
"26.3.build.263-stable"` (raised from 244, D9) and `paper-api263`; the run
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
- **D8 (2026-11-27), classification of the new 26.3 packets.** CB
  `swing_animation`: ephemeral scene effect at the entity, scope
  `REWRITE_IDENTITIES`, identical payload (as `animate`); CB
  `add_transient_block`: ephemeral scene effect at the block, scope `SAFE`,
  identical payload; CB `post_effects` (PLAY): viewer-local, never replayed;
  in CONFIGURATION it is connection control; SB `punch`: no reaction (as
  `swing` in 26.2). Reason: matches how the 26.3 server sends them (trackers
  for the first two, own connection only for `post_effects`).
- **D9 (2026-11-27), 26.3 server build.** The 26.3 dev bundle and
  `paper-api263` follow the newest stable 26.3 build
  (`26.3.build.263-stable` on this date), and `runServer` for 26.3 uses the
  same build. Reason: compile and test against the server that runs.
- **D10 (2026-11-27), commits in phase 2 per sub-step** (2a and 2b together,
  because the adapter's registry check needs the new packets), each after a
  green full build and on the user's word; the in-game check comes once the
  adapter is complete.

## Status

- Modularization phases 1 to 3 are done and committed (last commit before
  this brief: `cb8b4b1`); the previous development notes were removed in
  `058073c`.
- Phase 0: done 2026-11-25 (results below). No production code changed.
- Phase 1: approved 2026-11-25, implemented and committed:
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
  fingerprint unchanged (`d0fc878c...`). Committed as `7f5b69f`.
- Follow-ups outside this work (2026-11-27): issue #16 (playback stalls on
  recordings of high packet density; `MemoryPacketBuffer` scans, eviction of
  the playing segment, accounting, no frame limit per segment) and issue #17
  (diagnostics: recording statistics, playback counters, offline inspector,
  replay bundles). The reference recording `d01d7054` is the asset of the
  public pre-release `fixture-dense-playback-26.2` (deletable). Both planned
  after the 26.3 adapter.
- Phase 2: plan approved 2026-11-27 (D8, D9, D10); implemented and
  committed (`c078345` module, `5087fc1` runtime, docs and this brief in the
  following commit). 2a: `paper-26_3` taken over from `paper-26_2` by a scratchpad
  script (package, prefix, IDs and class names from the 26.3 registry of build
  263, which matches build 244); `swing` dropped. 2b: `swing_animation`,
  `add_transient_block`, `post_effects`, `punch` with tests. 2c/2d: the phase 0
  list plus `Paper26_3MovementDeltas` (linear and stepped deltas), scene decoder
  movement from the native packet, light rebuild through the 26.3 records,
  `UpdateInterval.periodic(1)`. Found by the 26.3 tests: the block break
  actor effect must send 2001, now named `PARTICLES_AND_SOUND_DESTROY_BLOCK`
  (26.3 `PARTICLES_DESTROY_BLOCK` is 2014, without sound); `BIT_SET` is a byte
  array; enum IDs fall back instead of failing; `animate` actions renumbered;
  previous game type is an optional VarInt. The scene decoder's literal entity
  data IDs (`SHARED_FLAGS = 0`, `CUSTOM_NAME = 2`, missed in phase 0) are now
  checked against the server by a test in 26.3. 2e: 1198 tests in
  `paper-26_3`, all green. 2f: runtime depends on both adapters; `runServer`
  pins the dev bundle's build for 26.2 and 26.3. 2g:
  `docs/adapters/paper-26.3.md` (generated from the 26.2 document with 26.3
  IDs, a differences section, Argus not yet measured on 26.3) and the index.
  Commits: 2a to 2e could not be green separately (the module compiles only
  as a whole), so they are one commit, then 2f and 2g (D10 adjusted with the
  user's approval).
  Full build green 2026-11-27 (paper-26_2 1183, paper-26_3 1198 tests; the
  plugin JAR lists both providers). In-game check passed 2026-11-27, nothing
  noticed: 26.3 replay `81f40801-9ead-4a93-9bc6-8a1cb8b70529` (`paper-26.3`,
  protocol 777, 239 s; contains 475 `swing_animation`, 192
  `add_transient_block`, 402 `level_event`, 128 `explode`); 26.2 replays
  `3e398c09-4df9-4838-a888-564986c41aca` and
  `51224375-3b90-4636-acfd-676c8dacb4c3` (server stopped; finalized), still
  `paper-26.2` with the unchanged fingerprint `d0fc878c...`. A server stop
  during a 26.3 recording was not part of this check.

## Phase 2 plan (approved 2026-11-27, done)

Goal: `replay-adapter:paper-26_3` records and plays back on a Paper 26.3
server, with its own definition class for every 26.3 packet, its own hooks
and its own tests (D1, D6). `paper-26_2` stays unchanged. Every sub-step ends
with a green full build; the in-game check is at the end (the adapter cannot
start before it is complete, because of the registry completeness check).

| Step | Content |
|---|---|
| 2a | **Module and take-over.** `replay-adapter:paper-26_3` (settings, build file with `paperweight.paperDevBundle(libs.versions.paper263)`, `MOJANG_PRODUCTION`). Sources and tests of `paper-26_2` taken over by a script: package `v26_2` → `v26_3`, prefix `Paper26_2` → `Paper26_3`, adapter ID `paper-26.3`, codec prefix `paper-26.3/`, version `26.3`; every definition renamed and its `PACKET_ID` set from the 26.3 `packets.json` (104 clientbound and 17 serverbound IDs move); test literals for packet IDs replaced by the definitions' constants. `swing` dropped. The script lives in the scratchpad, not in the repository. |
| 2b | **New packets** (classification below, approved by the user first): clientbound `add_transient_block`, `post_effects`, `swing_animation`, serverbound `punch`, each with its definition and test. |
| 2c | **API and wire changes** in the 26.3 definitions (phase 0 list): movement (`entity_position_sync`, `move_entity_pos`, `move_entity_pos_rot`, `move_entity_rot` with its raw wire parse), `explode` (`playSound`), `level_particles` (record, randomization, per-axis speed), `level_chunk_with_light` and `light_update` (records, `forEachBlockEntityTag`), `player_chat` (`Optional`), `remove_entities` (record), `player_action` (`CHANGE_DESTROY_DIRECTION`), `use_item_on` (record); `animate` documents that arm swings moved to `swing_animation`. |
| 2d | **Hooks and helpers:** `Paper26_3SnapshotProvider` (`ServerEntity` takes `UpdateInterval`), `Paper26_3SceneDecoder` and `Paper26_3ViewResetter` (movement, chunk, remove entities), `ReplayEntityInteractions` (`use_item_on`), the decoders' `CraftItemStack.asBukkitCopy(ItemInstance)`; Argus eye and platform checked against the 26.3 server classes (`RegionizedPlayerChunkLoader`, `NearbyPlayers`). |
| 2e | **Tests on 26.3:** the taken-over suite adapted where 26.3 differs: registry helpers (`RegistryLayer.WORLD`, no `VanillaRegistries.createLookup`), tests of changed native decoder behavior (`custom_chat_completions`, `open_book`, `open_sign_editor`, `remove_entities`), spawn info (`login`, `respawn`), `set_player_team`, `update_advancements`, `update_tags`, `move_vehicle`. Target: the full suite green with no test removed without a reason in the commit message. |
| 2f | **Runtime:** `replay-runtime-paper` depends on both adapters; service files merged (one provider per version); `PaperAdapterSelectorTest` covers 26.3. `runServer -PminecraftVersion=26.3` starts 26.3. |
| 2g | **Docs:** `docs/adapters/paper-26.3.md` (runtime baseline, differences to 26.2, protocol inventory of every 26.3 packet), the 26.3 row in `docs/adapters/README.md`; CLAUDE.md module map and status. |

### Classification proposal for the new packets

| Packet | What the server does | Proposal |
|---|---|---|
| CB `swing_animation` (`entityId`, `hand`, `SwingAnimation`) | `LivingEntity.swing` sends it through the chunk source to the entity's trackers (eyes included), replacing the swing actions of `animate` | ephemeral scene effect at the entity, playback scope `REWRITE_IDENTITIES` (entity ID), recipient copies `identicalPayload`, like `animate` |
| CB `add_transient_block` (`pos`, `BlockState`) | `FallingBlockEntity` sends it with a `block_update` to its trackers when it cannot place itself | ephemeral scene effect at the block position, scope `SAFE`, `identicalPayload` |
| CB `post_effects` (PLAY; list of identifiers) | `ServerPlayer.sendPostEffects` sends the player's own screen effects to its own connection only | viewer-local (`stateful`, `VIEWER_LOCAL`): describes one player's view, never replayed into the viewer's screen, like the camera |
| CB `post_effects` (CONFIGURATION) | the same during configuration | connection control, as every non-PLAY packet |
| SB `punch` (no fields) | a left click into the air; the handler fires Paper's interaction logic, the arm swing arrives as `swing_animation` | no reaction (`ServerboundReaction.NONE`), like `swing` in 26.2 |

### Questions (answered 2026-11-27)

1. Server build: dev bundle raised to the newest stable build (D9).
2. Commits: per sub-step (D10), as far as each can be green on its own.

### In-game check on 26.3

Start with `runServer -PminecraftVersion=26.3`. Record with a world change,
mobs and a player, block breaking and placing, containers, chat,
particles and sounds, explosions, arm swings (hitting air and mobs), sand or
gravel falling onto a torch (transient block); play back, seek back and
forth, two viewers; stop the server during a recording. Then a short
recording on 26.2 to confirm the 26.2 adapter is still selected there.

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
