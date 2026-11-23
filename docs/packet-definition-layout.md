# Naming and layout of packet definitions

This document defines only the naming and filesystem layout of version-specific
PLAY packet definitions.

Clientbound and serverbound packets are strictly separated. They have their
own base classes, catalogs and packages, and a definition never handles both
directions. The rules below apply to both; each direction reads its numeric
IDs and identifiers from its own registry (`play.clientbound.` or
`play.serverbound.`).

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
| 26.2 | 0 | `minecraft:bundle_delimiter` | `Id000BundleDelimiterPacketDefinition` |
| 26.2 | 1 | `minecraft:add_entity` | `Id001AddEntityPacketDefinition` |
| 26.2 | 37 | `minecraft:forget_level_chunk` | `Id037ForgetLevelChunkPacketDefinition` |

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
    ├── clientbound/
    │   ├── ClientboundPacketDefinition.java
    │   ├── ClientboundPacketDefinitionCatalog.java
    │   └── definition/
    └── serverbound/
        ├── ServerboundPacketDefinition.java
        ├── ServerboundPacketDefinitionCatalog.java
        └── ServerboundReaction.java
```

Every version adapter uses this split.

Each version adapter exposes `packet` as a sibling of the runtime consumers:

```text
replay-adapter/paper-26_2/src/main/java/
└── dev/voldechse/replayframework/adapter/paper/v26_2/
    ├── actor/
    ├── argus/
    ├── capture/
    ├── checkpoint/
    ├── packet/
    │   ├── serverbound/
    │   │   ├── Paper26_2ServerboundPacketCatalog.java
    │   │   └── definition/
    │   │       ├── connection/
    │   │       ├── diagnostic/
    │   │       ├── world/
    │   │       ├── entity/
    │   │       ├── player/
    │   │       ├── inventory/
    │   │       ├── chat/
    │   │       └── ui/
    │   └── clientbound/
    │       ├── Paper26_2ClientboundPacketCatalog.java
    │       └── definition/
    │           ├── connection/
    │           ├── diagnostic/
    │           ├── world/
    │           ├── entity/
    │           ├── player/
    │           ├── chat/
    │           ├── scoreboard/
    │           ├── ui/
    │           └── effect/
    └── playback/
```

The Java package of a definition is therefore:

```text
dev.voldechse.replayframework.adapter.paper.<version>.packet.<direction>.definition.<domain>
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
| `inventory` | serverbound container clicks, slots, held items, merchants and recipe books; clientbound container packets belong to `ui` |
| `chat` | player, system and disguised chat plus chat and command data |
| `scoreboard` | objectives, display slots, scores and teams |
| `ui` | titles, action bar, screens, containers, cooldowns, recipe books, dialogs, tab list, advancements, links and waypoints |
| `effect` | animations, particles, sounds, explosions, boss bars and other ordered scene effects |

Serverbound definitions use the same domains where they apply; the 26.2
serverbound registry has no `scoreboard` or `effect` packets.

Each `definition.<domain>` package contains one public aggregator named after
the domain, for example `EntityPacketDefinitions` for clientbound and
`EntityServerboundPacketDefinitions` for serverbound definitions. Concrete definitions
remain package-private; the aggregator exposes their immutable list to the
version catalog. No aggregator contains packet behavior.

Example:

```text
packet/clientbound/definition/connection/
├── ConnectionPacketDefinitions.java
└── Id000BundleDelimiterPacketDefinition.java

packet/serverbound/definition/world/
├── WorldServerboundPacketDefinitions.java
├── Id041PlayerActionPacketDefinition.java
└── Id066UseItemOnPacketDefinition.java
```

## Ownership boundary

| Package | Owns |
|---|---|
| `packet` | protocol identifiers, native packet types, classification, distribution, recipient copies and state, decode/encode and packet-specific read/rewrite/rebuild logic |
| `argus` | the eye and the state Argus samples from the server |
| `actor` | before and after reads around serverbound triggers and the effect families |
| `capture` | the connection handlers, the serverbound receiver, source tagging, ordering and frame emission |
| `checkpoint` | authoritative snapshots and checkpoint ordering |
| `playback` | viewer session state, identity-map lifetime, reset, queueing, outbound channel injection, scene queries and interactions |

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
