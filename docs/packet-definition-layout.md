# Naming and layout of packet definitions

This document defines only the naming and filesystem layout of version-specific
PLAY packet definitions.

## Class names

Every concrete packet class uses this form:

```text
<ProtocolIdentifierInUpperCamelCase>PacketDefinition
```

The name is derived mechanically:

1. Remove `play.clientbound.` and the `minecraft:` namespace.
2. Split the remaining identifier at `/`, `_` and `-`.
3. Convert every segment to UpperCamelCase.
4. Append `PacketDefinition`.

Examples:

| Identifier | Class |
|---|---|
| `minecraft:add_entity` | `AddEntityPacketDefinition` |
| `minecraft:set_entity_data` | `SetEntityDataPacketDefinition` |
| `minecraft:player_info_update` | `PlayerInfoUpdatePacketDefinition` |
| `minecraft:debug/block_value` | `DebugBlockValuePacketDefinition` |
| `minecraft:swing_animation` | `SwingAnimationPacketDefinition` |

The class name does not contain a Paper version. The same identifier therefore
has the same class name in the 26.2 and 26.3 modules.

Concrete definitions are `final` and package-private. Their filenames match
their class names exactly. The suffixes `Handler`, `Impl`, `Processor` and
`Adapter` are not used.

Shared abstract types use the form
`Abstract<Responsibility>PacketDefinition`. Domain aggregators use
`<Domain>PacketDefinitions`.

## Production layout

Shared NMS-free abstractions remain in:

```text
replay-adapter/paper-common/src/main/java/
└── dev/voldechse/replayframework/adapter/paper/packet/
```

Version-specific definitions are grouped by stable protocol domain:

```text
replay-adapter/paper-26_3/src/main/java/
└── dev/voldechse/replayframework/adapter/paper/v26_3/playback/
    └── packet/
        ├── Paper26_3PacketCatalog.java
        ├── connection/
        ├── diagnostic/
        ├── world/
        ├── entity/
        ├── player/
        ├── inventory/
        ├── chat/
        ├── scoreboard/
        ├── ui/
        └── effect/

replay-adapter/paper-26_2/src/main/java/
└── dev/voldechse/replayframework/adapter/paper/v26_2/playback/
    └── packet/
        ├── Paper26PacketCatalog.java
        ├── connection/
        ├── diagnostic/
        ├── world/
        ├── entity/
        ├── player/
        ├── inventory/
        ├── chat/
        ├── scoreboard/
        ├── ui/
        └── effect/
```

The domain is determined by protocol responsibility, not by the current
playback policy. A definition therefore stays in the same package if it changes
from viewer-local to replayable or from blocked to supported.

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

Each domain contains one public aggregator named after the domain, for example
`EntityPacketDefinitions`. Concrete definitions remain package-private; the
aggregator exposes their immutable list to the version catalog. No aggregator
contains packet behavior.

Example:

```text
playback/packet/entity/
├── EntityPacketDefinitions.java
├── AddEntityPacketDefinition.java
├── RemoveEntitiesPacketDefinition.java
├── EntityPositionSyncPacketDefinition.java
├── SetEntityDataPacketDefinition.java
└── SetEquipmentPacketDefinition.java
```

## Test layout

Tests mirror the production package and filename:

```text
src/test/java/.../playback/packet/<domain>/
└── <PacketDefinition>Test.java
```

For example:

```text
src/main/java/.../playback/packet/entity/AddEntityPacketDefinition.java
src/test/java/.../playback/packet/entity/AddEntityPacketDefinitionTest.java
```
