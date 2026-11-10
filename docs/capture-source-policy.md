# Capture source policy

## Purpose

Replay capture must describe server state independently of an arbitrary packet
recipient. The preferred source is therefore the Minecraft protocol itself.
PacketEvents and version-bound channel handling provide the protocol boundary;
Paper or Bukkit events are fallback sources only when no complete packet-driven
source exists.

This policy applies to current Paper adapters and to future adapter work. It is
intentionally independent of a specific Minecraft packet registry. Each adapter
documents the concrete packet names and encodings that implement the policy for
its version.

## Source precedence

Capture sources are selected in the following order:

1. **Clientbound packet capture.** A verified clientbound packet is copied when
   it represents scene state completely and does not depend on which player
   received it. This remains the default for chunks, blocks, entity lifecycle,
   metadata, effects, sounds, particles, and other protocol-visible state.
2. **Serverbound packet trigger with authoritative sampling.** Some server state
   is not sent back to the acting client and may not have another tracking
   recipient. In that case an accepted serverbound packet identifies that the
   player can have changed. The adapter coalesces the signal and samples the
   authoritative player state on the server thread, then emits the corresponding
   version-specific clientbound representation into the neutral recording
   stream.
3. **Channel handler boundary.** A channel handler is used where the exact
   transport boundary is the required contract, such as preventing replay
   packets and live connection-local packets from affecting one another. A
   channel handler must preserve event-loop ordering and must not turn a
   connection-local value into global scene state.
4. **Server event fallback.** A Paper or Bukkit event may be used only when the
   state has no reliable packet source or owner connection from which it can be
   sampled. The fallback must be documented with the missing packet capability,
   and its output must still be encoded as an adapter-native clientbound packet.

An event must not duplicate a state family that can be captured reliably from
packets. In particular, player movement, player equipment, and player arm swings
must not be based on `PlayerMoveEvent`, inventory/equipment events, or arm-swing
events while the accepted serverbound packet stream is available.

## Thread boundary

PacketEvents callbacks can execute on a Netty event-loop thread. They must not
read mutable Bukkit, Paper, CraftBukkit, or NMS world state. A receive callback
may copy only immutable protocol semantics needed to schedule work, such as:

- the PacketEvents user UUID and protocol entity ID;
- the packet type;
- a decoded hand or other self-contained packet field; and
- the current capture tick used for bounded duplicate suppression.

Authoritative state sampling runs on the server thread. Multiple accepted
packets for the same player are coalesced into one pending sample. The sampler
reads the current entity position, orientation, ground state, and equipment,
compares them with its previous snapshot, and emits only changed state plus any
queued ephemeral actions. No Bukkit entity or live connection reference is
stored in a replay frame.

Clientbound packet bytes are copied by a PacketEvents post-task before leaving
the callback. The post-task runs after all listeners and any wrapper
re-encoding, so capture parses the final VarInt packet ID and copies the final
uncompressed body without changing the live buffer's reader index.
Channel-handler work remains on the channel event loop. Cross-thread publication
uses the adapter's capture-order allocator so timestamps, server ticks, sequence
numbers, checkpoint barriers, and recording-scope filtering retain one ordering
contract.

## Recipient independence and duplicate suppression

Serverbound-triggered samples and event fallbacks produce recipient-independent
clientbound packets with the actor's world and position in their capture context.
The recording router therefore evaluates a bounded scope against the recorded
entity rather than against a packet recipient.

Once an adapter owns a state family for an entity, recipient-derived copies of
that family are suppressed before publication:

- movement authority suppresses relative movement, rotation, teleport, head
  rotation, and absolute position-sync copies;
- equipment authority suppresses equipment copies and the hand-swap entity
  event that would otherwise apply the swap twice; and
- a serverbound arm animation suppresses only the matching clientbound swing
  form in its server tick. Other animation actions and later plugin-originated
  swings remain capturable.

Suppression logic is version-bound and parses only packet layouts verified by
that adapter. It must not infer an entity ID from an arbitrary integer field.
Entity removal clears all retained authority for that protocol entity ID.

## Event fallback criteria

An event fallback is acceptable only when all of the following are true:

- no accepted serverbound packet belongs to the entity that changed;
- the normal clientbound stream is recipient-dependent or otherwise incomplete;
- authoritative state cannot be sampled from another packet-owned source; and
- the event can be translated into a verified native packet without changing
  gameplay.

The current Paper adapters use `EntityMoveEvent` and
`EntityEquipmentChangedEvent` only for non-player entities. Such entities have
no owner connection and therefore no serverbound player stream. Entity-removal
notification is used for lifecycle cleanup of capture indexes; it is not a
source of replay presentation. Player state does not use those event fallbacks.

If a future protocol exposes a complete packet-driven source for a fallback
family, the fallback should be removed. Conversely, a new event dependency must
be justified in the relevant adapter documentation before implementation.

## Adapter responsibilities

Packet-driven capture is divided into small version-bound responsibilities:

- the capture bridge owns PacketEvents registration, packet byte copying,
  capture ordering, and failure propagation;
- a main-thread player-scene index publishes immutable world keys for Netty
  capture callbacks; callbacks never query Bukkit player/world state;
- the player-state sampler owns receive-signal coalescing and main-thread state
  comparison;
- the entity-state coordinator owns non-player fallbacks and lifecycle cleanup;
- the native packet factory owns version-specific packet construction;
- the authoritative-state index owns duplicate suppression; and
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

Each adapter must test its own packet layouts and source decisions. At minimum,
tests cover movement and equipment authority, hand-swap suppression, the
version-specific swing representation, tick-scoped ephemeral suppression,
changed and cleared equipment slots, and entity-reference metadata rewriting.

Generic core tests continue to cover ordering, checkpoint boundaries, scope
routing, buffering, seek reconstruction, and neutral-observer contracts. An
adapter test must not be replaced by a generic test when the asserted packet
layout differs between Minecraft versions.
