# Metadata

Replay metadata consists of fixed catalog fields (`title` and `description`)
plus a typed, namespaced set of custom values. The public API requires each
custom field to define its Java type, Gson codec, and permitted query
capabilities. This keeps registration, persistence, revision history, and
query binding consistent.

## Register keys during plugin setup

Register every key before reading, writing, or querying it. A key is identified
by an Adventure namespaced `Key`; use a namespace owned by your plugin.

The value does not have to be a primitive. The following example stores a
structured match object with three typed properties. A Java `record` is a
regular immutable class at runtime, so it is a convenient value object for
metadata while still providing stable equality for updates and queries.

```java
public record MatchMetadata(String gameMode, int round, boolean ranked) {
    public MatchMetadata {
        Objects.requireNonNull(gameMode, "gameMode");
        if (gameMode.isBlank()) {
            throw new IllegalArgumentException("gameMode must not be blank");
        }
        if (round < 1) {
            throw new IllegalArgumentException("round must be positive");
        }
    }
}

Gson gson = new Gson();
TypeAdapter<MatchMetadata> matchCodec =
        gson.getAdapter(TypeToken.get(MatchMetadata.class));

ReplayMetadataKey<MatchMetadata> MATCH = ReplayMetadataKey.of(
        Key.key("my_plugin", "match"),
        TypeToken.get(MatchMetadata.class),
        matchCodec,
        QueryCapabilities.of(QueryCapabilities.Operation.EQUALITY));

// During startup, after the framework is available:
framework.metadata().register(MATCH);
```

The value contract is non-null. `MatchMetadata` is encoded as a JSON object,
for example:

```json
{
  "my_plugin:match": {
    "gameMode": "duel",
    "round": 3,
    "ranked": true
  }
}
```

Make codecs strict and deterministic: the framework uses the same
`TypeAdapter` to serialize the current value, record a revision, and bind query
values. Registration rejects a conflicting definition for an already
registered namespace. Re-registering the same compatible key is safe for normal
plugin startup.

For nontrivial values, declare the accurate `TypeToken<T>` and provide a codec
that round-trips only the intended JSON shape. A custom `TypeAdapter` is useful
when the default Gson representation is not strict enough or when the stored
shape must remain stable across plugin versions. Avoid a generic untyped JSON
field when a domain type can express the contract more clearly.

## Read the current snapshot

```java
framework.metadata().get(replayId).thenAccept(metadata -> {
    String title = metadata.title();
    long revision = metadata.revision();
    metadata.value(MATCH).ifPresent(match -> {
        logger.info(match.gameMode() + " round " + match.round()
                + " (ranked=" + match.ranked() + ")");
    });
});
```

`ReplayMetadata` is immutable. It contains the replay ID, title, description,
current revision number, and an immutable map of custom values. Use
`value(ReplayMetadataKey<T>)` to retrieve a typed optional instead of casting
the raw map yourself.

## Apply optimistic revisions

Updates are atomic and use optimistic locking. Read the current snapshot,
build a mutation using the revision you observed, then apply it:

```java
framework.metadata().get(replayId).thenCompose(current -> {
    MetadataMutation mutation = MetadataMutation.builder(replayId)
            .expectedRevision(current.revision())
            .title("Tournament final — corrected")
            .put(MATCH, new MatchMetadata("duel", 3, true))
            .build();
    return framework.metadata().apply(mutation);
}).thenAccept(updated -> {
    // updated.revision() is one greater than the observed revision.
});
```

A mutation is either a patch or a full replacement:

- A patch may change `title`, `description`, set custom values with `put`, and
  remove custom values with `remove`.
- A replacement uses `replaceWith(ReplayMetadata)` and cannot be combined with
  patch changes.
- Every mutation must specify a non-negative `expectedRevision` and make at
  least one change.

If another writer commits first, the operation fails with a metadata revision
conflict. Reload the current snapshot, let the application resolve the conflict
where necessary, then create a new mutation from the new revision. Do not
blindly retry a stale overwrite, particularly for user-authored fields.

## Audit history

Every successful change creates an immutable snapshot revision in PostgreSQL.
Load the full ascending history when an audit or restore workflow needs it:

```java
framework.metadata().history(replayId).thenAccept(history -> {
    for (MetadataRevision revision : history) {
        logger.info(revision.revision() + " " + revision.operation());
    }
});
```

Each `MetadataRevision` records the positive revision number, timestamp,
operation (`SET`, `REMOVE`, or `REPLACE`), and the complete metadata snapshot
at that revision. History is a durable audit trail; framework events are not a
replacement for it.

## Query by fixed fields and metadata

`ReplayQuery` is a typed, PostgreSQL-independent query AST. The runtime
compiles it to the catalog backend without exposing SQL to integrations.

```java
ReplayQuery query = ReplayQuery.builder()
        .status(RecordingStatus.AVAILABLE)
        .where(MATCH, MetadataPredicate.equalTo(new MatchMetadata("duel", 3, true)))
        .orderBy(ReplayQuery.SortField.CREATED_AT,
                ReplayQuery.SortDirection.DESCENDING)
        .limit(25)
        .build();

framework.replays().querySummaries(query).thenAccept(page -> {
    for (ReplaySummary summary : page.items()) {
        // Render a catalog list.
    }
});
```

Multiple `where(...)` criteria are AND-linked. Available predicates are:

- `exists()` — requires `EXISTENCE` capability.
- `equalTo(...)` and `oneOf(...)` — require `EQUALITY` capability.
- `between(...)` — requires `ORDERING` capability.
- `contains(...)` — requires `CONTAINMENT` capability.

The builder rejects predicates or metadata sorting that the key did not
declare. It also supports fixed-field filters for status, adapter ID, creation
time range, and exact title, along with fixed-field and metadata ordering.

`ReplayPage` returns immutable items and an opaque `nextCursor`. To retrieve a
later page, supply that cursor to a new `ReplayQuery`; never parse or modify
the cursor value.

## Catalog ownership

Use `framework.replays()` for catalog lookup, paged query, summaries, and
deletion. `delete(replayId)` transitions an available replay through
`DELETING`, removes its artifacts, then removes the catalog entry. Treat the
operation as asynchronous and do not offer deleted content for playback.
