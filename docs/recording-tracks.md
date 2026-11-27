# Recording tracks

A recording is a set of **tracks**, the way a media container holds video,
audio and subtitle tracks. Each track is a time-ordered stream of packet
frames with its own segments, index and checkpoints. What belongs to a whole
stream, such as its world, is stored once on the track instead of on every
frame.

Tracks keep different kinds of data apart. Playback reads the scene the
viewer watches and nothing else; the inventory of a player or the scene of
another world is read only when someone asks for it.

## Track kinds

| Kind | Keyed by | Role | Contents | Read during playback |
|---|---|---|---|---|
| `GLOBAL` | nothing | scene | world-wide packets: chat, tab list, player profiles | always |
| `WORLD` | world | scene | the scene of one world: chunks, blocks, entities, effects, time, weather, border | the viewed world always; other worlds when players there are queried |
| `STATE` | subject | state | the state of one subject that the scene does not show | when the subject's state is queried |
| `BLOCK_STATE` | world | state | the contents of the containers of one world: chests, barrels, furnaces and more | when a container's contents are queried |

A recording has exactly one `GLOBAL` track, one `WORLD` track per recorded
world, one `STATE` track per recorded subject and one `BLOCK_STATE` track
per world with containers. A track is created when its first packet
arrives.

`TrackKind` declares what each kind is keyed by and its role. A `TrackKey`
(kind plus world or subject) identifies a track: descriptor validation
checks the key against the kind, and a manifest holds at most one track per
key. Playback plays the tracks with the scene role and reads those with the
state role on demand. A new kind therefore needs its constant in
`TrackKind`, a `TrackRoute` and its case in the core's `TrackKeys`, and a
playback consumer; the wire format stays the same.

For example, a four-minute recording of two players who travelled from the
overworld into the nether, while a few chest minecarts and donkeys were in
view, consists of these tracks:

| ID | Kind | Key | Segments | Size |
|---:|---|---|---:|---:|
| 0 | `GLOBAL` | | 8 | 32 KB |
| 1 | `WORLD` | `minecraft:overworld` | 8 | 28.5 MB |
| 2, 3 | `STATE` | the two players | 3 and 8 | 3 KB and 9 KB |
| 4 to 7, 10 | `STATE` | five chest minecarts | 1 each | under 1 KB each |
| 8, 9 | `STATE` | two donkeys with chests | 1 and 2 | under 1 KB each |
| 11 | `WORLD` | `minecraft:the_nether` | 7 | 11.9 MB |

Nearly all of the data is scene. A viewer in the nether reads tracks 0 and
11; the overworld's 28.5 MB stay untouched until the viewer switches to it.

## Routing

Every captured packet carries exactly one `TrackRoute` in its
`CaptureContext`, set by the adapter. The core's `TrackKeys` maps it to a
track and to the world in which a playback client applies the frame:

| Route | Track | Frame world |
|---|---|---|
| `Subject` | `STATE` track of that subject | none |
| `BlockState` | `BLOCK_STATE` track of its world | its world |
| `WorldWide` | `GLOBAL` track | none, applies in every world |
| `Scene` | `WORLD` track of its world | its world |

A later route replaces an earlier one, so a packet can never claim two
tracks.

- A packet names a subject when its definition declares it as the
  recipient's own state (`RecipientState`), or when Argus sampled it for an
  entity.
- A packet is block state when Argus read it from a container block; see
  [containers](#containers).
- A packet is world-wide when its definition says so: public chat, system
  broadcasts and the tab list.
- Every other packet belongs to the world it was observed in.

Each track has its own segment writer and rotates its segments
independently under the recording's `ReplayBudget`.

## Subjects

A subject is anything with state of its own. It is identified by kind and
UUID; the label in the manifest is for display only.

| Subject kind | Identity | State recorded |
|---|---|---|
| `PLAYER` | profile UUID | inventory with armor and off hand, hotbar and selected slot, health, food and saturation, experience |
| `ENTITY` | entity UUID | the inventory of villagers, wandering traders, piglins and allays; the content of chest and hopper minecarts and chest boats; the chest of donkeys, mules and llamas |

The scene already shows what every client sees of an entity: equipment,
health where the client gets it, effects, flags and type-specific metadata.
A `STATE` track holds only what no client sees without opening the subject
or being it.

Not recorded: the item a player holds on its cursor, its abilities, and the
contents of an ender chest. The contents of chests and other container
blocks are recorded per world, see [containers](#containers).

### State sources

A state track is filled by one of two sources. Both produce the native
packets the server sends a client that views the subject, so a state track
is packet-based like every other track.

- **Observed.** The server always sends a player its health, food and
  experience. The player's own `set_health` and `set_experience` describe
  their recipient and go to its state track.
- **Sampled.** The adapter's `SubjectStates` (Argus) reads the state on the server thread
  and emits packets when its signature changed:
  - a player's inventory and selected slot **every tick**, as
    `container_set_slot` for each changed slot of window 0 and
    `set_held_slot`; more than 12 changed slots become one
    `container_set_content`. The server does not tell a player about every
    change of its own inventory: in creative mode the client sets its slots
    itself, and while a chest is open the inventory is updated through the
    chest's window. Reading the inventory records every change within one
    tick.
  - an entity's inventory every `ObserverOptions.stateSampleInterval`, as
    one `container_set_content`, because nobody receives it unless they open
    the entity.

When a subject comes into a watch's coverage, its complete state is
published. A subject that leaves the coverage keeps its track, which
receives empty checkpoints until the subject returns.

## Containers

Blocks hold items too: chests, barrels, shulker boxes, hoppers. Nobody
receives their contents without opening them, and a world can hold
thousands of them. They are therefore not subjects with a track each, but
share one `BLOCK_STATE` track per world, in which every frame names its
block.

| Recorded | Not recorded |
|---|---|
| chests, trapped and copper chests, barrels, shulker boxes, hoppers, dispensers, droppers, crafters, furnaces, blast furnaces, smokers, brewing stands, chiseled bookshelves, shelves, decorated pots, jukeboxes | ender chests, whose contents belong to the player who opens them |

- **Packets.** A container's contents are one native `block_entity_data`
  packet: position, block entity type and the block entity's complete data,
  items included. The server sends the same packet to clients with less
  data; the recording keeps all of it and never replays it to a viewer. A
  container that is gone is recorded as a `block_update` to air at its
  position.
- **Double chests.** Each half is recorded on its own and names its other
  half and its side in its data, so playback returns both as one container
  of 54 slots, the right half first, as the game shows it.
- **Reading.** The adapter's `BlockStates` (Argus) reads every chunk of the watch's
  coverage once per `ObserverOptions.stateSampleInterval`. The chunks are
  spread over the ticks of the interval, so a storage hall costs a little
  every tick instead of a lot at once. A container is published when it is
  new to the recording or its contents changed; a chunk that leaves the
  coverage is forgotten and read completely when it returns.

## Checkpoints across tracks

At every checkpoint barrier, every track writes a checkpoint: the packets a
client needs to start that track at that moment.

| Track | Checkpoint contents |
|---|---|
| `WORLD` | the scene of that world within the watch's coverage |
| `GLOBAL` | the tab list |
| `STATE` | the complete state of its subject, built like the packets a client receives on join or when opening the subject |
| `BLOCK_STATE` | the contents of every container of that world within the coverage |

All tracks share the barrier's ordinal and time. A track created later
writes empty checkpoints for the barriers before it, so every track has one
checkpoint per barrier and every index starts at ordinal 0. The figure under
[playback](#playback) shows a nether track with empty checkpoints before its
first packet.

Playback answers barrier *n* with checkpoint *n* of every track it reads.
The global track always exists as track 0, and playback seeks on its index.

A barrier is allocated on the server thread through the same ordering as
captured packets. Every packet ordered before the barrier belongs to the
time before the checkpoint, every later packet to the frames after it, so a
checkpoint and its following frames never overlap.

## Frame format

Frames are written in format revision 5:

| Field | Encoding |
|---|---|
| record length | VarInt |
| time | unsigned VarLong, nanoseconds since the previous frame of the segment or checkpoint |
| server tick | zigzag VarLong, ticks since the previous frame |
| sequence within the tick | VarInt |
| packet ID | VarInt |
| payload | the packet body |

Phase and world are properties of the track; every frame is a PLAY frame.
The first frame of a segment or checkpoint is relative to its start. A
frame header takes 6 to 10 bytes, and segments are compressed on top of
that.

The sequence is unique within a server tick across all tracks, so frames of
several tracks merge into one exact order by time, tick and sequence.

## Files

```text
manifest.json
tracks/<track-id>/index.bin
tracks/<track-id>/segments/<n>.segment
tracks/<track-id>/checkpoints/<n>.checkpoint
```

Segments and checkpoints are numbered with eight digits, for example
`tracks/11/segments/00000003.segment`.

The manifest names the adapter and registry the recording depends on, lists
the tracks, and lists every file with its type, size and SHA-256 digest:

```json
{
  "replayId": "0219de34-7e9f-47df-84e7-b85035cb7f43",
  "adapterId": "paper-<version>",
  "protocolVersion": 776,
  "registryFingerprint": "d0fc878c...",
  "formatRevision": 5,
  "durationNanos": 235472855100,
  "tracks": [
    { "id": 0, "kind": "GLOBAL" },
    { "id": 1, "kind": "WORLD", "world": "minecraft:overworld", "label": "minecraft:overworld" },
    { "id": 8, "kind": "STATE", "subjectKind": "ENTITY",
      "subjectId": "f5b00f08-9d7b-4a1e-bece-bf486047152f", "label": "minecraft:donkey" }
  ],
  "files": [
    { "type": "CHECKPOINT", "path": "tracks/0/checkpoints/00000000.checkpoint",
      "sizeBytes": 3618, "sha256": "e748627d..." }
  ]
}
```

Track IDs are small integers assigned in order of creation. Recordings of
earlier format revisions are not read.

## Playback

![Five tracks with checkpoints every 30 seconds. A viewer in the nether seeks to 1:45: the timeline reads checkpoint 3 and the following frames of the global and the nether track. The overworld track is read only for the player list, player A's state track only for a state query, and the donkey's state track is not read.](images/recording-tracks-playback.svg)

- **Opening.** Playback reads the index of every track. Segments are read
  only when their frames are requested, through the shared segment cache.
- **Timeline.** `MergedTrackData` merges the global track and the viewed
  world's track into one stream. A seek starts at the global track's floor
  checkpoint and the checkpoint of the same ordinal in the world track.
- **World switch.** `PlaybackSession.viewWorld(...)` replaces the world
  track of the timeline and rebuilds the scene at the current position,
  like a seek.
- **Other worlds.** `PlaybackSession.recordedPlayers()` reads, for every
  world the viewer does not watch, that world's checkpoint at the floor
  barrier and its frames up to the current position. The adapter scans them
  for players and their positions (`SceneQueries.players(frames)`).
- **State.** `PlaybackSession.state(subject)` opens the subject's track on
  first use, reads its checkpoint at the floor barrier and its frames up to
  the current position, and lets the adapter decode them
  (`StateDecoder.decodeState`).

- **Containers.** `PlaybackSession.container(world, position)` opens the
  world's block state track on first use and reads it like a state track.
  The adapter finds the latest frame of the position by the position every
  block state packet starts with, without decoding the others, and loads
  its data into a fresh block entity of its type
  (`StateDecoder.decodeBlock`).

State tracks and block state tracks keep the checkpoint of their last
barrier and the last decoded result; a query whose barrier and frames are
unchanged costs no reading or decoding.

Queries that may read a track outside the timeline return a
`CompletionStage`.

## API

### State of a player or an entity

```java
RecordedSubject subject = RecordedSubject.player(playerId);

session.state(subject).thenAccept(state -> state.ifPresent(found -> {
    ItemStack helmet = found.equipment().get(EquipmentSlot.HEAD);
    found.section(SubjectState.HOTBAR).ifPresent(hotbar ->
            hotbar.selected().ifPresent(slot -> { /* held slot 0-8 */ }));
    found.vitals().ifPresent(vitals -> { /* health, food, saturation */ });
    found.experience().ifPresent(xp -> { /* level, progress, total */ });
}));
```

`SubjectState` holds the equipment by `EquipmentSlot` and a list of item
sections named by keys. Which sections exist depends on the subject:

| Section | Subjects | Contents |
|---|---|---|
| `SubjectState.HOTBAR` | players | nine slots; `selected()` is the held slot |
| `SubjectState.INVENTORY` | players, villagers, piglins, allays | the main inventory or the items a mob carries |
| `SubjectState.CONTAINER` | minecarts, chest boats, chested animals | the container's content |

Vitals and experience exist for players. The result is empty when the
subject is not part of the recording at the current position.

### Entities of the scene

```java
for (RecordedEntity entity : session.entities()) {
    entity.location();          // world, x, y, z, yaw, pitch
    entity.health();            // when the scene shows it
    entity.flags();             // ON_FIRE, SNEAKING, GLIDING, ...
    entity.subject().ifPresent(subject -> session.state(subject));
}
```

`entities()` lists every entity of the viewed world at the current
position; `entity(int)` resolves an entity ID the viewer's client knows.
Both are answered from the scene packets already played and need no I/O.
`subject()` is present when the entity has a state track.

### Interactions

When a viewer right-clicks or attacks a replay entity, the playback bridge
reads the viewer's serverbound `interact` or `attack` packet before the
server's packet handler, consumes it, and publishes an event:

```java
framework.events().subscribe(event -> {
    if (event instanceof ReplayEventPublisher.PlaybackEntityInteracted clicked && !clicked.attack()) {
        // clicked.entity() is what the scene shows; schedule Bukkit work on the server thread
    }
});
```

Packets aimed at live entities pass unchanged. The example plugin opens an
inventory view of the clicked subject this way.

### Containers

```java
session.container(Key.key("minecraft:overworld"), new BlockPosition(12, 64, -30))
        .thenAccept(state -> state
                .flatMap(found -> found.section(SubjectState.CONTAINER))
                .ifPresent(chest -> { /* chest.items(): 27 slots, or 54 for a double chest */ }));
```

The result is a `SubjectState` with one `SubjectState.CONTAINER` section in
the container's own slot order: 3 slots for a furnace, 5 for a hopper or
brewing stand, 27 for a chest. It is empty when the recording has no
container at the position at the current playback position.

A viewer's right-click on a recorded container of the scene produces a
`PlaybackBlockInteracted` event with world, position and block entity type.
The playback bridge learns where containers are from the chunk and block update
packets it plays, and consumes the viewer's `use_item_on` packet for such a
block before the server's packet handler; clicks on other blocks pass.

```java
framework.events().subscribe(event -> {
    if (event instanceof ReplayEventPublisher.PlaybackBlockInteracted clicked) {
        // clicked.world(), clicked.position(), clicked.block(); query session.container(...)
    }
});
```
