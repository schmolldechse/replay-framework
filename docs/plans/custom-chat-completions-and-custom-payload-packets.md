# Implementation plan: PLAY packets 23 and 24

## Objective and corrected scope

Implement the next two clientbound PLAY packet definitions for both pinned
Paper adapters, without changing the recording format or introducing legacy
compatibility:

| Decimal ID | Hex ID | Protocol identifier | Native packet |
|---:|---:|---|---|
| 23 | `0x17` | `minecraft:custom_chat_completions` | `ClientboundCustomChatCompletionsPacket` |
| 24 | `0x18` | `minecraft:custom_payload` | `ClientboundCustomPayloadPacket` |

The request lists `minecraft:custom_chat_completions` for both IDs. The pinned
Paper 26.2 and 26.3 PLAY registries and the existing complete adapter
inventories identify ID 24 as `minecraft:custom_payload`; the implementation
will use that registry-owned identifier. Both versions keep these IDs, although
their native codec implementations differ internally.

All implementation work remains on
`codex/refactor-packet-capture-rewrite`. The pre-existing untracked `.idea/`
directory is outside the task and will remain untouched.

## Evidence and protocol contracts

The protocol analysis is based on the exact dependencies already pinned by the
project:

- Paper `26.2.build.135-stable`, Minecraft protocol 776;
- Paper `26.3.build.244-stable`, Minecraft protocol 777;
- the mapped Mojang sources and Paper patches contained in both development
  bundles;
- the official Paper 26.2/26.3 API sources and Javadocs;
- the official Paper plugin-messaging documentation.

Relevant official references:

- [Paper 26.2 Player API](https://jd.papermc.io/paper/26.2/org/bukkit/entity/Player.html)
  documents the add, remove and replace semantics for custom chat completions;
- [Paper plugin messaging](https://docs.papermc.io/paper/dev/plugin-messaging/)
  documents channel registration, byte-array payloads and their connection-bound
  behavior;
- [Paper PluginMessageRecipient API](https://jd.papermc.io/paper/26.1.1/org/bukkit/plugin/messaging/PluginMessageRecipient.html)
  documents outgoing channel registration and the message-size contract.

### ID 23: custom chat completions

The wire body consists of:

1. an action encoded as a VarInt enum ID:
   - `0`: `ADD`;
   - `1`: `REMOVE`;
   - `2`: `SET`;
2. a VarInt-sized ordered list of UTF-8 strings, each using Minecraft's normal
   maximum string length of 32,767 characters.

The actions alter the receiving client's persistent custom suggestion set.
`ADD` adds the supplied entries, `REMOVE` removes previously added custom
entries, and `SET` replaces the complete custom set. Online player-name
suggestions are separate and cannot be removed through this packet. Empty
lists, duplicate values, ordering, arbitrary valid UTF-8 content and repeated
action sequences are all valid wire-level cases and must be represented by the
tests.

Paper 26.2 decodes the action through `FriendlyByteBuf.readEnum`, which rejects
out-of-range ordinals. Paper 26.3 uses an ID mapper whose native
out-of-bounds strategy maps unknown IDs to the first action (`ADD`). The
version-specific tests will preserve and explicitly document this native
difference instead of imposing a shared interpretation.

The packet is receiver-local: replaying it would contaminate the observer's
own chat suggestions, while suppressing genuine live packets would prevent the
observer's server from maintaining its suggestions. Recorded frames must
therefore be retained but dropped before native decoding during playback.

### ID 24: custom payload

The wire body consists of:

1. a Minecraft `Identifier` naming the payload channel;
2. all remaining bytes in that framed packet as channel-defined payload data.

The gameplay codec has two important paths:

- `minecraft:brand` uses the native `BrandPayload` string codec;
- every other channel uses Paper's patched `DiscardedPayload`, which retains
  and re-encodes the opaque bytes rather than discarding them.

The gameplay payload limit is 1,048,576 bytes. Paper's plugin messaging API
uses the same maximum for outgoing plugin messages. Channel identifiers follow
Minecraft identifier validation and Paper reserves the `minecraft` namespace
except for supported channels such as `minecraft:brand`. The definition must
not interpret arbitrary payload bytes: their schema belongs to the selected
plugin/mod/proxy channel and can include state changes, requests, UI behavior,
or other client-side effects.

Replaying a captured payload into a neutral observer is unsafe even when the
channel names match: the observer may have a different mod/plugin-channel
handshake, payload schema, client state or no listener at all. The packet is
therefore captured as raw evidence but dropped before decoding and never
checkpointed. Genuine live custom payloads must continue through the outbound
gate unchanged.

## Production changes

### Packet definition for ID 23

Add one version-local class per adapter:

```text
paper-26_2/.../packet/definition/chat/Id023CustomChatCompletionsPacketDefinition.java
paper-26_3/.../packet/definition/chat/Id023CustomChatCompletionsPacketDefinition.java
```

Each definition will expose ID `0x17` and identifier
`minecraft:custom_chat_completions`, and use:

- disposition: `STATEFUL`, because ADD/REMOVE/SET maintain a client-side set;
- capture by default: yes;
- replayable descriptor: yes, so recorded frames reach the packet-local policy;
- playback scope: `VIEWER_LOCAL`, causing a drop before decode;
- checkpoint relevance: no, as viewer-local observer state must not be restored.

The class documentation will explain the three action semantics, the separation
from player-name suggestions and why the recorded connection's set must not be
applied to the observer.

The `chat` domain currently has no implemented definitions. Add matching
`ChatPacketDefinitions` aggregators in both versions, then include those
aggregators in `Paper26_2PacketCatalog` and `Paper26_3PacketCatalog`. This is the
smallest architecture-consistent addition prescribed by
`docs/packet-definition-layout.md`.

### Packet definition for ID 24

Add one version-local class per adapter:

```text
paper-26_2/.../packet/definition/connection/Id024CustomPayloadPacketDefinition.java
paper-26_3/.../packet/definition/connection/Id024CustomPayloadPacketDefinition.java
```

Each definition will expose ID `0x18` and identifier
`minecraft:custom_payload`, and use:

- disposition: `CONFIGURABLE`, matching the established disposition contract
  for custom, user/plugin-controlled presentation data;
- capture by default: yes;
- replayable descriptor: yes, so the explicit local policy owns recorded data;
- playback scope: `VIEWER_LOCAL`, causing a drop before any opaque data is
  parsed or allocated by the native payload codec;
- checkpoint relevance: no.

The class documentation will distinguish `minecraft:brand` from arbitrary
plugin/mod channels, describe the opaque remainder-of-frame payload and explain
why neither channel names nor bytes can be safely transferred to another
connection.

Register the definition in each existing `ConnectionPacketDefinitions`
aggregator. No shared payload abstraction will be added because playback must
not inspect or rewrite an unknown channel schema.

## Capture, playback and checkpoint boundaries

No changes are planned to the authoritative Paper ChannelHandler capture path,
the PacketEvents final-write/fallback integration, the frame format, or the
generic codec. Both packets already flow through the same registry-driven raw
capture mechanism as other explicit definitions.

No packet-specific rewriter is needed. `PacketDefinition.handlePlayback`
enforces `VIEWER_LOCAL` before calling the native decoder, which is important
for malformed chat lists and attacker-controlled or version-specific opaque
payloads. `rewrite` likewise drops an already decoded value without inspecting
its runtime type.

Both identifiers already belong to `NeutralObserverPolicy.LOCAL`. The
version-specific outbound-gate tests will be extended so genuine live packets
for both definitions are demonstrably forwarded rather than suppressed.

Neither packet will be added to a checkpoint encoder. Chat suggestions and
plugin-channel state belong to the recorded connection, not to reconstructible
world state.

## Dedicated tests

Every packet receives its own test class in every supported version; tests will
not be combined:

```text
paper-26_2/.../packet/definition/chat/Id023CustomChatCompletionsPacketDefinitionTest.java
paper-26_2/.../packet/definition/connection/Id024CustomPayloadPacketDefinitionTest.java
paper-26_3/.../packet/definition/chat/Id023CustomChatCompletionsPacketDefinitionTest.java
paper-26_3/.../packet/definition/connection/Id024CustomPayloadPacketDefinitionTest.java
```

### ID 23 test coverage

Each version-specific test will verify:

- exact numeric ID, identifier, native packet class and PLAY registry mapping;
- classification, registration exactly once in the `chat` domain and final
  catalog;
- drop-before-decode/encode for empty, valid, malformed, truncated and
  overlong recorded bodies;
- direct rewrite behavior and null/descriptor mismatch boundaries;
- all three actions and empty/single/multiple entry lists;
- entry order, duplicates and repeated ADD/REMOVE/SET packet sequences;
- ASCII, multibyte UTF-8 and string-length/prefix boundaries that can be tested
  without excessive allocation;
- valid list-count VarInt boundaries and rejection of truncated, negative,
  impossible or overlong counts;
- malformed UTF-8, truncated strings, overlong VarInts and trailing-body
  validation;
- the native version difference for unknown action IDs: rejection in 26.2 and
  fallback to `ADD` in 26.3.

### ID 24 test coverage

Each version-specific test will verify:

- exact numeric ID, identifier, native packet class and PLAY registry mapping;
- `CONFIGURABLE` viewer-local classification, registration exactly once in the
  connection domain and final catalog;
- drop-before-decode/encode for arbitrary, malformed, truncated and oversized
  recorded bodies;
- direct rewrite behavior and null/descriptor mismatch boundaries;
- `minecraft:brand` round trips, including empty, ASCII and multibyte values;
- arbitrary valid custom namespaces and channel identifier length-prefix
  boundaries;
- opaque payload preservation for empty data, embedded zero bytes, every byte
  value, representative sizes and the 1,048,576-byte maximum;
- rejection of a 1,048,577-byte payload, invalid identifiers, malformed UTF-8,
  truncated identifiers and overlong length VarInts;
- the remainder-of-frame rule: all bytes after the channel identifier belong
  to that payload, so separation from a following packet is the surrounding
  frame decoder's responsibility;
- independent decoding of repeated payload frames and no semantic assumptions
  about registration, proxy or mod channels.

Large boundary buffers will be released deterministically in `finally` blocks
to keep Netty leak behavior predictable.

## Integration and documentation updates

Update both `PacketCoverageTest` classes to include IDs 23 and 24 in catalog
order and assert their exact disposition, replayability, checkpoint and
playback-scope policies.

Update both `ReplayViewerOutboundGateTest` classes with
`custom_chat_completions` and `custom_payload` in the viewer-local matrix.

Update only rows 23 and 24 in:

- `docs/adapters/paper-26.2.md`;
- `docs/adapters/paper-26.3.md`.

The inventories remain complete and neutral. Their descriptions will state the
packet purpose, replay relevance and `DROP_LOCAL`/non-checkpoint policy without
mentioning implementation progress or tests.

No public API, recording schema, migration, dependency, capture handler,
PacketEvents listener or checkpoint format changes are required.

## Verification after approval

Before implementation, verify the live remote `master` revision against local
`master` and cached `origin/master` without switching away from or modifying
the required feature branch.

After implementation, run:

1. the four dedicated definition test classes;
2. both versions' `PacketCoverageTest`;
3. both versions' `ReplayViewerOutboundGateTest`;
4. the complete `paper-common`, `paper-26_2` and `paper-26_3` test suites;
5. `git diff --check`;
6. a final status and diff-scope review confirming that only task files and
   this temporary plan changed.

The feature changes will be committed with an English semantic commit. Once
the implementation is complete, this temporary plan will be removed in a
separate documentation commit, following the established workflow on this
branch. Nothing will be pushed unless explicitly requested.
