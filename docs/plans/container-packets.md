# Implementation plan: container packets

## Objective and scope

Implement the Paper 26.2 and Paper 26.3 clientbound PLAY packet definitions
for the complete contiguous container group:

- ID `17` / `0x11`: `minecraft:container_close`
- ID `18` / `0x12`: `minecraft:container_set_content`
- ID `19` / `0x13`: `minecraft:container_set_data`
- ID `20` / `0x14`: `minecraft:container_set_slot`

The change must explicitly classify every packet, preserve the neutral
observer's live menu and inventory state, cover every structural wire variant
in version-local native-codec tests, and retain the complete neutral adapter
inventories. No legacy recording migration, compatibility layer, unrelated
packet implementation, shared API refactoring, or dependency change is in
scope.

All work remains on `codex/refactor-packet-capture-rewrite` as explicitly
required.

## Existing architecture and relevant behavior

The Paper adapters discover the native PLAY registry and overlay explicit
version-local definitions through `Paper26_2PacketCatalog` and
`Paper26_3PacketCatalog`. UI-owned definitions are collected by each version's
`packet/definition/ui/UiPacketDefinitions` class. IDs 14 through 16 already
establish the naming and registration convention that this group will extend.

`PacketDefinition.handlePlayback` applies playback scope before decoding. A
`VIEWER_LOCAL` definition therefore drops a recorded frame without invoking
the native codec. This is the required safety boundary for container packets:
their container IDs, state IDs and slot indices are meaningful only for the
recorded client's currently open menu and cannot be mapped onto an unrelated
observer session.

The identifiers are already present in `NeutralObserverPolicy.LOCAL`, and both
checkpoint encoders already recognize their descriptor names only to classify
the broader protocol inventory. No shared policy or checkpoint-family change
is necessary. The authoritative Paper `ChannelHandler` remains the capture
source and preserves the original bytes. PacketEvents remains only the
existing final emission path; it will not be used to infer, repair or
semantically fall back from any container payload.

## Sources and verified protocol model

The primary protocol source is the mapped native implementation in the exact
Paper development bundles pinned by the repository:

- Paper `26.2.build.135-stable`
- Paper `26.3.build.244-stable`

The following native types and codecs were inspected in both bundles:

- `ClientboundContainerClosePacket`
- `ClientboundContainerSetContentPacket`
- `ClientboundContainerSetDataPacket`
- `ClientboundContainerSetSlotPacket`
- `FriendlyByteBuf.readContainerId` / `writeContainerId`
- `ByteBufCodecs.CONTAINER_ID`
- `ItemStack.OPTIONAL_STREAM_CODEC`
- `ItemStack.OPTIONAL_LIST_STREAM_CODEC`
- `AbstractContainerMenu` state, slot and data-slot synchronization contracts

Paper's [Menu Type API documentation](https://docs.papermc.io/paper/dev/menu-type-api/)
provides supporting semantics: a menu is a player-bound view joining the
displayed container and the player's inventory, and different menu types can
represent different block, entity or stateless UI behavior. Paper's
[data-component documentation](https://docs.papermc.io/paper/dev/data-component-api/)
supports treating an item stack as more than an item/count pair: its component
patch can carry version-bound item data that must remain under the native
registry codec.

The inspected 26.2 and 26.3 wire layouts are identical. Tests will nevertheless
remain version-local because item registries, data-component codecs and native
packet registration are version-bound.

### Shared container and item primitives

All four packets use Minecraft's `ContainerID` codec. In both pinned versions
this delegates to the normal signed VarInt codec, rather than the historical
single-byte window-ID representation. Normal server-created menu IDs are
non-negative, while the native codec itself represents the full VarInt domain.
The framework must not introduce a legacy byte interpretation or silently
clamp values.

The two item-bearing packets use the registry-aware optional `ItemStack`
codec. An empty stack has its explicit optional representation; a non-empty
stack contains an item holder, count and component patch. Component data is an
open, version-specific contract and can include names, enchantments, container
contents and other registered data. The replay implementation will not parse
or partially reconstruct this payload, because doing so would risk losing
components and because the enclosing packet is viewer-local.

Modern 26.x also has dedicated `set_cursor_item` and
`set_player_inventory` packets. The implementation will not import old
negative-window/negative-slot shortcuts from older protocol revisions into
the meaning of `container_set_slot`. `container_set_content` still explicitly
contains the carried/cursor stack as part of a full menu synchronization.

### `minecraft:container_close`

The packet contains one field:

1. the VarInt container ID whose menu the client must close.

It changes which menu is displayed and consequently which live container
session owns later state IDs and slot updates. Replaying a recorded close could
close the observer's unrelated live screen or target a reused numeric ID.
Although the packet is small, the resulting closed-menu state persists until
another screen is opened, so it will use:

- disposition: `STATEFUL`;
- capture by default: yes;
- structurally replayable: yes, allowing live playback to reach the explicit
  definition policy boundary;
- checkpoint relevant: no;
- playback scope: `VIEWER_LOCAL`.

### `minecraft:container_set_content`

The packet contains, in order:

1. a VarInt container ID;
2. a VarInt state ID used by menu transaction/state synchronization;
3. a VarInt-sized list of optional item stacks, with list position defining
   the menu slot;
4. one optional carried/cursor item stack.

The item list may be empty or contain empty and non-empty stacks in any
combination. Non-empty stacks retain their counts and complete registered data
component patches. The carried stack independently supports empty and
non-empty variants.

The native packet advertises a large-packet fallback. If the encoded full
content packet exceeds the connection's allowed packet size, Paper/Minecraft
sends one `ClientboundContainerSetSlotPacket` for each list position using the
same container and state IDs. This is a transport fallback, not a second replay
policy: capture may legitimately observe either a full content packet or the
resulting sequence of slot packets, and each observed packet is handled by its
own explicit definition.

The packet replaces durable client-side contents for the identified live menu,
but that state is private to the recorded connection. It will use
`STATEFUL`, capture by default, replayable, not checkpoint relevant, and
`VIEWER_LOCAL`.

### `minecraft:container_set_data`

The packet contains:

1. a VarInt container ID;
2. a signed 16-bit property/data-slot ID;
3. a signed 16-bit property value.

The last two fields are deliberately generic. Their meaning is selected by the
currently open menu type and its ordered `DataSlot`/`ContainerData` contract.
Depending on that menu, they can describe values such as processing progress,
fuel/burn state, brewing state, selected options, costs, enchantment data or
other menu-specific integers. The packet alone does not identify the menu type
and therefore cannot safely attach one universal property enum or value range.
Both signed-short boundaries and negative values are structurally representable
by the native codec even where a particular menu applies a narrower semantic
domain.

Applying a recorded property update to the observer could mutate an unrelated
menu that happens to reuse the same container ID and property index. It will
therefore use `STATEFUL`, capture by default, replayable, not checkpoint
relevant, and `VIEWER_LOCAL`.

### `minecraft:container_set_slot`

The packet contains:

1. a VarInt container ID;
2. a VarInt state ID;
3. a signed 16-bit slot index;
4. one optional registry-aware item stack.

The stack may be empty or non-empty and can carry the full native data
component patch. The native constructor defensively copies the supplied stack.
Slot meaning and valid semantic range depend on the active menu's layout; the
wire codec itself represents the signed-short domain. State IDs order/synchronize
changes within the active menu and are not globally stable identities.

The native large-content fallback can produce this packet for every slot in a
full content list. Independently, ordinary menu synchronization uses it for
incremental changes. Both origins have the same observer-local policy. The
packet will use `STATEFUL`, capture by default, replayable, not checkpoint
relevant, and `VIEWER_LOCAL`.

## Architectural decision

Add four concrete, version-local definitions under the existing semantic UI
domain. Each class will extend `PacketDefinition` directly and will contain its
own ID, stable identifier, classification and packet-specific contract
documentation. No new abstraction is justified: all four implementations are
small policy boundaries, while their distinct semantics should remain visible
and independently changeable if a later Paper version diverges.

No definition will override decode, rewrite or rebuild hooks. The complete
safe behavior is to reject recorded container UI state before decoding. This
also means malformed, truncated, overlong or future-extended recorded payloads
cannot trigger registry decoding merely to be discarded afterward.

The two traffic paths remain intentionally separate:

- recorded IDs 17 through 20 are selected during live replay, reach their
  definition, and produce `DROP_LOCAL` before native decoding;
- genuine live container packets created for the observer pass through
  `ReplayViewerOutboundGate`, preserving the observer's current server-owned
  menu and inventory state.

All four packets are classified `STATEFUL`, not `EPHEMERAL`, because each
changes client state that remains effective until a later close, full sync,
property update or slot update. `VIEWER_LOCAL` still forces checkpoint
relevance to `false`.

## Planned production changes

For each of `paper-26_2` and `paper-26_3`:

1. Add `packet/definition/ui/Id017ContainerClosePacketDefinition.java` with
   `PACKET_ID = 0x11`, identifier `minecraft:container_close`, stateful
   viewer-local classification, and documentation of container-ID reuse and
   live-screen ownership.
2. Add `packet/definition/ui/Id018ContainerSetContentPacketDefinition.java`
   with `PACKET_ID = 0x12`, identifier
   `minecraft:container_set_content`, stateful viewer-local classification,
   and documentation of state ID, positional item list, carried item, data
   components and the large-packet slot fallback.
3. Add `packet/definition/ui/Id019ContainerSetDataPacketDefinition.java` with
   `PACKET_ID = 0x13`, identifier `minecraft:container_set_data`, stateful
   viewer-local classification, and explicit documentation that property ID
   and value are signed shorts whose semantics depend on the active menu type.
4. Add `packet/definition/ui/Id020ContainerSetSlotPacketDefinition.java` with
   `PACKET_ID = 0x14`, identifier `minecraft:container_set_slot`, stateful
   viewer-local classification, and documentation of state ID, signed slot,
   optional component-bearing stack and full-content fallback origin.
5. Register the four definitions in `UiPacketDefinitions` in numeric order
   directly after `Id016CommandsPacketDefinition`.

No capture handler, PacketEvents writer, shared policy, checkpoint encoder,
public interface, replay format or dependency will change.

## Planned tests

### Definition and policy contract

Add symmetric version-local tests in the existing `packet/definition/ui`
packages. They will verify for every definition:

- exact decimal/hexadecimal ID and stable type name;
- `STATEFUL` disposition, capture-by-default and replayable flags;
- absent checkpoint relevance and `VIEWER_LOCAL` scope;
- `handlePlayback` returns `DROP_LOCAL` without invoking decode or encode;
- empty, arbitrary, malformed, truncated and overlong payload bytes are
  dropped before native parsing;
- direct rewrite drops arbitrary decoded objects without runtime-type access;
- descriptor mismatches for type, ID, phase, direction, replay flag and scope
  fail before policy application;
- the base definition's null contracts remain intact.

### Native `container_close` codec coverage

For Paper 26.2 and 26.3 independently, use the actual PLAY protocol codec to
verify ID `0x11`, exact re-encoding and round trips for:

- the player/default container ID `0` and representative positive menu IDs;
- multi-byte VarInt IDs;
- native signed VarInt boundary values accepted by the codec;
- truncated and overlong VarInts;
- packet framing ownership when trailing bytes follow a complete payload.

Tests will distinguish wire representability from normal server-side menu-ID
allocation rather than imposing an adapter-only numeric restriction.

### Native `container_set_content` codec coverage

Use the native packet constructor and registry-aware PLAY codec to cover:

- empty and populated item lists;
- empty slots mixed with non-empty slots while preserving list position;
- empty and non-empty carried items independently of list contents;
- representative stack counts and multiple item types;
- a component-bearing stack so the full component patch is proven to survive
  encode/decode/re-encode;
- zero, positive, multi-byte and signed-boundary container/state VarInts
  accepted by the native codec;
- exact list ordering and canonical re-encoding;
- the packet's advertised large-packet fallback and its per-index slot-packet
  construction, using a controlled connection boundary where feasible without
  introducing server integration infrastructure;
- negative/impossible list lengths, truncated list entries, truncated item
  components and a missing carried item;
- trailing-byte framing behavior.

The fallback test will focus on the native packet contract and generated slot
sequence. It will not attempt to manufacture an oversized network write merely
to exercise Netty's global packet-size enforcement if that would require
unrelated connection scaffolding.

### Native `container_set_data` codec coverage

Verify ID `0x13`, exact re-encoding and round trips for:

- representative container IDs, including multi-byte VarInts;
- property ID `0`, positive IDs, `Short.MIN_VALUE`, `Short.MAX_VALUE` and
  negative values;
- property value `0`, positive values, `Short.MIN_VALUE`, `Short.MAX_VALUE`
  and negative values;
- independent variation of property ID and value;
- constructor integers outside the signed-short range, documenting native
  write-short truncation/canonicalization rather than adding hidden adapter
  validation;
- truncated container IDs and missing/truncated short fields;
- trailing-byte framing behavior.

The tests will not assign one semantic enum to the property field. Menu-type
meaning belongs to the native menu implementation and is intentionally
open-ended.

### Native `container_set_slot` codec coverage

Verify ID `0x14`, exact re-encoding and round trips for:

- representative and multi-byte container/state IDs;
- slot zero, positive slots and signed-short boundaries;
- an empty item stack;
- non-empty stacks with different item types/counts;
- a component-bearing stack;
- the constructor's defensive item-stack copy behavior;
- malformed/truncated VarInts, slot fields and optional stack/component data;
- trailing-byte framing behavior.

### Catalog, playback bridge and outbound gate

Update the symmetric integration tests for both adapters:

- `PacketCoverageTest` must include IDs 17 through 20 in numeric order and
  assert `STATEFUL`, replayable, non-checkpoint and `VIEWER_LOCAL` metadata;
- `Paper26_*PlaybackBridgeTest` must select all four during `LIVE` emission so
  the explicit definition drops them, and exclude them from
  `RECONSTRUCTION`;
- `ReplayViewerOutboundGateTest` must include all four identifiers in its
  viewer-local matrix, proving genuine observer traffic remains live.

## Adapter documentation

Update only rows 17 through 20 in the complete inventories:

- `docs/adapters/paper-26.2.md`
- `docs/adapters/paper-26.3.md`

The neutral descriptions will state:

- close identifies the live menu to close;
- set content replaces the indexed contents and carried stack for one menu
  state;
- set data updates a menu-type-specific signed property;
- set slot updates one signed slot index with an optional component-bearing
  stack for one menu state.

Every row retains playback policy `DROP_LOCAL` and checkpoint policy `no`.
The documentation will not mention implementation status, definition classes
or tests, and no packet rows will be removed.

## Version control and implementation sequence

After explicit approval:

1. Verify the local main branch against the live remote main branch without
   switching away from or modifying the required feature branch.
2. Add and register all eight version-local definition classes.
3. Add symmetric native-codec and definition-policy tests.
4. Update coverage, playback-bridge and outbound-gate integration tests.
5. Update the four neutral inventory rows in each adapter document.
6. Run focused adapter tests, formatting/diff checks and cross-version
   structural comparison.
7. Commit the cohesive implementation with an English semantic commit.
8. Remove this temporary plan and commit that documentation cleanup
   separately, following the established packet workflow.

## Validation

Run at minimum:

```text
.\gradlew.bat :replay-adapter:paper-common:test :replay-adapter:paper-26_2:test :replay-adapter:paper-26_3:test --console=plain
git diff --check
```

Additionally normalize version-specific package/class names and compare the
26.2 and 26.3 definition/test trees for structural symmetry. Confirm that the
native registry reports IDs 17 through 20 with the expected names, that only
task-related files enter commits, and that the existing untracked `.idea/`
directory remains untouched.

## Expected impact and limitations

- Recordings retain the exact container traffic for format fidelity and
  diagnostics, including item component data, but replay never applies it to
  the observer.
- The observer continues receiving current live menu close, content, property
  and slot updates from its own connection.
- Container/state/slot numbers are not rewritten because they are
  connection-local protocol state, not replay identities.
- No container UI is reconstructed in checkpoints; it is outside the replayed
  world scene and could not be restored safely without taking ownership of the
  observer's live screen.
- Native tests cover every packet-owned structural variant and representative
  registry-backed item data. They do not duplicate the open-ended universe of
  item data components or menu-specific property tables.
- No PacketEvents semantic fallback, migration, shared API change or new
  dependency is introduced.
