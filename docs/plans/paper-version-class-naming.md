# Paper version class naming alignment

## Goal

Make every version-bound Paper type encode the complete supported Minecraft
version in the same form:

```text
Paper<major>_<minor><Responsibility>
```

For the two current adapters this means `Paper26_2...` and `Paper26_3...`.

Status: implemented for all version-bound types in the Paper 26.2 adapter.

## Existing inconsistency

The Java package and Gradle module already distinguish `v26_2` / `paper-26_2`
from `v26_3` / `paper-26_3`, but the 26.2 types omit the minor component:

```text
Paper26PacketRegistry
Paper26ReplayAdapter
Paper26CaptureBridge
```

The equivalent 26.3 types include it:

```text
Paper26_3PacketRegistry
Paper26_3ReplayAdapter
Paper26_3CaptureBridge
```

That makes `Paper26` ambiguous: it can be read as the entire 26.x line even
though every such type is compiled against Paper 26.2 specifically.

## Decision

All version-bound types in the 26.2 adapter receive the exact `Paper26_2`
prefix. Examples:

| Current | Aligned |
|---|---|
| `Paper26AdapterProvider` | `Paper26_2AdapterProvider` |
| `Paper26ReplayAdapter` | `Paper26_2ReplayAdapter` |
| `Paper26PacketRegistry` | `Paper26_2PacketRegistry` |
| `Paper26ProtocolIntrospector` | `Paper26_2ProtocolIntrospector` |
| `Paper26CaptureBridge` | `Paper26_2CaptureBridge` |
| `Paper26CheckpointEncoder` | `Paper26_2CheckpointEncoder` |
| `Paper26PlaybackBridge` | `Paper26_2PlaybackBridge` |
| `Paper26PacketCatalog` | `Paper26_2PacketCatalog` |

The same rule applies to version-bound package-private helpers, nested types and
tests. The 26.3 adapter already follows the target rule and is not renamed.

Types whose implementation is version-local but whose name intentionally does
not contain a version remain unchanged when their 26.2 and 26.3 counterparts
already match, for example `PaperConnectionAccessor`,
`PacketEventsReplayWriter` and `ReplayViewerOutboundGate`.

## Packet-definition naming

The approved packet-ID scheme is applied at the same time:

```text
BundleDelimiterPacketDefinition
    -> Id000BundleDelimiterPacketDefinition
```

This concrete packet name does not contain `Paper26_2` or `Paper26_3`; its
version is already unambiguous from the package. The numeric ID is version-local
and is validated against the corresponding live Paper registry.

The 26.2 definition therefore depends on
`Paper26_2ReplayPacketRewriter`, while the 26.3 definition continues to depend
on `Paper26_3ReplayPacketRewriter`.

## Affected integration points

- 29 version-prefixed production and test filenames in `paper-26_2`;
- all Java references, constructors, nested-type qualifiers and Javadocs in the
  26.2 module;
- the `PaperAdapterProvider` service registration;
- current adapter and packet-layout documentation;
- bundle-delimiter production/test filenames and connection aggregators;
- shared packet-definition constructors and catalog/registry ID validation.

No package, module, adapter ID (`paper-26.2`), codec key, recording format,
packet behavior or runtime policy changes.

## Validation

After the mechanical rename:

1. No version-bound `Paper26...` identifier without `_2` or `_3` may remain in
   production or test sources.
2. Both service-provider files must name an existing provider class.
3. The bundle definition must bind ID `0` / `0x00` in both versions.
4. A definition whose expected ID differs from the live registry must fail
   adapter discovery.
5. The shared, Paper 26.2 and Paper 26.3 test suites must pass.
