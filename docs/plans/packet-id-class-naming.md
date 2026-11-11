# Packet ID class naming proposal

## Goal

Add the version-local numeric PLAY clientbound packet ID to every concrete
packet-definition class without weakening the semantic protocol identifier or
the existing domain layout.

This document is a proposal only. It does not rename or otherwise modify the
current packet implementations.

## Findings

The numeric packet ID is not a stable semantic identity. It belongs to one
specific protocol version, phase and direction. The protocol identifier remains
stable while numeric IDs can move when Mojang inserts another packet.

The pinned registries already demonstrate this:

| Version | Decimal ID | Hex ID | Identifier |
|---|---:|---:|---|
| 26.2 | 37 | `0x25` | `minecraft:forget_level_chunk` |
| 26.3 | 37 | `0x25` | `minecraft:add_transient_block` |
| 26.3 | 38 | `0x26` | `minecraft:forget_level_chunk` |

Consequently, an ID may be part of a version-local class name, but must never
replace `minecraft:<identifier>` as the catalog lookup key.

## Recommended schema

Concrete definitions use:

```text
Id<DDD><ProtocolIdentifierInUpperCamelCase>PacketDefinition
```

`<DDD>` is the zero-padded, three-digit decimal packet ID from the pinned PLAY
clientbound registry.

Examples:

| Version | Identifier | Class |
|---|---|---|
| 26.2 / 26.3 | ID 0, `minecraft:bundle_delimiter` | `Id000BundleDelimiterPacketDefinition` |
| 26.2 / 26.3 | ID 1, `minecraft:add_entity` | `Id001AddEntityPacketDefinition` |
| 26.2 | ID 37, `minecraft:forget_level_chunk` | `Id037ForgetLevelChunkPacketDefinition` |
| 26.3 | ID 37, `minecraft:add_transient_block` | `Id037AddTransientBlockPacketDefinition` |
| 26.3 | ID 38, `minecraft:forget_level_chunk` | `Id038ForgetLevelChunkPacketDefinition` |
| 26.3 | ID 143, `minecraft:show_dialog` | `Id143ShowDialogPacketDefinition` |

For `bundle_delimiter`, the complete production and test names become:

```text
Id000BundleDelimiterPacketDefinition.java
Id000BundleDelimiterPacketDefinitionTest.java
```

### Why decimal and three digits

- Decimal matches the existing adapter registry tables and is immediately
  readable without conversion.
- A fixed width sorts definitions numerically in file explorers, IDE searches
  and diffs: `Id009`, `Id010`, `Id099`, `Id100`.
- Three digits cover the complete pinned 26.2 and 26.3 PLAY registries. If a
  future pinned registry reaches ID 1000, every concrete definition in that
  version must consistently move to four digits; mixed widths are forbidden.
- Hex notation remains useful in code and protocol documentation, but a class
  prefix such as `Id0x00` mixes numeric-literal syntax with an UpperCamelCase
  type name and needs another width rule. It is therefore not used in filenames.

## ID must be enforced, not decorative

The class name alone cannot protect against a stale or mistyped ID. Every
concrete definition should also bind the numeric value:

```java
static final int PACKET_ID = 0x00;
```

The hexadecimal literal matches conventional wire-protocol notation, while the
class name retains its sortable decimal representation.

`PacketDefinition` should expose this expected version-local packet ID in
addition to its stable type name. During registry discovery, the version
adapter must fail closed if either of these differs from the live Paper
registry:

```text
definition.typeName() == discovered.typeName()
definition.packetId() == discovered.packetId()
```

The catalog should continue to look up definitions by the stable type name.
The numeric ID is a version compatibility invariant, not the semantic lookup
key.

Focused definition tests should verify all representations together:

```text
Id000...  <->  packetId() == 0  <->  PACKET_ID == 0x00
```

## Names that do not receive an ID

Only concrete wire-packet definitions receive the prefix. The following names
do not represent one numeric packet and therefore remain unchanged:

- `ConnectionPacketDefinitions` and the other domain aggregators;
- `Paper26PacketCatalog` and `Paper26_3PacketCatalog`;
- shared bases such as `AbstractBlockedPacketDefinition`;
- codecs, capture bridges, playback bridges and channel handlers.

The ID is not added to package names. Definitions stay under their semantic
domain, for example:

```text
...packet.definition.connection.Id000BundleDelimiterPacketDefinition
```

This preserves the responsibility-based layout and avoids one package per
packet or renaming a package when a later protocol version shifts an ID.

## Changes after approval

1. Update `docs/packet-definition-layout.md` with the new mechanical naming
   rule and examples.
2. Add the expected numeric ID to the shared definition contract and validate
   it against both live Paper registries.
3. Rename both current bundle-delimiter definitions and their tests to
   `Id000BundleDelimiterPacketDefinition`.
4. Update the two connection aggregators and adapter references.
5. Run the shared and both version-specific adapter test suites.

No packet behavior, classification, capture policy, playback policy or package
placement changes as part of this rename.
