# Replay Framework

Replay Framework is a modular Java framework for recording and playing back
Minecraft Paper server activity. It captures server-side events as raw,
version-bound Minecraft network packets, persists replay artifacts in a
configurable storage backend, and maintains a PostgreSQL replay catalog.

The framework is designed for plugin developers. It provides a public Java API
for creating recordings, opening independent viewer timelines, querying the
catalog, and managing typed replay metadata. It deliberately does **not**
provide a fixed GUI, command tree, hotbar, resource pack, or player-state
policy. Those integration concerns belong to the consuming plugin; the
included example plugin demonstrates one possible implementation.

## Highlights

- Server-side packet capture with scoped recordings and configurable policies.
- Replay artifacts stored in Local, Amazon S3, or SFTP storage.
- PostgreSQL catalog with Flyway migrations, typed extensible metadata, and a
  complete metadata revision history.
- Independent playback timelines for each viewer, including pause, restart,
  absolute and relative seeking, and 0.25x, 0.5x, 1x, 2x, and 4x speeds.
- Checkpoint-based replay reconstruction and configurable playback buffering.
- A public asynchronous Java API; no HTTP or REST API is exposed.

## Support boundary

The current runtime supports **Paper 26.2** on **Java 26**. Replay content
contains raw protocol packets and is therefore tied to the adapter and
protocol version that produced it. At present, the supported adapter is
`paper-26_2`; a replay is not a portable video file or a cross-version format.

The runtime plugin requires [PacketEvents](https://github.com/retrooper/packetevents)
as a Paper server dependency. PostgreSQL is required for the catalog and
metadata, even when replay artifacts use Local storage.

## Documentation

Start here:

- [Getting started](docs/getting-started.md) — install and configure the runtime
  and connect a Paper plugin.
- [Recording](docs/recording.md) — create scoped recordings and handle their
  lifecycle.
- [Playback](docs/playback.md) — open and control a viewer-specific timeline.
- [Metadata](docs/metadata.md) — register typed fields, apply revisions, and
  query the catalog.
- [Configuration](docs/configuration.md) — configure PostgreSQL, artifact
  storage, recording, and playback resources.
- [Architecture](docs/architecture.md) — understand the module layout and the
  recording-to-playback data flow.

## Build

The project is a Gradle Kotlin DSL multi-project build. Build the Paper runtime
with:

```powershell
.\gradlew.bat :replay-runtime-paper:build
```

Use the reobfuscated runtime artifact produced in
`replay-runtime-paper/build/libs/` as the server plugin. Build the example
integration independently when needed:

```powershell
.\gradlew.bat :replay-example-plugin:build
```

The relevant modules are:

| Module | Responsibility |
| --- | --- |
| `replay-api` | Stable public Java API for plugin integrations. |
| `replay-runtime-paper` | Paper plugin that composes and publishes the framework. |
| `replay-core` | Recording, playback, artifact, metadata, and catalog orchestration. |
| `replay-adapter:paper-26_2` | Paper 26.2 packet capture, checkpoints, and playback bridge. |
| `replay-format` | Packet segment, checkpoint, index, and manifest codecs. |
| `replay-storage:*` | Local, S3, and SFTP artifact storage implementations. |
| `replay-database:postgresql` | PostgreSQL repositories, Flyway migrations, and metadata persistence. |
| `replay-example-plugin` | A reference integration with commands, browser, hotbar, and viewer policy. |

## Public API at a glance

After the runtime has completed its asynchronous bootstrap, consuming plugins
obtain one `ReplayFramework` facade from Paper's service manager. It exposes
five focused services:

```java
ReplayFramework framework = Bukkit.getServicesManager().load(ReplayFramework.class);

RecordingService recordings = framework.recordings();
PlaybackService playbacks = framework.playbacks();
ReplayService replays = framework.replays();
ReplayMetadataService metadata = framework.metadata();
ReplayEventPublisher events = framework.events();
```

All operations that can perform I/O return `CompletionStage`. Do not block the
Paper server thread with `join()` or `get()`; continue the operation
asynchronously and schedule any Bukkit-only work back to the server thread.
The [Getting started](docs/getting-started.md) guide describes lifecycle-safe
service discovery.

## Design principles

- **Public API only:** integrations use `replay-api`; core, adapter, storage,
  and database implementation types remain internal.
- **Integrity first:** a replay reaches `AVAILABLE` only after its artifacts
  have been finalized and published. Queue overflow, corruption, storage
  errors, adapter errors, and interrupted work result in `FAILED`.
- **Viewer isolation:** each playback session has its own timeline, speed, seek
  state, and resource budgets.
- **Explicit integration:** the framework owns replay mechanics, while the
  consuming plugin owns permissions, menus, hotbars, resource packs, and the
  player's normal-state restoration.

## Example integration

`replay-example-plugin` is intentionally not part of the runtime contract. It
shows how a plugin can provide a `/replay` command tree, an inventory browser,
a playback hotbar, a resource pack, and a protected viewer environment on top
of the public API. Use it as a reference rather than as a required user
interface.

