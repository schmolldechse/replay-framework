# Capture source policy

## Purpose

Replay capture must describe server state independently of an arbitrary packet
recipient. The preferred source is therefore the Minecraft protocol itself:
clientbound packets the server sends, captured as the server built them.
Paper or Bukkit events are not a capture source.

This policy applies to every Paper adapter. It is intentionally independent of
a specific Minecraft packet registry. Each adapter documents the concrete
packet names and encodings that implement the policy for its version.

## Source precedence

A recording takes every packet from exactly one of three sources, recorded as
the packet's `CaptureOrigin`:

1. **Argus eyes (`OBSERVER`).** The scene, meaning chunks, blocks, block
   entities and every tracked entity including players, comes from the
   virtual observers of the recording (see
   [virtual observers](virtual-observer.md)). An eye receives exactly what a
   neutral player at its position would receive, independent of the players
   that happen to be connected.
2. **Player connections (`PLAYER_ONLY`).** Packets the server sends only to
   listed players, such as sounds, particles, level events, explosions, chat
   and the tab list, are captured from the connections of real players. The
   server sends one packet object to all recipients, so copies of different
   recipients are merged.
3. **Effects that bypass their actor.** The server sends some effects of a
   player's action to everyone except that player. With nobody else in range
   no connection receives them. A serverbound packet then serves as the
   trigger, and the adapter rebuilds the clientbound packet the server would
   have sent (see below).

Serverbound packets are never recorded. Every serverbound packet has its own
definition that decides whether it triggers anything; most trigger nothing
because whatever they change reaches the recording through an eye.

Packets a player's own view receives, such as its chunk cache center or its
own position correction, describe that view, not the scene. They carry the
origin `PLAYER_VIEW`, and no recording accepts them.

## Effects that bypass their actor

The server sends many effects of a player's action to everyone except that
player, because the player's client plays them itself: the break particles
and sound of a block, the place sound of a block, the sound of a door,
trapdoor, fence gate, lever or button. With no other player in range no
connection receives them, so no clientbound packet can be captured.

The serverbound packet that reports the action is the trigger. Its
definition names the action and the positions involved. The adapter then
queues one task directly before and one directly after that packet in the
server's queue of received packets, so the blocks are read on the server
thread exactly before and after the server handles the action. An effect
family per kind of effect compares both reads and rebuilds the clientbound
packet the server sent to other players.

The derived packet is published only when no other player would have
received the original, using the server's own recipient test. Otherwise
that player's connection delivers the real packet. Derived packets pass
through their clientbound definition like any captured packet.

Derived today: breaking (level event 2001), placing, doors, trapdoors,
fence gates, levers and buttons. Every serverbound definition documents the
effects of its packet that are not derived yet.

## Thread boundary

The clientbound tap and the serverbound receiver execute on a Netty
event-loop thread. They must not read mutable Bukkit, Paper, CraftBukkit, or
NMS world state. Serverbound packets are seen as decoded objects by a channel
handler directly before the server's packet handler; it never changes, drops
or delays a packet. A serverbound definition may only read fields of its
packet.

Clientbound packets are seen as objects by a channel handler at the tail of
each player connection's pipeline, before the server encodes them. The tap
never changes, drops or delays a packet. A definition reads the object
directly, and the packet is encoded once, for the frame.

Eyes, sampled time and weather, and actor effects run on the server thread.
Cross-thread publication uses the adapter's capture-order allocator so
timestamps, server ticks, sequence numbers, checkpoint barriers, and
recording-scope filtering retain one ordering contract.

## Recipient copies

The server sends a player-only event, such as a sound or an explosion, to
every player in range. A recording observes it once per recipient and must
keep one event. Eye packets need no such step: the server hands the same
object to every eye, and Argus merges by object identity and reference
counts.

Every definition of a packet recorded from player connections declares how
its copies are recognized (`RecipientCopies`). There is no default; the
catalog rejects a definition without a declaration, because only the
packet's server code tells which kind applies:

| Kind | Meaning | 26.2 examples |
|---|---|---|
| `identicalPayload` | equal bytes for every recipient, often one packet object | sounds, particles, block events, local level events, boss bar, tab list, border |
| `keyedBy` | built per recipient with recipient-specific fields; the identity holds only the fields that describe the event | explosion without knockback, global level event without its projected position, player chat without its recipient index |
| `deliveredByScene` | the scene supplies the packet itself; copies from players are dropped | time and weather, sampled by Argus |
| `unique` | every observation is an event of its own | |

The adapter attaches the identity to the packet as a `RecipientCopy`. Each
recording merges copies itself, after deciding whether the packet belongs
to it, so a copy outside a region never hides the copy inside it. Of all
copies of an event a recording keeps as many as a single recipient
received. Because each connection writes its copy on its own thread, a copy
can be captured one tick later; an event therefore stays open for the tick
it was first seen in and the next one.

Known limit: two equal events in adjacent ticks that reached disjoint
recipients are merged into one. Sounds carry a random seed and most events a
position, so this practically affects only repeated identical particles.

A definition may also suppress an observation that must not become part of
the scene at all, such as a chat message addressed to one player.

A message the server sends as player chat to some recipients and as
disguised chat to recipients that hide the sender is recorded once per form.

## Recipient state

Some packets a player receives describe that player rather than the scene,
such as its health and experience. They are not part of the scene and are
never replayed into the viewer's own HUD, but they are a source of that
player's state.

Every definition declares whether its packet describes its recipient
(`RecipientState`):

| Declaration | Meaning | 26.2 examples |
|---|---|---|
| `none` | the packet does not describe its recipient | default |
| `always` | every copy describes the recipient | `set_health`, `set_experience` |
| `when(...)` | only copies that match a condition do | none in 26.2 |

A packet that describes its recipient names the recipient as its subject.
The core then writes it to that player's state track instead of the scene;
see [recording tracks](recording-tracks.md#subjects). Copies of other
players are irrelevant here, because each player receives only its own
state.

A player's inventory and selected slot are not declared. The server does
not send a player every change of them: in creative mode the client sets
its slots itself, and while a container is open the inventory is updated
through that container's window. Argus therefore reads both every tick.
The state of entities nobody receives without opening them, such as the
chest of a llama, has no packet to capture either and is sampled too; see
[subject state](virtual-observer.md#subject-state).

## Adapter responsibilities

Packet-driven capture is divided into small version-bound responsibilities:

- the capture bridge owns the connection handlers, capture ordering, and
  failure propagation;
- the clientbound definitions own context, distribution and playback policy
  of their packet;
- the serverbound receiver hands every received packet to its serverbound
  definition;
- the actor effects own the before and after reads and the effect families;
- the Argus platform owns the eye, sampled world state and player queries; and
- the playback rewriter owns session-local entity and UUID references.

Replay injection is not an event fallback. The version bridge first encodes a
rebuilt native packet with the live Paper codec, then writes the packet ID and
body from an anchor handler behind the connection's compression. Replay
traffic therefore never reaches the clientbound tap and is not recaptured. The
live outbound gate remains a
channel handler whose sole responsibility is suppressing conflicting live
scene packets for that viewer.

The version-neutral core does not depend on Paper, CraftBukkit, NMS, or
Netty types. A new adapter may use different native packet classes, but
it must preserve these responsibility and thread boundaries.

## Validation requirements

Each adapter must test its own packet layouts and source decisions: every
clientbound and serverbound definition against the live registry, the
distribution and scene membership of observable packets, the reactions of
triggering serverbound packets, every effect family, and entity-reference
rewriting.

Generic tests continue to cover ordering, checkpoint boundaries, scope
routing, merging of eyes, buffering, seek reconstruction, and neutral-observer
contracts. An adapter test must not be replaced by a generic test when the
asserted packet layout differs between Minecraft versions.
