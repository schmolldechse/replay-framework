# Argus: virtual observers

Status: phase 1 is implemented for Paper 26.2. Recordings with bounded
regions take their scene from eyes; recordings of whole worlds still use
player views. Playback is unchanged. See [Implementation](#implementation).

Argus is the internal codename of the observer system. One virtual observer
is an **eye**; a real player whose connection contributes ambient effects is
an **ear**.

## Why

Today a recording is the union of what connected players receive. That source
has gaps no single packet definition can close:

- a player never receives its own entity, so movement, equipment, respawn,
  login and logout of the recorded player have to be reconstructed;
- the server tears a dead player's view down and rebuilds it;
- nothing is recorded while no player is connected or in range; and
- every packet arrives once per recipient and must be deduplicated by content.

A virtual observer is a packet recipient that belongs to the recording
instead of to a player. It sees every player the way another player would,
its view is never torn down, and it exists whether or not anyone is online.

## What an observer is

`Paper26_2VirtualObserver` creates a `ServerPlayer` object that is **never
added to its level and never added to the player list**. It is registered
with exactly two server systems:

| System | Registration | What it delivers |
|---|---|---|
| Moonrise chunk loader | `RegionizedPlayerChunkLoader.addPlayer` | chunks, light, block and block-entity changes, view center and radius |
| Nearby-player map | `NearbyPlayers.addPlayer` | everything the entity tracker sends: spawn, pairing data, movement, metadata, equipment, attributes, effects, removal |

Its connection has no socket. `Connection.send` is overridden and hands each
packet object to the recording on the server thread, in server order.

The level only ticks chunk loaders of listed players and pauses entity
ticking after 300 ticks without listed players, so the observer drives its
own loader and resets the level's empty timer once per tick.

### Spike results (Paper 26.2 build 135, no player online)

| Check | Result |
|---|---|
| Chunks at view distance 6 | 213 `level_chunk_with_light` within 100 ticks, no client acknowledgement needed |
| Spawned cow | `add_entity`, metadata, attributes, then continuous movement packets |
| Block placed | `block_update` |
| Sound and particle played at the observer's position | **not received** |
| `Bukkit.getOnlinePlayers()`, `World.getPlayers()`, NMS level players, player-list count | all 0 |
| Listed among the world's entities | no |
| Second, overlapping observer | broadcast packets arrive as the **same object** for both; chunks and entity pairing arrive once per observer |
| After `close()` | no further packets |

## Imperceptibility

Players cannot see, hear or interact with an observer because no client is
ever told about it: it is not an entity, so the tracker never sends it, and
it is not in the player list, so there is no tab entry, join message, player
count, `/list` entry or `@a` match. It has no hitbox, cannot be targeted,
damaged, pushed, ridden, or selected with `@e`.

Remaining places where server-side code can notice it, each to be closed in
the prototype:

- `PlayerTrackEntityEvent` fires with the observer's Bukkit handle when a
  plugin listens for it.
- The observed chunks stay loaded and simulated. This is intended, but it is
  a change in server behavior and must be bounded by a budget.
- Natural mob spawning and random ticks consult the nearby-player map. The
  observer is in spectator mode, which those checks exclude; this needs an
  explicit test.

## Merging several observers into one scene

All observers of a recording feed one scene stream. Because packets are
captured as objects on the server thread, merging is exact and needs no
payload comparison. Every packet belongs to one of three distribution kinds:

| Kind | Examples | Rule |
|---|---|---|
| Broadcast | movement, metadata, equipment, animations, block updates | the server sends one object to all recipients; publish it on first sight, drop the identical object for further observers in the same tick |
| View membership | chunk load and forget, entity spawn with pairing data, entity removal | reference-counted per chunk and per entity: publish the load when the first observer gains it, the unload when the last observer loses it, drop everything in between |
| View-local | chunk cache center and radius, simulation distance | describes one observer's view; never part of the scene |

The reference counts are the scene model. They make overlap free of
duplicates, make an observer joining or leaving invisible in the replay, and
give moving observers correct load and unload behavior.

## From recording scope to observers

`RecordingScope` has worlds, cuboid regions and a chunk-loading policy. An
observer has a position, a view distance and, per entity type, a tracking
range that is measured horizontally from its position. Coverage therefore
has to be planned.

**Bounded regions.** The planner covers the region's chunk rectangle with a
lattice of static observers. The spacing is the smaller of the chunk view
diameter and √2 times the smallest configured entity tracking range, so
that every block of the region is inside every entity type's range of at
least one observer. Each observer uses the smallest view distance that
closes the lattice, which keeps the number of loaded chunks close to the
region itself. Small arenas need one observer.

**Whole worlds.** A world has no position, so observers follow anchors. Each
player in the world gets a companion observer that moves with the player but
does not share its fate: it stays in place through death, and for a grace
period after logout. Overlapping companions cost nothing in the stream
because of the reference counts.

**Developer anchors.** A scope may add fixed points and entities to follow
(a boss, a moving platform, a spectator camera path). They are planned like
regions and companions.

**Chunk-loading policy.** `PRELOAD_SCOPE` maps to static observers, which
keep their area loaded and simulated. `LOADED_ONLY` maps to companions only:
the recording never loads anything a player would not have loaded.

**Budget.** Observer count and loaded chunks are bounded per recording and
per server. A scope that cannot be covered within the budget is rejected
when the recording starts, not silently recorded with holes.

## Ambient packets

The spike confirms that an unlisted recipient does not receive packets the
server distributes through the player list or the level's player list:
sounds, level events, particles, block-break progress, explosions, weather,
time and player-profile entries.

Adding the observer to those lists would deliver them, but would make it an
online player for Bukkit and every plugin (player count, tab, scoreboard and
anti-cheat plugins). Citizens keeps its NPCs out of the player list for the
same reason. Listing would also not remove the range limit: a sound reaches
recipients within 16 blocks, particles within 32, level and block events and
explosions within 64.

Decision: eyes stay unlisted, and ambient packets have two sources.

1. **Sampled state** for families that are state rather than events. Time,
   weather (rain and thunder level, start and stop), world border and player
   profiles are read from the level on the server thread and emitted as
   native packets when they change. This needs no recipient and works with
   no player online.
2. **Player ears** for event families: sounds, entity sounds, particles,
   level events, block events, block-break progress and explosions are
   captured from the connections of real players, through the existing
   PacketEvents capture path. `PlayerList.broadcast` sends one packet object
   to all recipients, so the scene merges them by identity like eye
   broadcasts.

Known gaps of player ears, accepted for now: an effect nobody is in range of
is not recorded, and a sound a player causes itself (steps, digging) is not
sent to that player, so it is recorded only when a second player hears it.

## Packet definitions

Per-packet definitions stay the unit of decision. Each definition keeps its
playback policy and additionally declares its `PacketDistribution`:

| Kind | Meaning | Declared by |
|---|---|---|
| `PLAYER_ONLY` | sent to listed players only; an eye never receives it | default |
| `BROADCAST` | one object for all recipients of the chunk system or entity tracker | 26 definitions |
| `VIEW_MEMBERSHIP` | adds or removes a chunk or entity; names it via `sceneMembership` | add entity, remove entities, chunk load, chunk forget |
| `VIEW_LOCAL` | describes one view; never part of the scene | chunk cache center and radius, simulation distance, chunk batch |

Definitions receive the packet object, not bytes.

Capture logic that only compensates for the player-connection source
disappears for observer-sourced recordings: the player-state sampler, the
movement/equipment/swing authority index, the respawn replacement and the
death-teardown withholding.

## Developer extension points

Plugins often show things to specific players only. An observer is not one
of them, so the API lets a plugin treat a recording as one more viewer:

- **Viewer handle.** A recording exposes a restricted viewer object. A
  plugin passes it wherever it passes players today: `showEntity` for
  display entities and holograms that are hidden by default, per-player
  block changes, packets sent through a packet library. Whatever the plugin
  sends reaches every observer of that recording.
- **Visibility rules.** By default observers see everything that is visible
  by default. A plugin can hide entities from recordings (vanished staff) or
  reveal entities that are hidden by default.
- **Custom payloads.** A plugin registers a channel with a policy (record
  and replay, record only, drop), so its own packets get an explicit
  decision like built-in ones.
- **Scene contributions.** A plugin can publish a native packet into the
  scene stream directly, for effects that never exist as server state.
- **Anchors.** See above.

## Checkpoints and playback

A checkpoint is the state a client needs to join the scene. That is what a
freshly registered observer receives: all chunks in view and the pairing
data of all tracked entities. The scene model can therefore produce
checkpoints from its own reference-counted state instead of a separate
snapshot of server objects.

Playback changes more fundamentally. A multi-observer scene is larger than
one client's view, and the stream no longer follows one player's chunk
cache. Playback needs to hold the materialized scene and serve the viewer by
position and view distance, like a server does. This is a separate work
package and is not covered by the spike.

## Implementation

Everything except the eye itself is shared by all adapters.

| Part | Module | Role |
|---|---|---|
| `SceneObservation`, `CaptureOrigin` | `replay-adapter:api` | core boundary: open and close a watch per recording; tag where a packet was observed |
| `Argus` | `paper-common` | plans eyes for a scope, drives them, merges their packets, publishes the scene |
| `ArgusScene` | `paper-common` | reference counts and broadcast identity |
| `EyePlacement`, `LatticeEyePlacement` | `paper-common` | where eyes stand |
| `ArgusPlatform`, `ArgusEye`, `AmbientSource` | `paper-common` | the interfaces one Minecraft version implements |
| `Paper26_2ArgusPlatform`, `Paper26_2ArgusEye`, `Paper26_2AmbientSource` | `paper-26_2` | the 26.2 implementation |

A new adapter implements `ArgusPlatform` and declares the distribution of
its packet definitions. Placement, merging and lifecycle are inherited.

**Source selection.** Every captured packet carries a `CaptureOrigin`. A
recording whose scope the adapter observes accepts `OBSERVER` and
`PLAYER_ONLY` packets; any other recording accepts `PLAYER_VIEW` and
`PLAYER_ONLY`. The player-connection path tags a packet `PLAYER_ONLY` when
its definition is not observable, and `PLAYER_VIEW` otherwise. No scene
change is therefore recorded from two sources.

**Lifecycle.** The core opens the watch when a recording becomes active and
closes it when the recording leaves the capture route. Eyes open on the
next server tick. Closing an eye publishes the unloads of what only it was
holding. A scope beyond the eye budget (64 eyes per region, view distance
8) fails the start of the recording.

**The eyes define the scope.** Every watch has scenes of its own, and each
scene packet carries the identity of its watch. A recording accepts every
packet of its own watch and none of another, without looking at the
position of the packet. A recording therefore contains its regions plus the
margin its eyes see.

Filtering scene packets one by one by position does not work: the tracker
sends entity movement as steps relative to the last position. One dropped
step, for example while a player is above the region, leaves every later
position wrong until the server happens to send an absolute one. With the
eyes as the boundary, an entity that leaves is removed by the server and
spawned again when it returns.

**Checkpoints** of an observed recording cover the same area
(`SceneObservation.coverage`): the chunks its eyes receive, over the full
height of the world. Otherwise a seek would restore a scene the following
stream does not fit. The checkpoint area is a rectangle while an eye
receives a rough circle, so a checkpoint can hold entities in the corners
that no eye tracks. They stay where they were until scene-model checkpoints
replace the snapshot.

**Scene packets** pass through the same packet definitions as player
packets to obtain their capture context.

**Bundles.** The tracker sends a new entity and its initial state as one
bundle. When another eye already holds the entity, the whole bundle is
dropped.

### Verified on Paper 26.2 build 135, no player online

| Check | Result |
|---|---|
| One watch with several eyes | each chunk and entity published once |
| Two overlapping watches | each has a complete scene of its own; closing one leaves the other untouched |
| Movement of 30 wandering animals over 62 seconds, replayed like a client | 1800 relative steps, 148 absolute checks, largest difference 0.27 blocks, no step for an unknown entity |
| 400x400 region | lattice loaded 1077 chunks without duplicates |
| Storm started | rain start and rain level packets from the sampled source |
| Codec and definition context | 1702 packets encoded with world and position, no failure |

Not verified in game: a full recording with a region scope through storage
and playback.

### Player-only packets in a region recording

Packets that only players receive are single events, not a stream of
relative steps. A region accepts them by their position, so such a packet
must say where it belongs:

- Sound, particles, level event and explosion carry their position; an
  entity sound takes the position of its entity.
- The tab list and public chat are **world-wide**: every region recording of
  that world accepts them wherever the recipient stands. Without the tab
  list a client cannot show a player who joined after the recording began.
- Everything else without a position stays out of a region recording.

Chat follows one rule in every recording: a replay shows what everyone
could read and nothing the server told one player only.

| Packet | Recorded and replayed | Neither recorded nor replayed |
|---|---|---|
| Player chat, disguised chat | every chat type except the four below | whisper and team message types |
| System message | death, join, quit and advancement broadcasts, once per tick | everything else: command feedback, plugin text, action bar |

A system message is recognized by its translation key. A broadcast a plugin
writes as plain text is therefore treated as private.

### Known limits of phase 1

- Player chat is recorded once per recipient, because each copy carries
  recipient-specific signature data. With several players online a replay
  shows a chat line several times.

- A player in the region receives time and weather from the server as well,
  so a recording can hold them twice. These packets set state and are
  harmless when repeated.
- Overlapping recordings encode the packets they share once each.
- `PlayerTrackEntityEvent` still fires for an eye.
- Scene packets are encoded on the server thread.

## Suggested phases

1. Done: scene model, lattice placement, sampled time and weather, and the
   distribution kind on packet definitions, for bounded regions.
2. Companion eyes for whole worlds; then remove the compensation logic of
   the player-view source.
3. Developer anchors and a configurable budget.
4. Developer API.
5. Playback scene server and scene-model checkpoints.

The 26.3 adapter keeps the default `SceneObservation.none()` and records
from player views as before. It and the player-view capture path can move
to a legacy location once phase 2 removes that path from 26.2.
