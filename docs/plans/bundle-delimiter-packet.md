# Bundle delimiter packet implementation

## Scope

Implement `play.clientbound.minecraft:bundle_delimiter` for the Paper 26.2 and
26.3 adapters. No other packet becomes supported and no recording migration is
introduced.

## Protocol findings

- Mojang introduced the clientbound delimiter in Java Edition 1.19.4. Every
  packet between two delimiters is processed by the client in the same tick;
  there is deliberately no serverbound equivalent.
- The packet has no payload fields. Its only information is its position in the
  ordered stream: the first occurrence opens a bundle and the next occurrence
  closes it.
- Both pinned Paper dev bundles use the same implementation. The PLAY protocol
  registers `ClientboundBundleDelimiterPacket` through
  `ProtocolInfoBuilder.withBundlePacket`. `PacketBundleUnpacker` expands a
  logical `ClientboundBundlePacket` to delimiter, sub-packets, delimiter before
  Paper's encoder. `BundlerInfo` uses one identity-sensitive delimiter instance
  and limits a bundle to 4096 packets; a separately constructed delimiter is
  rejected by the protocol codec.
- Paper's delimiter packet cannot reach a packet listener. Its `handle` method
  throws because `PacketBundlePacker` must consume the delimiter in the Netty
  pipeline.
- PacketEvents 2.14.0 exposes the on-wire delimiter as
  `PacketType.Play.Server.BUNDLE` / `WrapperPlayServerBundle`. Its documentation
  explicitly warns that an uncoordinated delimiter can close somebody else's
  open bundle and invert every later delimiter.

Primary references:

- [Minecraft Java Edition 1.19.4 protocol notes](https://feedback.minecraft.net/hc/en-us/articles/13987663727757-Minecraft-Java-Edition-1-19-4)
- [PacketEvents 2.14.0 bundle wrapper](https://github.com/retrooper/packetevents/blob/v2.14.0/api/src/main/java/com/github/retrooper/packetevents/wrapper/play/server/WrapperPlayServerBundle.java)
- Paper 26.2 and 26.3 dev-bundle sources for `BundlerInfo`,
  `PacketBundlePacker`, `PacketBundleUnpacker`, `BundleDelimiterPacket`,
  `ClientboundBundleDelimiterPacket` and `GameProtocols`

## Decision

The delimiter is an explicitly classified transport-control packet:

- capture by default, so the recording retains the original bundle boundaries
  for validation, diagnostics and a future atomic-bundle playback layer;
- never decode, rewrite, rebuild or replay it as an isolated frame;
- never include it in checkpoints;
- mark its playback scope unsafe.

Replaying a delimiter through the current raw replay writer would bypass both
Paper's bundle unpacker and PacketEvents. It could interleave with a live bundle
that Paper or a plugin already opened for the viewer. A delimiter contains no
owner, open/close bit or bundle identifier, so a packet-local implementation
cannot make that operation safe. Safe bundle replay requires a later
channel-scoped state machine that owns complete delimiter pairs and emits each
bundle atomically; that is intentionally outside this packet-only change.

## Planned changes

1. Add the shared `AbstractBlockedPacketDefinition` policy base. It registers a
   concrete identifier as captured `CONTROL` data and structurally prevents the
   packet from entering the playback decode path.
2. Add a package-private `Id000BundleDelimiterPacketDefinition` and the public
   `ConnectionPacketDefinitions` aggregator to each version adapter.
3. Move each version catalog from `playback` to the neutral `packet` package
   defined by `docs/packet-definition-layout.md`, then register only the
   connection aggregator.
4. Point both live registries and coverage tests at the neutral catalog.
5. Add version-local tests that prove the native wire form is packet ID `0`
   with an empty payload, the definition is captured control data, and every
   other PLAY packet remains explicitly unsupported.

## Expected impact

The registry fingerprint changes because `bundle_delimiter` changes from
`UNSUPPORTED` to `CONTROL`; both policies remain capturable and non-replayable.
Capture and playback channel placement does not change. The packet cannot reach
the rewriter, so there is no new decode, encode or outbound injection path.
