# Implementation plan: command suggestions and commands packets

## Objective and scope

Implement the Paper 26.2 and Paper 26.3 clientbound PLAY packet definitions for:

- ID `15` / `0x0F`: `minecraft:command_suggestions`
- ID `16` / `0x10`: `minecraft:commands`

The implementation must explicitly classify both packet families, preserve the
neutral observer's live command UI and command tree, cover their native wire
contracts in version-local tests, and keep the adapter inventories complete.
No legacy recording compatibility, migration, unrelated packet work, or shared
API refactoring is part of this change.

All implementation work remains on
`codex/refactor-packet-capture-rewrite`.

## Sources and verified protocol model

The implementation is based primarily on the exact development bundles pinned
by this repository:

- Paper `26.2.build.135-stable`
- Paper `26.3.build.244-stable`

Inspection of the mapped native classes
`ClientboundCommandSuggestionsPacket`,
`ClientboundCommandSuggestionsPacket.Entry`, and
`ClientboundCommandsPacket` confirms the packet IDs, codecs, fields, flags and
validation used by each supported adapter. The two versions have the same wire
model. Paper 26.3 changes the internal composition of the commands stream codec,
but not the serialized fields.

Supporting semantic references:

- [Mojang Brigadier](https://github.com/Mojang/brigadier) defines the command
  tree, executable nodes, redirects, argument nodes and suggestion model used by
  Minecraft.
- [Mojang's `Suggestion` implementation](https://github.com/Mojang/brigadier/blob/master/src/main/java/com/mojang/brigadier/suggestion/Suggestion.java)
  defines replacement ranges, suggestion text and optional tooltip semantics.
- [Paper command-tree documentation](https://docs.papermc.io/paper/dev/command-api/basics/command-tree/)
  documents the root/child structure and literal/argument branches used by the
  server's Brigadier integration.
- [Paper argument-suggestion documentation](https://docs.papermc.io/paper/dev/command-api/basics/argument-suggestions/)
  documents how server-side suggestion providers produce client suggestions.

### `minecraft:command_suggestions` wire contract

The native packet is a record containing:

1. a VarInt transaction ID correlating the response to a command-suggestion
   request;
2. a VarInt start offset;
3. a VarInt replacement length;
4. a VarInt-sized list of suggestion entries;
5. per entry, a UTF-8 string and an optional trusted chat component tooltip.

The start and length form the Brigadier `StringRange` that is replaced when the
suggestion is applied. The list may be empty or contain multiple entries.
Tooltip absence and presence are both valid. The payload can reveal the exact
completion candidates offered to the recorded player and rich tooltip content;
the transaction ID and replacement range also expose which response belonged
to the recorded client's active input state.

This is a transient response to one request, not durable world state. It will
therefore use:

- disposition: `EPHEMERAL`;
- capture by default: yes;
- structurally replayable: yes, so live playback reaches the explicit packet
  policy boundary;
- checkpoint relevant: no;
- playback scope: `VIEWER_LOCAL`.

Replaying the response cannot be made safe by changing its transaction ID:
the observer may have no matching pending request, a different input string,
or a newer request with the same numeric ID. Recorded completions and tooltips
must never replace the observer's current command UI.

### `minecraft:commands` wire contract

The native packet serializes a VarInt-sized node list followed by the VarInt
index of the root node. Each node contains:

1. one flag byte;
2. a VarInt array of child-node indices;
3. an optional VarInt redirect-node index;
4. node-type-specific data.

The low two flag bits encode the node type:

- `0`: root;
- `1`: literal;
- `2`: argument.

The remaining native flags describe:

- `0x04`: executable node;
- `0x08`: redirect index present;
- `0x10`: custom suggestions identifier present on an argument node;
- `0x20`: restricted node.

Literal nodes add their UTF-8 literal. Argument nodes add their UTF-8 name, a
VarInt entry from Minecraft's command-argument-type registry, the argument
type's own variable network properties, and—when flagged—a suggestion-provider
identifier. Root nodes add no type-specific fields. The decoder validates that
children and redirects can be resolved into a possible tree; an invalid graph
is rejected as an impossible command tree.

The packet replaces the receiving client's available command tree. Its content
depends on the player's permissions and server/plugin command registration, and
can disclose available literals, argument shapes, restrictions and suggestion
providers. It is durable for the connection until another commands packet
replaces it, but it is not replay-scene state. It will use:

- disposition: `STATEFUL`;
- capture by default: yes;
- structurally replayable: yes, so live playback reaches the explicit packet
  policy boundary;
- checkpoint relevant: no;
- playback scope: `VIEWER_LOCAL`.

The recorded command tree must not replace the neutral observer's live tree.
In particular, replaying it could expose commands unavailable to the observer,
hide live commands, install stale argument schemas, or desynchronize client-side
parsing and completion from the live server.

## Architectural decision

Both definitions will extend the plain version-local `PacketDefinition` and
will not implement payload decoding, rewriting or rebuilding.

`PacketDefinition.handlePlayback` applies `VIEWER_LOCAL` before invoking the
adapter codec. This is the complete safe contract for these packets: every
recorded payload, including malformed or future-extended payloads, is dropped
without parsing. Decoding these registry- and component-sensitive structures
would add failure modes without producing a safe replay form.

This design deliberately keeps two paths separate:

- recorded packets selected during live playback reach the definition and
  become `DROP_LOCAL` before decode;
- genuine live `commands` and `command_suggestions` packets produced for the
  neutral observer pass through `ReplayViewerOutboundGate`, because
  viewer-local traffic remains owned by the live connection.

Both identifiers already exist in `NeutralObserverPolicy.LOCAL`; no API policy
change is required. The authoritative Paper channel capture and PacketEvents
final writer remain unchanged. Capture continues to retain the original wire
payload, while PacketEvents is only the existing final emission mechanism and
is not used as a semantic fallback for either definition.

## Planned production changes

For each of `paper-26_2` and `paper-26_3`:

1. Add
   `packet/definition/ui/Id015CommandSuggestionsPacketDefinition.java` with:
   - `PACKET_ID = 0x0F`;
   - `IDENTIFIER = "minecraft:command_suggestions"`;
   - `PacketClassification.ephemeral(VIEWER_LOCAL)`;
   - documentation of transaction correlation, replacement range, entries,
     optional tooltips and why all recorded variants are dropped.
2. Add
   `packet/definition/ui/Id016CommandsPacketDefinition.java` with:
   - `PACKET_ID = 0x10`;
   - `IDENTIFIER = "minecraft:commands"`;
   - `PacketClassification.stateful(VIEWER_LOCAL)`;
   - documentation of the graph, node/flag variants, permission sensitivity,
     argument-type registry dependency and observer-isolation reason.
3. Register both definitions in `UiPacketDefinitions` in numeric order after
   `Id014ClearTitlesPacketDefinition`.

No shared abstraction will be introduced: the definitions are intentionally
small, and version-local ownership keeps future protocol changes isolated.

## Planned tests

### Definition and policy tests

Add version-local tests for both packet definitions in the existing `ui`
test packages. Each test suite will verify:

- exact decimal and hexadecimal ID, stable type name, disposition, capture,
  replay, checkpoint and playback-scope metadata;
- `handlePlayback` returns `DROP_LOCAL` without invoking decode or encode;
- empty, arbitrary, malformed and overlong payload bytes are dropped before
  native parsing;
- direct rewrite drops an arbitrary decoded object without inspecting its
  runtime type;
- mismatched type, ID, phase, direction, replay flag or playback scope fails
  before policy is applied;
- null boundaries retain the base definition's contract.

### Native `command_suggestions` codec coverage

For Paper 26.2 and 26.3 independently, use the actual PLAY protocol codec to
verify packet ID `0x0F` and round trips for:

- an empty suggestion list;
- one and multiple suggestions;
- zero and non-zero replacement ranges;
- absent and present rich-component tooltips;
- UTF-8 suggestion text;
- representative VarInt boundary values accepted by the native record codec;
- canonical list/optional encoding;
- rejection of truncated VarInts, missing list entries, malformed strings and
  incomplete optional tooltips;
- packet framing ownership: trailing bytes are not incorrectly attributed to
  the packet's field codec.

Where the native codec intentionally accepts structurally unusual signed range
values without semantic validation, tests will document that behavior rather
than adding stricter replay-framework rules that are never reached for a
viewer-local packet.

### Native `commands` codec coverage

For each version, build representative Brigadier trees and round-trip them
through the actual PLAY protocol codec at ID `0x10`. Coverage will include:

- a root-only tree;
- literal and argument nodes;
- executable and non-executable nodes;
- restricted and unrestricted nodes;
- child arrays and multiple branches;
- redirects;
- argument types with no extra properties and argument types with serialized
  bounds/options;
- absent and present custom suggestion-provider identifiers;
- correct root-index handling;
- malformed/truncated node data, invalid node references and impossible graph
  rejection;
- trailing-byte framing behavior.

Tests will use the native constructor and stream codec rather than duplicating
Minecraft's argument-type serializer. This exercises every structural variant
owned by the packet while leaving the open-ended set of registered argument
types to the version-pinned native registry implementation.

### Catalog, bridge and gate integration

Update the symmetric 26.2 and 26.3 integration tests to verify:

- `PacketCoverageTest` lists IDs 15 and 16 after ID 14 and assigns
  `EPHEMERAL` to suggestions, `STATEFUL` to commands, and `VIEWER_LOCAL` with
  no checkpoint relevance to both;
- `Paper26_*PlaybackBridgeTest` selects both during `LIVE` emission so the
  definition can drop them, but excludes both from `RECONSTRUCTION`;
- `ReplayViewerOutboundGateTest` includes both identifiers in its viewer-local
  matrix, proving genuine live packets continue to reach the observer.

## Adapter documentation

Update only the rows for IDs 15 and 16 in both complete adapter inventories:

- `docs/adapters/paper-26.2.md`
- `docs/adapters/paper-26.3.md`

The descriptions will neutrally state the payload and relevance:

- command suggestions correlate an input request with a replacement range,
  completion texts and optional tooltips;
- commands installs the permission-sensitive Brigadier command tree, including
  literals, typed arguments, redirects, restrictions and suggestion providers.

Both rows retain playback policy `DROP_LOCAL` and checkpoint policy `no`.
The documentation will not mention implementation progress, class names or
test status, and all other packet rows remain intact.

## Version control and implementation sequence

After approval:

1. Verify the local main branch against the current remote main branch without
   switching away from or modifying the required feature branch.
2. Implement both version-local definitions and registration changes.
3. Add the symmetric native and integration tests.
4. Update the two adapter inventory rows.
5. Run focused adapter tests and consistency checks.
6. Commit the cohesive implementation with an English semantic commit.
7. Remove this temporary implementation plan and commit that documentation
   cleanup separately, matching the established packet workflow.

## Validation

Run at minimum:

```text
.\gradlew.bat :replay-adapter:paper-common:test :replay-adapter:paper-26_2:test :replay-adapter:paper-26_3:test --console=plain
git diff --check
```

Also compare the two version trees after normalizing version-specific names to
ensure the definitions, tests and catalog changes remain structurally
symmetric. Finally verify that only task-related files are committed and that
the existing untracked `.idea/` directory is untouched.

## Expected impact and limitations

- Recorded command completions and command trees remain captured for format
  fidelity and diagnostics but never mutate the observer.
- The observer continues receiving current live command state and completions.
- Neither packet contributes to checkpoints because both belong to the client
  connection rather than the replayed scene.
- No PacketEvents fallback semantics, identity rewriting, shared API changes,
  format migration or dependency changes are introduced.
- Native wire tests are intentionally duplicated per adapter because argument
  registries, component codecs and codec composition are version-bound even
  where the current wire layout matches.
