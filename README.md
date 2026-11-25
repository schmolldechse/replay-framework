# Replay Framework

Replay Framework records what happens on a Paper server as Minecraft network
packets and plays it back to any player, with its own timeline per viewer.
Recordings are stored in a configurable storage backend and listed in a
PostgreSQL catalog with typed, revisioned metadata.

The framework is a library for plugin developers. It provides a public Java
API for starting recordings, opening playback, querying the catalog and
inspecting a replay's players, entities and inventories. It does not provide
a fixed GUI, command tree, hotbar, resource pack or player-state policy; the
included example plugin shows one way to build them.

## How it works

![Capture with eyes and player connections, merge into one scene per world, store in tracks, persist to storage and catalog, and play back on independent viewer timelines.](docs/images/overview.svg)

- **Argus** gives every recording its own virtual observers. The server
  sends them chunks and entities like it does to players, but no player,
  plugin or player count can see them. A recording therefore does not
  depend on who is online, and nobody is missing from it, not even the
  recorded players themselves.
- **Tracks** keep a recording's data apart: one global track, one track per
  world and one per subject with private state, such as a player's inventory
  or the chest of a llama. Playback reads only what the viewer watches.
- **Playback** rebuilds the scene from checkpoints and packet deltas on each
  viewer's own timeline, with seeking, five speeds and buffering against
  remote storage.

## Highlights

- Region recordings that run without any player present, and world
  recordings that follow players through every dimension.
- Inventories, held items, health and experience of players, and the
  inventories of villagers, minecarts, chested animals and more, at any
  point of a replay.
- Entity queries and click events for replay entities, for building
  inspection menus.
- Independent viewer timelines with pause, restart, absolute and relative
  seeking, and 0.25x to 4x speed.
- Replay artifacts in Local, Amazon S3 or SFTP storage, verified by digest.
- A PostgreSQL catalog with Flyway migrations, typed extensible metadata and
  a complete revision history.
- An asynchronous Java API; no HTTP or REST API.

## Support boundary

The runtime supports **Paper 26.2** (build 135) on **Java 26**. A replay
contains raw protocol packets and is tied to the adapter, protocol version
and packet registry that produced it; it is not a portable video or a
cross-version format. Further Minecraft versions are supported by adding an
adapter that implements the
[version contract](docs/virtual-observer.md#version-contract).

The runtime needs no other server plugin. It requires a PostgreSQL database,
even when artifacts use Local storage.

## Documentation

Using the framework:

| Guide | Contents |
|---|---|
| [Getting started](docs/getting-started.md) | install the runtime and connect a plugin |
| [Recording](docs/recording.md) | scopes, participants, observers, budgets and the recording lifecycle |
| [Playback](docs/playback.md) | viewer sessions, timeline control, worlds, players and entities |
| [Metadata](docs/metadata.md) | typed fields, revisions and catalog queries |
| [Configuration](docs/configuration.md) | PostgreSQL, artifact storage and resource limits |

How it is built:

| Document | Contents |
|---|---|
| [Architecture](docs/architecture.md) | modules and the data flow from capture to playback |
| [Argus: virtual observers](docs/virtual-observer.md) | eyes, their placement, scene merging and ambient sources |
| [Recording tracks](docs/recording-tracks.md) | track kinds, subjects, checkpoints, file format and the state API |
| [Capture source policy](docs/capture-source-policy.md) | which packet comes from which source, and how copies are merged |
| [Packet definition layout](docs/packet-definition-layout.md) | naming and placement of per-packet definitions |
| [Paper 26.2 adapter](docs/adapters/paper-26.2.md) | the reference adapter and the policy of every packet |

## Public API at a glance

After the runtime has completed its asynchronous bootstrap, plugins obtain
one `ReplayFramework` facade from Paper's service manager:

```java
ReplayFramework framework = Bukkit.getServicesManager().load(ReplayFramework.class);

RecordingService recordings = framework.recordings();
PlaybackService playbacks = framework.playbacks();
ReplayService replays = framework.replays();
ReplayMetadataService metadata = framework.metadata();
ReplayEventPublisher events = framework.events();
```

A recording that follows two players through every world, and a viewer
inspecting one of them later:

```java
RecordingRequest request = RecordingRequest.builder()
        .title("Speedrun")
        .description("Any%, two players")
        .scope(RecordingScope.builder().build())
        .participants(Set.of(runner.getUniqueId(), partner.getUniqueId()))
        .capturePolicy(CapturePolicy.builder().build())
        .budget(framework.defaults().recordingBudget())
        .options(framework.defaults().recordingOptions())
        .build();
framework.recordings().start(request);

// later, in a playback session
session.state(RecordedSubject.player(runnerId)).thenAccept(state ->
        state.ifPresent(found -> found.vitals().ifPresent(vitals -> { /* health, food */ })));
```

Every operation that can perform I/O returns a `CompletionStage`. Do not
block the server thread with `join()` or `get()`; schedule Bukkit work back
onto the server thread. [Getting started](docs/getting-started.md) shows
lifecycle-safe service discovery.

## Build

The project is a Gradle Kotlin DSL multi-project build. Build the Paper
runtime with:

```powershell
.\gradlew.bat :replay-runtime-paper:jar
```

Use the bundled artifact from `replay-runtime-paper/build/libs/` as the
server plugin. Build the example integration with:

```powershell
.\gradlew.bat :replay-example-plugin:jar
```

The `runServer` task starts a local Paper 26.2 development server.

| Module | Responsibility |
| --- | --- |
| `replay-api` | stable public Java API for plugin integrations |
| `replay-runtime-paper` | Paper plugin that composes and publishes the framework |
| `replay-core` | recording, tracks, playback, artifact, metadata and catalog orchestration |
| `replay-adapter:api` | contract between the core and a version adapter |
| `replay-adapter:paper-common` | version-independent Paper parts: Argus, definition catalogs, recipient copies and state, and the shared capture, playback and checkpoint bridges |
| `replay-adapter:paper-26_2` | Paper 26.2 packet definitions, codecs, eye, world snapshots and the hooks the shared bridges call |
| `replay-format` | track, segment, checkpoint, index and manifest codecs |
| `replay-storage:*` | Local, S3 and SFTP artifact storage |
| `replay-database:postgresql` | PostgreSQL repositories, Flyway migrations and metadata persistence |
| `replay-example-plugin` | a reference integration with commands, browser, hotbar, menus and viewer worlds |

## Design principles

- **Public API only:** integrations use `replay-api`; core, adapter, storage
  and database types remain internal.
- **Packets as the source:** recordings consist of the packets the server
  builds, captured from observers and connections, never reconstructed from
  Bukkit events.
- **Integrity first:** a replay reaches `AVAILABLE` only after its artifacts
  have been finalized, verified and published. Queue overflow, corruption,
  storage errors, adapter errors and interrupted work result in `FAILED`.
- **Viewer isolation:** each playback session has its own timeline, speed,
  seek state, identity space and resource budgets.
- **Explicit integration:** the framework owns replay mechanics; the
  consuming plugin owns permissions, menus, hotbars, resource packs, viewer
  worlds and the restoration of the player's normal state.

## Example integration

`replay-example-plugin` is not part of the runtime contract. It provides a
`/replay` command tree, an inventory browser, a playback hotbar, a resource
pack, one viewer world per dimension, a players menu that follows a recorded
player into any world, and an inventory view of players and entities that
opens on right-click. Use it as a reference rather than a required interface.
