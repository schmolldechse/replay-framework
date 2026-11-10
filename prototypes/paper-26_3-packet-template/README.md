# Paper 26.3 packet-handler prototype

This isolated Gradle project demonstrates the proposed packet-oriented adapter
design with one representative Paper 26.3 packet:
`minecraft:add_entity`.

It is intentionally **not** included by the repository root build. It is a
design prototype, not production code and not a second adapter implementation.

## What the example shows

`AddEntityPacketHandler` owns the packet-specific work:

1. read a decoded `ClientboundAddEntityPacket` into an immutable value;
2. map the recorded entity ID and UUID through the playback-session context;
3. map the fishing-hook owner when the packet data field has that meaning; and
4. rebuild a new native Paper 26.3 packet.

`AbstractClientboundPacketHandler` owns the invariant workflow:

1. validate frame and descriptor;
2. drop or block unsafe policies before decoding;
3. decode through the connection-bound protocol codec;
4. invoke `read`, `rewrite` and `rebuild`;
5. encode the rebuilt native packet; and
6. verify that the codec still resolves to the bound packet ID.

`PacketEventsReplayWriter` demonstrates the separate transport concern. It
writes the already encoded packet from the PacketEvents encoder context. This
skips the replay live-gate, Paper's packet encoder and PacketEvents' own capture
callback while retaining the earlier framing, compression and encryption
handlers.

## Relevant files

- `AbstractClientboundPacketHandler.java`: shared template method.
- `AddEntityPacketHandler.java`: the concrete 26.3 packet implementation.
- `Paper26_3ConnectionCodecSession.java`: native protocol decode and encode.
- `PacketHandlerCatalog.java`: explicit, immutable lookup.
- `PacketEventsReplayWriter.java`: replay output at the correct channel boundary.
- `AddEntityPacketHandlerTest.java`: focused behavior example using a fake codec.

## Deliberate simplifications

- The catalog contains only `add_entity`; a productive 26.3 catalog would bind
  all 144 PLAY packet types.
- The numeric packet ID is supplied by the caller. Production code would obtain
  it from the verified `Paper26_3ProtocolIntrospector` snapshot.
- Identity allocation is represented by two mapping functions. Production code
  would adapt the existing session-local `ReplayIdentityMap`.
- Capture-context extraction, authoritative player sampling, event fallbacks,
  checkpoints and reset tracking are outside this single-packet example.
- The prototype may be built independently, but build success is not a design
  requirement for this review artifact.

## Optional standalone build

From the repository root on Windows:

```text
.\gradlew.bat -p prototypes\paper-26_3-packet-template test
```

The repository root `gradlew test` does not include this project.
