# Configuration

The Paper runtime reads `plugins/ReplayFramework/config.json`. If the file is
missing, it creates a formatted Local-storage template during bootstrap. The
loader validates the complete JSON shape, rejects unknown fields, requires
positive durations and limits, and resolves filesystem paths relative to the
runtime plugin data directory.

Do not use absolute paths or paths that escape the plugin directory. The
runtime rejects them, and it also rejects symbolic links for sensitive local
and playback paths.

## PostgreSQL

PostgreSQL is mandatory. Configure a dedicated database and credential under
`postgresql`:

```json
{
  "postgresql": {
    "jdbcUrl": "jdbc:postgresql://127.0.0.1:5432/replay",
    "username": "replay",
    "password": "change-me",
    "minimumIdle": 1,
    "maximumPoolSize": 8,
    "connectionTimeout": "PT10S",
    "validationTimeout": "PT5S",
    "idleTimeout": "PT5M",
    "maxLifetime": "PT30M",
    "databaseParallelism": 4,
    "databaseQueueCapacity": 256
  }
}
```

Durations use ISO-8601 syntax, for example `PT10S` or `PT5M`. Flyway runs the
packaged migrations before Hibernate validates the schema. Protect
`config.json` using normal server filesystem permissions; the database password
is currently configured as a JSON string.

## Artifact storage

Choose one backend through `storage.backend`: `LOCAL`, `S3`, or `SFTP`. The
runtime stores replay artifacts in the selected backend and records their
location and backend in PostgreSQL.

### Local storage

```json
{
  "storage": {
    "backend": "LOCAL",
    "local": {
      "root": "replay-storage"
    }
  }
}
```

`root` is relative to `plugins/ReplayFramework/`. Keep it on durable storage
with enough room for replay artifacts and operational backups.

### Amazon S3

```json
{
  "storage": {
    "backend": "S3",
    "s3": {
      "bucket": "my-replays",
      "prefix": "production",
      "region": "eu-central-1",
      "credentialProvider": "DEFAULT_CHAIN",
      "maxAttempts": 3,
      "baseBackoff": "PT0.1S",
      "maxBackoff": "PT2S"
    }
  }
}
```

Optional S3 fields are `endpointOverride`, `pathStyleAccessEnabled`, and
`profileName`. The default credential provider is `DEFAULT_CHAIN`; prefer the
host's IAM role or an external credential chain over static secrets in the
configuration file. Use a restrictive bucket policy scoped to the chosen
prefix.

### SFTP

```json
{
  "storage": {
    "backend": "SFTP",
    "sftp": {
      "host": "storage.example.net",
      "port": 22,
      "username": "replay",
      "basePath": "/srv/replays",
      "knownHostsFile": "ssh/known_hosts",
      "authentication": "PRIVATE_KEY",
      "privateKeyFile": "ssh/id_ed25519",
      "poolSize": 4
    }
  }
}
```

SFTP supports `SSH_AGENT`, `PRIVATE_KEY`, and password-based authentication as
configured by the runtime. Keep `knownHostsFile` populated and use a verified
host key. `privateKeyFile`, `knownHostsFile`, and all password/passphrase
references are relative to the plugin directory. Use `passwordEnv` and
`privateKeyPassphraseEnv` for environment-variable names rather than writing
SFTP secrets into JSON.

## Recording resources

```json
{
  "recording": {
    "workspaceRoot": "replay-work",
    "maxSegmentBytes": 67108864,
    "maxSegmentDuration": "PT30S",
    "checkpointInterval": "PT30S",
    "maxQueueBytes": 33554432
  }
}
```

The workspace holds transient recording and publication work. `maxSegmentBytes`
is the required structural segment limit. `maxSegmentDuration` rotates long
segments; `checkpointInterval` controls default seek checkpoints; and
`maxQueueBytes` protects the capture pipeline. The runtime exposes these values
through `framework.defaults()` so plugin requests can begin from the server
operator's chosen limits.

## Playback resources

```json
{
  "playback": {
    "workDirectory": "playback-work",
    "cacheRoot": "playback-cache",
    "cacheMaxBytes": 2147483648,
    "preloadAhead": "PT30S",
    "retainBehind": "PT30S",
    "minimumResumeBuffer": "PT3S",
    "memoryBudgetBytes": 134217728,
    "diskBudgetBytes": 2147483648,
    "maxParallelFetches": 4
  }
}
```

These settings set the defaults for session-local `PlaybackBufferOptions`.
Cache limits should account for simultaneous viewers and remote-storage
latency. The disk cache may improve repeated reads, while the memory budget
controls decoded packet-buffer use. Set `maxParallelFetches` conservatively for
the storage backend and server I/O capacity.

## Shutdown

```json
{
  "shutdown": {
    "timeout": "PT30S"
  }
}
```

On server shutdown, the runtime stops admitting recordings, attempts clean
finalization, closes playback sessions, and releases leases. If finalization
outlives the timeout, outstanding recordings are failed rather than treated as
available.

## Operational guidance

- Back up PostgreSQL and the artifact backend together; a catalog entry without
  its artifact, or an orphaned artifact, is not a usable replay backup.
- Monitor available disk space for Local storage, recording workspaces, and the
  playback cache.
- Treat the selected storage backend as part of replay durability. A completed
  `AVAILABLE` recording has passed publication and integrity checks, but later
  external deletion or credential changes can still make it unreadable.
- Change configuration while the runtime is stopped, then restart Paper. The
  runtime owns configuration loading during bootstrap.

