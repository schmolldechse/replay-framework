# Implementation plan: PLAY debug packets 26–30

## Goal and scope

Implement the five adjacent PLAY clientbound packet definitions in Paper 26.2
and 26.3:

| Decimal ID | Hex ID | Protocol identifier | Planned definition |
|---:|---:|---|---|
| 26 | `0x1A` | `minecraft:debug/block_value` | `Id026DebugBlockValuePacketDefinition` |
| 27 | `0x1B` | `minecraft:debug/chunk_value` | `Id027DebugChunkValuePacketDefinition` |
| 28 | `0x1C` | `minecraft:debug/entity_value` | `Id028DebugEntityValuePacketDefinition` |
| 29 | `0x1D` | `minecraft:debug/event` | `Id029DebugEventPacketDefinition` |
| 30 | `0x1E` | `minecraft:debug_sample` | `Id030DebugSamplePacketDefinition` |

The work is limited to the version-specific packet-definition layer, its
catalogs and coverage tests, one focused test for each packet in each version,
and the existing protocol inventories. It will not change capture handlers,
PacketEvents integration, event fallbacks, checkpoint assembly, playback
injection, the recording format, or public API.

## Findings and protocol shape

The pinned adapter inventories already list all five packets as diagnostic or
special-purpose traffic with `BLOCKED` playback and no checkpoint role. The
packet protocol descriptions give these wire fields:

| Packet | Wire fields and important variants |
|---|---|
| `debug/block_value` | A packed block `Position`, then a `DebugSubscription.Update`: subscription type plus an optional, type-dependent debug value. Absence of the optional value represents a clear/removal update. |
| `debug/chunk_value` | Chunk Z as an `Int`, then chunk X as an `Int`, then a `DebugSubscription.Update`. The Z/X wire order is significant; the client treats the coordinates as one packed long. |
| `debug/entity_value` | Entity ID as a `VarInt`, then a `DebugSubscription.Update`. The entity ID belongs to the source client's entity namespace. |
| `debug/event` | A `DebugSubscription.Event`: subscription type plus its type-dependent event data. Unlike an update, the event data is not optional. |
| `debug_sample` | A VarInt-prefixed array of `Long` values, then a sample-type VarInt enum. In the pinned protocol, tick-time samples contain four nanosecond metrics: full tick, server tick, scheduled tasks, and idle time. These periodic samples follow a client debug-sample subscription; the vanilla server sends them only to operators. |

The type-dependent subscription payload is not a single common record. The
protocol documents these variants for this version range:

| Subscription ID | Data shape |
|---:|---|
| 0 | Dedicated-server tick-time marker; no data fields. |
| 1 | Bee: optional hive and flower positions, travel ticks, and blacklisted hive positions. |
| 2 | Villager brain: name, profession, XP, health/max health, inventory, golem intent, anger, activity/behavior/memory/gossip strings, POIs, and potential POIs. |
| 3 | Breeze: optional attack-target entity ID and optional jump-target position. |
| 4 | Goal selector: priority, running flag, and goal name. |
| 5 | Entity path: reached flag, next block index, block position, and path nodes. |
| 6 | Entity/block intersection enum: in block, in fluid, or in air. |
| 7 | Bee hive: block-registry type, occupant count, honey level, and sedated flag. |
| 8 | Point of interest: position, POI-registry type, and free-ticket count. |
| 9 | Redstone-wire orientation ID. |
| 10 | Village-section marker; no data fields. |
| 11 | Raid: array of positions. |
| 12 | Structure: array of structure bounds and piece bounds, including the start marker. |
| 13 | Game-event listener radius. |
| 14 | Neighbor-update position. |
| 15 | Game event: game-event registry ID and X/Y/Z doubles. |

The protocol also defines debug path-node enums and coordinate/weight fields
used by variant 5. Dynamic registry-bound values in variants 7, 8 and 15 must
be interpreted with the connection's registry. Subscription updates can clear
a previously populated value, while debug events carry mandatory data. Debug
payloads can contain world positions, source entity IDs, registry-bound
block/POI/game-event values, and detailed server diagnostics. The implementation
must preserve the entire payload during capture without making assumptions
about its variants.

## Technical decisions

1. **Keep the five definitions in the `diagnostic` protocol domain.** Add a
   `DiagnosticPacketDefinitions` aggregator to each version and include it in
   that version's `Paper26_*PacketCatalog`. This follows the documented
   `packet/definition/diagnostic` layout and keeps diagnostic classification
   independent of current capture or replay policy.
2. **Classify every packet as captured control data.** Each definition will
   extend the existing `AbstractBlockedPacketDefinition`, which retains the
   final outbound wire frame for recording/analysis, marks it captured by
   default, and rejects it before playback decoding or rewriting. Keep the
   existing `BLOCKED` / non-checkpoint policy: subscription state belongs to
   the receiving debug UI, entity references belong to the recorded client's
   entity IDs, and debug samples expose server telemetry rather than replay
   scene state. Replaying these into a neutral observer has no safe general
   contract, even if individual fields could be decoded.
3. **Retain the packet-specific protocol facts in the definitions.** Each
   class will document its exact field role and why the frame is unsafe to
   replay. In particular, document the reversed chunk coordinate order, the
   entity-identity dependency, the optional clear semantics, the non-optional
   event payload, and the sample subscription/operator context.
4. **Keep 26.2 and 26.3 naming and behavior synchronized.** Both versions use
   the same IDs and identifiers for this range. Version-specific packages,
   packet types, catalogs and tests remain separate, as required by the
   existing layout.
5. **Make the adapter inventory descriptions packet-specific but neutral.**
   Update the existing rows in both adapter docs to describe each packet's
   payload and relevance while retaining `BLOCKED` playback and `no`
   checkpoint. The documentation will not mention whether an implementation
   or test exists.

## Planned changes

For both `paper-26_2` and `paper-26_3`:

- Add `packet/definition/diagnostic/DiagnosticPacketDefinitions.java`.
- Add one package-private `final` definition per packet using the established
  `Id<DDD><ProtocolName>PacketDefinition` filename/class scheme.
- Add the diagnostic aggregator to the version packet catalog.
- Add five standalone `Id026...PacketDefinitionTest` through
  `Id030...PacketDefinitionTest` classes under the matching test package.
- Extend `PacketCoverageTest` to assert registry identity, captured control
  disposition, non-replayability and non-checkpoint status for all five IDs.
- Clarify rows 26–30 in `docs/adapters/paper-26.2.md` and
  `docs/adapters/paper-26.3.md`, keeping the inventories complete and neutral.

## Test design

Each packet/version gets an independent test. Each test will verify the
definition's decimal and hexadecimal ID, identifier, exact catalog registration,
and classification; prove that valid-looking, truncated and arbitrary raw
payloads are rejected before the playback codec is asked to decode; and verify
that already-decoded values cannot bypass the block. Descriptor mismatch and
null validation will be checked where applicable.

Native protocol codec coverage will exercise each packet's distinct wire
shape, including negative and boundary entity/chunk coordinates, clear updates
for every codec-backed subscription type, a present optional debug value, and
the required type-specific event payload. Debug-sample type 0 is checked with
its four `long` metrics and signed/boundary values. Registry-backed cases use
the pinned bootstrap registries. These round trips validate that these values
can be represented by the pinned Paper codec while policy still prevents their
unsafe replay. Packet coverage tests confirm that an unknown PLAY packet
remains unsupported.

## Validation and version control

Before implementation, verify the current local `master` against the live
`origin/master` without checking it out or overwriting workspace changes. Work
only on `codex/refactor-packet-capture-rewrite`; preserve the pre-existing
untracked `.idea/` content. After implementation, run focused tests for the ten
packet/version definitions, both adapter packet-coverage tests, the shared
paper adapter tests, and then the full `paper-common`, `paper-26_2`, and
`paper-26_3` test tasks if available. Review `git diff --check`, the final diff,
and status. Keep implementation changes in a scoped English semantic commit;
do not push unless requested.

The local `master` matched the cached `origin/master` ref. A live fetch could
not complete because GitHub DNS resolution failed in the environment. The
focused tests and a forced full test run completed successfully for all three
adapter modules. `git diff --check` reported no whitespace errors.

## Protocol references

- [Minecraft Wiki — Java Edition protocol/Packets](https://minecraft.wiki/w/Java_Edition_protocol/Packets#Debug_Block_Value): packet IDs and field descriptions for the five PLAY packets, plus `Debug Subscription Event`, `Debug Subscription Update`, and sample-type notes.
- [Minecraft Wiki — Java Edition protocol/Data types](https://minecraft.wiki/w/Java_Edition_protocol/Data_types#Debug_Subscription_Data): type-specific debug-subscription payload variants.
- [Minecraft 26.3 packet source reference — `ClientboundDebugEntityValuePacket`](https://mc-packet-reference.netlify.app/26.x/packets/clientbounddebugentityvaluepacket/): confirms the 26.3 native packet record and `DebugSubscription.Update<?>` codec field.
- The project's pinned Paper dev bundles: `26.2.build.135-stable` and `26.3.build.244-stable`; implementation and tests will use their version-local NMS packet and stream codec APIs as the final compatibility authority.
