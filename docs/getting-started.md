# Getting started

This guide installs the Replay Framework Paper runtime and connects a plugin to
its public Java API. The runtime owns all framework lifecycle work; consuming
plugins only use the `ReplayFramework` service after it has been published.

## Prerequisites

Prepare a Paper server with the following components:

- Paper **26.2** running on **Java 26**.
- PacketEvents installed as a server plugin. The runtime declares it as a
  required dependency.
- A reachable PostgreSQL database. The framework uses it for the replay
  catalog, lifecycle state, leases, and metadata revisions.
- One artifact storage choice: Local filesystem, Amazon S3, or SFTP.

The runtime executes Flyway migrations before Hibernate initializes. The
database user therefore needs permission to create and migrate the framework
schema.

## Install the runtime

1. Build the runtime with `.\gradlew.bat :replay-runtime-paper:build` or obtain
   its corresponding runtime JAR.
2. Copy the reobfuscated JAR from `replay-runtime-paper/build/libs/` into the
   server's `plugins/` directory.
3. Install PacketEvents in the same directory.
4. Start the server once. The runtime creates
   `plugins/ReplayFramework/config.json` when it is missing.
5. Stop the server, configure PostgreSQL and the desired storage backend, then
   start it again.

The generated configuration intentionally contains development-safe Local
storage defaults but not production database credentials. See
[Configuration](configuration.md) before attempting a production start.

On successful bootstrap, the runtime registers `ReplayFramework` with Paper's
`ServicesManager` and installs `ReplayFrameworkProvider` as a convenience
fallback. Bootstrap occurs asynchronously, so the service may not exist during
the first instant of another plugin's `onEnable` method.

## Add the API to a plugin project

`replay-api` is the integration boundary. When developing within this source
tree, declare it as a compile-only dependency:

```kotlin
dependencies {
    compileOnly(project(":replay-api"))
    compileOnly(libs.paper.api)
}
```

For a separately built plugin, compile against the matching API JAR supplied
with the framework distribution. Do not shade or bundle a second copy of the
API into the consuming plugin: Paper's shared dependency classpath should
provide the same API classes as the runtime.

Declare the runtime as a Paper dependency in `paper-plugin.yml`:

```yaml
dependencies:
  server:
    ReplayFramework:
      required: true
      load: BEFORE
      join-classpath: true
```

`load: BEFORE` ensures your plugin is loaded after the runtime dependency.
The runtime still has an asynchronous bootstrap phase, so loading order alone
does not guarantee immediate service availability.

## Acquire the framework safely

Prefer Paper's service manager. `ReplayFrameworkProvider.get()` is available as
a convenience fallback, but it throws until the runtime has installed the
facade.

```java
private ReplayFramework findFramework() {
    ReplayFramework framework = getServer()
            .getServicesManager()
            .load(ReplayFramework.class);
    if (framework != null) {
        return framework;
    }

    try {
        return ReplayFrameworkProvider.get();
    } catch (IllegalStateException notReady) {
        return null;
    }
}
```

If `findFramework()` returns `null`, retry from a scheduled Paper task or defer
your feature setup until the service becomes available. The example plugin uses
this approach. Do not instantiate `ReplayFramework`, call
`ReplayFrameworkProvider.install`, or call `clear`; those methods are runtime
lifecycle hooks rather than integration APIs.

Once acquired, retain the interface reference and use only its public
services:

```java
ReplayDefaults defaults = framework.defaults();
RecordingService recordings = framework.recordings();
PlaybackService playbacks = framework.playbacks();
ReplayService replays = framework.replays();
ReplayMetadataService metadata = framework.metadata();
```

## Threading model

The API separates Paper-bound inputs from asynchronous work:

- A `PlaybackRequest` accepts a Paper `Player` at the integration boundary.
- Starting a recording, opening playback, catalog access, metadata access, and
  seek/close operations return `CompletionStage` values.
- `PlaybackSession.play()`, `pause()`, and `speed(...)` are non-blocking.

Never synchronously wait for a returned stage on the server thread. When a
completion callback must touch Bukkit state, schedule that small part through
the Paper scheduler. Keep chat filters and event listeners fast; framework
events may be delivered off the server thread.

## Next steps

- Create a replay with [Recording](recording.md).
- Provide a viewer experience with [Playback](playback.md).
- Add typed fields and catalog search through [Metadata](metadata.md).
- Tune database, artifact storage, and resource budgets in
  [Configuration](configuration.md).

