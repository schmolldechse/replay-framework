# Implementation plan: `cookie_request`

## Objective and scope

Implement the clientbound PLAY packet definition for ID `21` / `0x15`,
`minecraft:cookie_request`, for both pinned adapters:

- Paper `26.2.build.135-stable`
- Paper `26.3.build.244-stable`

The implementation must classify the packet explicitly, document its complete
request/response semantics, prevent recorded requests from interacting with an
observer's current connection, and verify the complete packet-owned wire
contract against each version's native codec. Each version receives a separate
test class dedicated exclusively to this packet.

No legacy-recording migration, old-system compatibility, unrelated packet
implementation, public API change, dependency change, or shared architectural
refactoring is in scope. All work remains on
`codex/refactor-packet-capture-rewrite` as explicitly required.

## Existing architecture and affected boundaries

The version-specific Paper registries discover the native protocol and overlay
explicit definitions from `Paper26_2PacketCatalog` and
`Paper26_3PacketCatalog`. Connection-owned definitions are collected by each
version's `packet/definition/connection/ConnectionPacketDefinitions` class.
The existing definitions for `bundle_delimiter`, `chunk_batch_finished`, and
`chunk_batch_start` establish the correct domain and the blocked-control
pattern.

`AbstractBlockedPacketDefinition` assigns the shared control classification:

- disposition: `CONTROL`;
- capture by default: yes;
- replayable: no;
- checkpoint relevant: no;
- playback scope: `UNSAFE`.

The registry and playback bridge reject such a descriptor before packet-local
decode or rewrite can run. Capturing the exact outbound frame remains useful
for recording fidelity, validation, and diagnostics, but the recorded request
can never be emitted into an established observer connection.

The authoritative Paper channel handler remains the capture source. The
PacketEvents path remains the existing final write mechanism only; it will not
be used to decode, infer, regenerate, or semantically fall back from a cookie
request.

## Sources and verified protocol model

The primary source is the mapped native code in the exact Paper development
bundles pinned by the repository. The following types and methods were
inspected in both versions:

- `ClientboundCookieRequestPacket`;
- `ServerboundCookieResponsePacket`;
- `ClientboundStoreCookiePacket.PAYLOAD_STREAM_CODEC`;
- `FriendlyByteBuf.readIdentifier` and `writeIdentifier`;
- `Identifier.parse` and the identifier namespace/path validation;
- the clientbound PLAY protocol registration in `GameProtocols`.

The Paper API's
[`ReadablePlayerCookieConnection`](https://jd.papermc.io/paper/1.21.8/io/papermc/paper/connection/ReadablePlayerCookieConnection.html)
provides the public semantic contract: retrieving a cookie completes
asynchronously when the response arrives, and an unset cookie produces a null
result. This supports treating the packet as a connection-correlated request,
not replayable scene data.

The inspected 26.2 and 26.3 native implementations have the same field layout
and response contract. Tests remain version-local because packet IDs,
registrations, codecs, and native classes are version-bound.

## Complete packet contract

### Request wire layout

The PLAY clientbound packet contains exactly one field after packet ID `0x15`:

1. `key`: a Minecraft `Identifier`, encoded as the protocol's length-prefixed
   UTF-8 string and parsed with the native identifier validator.

There is no request ID, payload, size field, or cookie value in the request.
The key identifies which client-side cookie should be returned. It can use the
default `minecraft` namespace or an explicit valid namespace, and its path can
contain the complete character set accepted by the native `Identifier`
contract. The decoder applies the native UTF/string limits and rejects invalid
namespace/path characters, malformed UTF-8, truncated input, and oversized
strings. A textual key without an explicit namespace is normalized by
`Identifier.parse`; re-encoding therefore uses the canonical qualified form.
The adapter must not introduce a second key grammar, reduce the accepted key
space, or preserve a non-canonical textual spelling after native decoding.

### Correlated response behavior

Receiving the packet causes the client to send a
`ServerboundCookieResponsePacket` carrying:

1. the same identifier key;
2. a nullable payload.

A null payload means that no cookie is stored for the key. A present payload
may be empty or contain up to 5,120 bytes, enforced by the shared native cookie
payload codec. Repeated requests and concurrent requests are correlated by the
key and current connection state; the request has no independent transaction
number. This packet does not itself reveal the cookie value in the clientbound
recording, but replaying it would actively solicit persisted client data and
could satisfy, confuse, or race a live server-side cookie retrieval.

The same native cookie request concept is usable in multiple protocol phases,
with phase-specific packet IDs and descriptors. This task implements only the
PLAY clientbound descriptor at ID `21`. Existing configuration/login inventory
rows and policies remain independent and must not be folded into the PLAY
definition.

### Playback and checkpoint policy

The packet changes no replayable world, entity, UI, or player state. Its sole
effect is a request/response exchange owned by the receiving connection. A
recorded request must therefore never be injected into an observer connection,
even when the key appears harmless or no cookie exists.

The definition will use the existing blocked-control contract:

- capture the original frame by default;
- do not decode or inspect it during playback;
- never select it for live or reconstruction playback;
- never include it in a checkpoint;
- classify it as unsafe if it reaches the rewrite boundary.

There is no safe key-rewrite strategy. Replacing the namespace or key would
turn the recorded request into a different live request, while preserving it
would still query observer-owned persisted data. There is likewise no useful
checkpoint representation because the request is transient connection flow,
not durable replay state.

## Architectural decision

Add one concrete version-local class per adapter in the existing connection
domain:

- `Id021CookieRequestPacketDefinition`

Each class will extend `AbstractBlockedPacketDefinition` and define
`PACKET_ID = 0x15` plus identifier `minecraft:cookie_request`. Its Javadoc will
state that the packet asks the current client for the cookie stored under one
identifier, that the response can be absent or carry bounded data, and that
replaying a captured request could disclose or interfere with live
connection-owned data.

No packet-specific decode, encode, rewrite, or rebuild hook is appropriate.
The native codec belongs in tests for structural verification, while the
production policy intentionally blocks the descriptor before decoding. A new
abstraction would add no behavior beyond the existing blocked-control base
class.

## Planned production changes

For `paper-26_2` and `paper-26_3` independently:

1. Add
   `packet/definition/connection/Id021CookieRequestPacketDefinition.java`.
2. Register the definition in `ConnectionPacketDefinitions` after ID `12`.
   The catalog performs global numeric sorting, while the domain list remains
   locally ordered by packet ID.

No capture handler, PacketEvents writer, shared packet API, neutral-observer
policy, checkpoint encoder, replay format, or dependency will change.

## Planned tests

### Dedicated test files

To satisfy the one-packet-per-test requirement, add exactly one dedicated test
class in each version module:

- Paper 26.2:
  `packet/definition/connection/Id021CookieRequestPacketDefinitionTest.java`
- Paper 26.3:
  `packet/definition/connection/Id021CookieRequestPacketDefinitionTest.java`

No combined `Id...And...` or range-based test file will be introduced. The
two files will be structurally symmetric apart from version-specific package,
adapter, and codec names.

### Definition and safety contract

Each dedicated test will verify:

- decimal ID `21`, hexadecimal constant `0x15`, and exact type name;
- `CONTROL` disposition, capture-by-default, non-replayable,
  non-checkpoint, and `UNSAFE` metadata;
- empty, arbitrary, malformed, truncated, and overlong recorded payload bytes
  cannot invoke the playback codec because the descriptor is rejected first;
- already-decoded arbitrary values cannot enter a rewrite hook;
- descriptor mismatches and null contracts fail at the established boundary;
- the definition is present exactly once in its connection-domain collection.

### Native request codec coverage

Using each module's actual clientbound PLAY protocol codec, the dedicated test
will verify packet ID `0x15`, field ownership, exact consumption, and canonical
round trips for:

- the default `minecraft` namespace;
- explicit custom namespaces;
- paths containing every native-valid separator category such as `/`, `.`,
  `_`, and `-`;
- short keys and representative multi-byte length-prefix boundaries;
- the maximum wire-string boundary accepted by `readIdentifier` where
  practical, plus the first oversized case;
- a wire spelling without a namespace and its canonical qualified
  re-encoding;
- repeated instances and different keys, proving that the packet contains no
  hidden request ID or state;
- missing input, truncated length prefixes, declared lengths larger than the
  available bytes, malformed UTF-8, invalid namespace/path characters, and
  over-limit input;
- framing behavior when bytes remain after a complete packet.

The tests will distinguish the packet-owned identifier from its correlated
response: no request payload bytes may be silently consumed as part of the
request.

### Native response-contract coverage

Because the safety decision depends on what the request elicits, each dedicated
test will also exercise the native `ServerboundCookieResponsePacket` stream
codec directly for the same key and verify:

- null payload for a missing cookie;
- present empty payload;
- representative non-empty binary payload, including zero bytes;
- the exact 5,120-byte maximum;
- rejection of a 5,121-byte payload;
- preservation of the response key.

These assertions document the full effect of the request without adding
serverbound recording or playback behavior to the production scope.

### Catalog and playback integration

Update the symmetric integration tests for both adapters:

- `PacketCoverageTest` includes `cookie_request` immediately after
  `container_set_slot` and asserts its control/unsafe, captured,
  non-replayable, non-checkpoint contract;
- `Paper26_*PlaybackBridgeTest` extends the existing blocked connection-flow
  coverage to ID `21`, proving exclusion from both `LIVE` and
  `RECONSTRUCTION` emission;
- the outbound-gate tests retain and, if needed, explicitly exercise the
  existing rule that genuine current-server control traffic is forwarded.
  This allows a legitimate live cookie request to reach the observer while
  recorded requests remain excluded before emission.

## Adapter documentation

Update only PLAY row `21` in both complete neutral inventories:

- `docs/adapters/paper-26.2.md`
- `docs/adapters/paper-26.3.md`

The description will explain that the packet requests the client-side cookie
under one namespaced key and elicits a nullable, size-bounded response owned by
the current connection. Playback remains `BLOCKED` and checkpoint policy
remains `no`.

The documents will continue to list every packet for their version. They will
not mention implementation status, Java classes, test coverage, or development
progress. The same identifier in other protocol-phase tables remains unchanged
unless correcting a directly inconsistent semantic description is required;
such a change would be proposed separately before implementation.

## Version-control and implementation sequence

After explicit approval:

1. Verify the local main branch against the live remote main branch without
   switching away from or modifying the required feature branch.
2. Add and register the two version-local definitions.
3. Add the two dedicated, single-packet native-codec and policy test classes.
4. Update catalog and playback integration tests.
5. Update PLAY row `21` in both neutral adapter inventories.
6. Run focused tests, the complete relevant adapter suite, diff checks, and a
   cross-version structural comparison.
7. Commit the cohesive implementation with an English semantic commit.
8. Remove this temporary plan and commit that documentation cleanup
   separately, following the established packet workflow.

## Validation

Run at minimum:

```text
.\gradlew.bat :replay-adapter:paper-common:test :replay-adapter:paper-26_2:test :replay-adapter:paper-26_3:test --console=plain
git diff --check
```

Additionally:

- compare normalized 26.2 and 26.3 definition/test sources for structural
  symmetry;
- confirm native discovery reports PLAY clientbound ID `21` as
  `minecraft:cookie_request` in both versions;
- confirm the dedicated test filenames contain no other packet definition;
- confirm no request codec is invoked through recorded playback;
- confirm only task-related files enter the commits;
- leave the existing untracked `.idea/` directory untouched.

## Expected impact and limitations

- Recordings preserve the exact outbound request key for fidelity and
  diagnostics, but playback cannot query the observer's cookie store.
- Legitimate cookie requests from the observer's current server continue to
  pass through the live outbound channel.
- Cookie values are not added to clientbound recordings; the response is
  serverbound and remains outside this task's capture direction.
- No key rewriting, request replay, checkpoint state, migration, or
  PacketEvents semantic fallback is introduced.
- Tests cover every request-owned field and wire shape plus every response
  payload presence/size variant relevant to the request's effect. They do not
  simulate the proprietary client-side persistence implementation or add a
  full client/server integration harness.
