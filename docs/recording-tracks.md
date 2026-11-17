# Recording tracks

Status: part A is implemented. A recording is written as a global track and
one track per world in format v5; playback opens all tracks and merges them
into one timeline. State tracks (B), on-demand tracks (C) and the state and
entity API (D) follow.

A recording is a set of **tracks**, like a media container holds video,
audio and subtitle tracks. Each track is a time-ordered stream of packet
frames with its own segments, index and keyframes. Information that
belongs to a whole stream is stored once on its track, not on every frame.

## Why

Format v4 keeps one stream. Every frame carries its world as text and its
phase, and a planned subject field would have followed. That costs every
frame, mixes scene and private state, makes playback read and discard the
private state of everybody, and grows every checkpoint with it. Tracks
remove all of that and are the place for future kinds of data.

## Track kinds

| Kind | Key | Contents | Playback |
|---|---|---|---|
| `GLOBAL` | none | world-wide scene packets: chat, tab list | always open |
| `WORLD` | world key | scene packets of one world: chunks, blocks, entities, effects, time and weather | the viewed world always; others on demand |
| `STATE` | subject | the state of one subject that the scene does not show | on demand |

There is exactly one `GLOBAL` track, one `WORLD` track per recorded world
and one `STATE` track per recorded subject. A track is created when its
first frame arrives.

### Subjects

A subject is anything with state of its own. It is identified by kind and
UUID; the label is for display only.

| Subject kind | Identity | Examples of state |
|---|---|---|
| `PLAYER` | profile UUID | inventory with armor and off hand, selected slot, health, food, experience |
| `ENTITY` | entity UUID | inventory of a llama, donkey or mule, a chest or hopper minecart or chest boat, a villager's or allay's or piglin's inventory |
| `BLOCK` | reserved | container contents of block entities, later |

The scene already shows what every client sees of an entity: equipment,
health, effects, flags and type-specific data. A `STATE` track holds only
what no client sees without opening it or being it.

### State sources

A state track is filled by one of two sources, chosen per subject kind by
the adapter. Both produce the native packets the server sends a client that
views the subject, so a state track is packet-based like every other track.

- **Observed.** The subject receives its state itself. A player's own
  connection receives its inventory, selected slot, health and experience;
  the packet definitions that carry them declare the player as subject.
- **Sampled.** Nobody receives the state unless they open the subject. The
  adapter reads it on the server thread and emits the packets when it
  changed, like Argus samples time and weather. Entity inventories are
  sampled.

### Keyframes

Every track writes a keyframe at the shared checkpoint barrier: the packets
a client needs to start the track at that moment. For a `WORLD` track that
is the scene checkpoint of that world, for `GLOBAL` the tab list, for a
`STATE` track the complete state of its subject, built like the packets the
server sends on join or on opening. All keyframes of one barrier share its
time, so tracks start consistently at any seek point.

## Frame format v5

| Field | v4 | v5 |
|---|---|---|
| record length | VarInt | VarInt |
| time | absolute nanoseconds | difference to the previous frame of the segment |
| server tick | absolute | difference to the previous frame of the segment |
| sequence within tick | VarInt | VarInt |
| phase | per frame | per track |
| packet ID | VarInt | VarInt |
| world | text per frame | per track |
| payload | length and bytes | length and bytes |

The first frame of a segment or keyframe is relative to the start of the
segment. The header of a frame shrinks from about 35 to 40 bytes to 6 to 10.
Segments are still compressed; the saving shows most in the decompressed
playback buffer and in parsing.

The sequence is unique within a server tick across all tracks, so frames of
several tracks merge into one exact order by time, tick and sequence.

## Files

```
manifest.json
tracks/<track-id>/index.bin
tracks/<track-id>/segments/<n>.segment
tracks/<track-id>/checkpoints/<n>.checkpoint
```

The keyframes of a track are its checkpoints. Every track has one
checkpoint per barrier of the recording, numbered like the barriers; a
track created later writes empty checkpoints for the barriers before it.
Every index therefore starts at `(0, 0, 0, 0)`, and playback answers a
barrier with the checkpoint of the same number from every track. The global
track always exists as track 0; playback seeks on its index.

The manifest lists the tracks: ID, kind, key (world key or subject kind and
UUID) and label, followed by every file with type, path, size and digest.
Track IDs are small integers assigned in order of creation.

## Recording

The core routes every captured packet to a track:

1. the packet definition names a subject: that subject's `STATE` track;
2. the packet is world-wide: `GLOBAL`;
3. otherwise: the `WORLD` track of its world.

Each track has its own segment writer and rotation; keyframes are written
for all open tracks at every checkpoint barrier.

## Playback

- `GLOBAL` and the viewed `WORLD` track are always open and merged into one
  timeline.
- Switching the viewed world opens the other world's track at the current
  position (keyframe floor, then frames up to the position) and closes the
  previous one.
- Other tracks open on demand: a subject's `STATE` track when its state is
  queried, other `WORLD` tracks when the positions of players there are
  needed. They follow the playback clock while in use and close afterwards.
- Queries that may need a closed track are asynchronous, for example the
  list of recorded players with their positions.

## API

- `PlaybackSession.state(Subject)` returns the state of a subject at the
  current position: an inventory made of named sections (armor, off hand,
  hotbar, main, container, ...) with the selected slot where it applies,
  and vitals where they apply.
- `PlaybackSession.entity(...)` returns what the scene shows of an entity:
  type, UUID, position, name, equipment, health, effects, passengers,
  flags and type-specific details. It names the entity's subject, so its
  state can be queried as well.
- Interactions of a viewer with a replay entity are published as an event
  carrying the session, the viewer and the entity.

## Implementation parts

| Part | Content |
|---|---|
| A | format v5: track model, manifest, compact frame codec, per-track index, segments and keyframes |
| B | recording: track routing, per-track writers, keyframes per track, subject declarations, sampled entity state |
| C | playback: open tracks, merged timeline, world switch by track, on-demand tracks, asynchronous player list |
| D | state API, entity API, interaction event; example menus |

Recordings of format v4 are not read.
