# Paper 26.2 update and Paper 26.3 adapter plan

Status: approved and implemented on `codex/verify-paper-26-3`.

## Objective

Update the existing Paper 26.2 integration to its latest stable build, add a
separate Paper 26.3 adapter, and remove the runtime's fixed dependency on 26.2.
Both adapters must preserve the abstract adapter boundary and keep a playback
viewer neutral: a replay may render its scene, but it must not replace the
viewer's health, inventory, movement, abilities, camera, menus, HUD, identity,
or other connection-local state.

## Architecture

- `replay-adapter:api` owns `AbstractReplayAdapter`, the provider contract, and
  neutral-observer policy without importing NMS classes.
- `paper-26_2` and `paper-26_3` each own their protocol discovery, native codec,
  capture bridge, checkpoint generation, playback rewriting, and reset logic.
- Each adapter publishes a `PaperAdapterProvider` through `ServiceLoader`.
- The runtime selects the provider whose Minecraft version exactly matches the
  running Paper server and rejects missing or duplicate providers.
- Replay artifacts retain adapter ID, protocol version, format revision, and
  registry fingerprint. Cross-version packet translation is outside scope.

The pinned targets are Paper 26.2 build 135 and Paper 26.3 build 244, both
stable as verified through Paper's downloads API on 7 November 2026. Java 26,
PacketEvents 2.14.0, MockBukkit 4.117.0 for 26.2, and MockBukkit 4.119.0 for
26.3 are used.

## Packet review and playback decisions

Every clientbound packet in each pinned NMS registry is classified explicitly.
Essential scene families include chunks, lighting, blocks, block entities,
entity lifecycle and movement, metadata, equipment, passengers, leashes,
effects, profiles, animations, sounds, chat, and damage presentation.

Packets with entity IDs, UUIDs, or player profiles are rewritten using
playback-session identities. Recorded player chat is filtered and converted to
unsigned system chat. Explosion presentation is retained while observer
knockback is removed. Scoreboards, teams, waypoints, inventory, health,
movement, menus, camera, and other recorded-viewer state are dropped from
playback; equivalent live server packets remain allowed.

Paper 26.3 additionally supplies `swing_animation`, `add_transient_block`, and
`post_effects`. Swing animation receives an entity-ID rewrite. Transient blocks
are ordered scene effects and are cleared by unloading replay chunks on reset.
Post-processing is viewer-local and is dropped.

The exhaustive, version-specific packet inventories and decisions live in
`docs/adapters/paper-26.2.md` and `docs/adapters/paper-26.3.md`. They are
maintained as reviewed documentation, not generated from a shared template.

## Tests

- Each adapter loads its real pinned NMS registry and asserts that every PLAY
  packet has an explicit classification and that essential families remain
  replayable.
- Adapter-specific tests cover registry failure modes, native codecs,
  checkpoints, snapshots, capture behavior, observer isolation, packet
  rewriting, outbound gating, and reset behavior.
- Generic framework tests cover recording queues and budgets, finalization,
  checkpoint scheduling, playback isolation, timeline behavior, formats,
  runtime adapter selection, and configuration.
- The existing example plugin runs against the matching 26.2 and 26.3
  MockBukkit artifacts. No verification-only Gradle module is introduced.
- S3, SFTP, and PostgreSQL contract tests remain opt-in integration tests and
  skip when their connection settings are absent.

## Development environment

`.devcontainer` contains only a Java 26 image, Git, and a source/test
compilation check. It starts no Paper server and no sidecar infrastructure.
Normal verification is `./gradlew test`, followed by the two explicit
MockBukkit test invocations. The `runServer` task defaults to 26.3 and accepts
`-PminecraftVersion=26.2` for the older adapter.

## Known limits

Unit, NMS, and MockBukkit tests verify codec and server-side behavior but do not
prove every custom datapack or visual client rendering. Map IDs and arbitrary
integer entity references embedded in specialized metadata are not generically
rewritten. Diagnostic and game-test packets remain blocked. These limits are
also recorded in each adapter reference.
