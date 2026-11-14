# Paper 26.2 adapter

This document describes the architectural boundaries and compatibility
contract of the Paper 26.2 adapter. It intentionally does not track packet
implementation or test status.

## Runtime baseline

The adapter targets Paper `26.2.build.135-stable`, Java 26, and PacketEvents
2.14.0. Version-bound NMS access remains inside
`dev.voldechse.replayframework.adapter.paper.v26_2`.

The registry fingerprint is derived at runtime from the active protocol
registry and adapter policy. Adapter format revision `4` is required;
recordings from earlier revisions are not migrated or accepted.

## Architecture

`Paper26_2ReplayAdapter` is the adapter entry point and
`Paper26_2AdapterProvider` exposes it to adapter discovery. Shared,
NMS-independent contracts live in `replay-adapter:paper-common` and
`replay-adapter:api`.

The version module is divided by responsibility:

- `packet` owns protocol discovery, native codec access, classifications, and
  version-local packet definitions.
- `capture` owns outbound observation, semantic capture context, source
  selection, ordering, and frame emission.
- `checkpoint` owns scoped Paper snapshots, deterministic assembly, and the
  capture barrier used to separate snapshots from later deltas.
- `playback` owns decoding, session isolation, queueing, channel injection,
  and cleanup of replay-owned client state.

Packet definition naming and placement are specified centrally in
[`packet-definition-layout.md`](../packet-definition-layout.md).

## Capture boundary

Clientbound traffic is observed after Paper and other handlers have produced
its final protocol representation. Capture copies immutable frame data before
leaving the network thread and attaches only semantic context that can be
derived safely from typed adapter state.

Packet observation remains the authoritative source for recipient-specific
wire behavior. Server-side events are used only where the protocol stream
cannot provide the required authoritative state and where an equivalent,
well-defined fallback exists.

## Checkpoints

Checkpoint creation preloads the requested scope when necessary, captures a
Paper-owned snapshot, and allocates a server tick/time/sequence barrier only
after that snapshot is ready. The core drains earlier deltas before persisting
the checkpoint, preserving an exclusive boundary between snapshot state and
subsequent traffic.

Snapshot data is converted to ordered native protocol frames without exposing
mutable Paper objects to asynchronous persistence work.

## Playback boundary

Playback validates the recorded adapter identity, format revision, and
registry fingerprint before emitting frames. Version-native decoding and
encoding stay behind the adapter boundary.

Replay-owned identities are deterministic per playback session and isolated
from live server identities. Viewer-local state is kept separate from scene
state, while unsupported or unsafe traffic is rejected by registry policy.
Live outbound traffic that would conflict with an active replay view is
filtered at the Paper channel boundary.

Seek reconstruction emits only state admitted by the verified checkpoint
contract. Normal timeline playback may additionally emit ordered transient
state. Reset removes tracked replay-owned client state in deterministic order
and reports partial failures without silently discarding its tracking data.

## Compatibility and limits

The adapter requires the pinned Paper protocol and compatible registries.
Custom server software, modified registries, datapacks, or intermediary
protocol translators can invalidate assumptions even when the nominal game
version matches.

Only semantics explicitly modeled by the adapter are transformed. Opaque or
unknown values are never guessed from field shape, packet names, or payload
bytes. The registry fingerprint is a compatibility guard, not a substitute
for validating custom runtime content.

## Quality assurance

The version module is covered by protocol-registry, native-codec, capture,
checkpoint, playback-isolation, reset, and outbound-channel tests. Validation
uses the pinned Paper development bundle so runtime IDs, codecs, and native
types are checked against the same version used for compilation.

## Protocol inventory

The following tables document every clientbound packet in the pinned
protocol registry, including its protocol role, replay relevance, and
intended playback/checkpoint policy. They do not state implementation or
test status.

### STATUS

| ID | Identifier | Description / relevance | Playback | Checkpoint |
|---:|---|---|---|---|
| 0 | `minecraft:status_response` | Connection setup or management; never inject into an established PLAY connection | BLOCKED | no |
| 1 | `minecraft:pong_response` | Connection setup or management; never inject into an established PLAY connection | BLOCKED | no |

### LOGIN

| ID | Identifier | Description / relevance | Playback | Checkpoint |
|---:|---|---|---|---|
| 0 | `minecraft:login_disconnect` | Connection setup or management; never inject into an established PLAY connection | BLOCKED | no |
| 1 | `minecraft:hello` | Connection setup or management; never inject into an established PLAY connection | BLOCKED | no |
| 2 | `minecraft:login_finished` | Connection setup or management; never inject into an established PLAY connection | BLOCKED | no |
| 3 | `minecraft:login_compression` | Connection setup or management; never inject into an established PLAY connection | BLOCKED | no |
| 4 | `minecraft:custom_query` | Connection setup or management; never inject into an established PLAY connection | BLOCKED | no |
| 5 | `minecraft:cookie_request` | Connection setup or management; never inject into an established PLAY connection | BLOCKED | no |

### CONFIGURATION

| ID | Identifier | Description / relevance | Playback | Checkpoint |
|---:|---|---|---|---|
| 0 | `minecraft:cookie_request` | Connection setup or management; never inject into an established PLAY connection | BLOCKED | no |
| 1 | `minecraft:custom_payload` | Connection setup or management; never inject into an established PLAY connection | BLOCKED | no |
| 2 | `minecraft:disconnect` | Connection setup or management; never inject into an established PLAY connection | BLOCKED | no |
| 3 | `minecraft:finish_configuration` | Connection setup or management; never inject into an established PLAY connection | BLOCKED | no |
| 4 | `minecraft:keep_alive` | Connection setup or management; never inject into an established PLAY connection | BLOCKED | no |
| 5 | `minecraft:ping` | Connection setup or management; never inject into an established PLAY connection | BLOCKED | no |
| 6 | `minecraft:reset_chat` | Connection setup or management; never inject into an established PLAY connection | BLOCKED | no |
| 7 | `minecraft:registry_data` | Connection setup or management; never inject into an established PLAY connection | BLOCKED | no |
| 8 | `minecraft:resource_pack_pop` | Connection setup or management; never inject into an established PLAY connection | BLOCKED | no |
| 9 | `minecraft:resource_pack_push` | Connection setup or management; never inject into an established PLAY connection | BLOCKED | no |
| 10 | `minecraft:store_cookie` | Connection setup or management; never inject into an established PLAY connection | BLOCKED | no |
| 11 | `minecraft:transfer` | Connection setup or management; never inject into an established PLAY connection | BLOCKED | no |
| 12 | `minecraft:update_enabled_features` | Connection setup or management; never inject into an established PLAY connection | BLOCKED | no |
| 13 | `minecraft:update_tags` | Connection setup or management; never inject into an established PLAY connection | BLOCKED | no |
| 14 | `minecraft:select_known_packs` | Connection setup or management; never inject into an established PLAY connection | BLOCKED | no |
| 15 | `minecraft:custom_report_details` | Connection setup or management; never inject into an established PLAY connection | BLOCKED | no |
| 16 | `minecraft:server_links` | Connection setup or management; never inject into an established PLAY connection | BLOCKED | no |
| 17 | `minecraft:clear_dialog` | Connection setup or management; never inject into an established PLAY connection | BLOCKED | no |
| 18 | `minecraft:show_dialog` | Connection setup or management; never inject into an established PLAY connection | BLOCKED | no |
| 19 | `minecraft:code_of_conduct` | Connection setup or management; never inject into an established PLAY connection | BLOCKED | no |

### PLAY

| ID | Identifier | Description / relevance | Playback | Checkpoint |
|---:|---|---|---|---|
| 0 | `minecraft:bundle_delimiter` | Payload-free, identity-sensitive bundle boundary; retain for stream analysis but never replay without channel-scoped bundle ownership | BLOCKED | no |
| 1 | `minecraft:add_entity` | Essential scene state: spawn an entity with type, position, UUID, and type-specific data | REWRITE | yes |
| 2 | `minecraft:animate` | Ordered scene effect: entity animation such as wake-up or critical hit; rewrite entity ID | REWRITE | no |
| 3 | `minecraft:award_stats` | Must remain local: recorded player or connection state; leave the neutral observer unchanged | DROP_LOCAL | no |
| 4 | `minecraft:block_changed_ack` | Cumulative recorded-client prediction acknowledgement; no block state or result | BLOCKED | no |
| 5 | `minecraft:block_destruction` | Spatial, ordered block-breaking overlay; isolate its source/overlay ID, preserve all unsigned progress values, and clear active overlays during reset | REWRITE | no |
| 6 | `minecraft:block_entity_data` | Essential scene state: durable world or scene state | SEND | yes |
| 7 | `minecraft:block_event` | Spatial, ordered block-local action; preserve the registered block and both unsigned action fields because their meaning depends on the block state at the position | SEND | no |
| 8 | `minecraft:block_update` | Spatial, durable replacement of one global block state, preserving every state property such as orientation, shape, level, age and flags | SEND | yes |
| 9 | `minecraft:boss_event` | Durable boss-bar lifecycle with six operation variants; preserve its component, progress, style and properties while isolating the bar UUID per playback session | REWRITE | yes |
| 10 | `minecraft:change_difficulty` | Synchronizes the four-level world difficulty and its lock flag with the receiving client; keep the recorded connection state from changing the neutral observer | DROP_LOCAL | no |
| 11 | `minecraft:chunk_batch_finished` | Ends the client-side chunk-throughput measurement with the transmitted batch size and triggers a serverbound rate acknowledgement; never replay into an established connection | BLOCKED | no |
| 12 | `minecraft:chunk_batch_start` | Payload-free start of a connection-local chunk-batch timing measurement; never replay into an established connection | BLOCKED | no |
| 13 | `minecraft:chunks_biomes` | Replaces the serialized biome palettes of zero or more chunks while preserving entry order, duplicate coordinates, and the complete per-chunk payload | SEND | yes |
| 14 | `minecraft:clear_titles` | Clears the recorded connection's title and subtitle and can also reset its animation timings; never apply either operation to the neutral observer | DROP_LOCAL | no |
| 15 | `minecraft:command_suggestions` | Correlates a recorded input request with its replacement range, completion texts and optional tooltips; preserve the observer's current command input | DROP_LOCAL | no |
| 16 | `minecraft:commands` | Installs the permission-sensitive Brigadier tree with literals, typed arguments, redirects, restrictions and suggestion providers; preserve the observer's live command tree | DROP_LOCAL | no |
| 17 | `minecraft:container_close` | Closes the connection-local menu identified by its container ID; preserve the neutral observer's live screen | DROP_LOCAL | no |
| 18 | `minecraft:container_set_content` | Replaces one menu state's indexed item contents and carried stack; preserve the neutral observer's live inventory | DROP_LOCAL | no |
| 19 | `minecraft:container_set_data` | Updates a signed, menu-type-specific property for one container; preserve the neutral observer's live menu data | DROP_LOCAL | no |
| 20 | `minecraft:container_set_slot` | Updates one signed menu slot with an optional component-bearing item stack for a container state; preserve the neutral observer's live inventory | DROP_LOCAL | no |
| 21 | `minecraft:cookie_request` | Requests the client cookie under one namespaced key and elicits a nullable, size-bounded response owned by the current connection; never replay into an established connection | BLOCKED | no |
| 22 | `minecraft:cooldown` | Sets, replaces or clears an item-use cooldown for one receiver-local group; shared groups can affect multiple items, so leave the neutral observer unchanged | DROP_LOCAL | no |
| 23 | `minecraft:custom_chat_completions` | Adds, removes or replaces receiver-local chat suggestions; leave the neutral observer's own suggestion set unchanged | DROP_LOCAL | no |
| 24 | `minecraft:custom_payload` | Carries brand or channel-defined plugin/mod bytes whose schema and effects belong to the receiving connection | DROP_LOCAL | no |
| 25 | `minecraft:damage_event` | Ordered hurt effect with rewritten target, causing-entity and direct-source IDs; optional source position takes precedence over entity sources | REWRITE | no |
| 26 | `minecraft:debug/block_value` | Subscription-keyed diagnostic value at a block position; optional value can clear the viewer's debug entry and registry-backed values describe the recorded world | BLOCKED | no |
| 27 | `minecraft:debug/chunk_value` | Subscription-keyed diagnostic value at a chunk position; the protocol sends Z before X and updates can clear the viewer's debug entry | BLOCKED | no |
| 28 | `minecraft:debug/entity_value` | Subscription-keyed diagnostic value at a source entity ID; entity identity and the viewer's active debug subscriptions are connection-local | BLOCKED | no |
| 29 | `minecraft:debug/event` | One-shot debug-subscription event with a mandatory type-specific payload that can contain registry-backed or world-specific data | BLOCKED | no |
| 30 | `minecraft:debug_sample` | Periodic subscription-selected server telemetry; tick-time samples report full, server, task and idle nanoseconds and are operator-only | BLOCKED | no |
| 31 | `minecraft:delete_chat` | Removes a signed player-chat entry using a connection-local packed signature reference or full signature; preserve the observer's own chat history | DROP_LOCAL | no |
| 32 | `minecraft:disconnect` | Terminates the receiving connection with a required chat-component reason; retain for recording fidelity and diagnostics, never replay into the observer connection | BLOCKED | no |
| 33 | `minecraft:disguised_chat` | Disguised chat presentation with message content, bound chat type, sender name and optional target name; keep recorded chat local to preserve the observer's chat history | DROP_LOCAL* | no |
| 34 | `minecraft:entity_event` | Entity-specific transient status byte; rewrite its entity ID and preserve unknown values. Player debug and permission statuses belong to the recorded connection | REWRITE / DROP_LOCAL* | no |
| 35 | `minecraft:entity_position_sync` | Absolute entity position, movement vector, rotation and ground state; preserve as essential scene state with playback-session identity rewriting | REWRITE | yes |
| 36 | `minecraft:explode` | Explosion scene effect with sound and weighted particle data; suppress recorded-player knockback and remap entity-backed vibration destinations | REWRITE | no |
| 37 | `minecraft:forget_level_chunk` | Unloads a recorded chunk column from the client view; preserve its signed coordinates to remove stale terrain | SEND | yes |
| 38 | `minecraft:game_event` | Forward rain and thunder changes; keep recorded-player, game-rule and loading-screen events from changing the observer | SEND / DROP_LOCAL* | no |
| 39 | `minecraft:game_rule_values` | Carries serialized values for the recorded player's available game rules in response to a privileged request; keep the observer's rule view unchanged | DROP_LOCAL | no |
| 40 | `minecraft:game_test_highlight_pos` | GameTest `showPos` highlight for a selected block and its test-relative coordinates; no general replay target exists | BLOCKED | no |
| 41 | `minecraft:mount_screen_open` | Opens the recorded player's horse or Nautilus inventory using its connection-local container ID and mount entity ID; leave the observer's UI unchanged | DROP_LOCAL | no |
| 42 | `minecraft:hurt_animation` | Directional damage animation for a replay entity; remap its target entity ID and preserve yaw | REWRITE | no |
| 43 | `minecraft:initialize_border` | Full world-border center, current and target size, transition, maximum boundary, and warning settings | SEND | yes |
| 44 | `minecraft:keep_alive` | 64-bit keep-alive challenge; the live connection expects a matching response and may disconnect on a mismatch | BLOCKED | no |
| 45 | `minecraft:level_chunk_with_light` | Chunk terrain, heightmaps, block entity updates, and sky/block light for one world coordinate | SEND | yes |
| 46 | `minecraft:level_event` | One-shot sound, particle, animation, or related world effect; event-specific data and the global-volume flag must be preserved | SEND in event order, retaining global-volume semantics | no |
| 47 | `minecraft:level_particles` | Particle spawn with visibility flags, coordinates, spread, speed, count, and registry-specific payload; vibration particles can reference a block or entity destination | REWRITE entity vibration destination; preserve other payloads and rendering flags | no |
| 48 | `minecraft:light_update` | Incremental sky/block light data for one chunk; masks distinguish changed sections, explicit all-zero sections, and unchanged sections | SEND with exact masks and section arrays | yes |
| 49 | `minecraft:login` | Initializes the active player and world with player ID, available dimensions, view/simulation settings, and common spawn information | BLOCKED; do not replace an established observer connection's player/world context | no |
| 50 | `minecraft:low_disk_space_warning` | Source-server low-storage warning presented as a system toast; describes the recorded server's storage environment | DROP_LOCAL; do not present it as a warning about the playback observer's environment | no |
| 51 | `minecraft:map_item_data` | Updates saved map scale, lock state, decorations, and color patches by numeric map ID; map items share references to that ID | DROP_LOCAL until map IDs are isolated consistently across map updates and map-item references | no |
| 52 | `minecraft:merchant_offers` | Supplies the active trading container's offers, merchant level/XP, progress visibility, and restock capability; container IDs and trade UI state belong to the recorded player's connection | DROP_LOCAL; preserve the observer's own trade menu and offers | no |
| 53 | `minecraft:move_entity_pos` | Ordered movement delta for any tracked entity: signed 1/4096-block X/Y/Z deltas and on-ground flag; remap its entity ID | REWRITE in timeline order | no |
| 54 | `minecraft:move_entity_pos_rot` | Ordered relative entity movement with signed 1/4096-block X/Y/Z deltas, raw yaw/pitch bytes, and on-ground flag; remap its entity ID | REWRITE in timeline order | no |
| 55 | `minecraft:move_minecart_along_track` | Ordered minecart trajectory steps, each with position, movement vector, yaw/pitch, and interpolation weight; remap its entity ID | REWRITE in timeline order | yes |
| 56 | `minecraft:move_entity_rot` | Relative entity rotation as raw yaw/pitch bytes and an on-ground flag; remap the entity ID | REWRITE in timeline order | no |
| 57 | `minecraft:move_vehicle` | Absolute vehicle position as three doubles plus Y/X rotation floats; corrects the recorded player's client-controlled vehicle | DROP_LOCAL to keep recorded player movement local to its source connection | no |
| 58 | `minecraft:open_book` | Opens book-reading UI for the recorded player's main- or off-hand item; hand is an enum VarInt | DROP_LOCAL to keep the recorded player's UI action local to its source connection | no |
| 59 | `minecraft:open_screen` | Container ID as VarInt, `minecraft:menu` registry type and trusted title component; opens the recorded player's menu UI | DROP_LOCAL to preserve the observer's own open container and title | no |
| 60 | `minecraft:open_sign_editor` | Sign block position and front/back text side; opens the recorded player's sign editor | DROP_LOCAL to keep the recorded player's editing UI local to its source connection | no |
| 61 | `minecraft:ping` | Connection-local synchronization challenge with a signed 32-bit ID echoed by the client's Pong response | BLOCKED to keep the challenge and response on their originating connection | no |
| 62 | `minecraft:pong_response` | Returns the client-generated 64-bit ping time used for that connection's latency sample | BLOCKED to keep the response paired with its originating client request | no |
| 63 | `minecraft:place_ghost_recipe` | Container ID and registry-dispatched recipe display, including nested slot displays; previews the selected recipe in the active crafting UI | DROP_LOCAL to preserve the observer's own menu and recipe selection | no |
| 64 | `minecraft:player_abilities` | Four player-ability flags (invulnerable, flying, may fly, instabuild) and flying/walking speeds; controls the receiving player's local abilities | DROP_LOCAL to preserve the observer's own abilities and movement settings | no |
| 65 | `minecraft:player_chat` | Global and sender message indexes, optional signature, packed signed body and last-seen chain, optional unsigned display component, filter mask, and bound chat decoration; recorded chat belongs to the replay's presentation state | Convert to `minecraft:disguised_chat` for unsigned visible presentation; source signature and last-seen chain are not applied to the observer | no |
| 66 | `minecraft:player_combat_end` | Transient combat-tracker event with combat duration in ticks (VarInt); not checkpoint state | DROP_LOCAL to keep the recorded player's combat episode off the observer's connection | no |
| 67 | `minecraft:player_combat_enter` | No payload; transient recorded-player combat-entry event, not checkpoint state | DROP_LOCAL to keep the recorded player's combat state off the observer's connection | no |
| 68 | `minecraft:player_combat_kill` | Dying player's entity ID (VarInt) and trusted death-message component; victim-local death-screen event, with public death chat broadcast sent separately | DROP_LOCAL to keep the recorded victim's death screen off the observer's connection | no |
| 69 | `minecraft:player_info_remove` | Length-prefixed list of profile UUIDs; removes listed profiles from the player list | Rewrite every UUID into the replay identity space so only replay profiles can be removed | yes |
| 70 | `minecraft:player_info_update` | Ordered profile UUID entries with action-selected name/properties, optional chat session, game mode, listed state, latency, display name, hat visibility, and list order; essential player-profile state | Rewrite profile UUIDs, force `listed=false` when that action is present, and discard source chat-session credentials | yes |
| 71 | `minecraft:player_look_at` | Rotates the receiving player's view from feet/eyes toward an absolute position or an entity ID and target anchor; entity mode retains an absolute-coordinate fallback; personal camera state | DROP_LOCAL to leave the observer's view unchanged | no |
| 72 | `minecraft:player_position` | Teleport ID, position, delta movement, yaw/pitch and nine relative-position, rotation and velocity flags; server correction and confirmation state for the receiving player's own connection | DROP_LOCAL to preserve the observer's position, movement and connection state | no |
| 73 | `minecraft:player_rotation` | Yaw and pitch angles with independent relative flags; server correction for the receiving player's own view rotation | DROP_LOCAL to preserve the observer's view | no |
| 74 | `minecraft:recipe_book_add` | List of recipe display entries with registry-backed display/category data, optional group and crafting requirements, notification/highlight flags; replace or add to the receiving player's known recipe book | DROP_LOCAL to preserve the observer's recipe-book contents and notifications | no |
| 75 | `minecraft:recipe_book_remove` | Variable list of recipe display IDs to remove from the receiving player's known recipe book and highlights | DROP_LOCAL to preserve the observer's recipe-book contents | no |
| 76 | `minecraft:recipe_book_settings` | For crafting, furnace, blast furnace and smoker: whether each recipe book is open and whether its craftable-only filter is active; per-player recipe-book UI preferences | DROP_LOCAL to preserve the observer's open tabs and filters | no |
| 77 | `minecraft:remove_entities` | Ordered list of entity IDs to destroy in the client scene; target IDs are translated into the playback identity namespace | REWRITE | yes |
| 78 | `minecraft:remove_mob_effect` | Removes one registry-backed status effect from the target entity; target entity identity is translated into the playback identity namespace | REWRITE | yes |
| 79 | `minecraft:reset_score` | Owner string with optional objective: remove that score, or every score for the owner when absent; recorded-viewer scoreboard state | DROP_LOCAL to preserve the observer's scoreboard | no |
| 80 | `minecraft:resource_pack_pop` | Optional UUID of a server-provided resource pack to remove; if absent, removes all server-provided packs and changes the receiving client's resource assets | BLOCKED | no |
| 81 | `minecraft:resource_pack_push` | UUID, download URL, SHA-1 hash, required flag and optional prompt component; asks the receiving client to download and apply server resource assets | BLOCKED | no |
| 82 | `minecraft:respawn` | Dimension type and key, world seed, current/previous game mode, debug and flat flags, optional last-death position, portal cooldown, sea level, and attribute/entity-data keep mask; respawn state for the receiving player | DROP_LOCAL to preserve the observer's world and player state | no |
| 83 | `minecraft:rotate_head` | Entity ID and absolute head yaw angle in 1/256-turn steps; head orientation is independent of body rotation | REWRITE the entity ID into the playback identity space | yes |
| 84 | `minecraft:section_blocks_update` | Packed section position, VarInt update count, then ordered VarLong records (`global block-state ID << 12 | local X << 8 | local Z << 4 | local Y`); each record changes one block using a global state and three 4-bit section-relative coordinates | SEND | yes |
| 85 | `minecraft:select_advancements_tab` | Optional advancement-tab identifier; switches tabs after a client selection or an advancement in another tab; an absent or unknown identifier selects the first available tab | DROP_LOCAL to preserve the observer's advancements view | no |
| 86 | `minecraft:server_data` | Server MOTD as a rich text component and an optional byte array containing the server icon; connection-specific server presentation | DROP_LOCAL to preserve the observer's server details | no |
| 87 | `minecraft:set_action_bar_text` | Rich text component displayed temporarily above the hotbar as an action-bar message; carries no duration or clear operation | DROP_LOCAL to preserve the observer's action bar | no |
| 88 | `minecraft:set_border_center` | Center X and Z as doubles; updates the shared world's border center without changing its size or other border settings | SEND | yes |
| 89 | `minecraft:set_border_lerp_size` | Current and target border diameters with a remaining interpolation time; smoothly updates the shared world's border size | SEND | yes |
| 90 | `minecraft:set_border_size` | One new border diameter as a double; applies immediately without interpolation to the shared world border | SEND | yes |
| 91 | `minecraft:set_border_warning_delay` | Nonnegative VarInt warning threshold stored as ticks in Paper 26.2; configures when the shared world border warning appears | SEND | yes |
| 92 | `minecraft:set_border_warning_distance` | Nonnegative VarInt distance in blocks; configures how far from the shared world border its distance warning appears | SEND | yes |
| 93 | `minecraft:set_camera` | Entity ID selecting the entity followed by the receiving client's camera; changing it would take control of the observer's view | DROP_LOCAL | no |
| 94 | `minecraft:set_chunk_cache_center` | Chunk X and Z coordinates as signed VarInts; centers the receiving client's chunk cache and view area | SEND | yes |
| 95 | `minecraft:set_chunk_cache_radius` | Server-imposed view radius as a VarInt number of chunks; changes the receiving client's effective chunk view distance | SEND | yes |
| 96 | `minecraft:set_cursor_item` | Optional Slot containing the cursor ItemStack and its data components; sets the receiving player's cursor independently of a container | DROP_LOCAL | no |
| 97 | `minecraft:set_default_spawn_position` | Dimension key, packed block position, yaw and pitch; sets that dimension's default spawn destination and compass target | SEND | yes |
| 98 | `minecraft:set_display_objective` | VarInt display slot and objective name (empty clears it); selects a player-list, sidebar, below-name, or team-colored sidebar objective, all part of the observer's local scoreboard presentation | DROP_LOCAL | no |
| 99 | `minecraft:set_entity_data` | Target entity VarInt followed by sparse typed metadata entries ending at `0xFF`; updates that entity's synchronized appearance and state | REWRITE | yes |
| 100 | `minecraft:set_entity_link` | Leash relationship between attached entity and holder; two fixed-width Int entity IDs, with destination `0` clearing the leash; rewrite both non-sentinel IDs | REWRITE | yes |
| 101 | `minecraft:set_entity_motion` | Target entity VarInt and velocity as a variable-length low-precision `LpVec3`; updates that entity's current motion, including zero velocity | REWRITE | yes |
| 102 | `minecraft:set_equipment` | Updates one or more equipment slots on an entity; ordered optional item stacks preserve item components and empty stacks clear slots | REWRITE | yes |
| 103 | `minecraft:set_experience` | Receiver-local experience HUD: progress Float, level VarInt, and total-experience VarInt; retain in recordings but do not replace the observer's own XP state | DROP_LOCAL | no |
| 104 | `minecraft:set_health` | Receiver-local vitals: health Float, food VarInt, and saturation Float; retain for recording completeness but leave the observer's own health and hunger state unchanged | DROP_LOCAL | no |
| 105 | `minecraft:set_held_slot` | Receiver-local hotbar selection as a VarInt (slot 0–8); retain in recordings but do not change the observer's selected item | DROP_LOCAL | no |
| 106 | `minecraft:set_objective` | Objective name and method byte (add, remove, change); add/change also carry display text, integer/hearts render type, and optional blank/styled/fixed score-number format; mutates the recorded viewer's local scoreboard | DROP_LOCAL | no |
| 107 | `minecraft:set_passengers` | Essential scene state: vehicle and passengers; rewrite every entity ID | REWRITE | yes |
| 108 | `minecraft:set_player_inventory` | Must remain local: recorded player or connection state; leave the neutral observer unchanged | DROP_LOCAL | no |
| 109 | `minecraft:set_player_team` | Recorded-viewer team and name presentation; keep local to preserve observer state | DROP_LOCAL | no |
| 110 | `minecraft:set_score` | Recorded-viewer scoreboard value; preserve the observer scoreboard | DROP_LOCAL | no |
| 111 | `minecraft:set_simulation_distance` | Supplementary durable world or scene state | SEND | yes |
| 112 | `minecraft:set_subtitle_text` | Must remain local: recorded player or connection state; leave the neutral observer unchanged | DROP_LOCAL | no |
| 113 | `minecraft:set_time` | Essential scene state: durable world or scene state | SEND | yes |
| 114 | `minecraft:set_title_text` | Must remain local: recorded player or connection state; leave the neutral observer unchanged | DROP_LOCAL | no |
| 115 | `minecraft:set_titles_animation` | Must remain local: recorded player or connection state; leave the neutral observer unchanged | DROP_LOCAL | no |
| 116 | `minecraft:sound_entity` | Ordered scene effect: sound attached to a rewritten entity ID | REWRITE | no |
| 117 | `minecraft:sound` | Ordered presentation or environment effect | SEND | no |
| 118 | `minecraft:start_configuration` | Transport, connection, or server control; never replay into an established connection | BLOCKED | no |
| 119 | `minecraft:stop_sound` | Ordered presentation or environment effect | SEND | no |
| 120 | `minecraft:store_cookie` | Transport, connection, or server control; never replay into an established connection | BLOCKED | no |
| 121 | `minecraft:system_chat` | Recorded connection chat and action-bar presentation retained according to capture policy but suppressed for neutral playback | DROP_LOCAL* | no |
| 122 | `minecraft:tab_list` | Must remain local: recorded player or connection state; leave the neutral observer unchanged | DROP_LOCAL | no |
| 123 | `minecraft:tag_query` | Diagnostic or special-purpose packet without a safe playback contract | BLOCKED | no |
| 124 | `minecraft:take_item_entity` | Scene presentation with playback-session identity rewriting | REWRITE | no |
| 125 | `minecraft:teleport_entity` | Essential scene state: scene presentation with playback-session identity rewriting | REWRITE | yes |
| 126 | `minecraft:test_instance_block_status` | Diagnostic or special-purpose packet without a safe playback contract | BLOCKED | no |
| 127 | `minecraft:ticking_state` | Transport, connection, or server control; never replay into an established connection | BLOCKED | no |
| 128 | `minecraft:ticking_step` | Transport, connection, or server control; never replay into an established connection | BLOCKED | no |
| 129 | `minecraft:transfer` | Transport, connection, or server control; never replay into an established connection | BLOCKED | no |
| 130 | `minecraft:update_advancements` | Must remain local: recorded player or connection state; leave the neutral observer unchanged | DROP_LOCAL | no |
| 131 | `minecraft:update_attributes` | Essential scene state: scene presentation with playback-session identity rewriting | REWRITE | yes |
| 132 | `minecraft:update_mob_effect` | Essential scene state: scene presentation with playback-session identity rewriting | REWRITE | yes |
| 133 | `minecraft:update_recipes` | Must remain local: recorded player or connection state; leave the neutral observer unchanged | DROP_LOCAL | no |
| 134 | `minecraft:update_tags` | Registry tags; requires compatible server and datapack registries | SEND | yes |
| 135 | `minecraft:projectile_power` | Scene presentation with playback-session identity rewriting | REWRITE | yes |
| 136 | `minecraft:custom_report_details` | Transport, connection, or server control; never replay into an established connection | BLOCKED | no |
| 137 | `minecraft:server_links` | Must remain local: recorded player or connection state; leave the neutral observer unchanged | DROP_LOCAL | no |
| 138 | `minecraft:waypoint` | Recorded-viewer waypoint presentation; keep local to preserve observer state | DROP_LOCAL | no |
| 139 | `minecraft:clear_dialog` | Must remain local: recorded player or connection state; leave the neutral observer unchanged | DROP_LOCAL | no |
| 140 | `minecraft:show_dialog` | Must remain local: recorded player or connection state; leave the neutral observer unchanged | DROP_LOCAL | no |

