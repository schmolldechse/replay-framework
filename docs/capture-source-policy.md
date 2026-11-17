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

PacketEvents callbacks and the serverbound receiver execute on a Netty
event-loop thread. They must not read mutable Bukkit, Paper, CraftBukkit, or
NMS world state. Serverbound packets are seen as decoded objects by a channel
handler directly before the server's packet handler; it never changes, drops
or delays a packet. A serverbound definition may only read fields of its
packet.

Clientbound packet bytes are copied by a PacketEvents post-task before leaving
the callback. The post-task runs after all listeners and any wrapper
re-encoding, so capture parses the final VarInt packet ID and copies the final
uncompressed body without changing the live buffer's reader index.

Eyes, sampled time and weather, and actor effects run on the server thread.
Cross-thread publication uses the adapter's capture-order allocator so
timestamps, server ticks, sequence numbers, checkpoint barriers, and
recording-scope filtering retain one ordering contract.

## Duplicate suppression

Copies of one packet that several players received are recognized by their
packet ID, world and payload within a server tick, when the packet's
definition declares that recipient copies are possible. Eye packets need no
such comparison: the server hands the same object to every eye, and Argus
merges by object identity and reference counts.

A definition may also suppress an observation that must not become part of
the scene at all, such as a chat message addressed to one player.

Known gap: in 26.2 only entity packets and system chat declare recipient
copies. Sounds, particles, level and block events, block-break progress,
explosions, player chat and tab-list updates are recorded once per player
that received them; with several players in range a replay repeats them.

## Adapter responsibilities

Packet-driven capture is divided into small version-bound responsibilities:

- the capture bridge owns PacketEvents registration, packet byte copying,
  capture ordering, and failure propagation;
- the clientbound definitions own context, distribution and playback policy
  of their packet;
- the serverbound receiver hands every received packet to its serverbound
  definition;
- the actor effects own the before and after reads and the effect families;
- the Argus platform owns the eye, sampled world state and player queries; and
- the playback rewriter owns session-local entity and UUID references.

Replay injection is not an event fallback. The version bridge first encodes a
rebuilt native packet with the live Paper codec, then writes the packet ID and
body from the current PacketEvents encoder context. PacketEvents therefore
cannot observe or recapture replay traffic. The live outbound gate remains a
channel handler whose sole responsibility is suppressing conflicting live
scene packets for that viewer.

The version-neutral core does not depend on Paper, CraftBukkit, NMS, or
PacketEvents types. A new adapter may use different native packet classes, but
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
