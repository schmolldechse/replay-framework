# Naming and layout of packet definitions

This document defines only the naming and filesystem layout of version-specific
PLAY packet definitions.

## Class names

Every concrete packet class uses this form:

```text
Id<DDD><ProtocolIdentifierInUpperCamelCase>PacketDefinition
```

The name is derived mechanically:

1. Read the numeric ID from the pinned version's PLAY clientbound registry.
2. Format it as a zero-padded three-digit decimal value and prepend `Id`.
3. Remove `play.clientbound.` and the `minecraft:` namespace.
4. Split the remaining identifier at `/`, `_` and `-`.
5. Convert every segment to UpperCamelCase.
6. Append `PacketDefinition`.

Examples:

| Version | ID | Identifier | Class |
|---|---:|---|---|
| 26.2 / 26.3 | 0 | `minecraft:bundle_delimiter` | `Id000BundleDelimiterPacketDefinition` |
| 26.2 / 26.3 | 1 | `minecraft:add_entity` | `Id001AddEntityPacketDefinition` |
| 26.2 | 37 | `minecraft:forget_level_chunk` | `Id037ForgetLevelChunkPacketDefinition` |
| 26.3 | 37 | `minecraft:add_transient_block` | `Id037AddTransientBlockPacketDefinition` |
| 26.3 | 38 | `minecraft:forget_level_chunk` | `Id038ForgetLevelChunkPacketDefinition` |

The class name does not contain a Paper version because the version package
already owns that context. The numeric ID is version-local, so the same
identifier may have a different class name when its ID moves between versions.
Every definition also exposes the numeric ID; adapter discovery validates it
against the live Paper registry instead of treating the name as documentation.

Concrete definitions are `final` and package-private. Their filenames match
their class names exactly. The suffixes `Handler`, `Impl`, `Processor` and
`Adapter` are not used.

Shared abstract types use the form
`Abstract<Responsibility>PacketDefinition`. Domain aggregators use
`<Domain>PacketDefinitions`.

## Production layout

Packet definitions form their own protocol layer. They are not children of
`capture`, `checkpoint` or `playback`.

Shared NMS-free abstractions live in:

```text
replay-adapter/paper-common/src/main/java/
└── dev/voldechse/replayframework/adapter/paper/packet/
    └── definition/
```

Each version adapter exposes `packet` as a sibling of the runtime consumers:

```text
replay-adapter/paper-26_3/src/main/java/
└── dev/voldechse/replayframework/adapter/paper/v26_3/
    ├── capture/
    ├── checkpoint/
    ├── packet/
    │   ├── Paper26_3PacketCatalog.java
    │   ├── Paper26_3PacketCodec.java
    │   └── definition/
    │       ├── connection/
    │       ├── diagnostic/
    │       ├── world/
    │       ├── entity/
    │       ├── player/
    │       ├── inventory/
    │       ├── chat/
    │       ├── scoreboard/
    │       ├── ui/
    │       └── effect/
    └── playback/

replay-adapter/paper-26_2/src/main/java/
└── dev/voldechse/replayframework/adapter/paper/v26_2/
    ├── capture/
    ├── checkpoint/
    ├── packet/
    │   ├── Paper26_2PacketCatalog.java
    │   ├── Paper26_2PacketCodec.java
    │   └── definition/
    │       ├── connection/
    │       ├── diagnostic/
    │       ├── world/
    │       ├── entity/
    │       ├── player/
    │       ├── inventory/
    │       ├── chat/
    │       ├── scoreboard/
    │       ├── ui/
    │       └── effect/
    └── playback/
```

The Java package of a definition is therefore:

```text
dev.voldechse.replayframework.adapter.paper.<version>.packet.definition.<domain>
```

The domain is determined by protocol responsibility, not by the current
capture or playback policy. A definition therefore stays in the same package
if it changes from viewer-local to replayable, from blocked to supported, or
gains capture-specific metadata.

| Package | Contents |
|---|---|
| `connection` | transport, session, cookie, resource-pack, configuration-transition and custom-payload packets |
| `diagnostic` | debug, game-test, query and warning packets |
| `world` | chunks, light, blocks, border, time, spawn, cache, registry tags and maps |
| `entity` | lifecycle, movement, metadata, equipment, attributes, effects, passengers, links, projectiles and player profiles |
| `player` | abilities, health, experience, camera, position, respawn, combat and other player-local state |
| `inventory` | containers, slots, held items, cooldowns, merchants and recipe books |
| `chat` | player, system and disguised chat plus chat and command data |
| `scoreboard` | objectives, display slots, scores and teams |
| `ui` | titles, action bar, screens, dialogs, tab list, advancements, links and waypoints |
| `effect` | animations, particles, sounds, explosions, boss bars and other ordered scene effects |

Each `definition.<domain>` package contains one public aggregator named after
the domain, for example `EntityPacketDefinitions`. Concrete definitions
remain package-private; the aggregator exposes their immutable list to the
version catalog. No aggregator contains packet behavior.

Example:

```text
packet/definition/connection/
├── ConnectionPacketDefinitions.java
└── Id000BundleDelimiterPacketDefinition.java
```

## Ownership boundary

| Package | Owns |
|---|---|
| `packet` | protocol identifiers, native packet types, classification, decode/encode and packet-specific read/rewrite/rebuild logic |
| `capture` | PacketEvents observation, source selection, event fallbacks, ordering and frame emission |
| `checkpoint` | authoritative snapshots and checkpoint ordering |
| `playback` | viewer session state, identity-map lifetime, reset, queueing and outbound channel injection |

A packet definition may consume a narrow context supplied by capture or
playback, but it does not own listeners, schedulers, Netty handlers or viewer
lifecycles. Conversely, capture and playback do not contain switches over
individual protocol packet names.

## Test layout

Tests mirror the production package and filename:

```text
src/test/java/.../packet/definition/<domain>/
└── Id<DDD><ProtocolName>PacketDefinitionTest.java
```

For example:

```text
src/main/java/.../packet/definition/connection/Id000BundleDelimiterPacketDefinition.java
src/test/java/.../packet/definition/connection/Id000BundleDelimiterPacketDefinitionTest.java
```
