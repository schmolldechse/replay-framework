# Implementation plan: PLAY clientbound `minecraft:cooldown` (`0x16`)

## Objective and scope

Implement the PLAY clientbound packet with decimal ID `22` (`0x16`) and identifier
`minecraft:cooldown` for the pinned Paper `26.2.build.135-stable` and
`26.3.build.244-stable` adapters.

The change is deliberately limited to the version-local packet definitions, their
catalog registration, one dedicated test class per adapter version, the existing
coverage and outbound-gate integration tests, and the two neutral adapter packet
inventories. It does not change the capture handler, PacketEvents fallback,
recording format, shared packet APIs, playback bridge, checkpoint encoder, or any
dependency.

No legacy recording migration or compatibility layer is required.

## Evidence and protocol analysis

### Pinned implementations

The mapped sources in both pinned Paper development bundles define the same record:

```java
ClientboundCooldownPacket(Identifier cooldownGroup, int duration)
```

Its stream codec serializes exactly these fields, in this order:

1. `cooldownGroup` via `Identifier.STREAM_CODEC`;
2. `duration` via `ByteBufCodecs.VAR_INT`.

The packet has no entity ID, item stack, registry entry, boolean mode, optional
field, or trailing payload owned by this packet. Both pinned PLAY clientbound
registries assign it ID `22` (`0x16`).

The mapped `ItemCooldowns`, `ServerItemCooldowns`, and `UseCooldown` sources establish
the behavioral meaning:

- cooldowns are keyed by an `Identifier`, not directly by an item type;
- an item's `minecraft:use_cooldown` component may supply a shared
  `cooldown_group`;
- when that component or group is absent, the registered item identifier is used;
- a positive duration starts or replaces the entry for that group and is measured
  in ticks;
- duration `0` is emitted when a group ends or is explicitly removed;
- different groups are independent, while every item resolving to the same group
  observes the same cooldown;
- the wire codec itself represents the complete signed Java `int` range. Negative
  values are therefore syntactically decodable even though Paper's public cooldown
  contract requires a positive duration and reserves `0` for removal.

Paper's public API corroborates that the group comes from the item's cooldown
component, that the duration is expressed in ticks, and that `0` removes an existing
cooldown:

- [PlayerItemGroupCooldownEvent](https://jd.papermc.io/paper/1.21.11/io/papermc/paper/event/player/PlayerItemGroupCooldownEvent.html)

### Playback implications

The packet mutates item-use and overlay state owned by the receiving client. It does
not describe the recorded world or an independently rendered replay entity. Sending
it to a replay observer could disable the observer's own items or show a cooldown for
an unrelated item/group. Reconstructing it in a checkpoint would create the same
cross-connection contamination and would additionally require elapsed-tick
adjustment.

The correct policy is consequently:

- disposition: `STATEFUL`;
- capture by default: `true`, preserving the original stream and future analysis;
- replayable descriptor: `true`, as required by the established viewer-local
  dispatch path;
- playback scope: `VIEWER_LOCAL`, causing `DROP_LOCAL` before decode;
- checkpoint relevant: `false`.

This matches the existing semantics of other receiver-owned UI packets. The
observer's genuine live cooldown packets must continue to pass through the outbound
isolation gate.

## Planned production changes

### Paper 26.2

Add:

`replay-adapter/paper-26_2/src/main/java/dev/voldechse/replayframework/adapter/paper/v26_2/packet/definition/ui/Id022CooldownPacketDefinition.java`

The package-private final class will:

- extend `PacketDefinition<Paper26_2ReplayPacketRewriter>`;
- declare `PACKET_ID = 0x16` and `IDENTIFIER = "minecraft:cooldown"`;
- use `PacketClassification.stateful(PacketDescriptor.PlaybackScope.VIEWER_LOCAL)`;
- document the cooldown-group fallback/shared-group behavior, start/replace/remove
  semantics, and why recorded payloads are dropped for a neutral observer.

Register it in `UiPacketDefinitions` after ID `20`; the catalog's existing numeric
sort preserves global wire order even though ID `21` belongs to the connection
domain.

### Paper 26.3

Add the symmetric class under the `v26_3` package with the corresponding
`Paper26_3ReplayPacketRewriter` type and register it in that version's
`UiPacketDefinitions`.

No custom decode, encode, or rewrite hook is needed. `PacketDefinition` already
enforces the required early viewer-local drop, including malformed, truncated,
overlong, negative-duration, and otherwise unusual recorded payloads without
invoking the native codec.

## Planned tests

Create exactly one dedicated packet test class in each adapter version:

- `paper-26_2`: `Id022CooldownPacketDefinitionTest`
- `paper-26_3`: `Id022CooldownPacketDefinitionTest`

The two tests will remain structurally symmetric and will cover:

1. **Definition and registration**
   - decimal/hex packet ID, canonical type name, and exact classification;
   - registration exactly once in the UI definition set and final catalog;
   - native PLAY protocol lookup resolves ID `22` to
     `ClientboundCooldownPacket`.

2. **Viewer-local safety boundary**
   - valid, malformed, truncated, overlong, and arbitrary payloads return
     `DROP_LOCAL` before any decode or encode call;
   - direct rewrite drops without inspecting the decoded object's type;
   - null arguments and descriptor mismatches retain the shared boundary behavior.

3. **Native wire contract**
   - default-namespace and custom-namespace groups;
   - identifiers containing every valid separator form relevant to the native
     grammar;
   - missing namespaces canonicalize to `minecraft`;
   - empty, invalid, truncated, malformed UTF-8, and over-limit identifiers are
     rejected by the native decoder/constructor as applicable;
   - identifier length-prefix boundaries and the effective native identifier-size
     boundary are exercised;
   - duration `0` (remove), representative positive values (start/replace), and the
     complete signed `int` extremes are preserved;
   - VarInt byte-width boundaries are verified, including negative values using five
     bytes;
   - missing/truncated/overlong VarInts fail;
   - repeated packets for the same group and packets for distinct groups remain
     independent packet records with no hidden sequence or transaction field;
   - trailing bytes remain available to the enclosing frame decoder rather than
     becoming part of this packet.

4. **Catalog and live-traffic integration**
   - extend each `PacketCoverageTest` expected list and policy assertions;
   - extend each `ReplayViewerOutboundGateTest` viewer-local matrix with `cooldown`
     to prove that genuine live observer cooldowns still pass while recorded ones
     are dropped by playback.

The tests will use the native `GameProtocols.CLIENTBOUND_TEMPLATE` codec from each
pinned bundle. This validates the real version-local packet registry and serialization
instead of duplicating the codec in test-only code.

## Documentation changes

Update only PLAY row `22` in both complete inventories:

- `docs/adapters/paper-26.2.md`
- `docs/adapters/paper-26.3.md`

The neutral description will explain that the packet sets, replaces, or clears a
receiver-local item cooldown group. Playback remains `DROP_LOCAL`; checkpoint policy
remains `no`. The documents will not contain implementation progress, class names,
or test details, and every other packet row will remain intact.

## Validation after approval

Before implementation, verify the local main branch against the live remote without
switching away from or modifying `codex/refactor-packet-capture-rewrite`. Then run:

1. focused `Id022CooldownPacketDefinitionTest` tests for Paper 26.2 and 26.3;
2. both `PacketCoverageTest` suites;
3. both `ReplayViewerOutboundGateTest` suites;
4. the complete `paper-common`, `paper-26_2`, and `paper-26_3` test tasks;
5. structural symmetry checks for the two definitions and dedicated tests;
6. adapter-inventory row-count checks and `git diff --check`;
7. final Git status review to ensure only task-relevant files are committed and the
   pre-existing untracked `.idea/` directory remains untouched.

Implementation will be committed separately with a concise English semantic commit.
After successful validation, this temporary plan will be removed in a separate docs
commit, following the established branch workflow.
