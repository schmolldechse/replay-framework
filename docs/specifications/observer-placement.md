# Safe Replay Observer Placement

Status: approved and implemented.

## Objective

Replay observers must enter playback in the part of the scene that is actually
being replayed. A server-wide fixed location is not suitable because replay
coordinates vary between recordings. The framework must therefore:

- remove the complete `viewer.*` configuration contract from the Example
  plugin;
- let an integration provide an observer location through the public playback
  API;
- derive a deterministic replay-local position when the integration does not
  provide one;
- validate and perform the teleport without blocking Paper's main thread;
- apply the initial checkpoint only after the observer has reached the target;
- retain the existing isolated, neutral-observer lifecycle and restore the
  player's original state when playback ends or opening fails.

This change concerns observer placement. It does not make recorded movement
packets control the real Paper player and does not turn replay entities into
server-side entities.

## Current behavior and cause

`ReplayExamplePlugin` reads `viewer.world`, `viewer.x`, `viewer.y`,
`viewer.z`, `viewer.yaw`, and `viewer.pitch` from Bukkit's `config.yml`.
`ExampleViewerEnvironment` creates a private flat viewer world, replaces the
configured world's reference with that private world, and then calls the
synchronous `Player.teleport(Location)` method.

This has three consequences:

1. every replay starts at the same coordinates, independent of its captured
   chunks and entities;
2. a replay around `(500, 90, -200)` is initially viewed from a configured
   location such as `(0.5, 100, 0.5)`;
3. the synchronous teleport may synchronously load or generate the destination
   chunk.

The public `PlaybackRequest` currently contains only the replay, viewer, and
buffer options. `DefaultPlaybackService` consequently has no placement input,
and the adapter contract exposes no replay-derived observer anchor.

## Paper API findings

Paper provides synchronous `teleport(...)` and asynchronous
`teleportAsync(...)` variants. The official guidance recommends
`teleportAsync` whenever the destination chunk may be unloaded because a
synchronous chunk load can stall the server thread. The asynchronous result is
completed on the main server thread. Calling `get()` or `join()` on that future
from the main thread can deadlock the server.

Relevant official references:

- [Paper teleportation guide](https://docs.papermc.io/paper/dev/entity-teleport/)
- [Paper 26.3 Entity API](https://jd.papermc.io/paper/26.3/org/bukkit/entity/Entity.html)
- [Paper scheduler and thread-safety guide](https://docs.papermc.io/paper/dev/scheduler/)
- [Paper 26.3 World API](https://jd.papermc.io/paper/26.3/org/bukkit/World.html)
- [Paper 26.3 Location API](https://jd.papermc.io/paper/26.3/org/bukkit/Location.html)

The selected operation will use `PlayerTeleportEvent.TeleportCause.PLUGIN`.
No relative velocity flags are appropriate: observer placement is absolute and
must not retain live-player velocity. The deprecated passenger-retention flag
is also unnecessary. A player with a vehicle or passengers will be rejected
before preparation because the current stored-state contract cannot restore a
mount graph safely, and Paper documents restrictions for cross-world player
teleports with passengers.

`teleportAsync` loads the destination chunk and reports whether the teleport
succeeded. It does not prove that a coordinate is finite, inside the world's
height and border, free of collision, or unchanged by another plugin's
teleport event. Those checks remain framework responsibilities.

## Public API

Introduce an immutable `ObserverPlacement` API type in `replay-api` with two
explicit modes:

```java
public sealed interface ObserverPlacement {
    static ObserverPlacement automatic(World targetWorld);
    static ObserverPlacement fixed(Location target);
}
```

- `automatic(World)` selects the world in which the observer is placed while
  allowing the framework to obtain coordinates from replay state.
- `fixed(Location)` lets the developer select the world, coordinates, yaw,
  and pitch.
- Mutable Bukkit `Location` values are cloned on input and output.
- Worlds must be loaded; coordinates and rotation must be finite.

`PlaybackRequest.Builder` gains:

```java
.observerPlacement(ObserverPlacement.fixed(location))
```

If omitted, the request resolves to `ObserverPlacement.automatic` for the
viewer's current world at the start of `PlaybackService.open`. This keeps the
builder convenient for direct integrations. The Example integration will
explicitly pass its private viewer world so replay packets remain isolated
from the live world.

The new field is part of the immutable request and therefore does not add
mutable placement methods to an active `PlaybackSession`. Moving the observer
after opening remains an integration action and is deliberately outside this
change.

## Replay-derived automatic placement

The generic core must not decode version-bound Minecraft packet payloads.
Extend the abstract adapter boundary with an observer-anchor resolver whose
input is the already verified initial checkpoint and whose output is an
ordered list of version-neutral coordinate candidates.

Candidate priority is deterministic: recorded player spawns come first,
followed by other recorded living entities. A checkpoint without either type
is rejected; unrelated world-spawn coordinates are deliberately not used
because they need not be near replay activity.

Paper 26.2 and Paper 26.3 implement the resolver separately with their native
packet codecs and registries. This retains the existing abstract adapter
model: core owns selection policy and orchestration, while each versioned
adapter owns native packet interpretation. Projectiles, item entities, armor
stands, paintings, displays, and other non-living anchors are excluded.

For an entity anchor, the adapter returns the recorded feet position and
rotation. Core expands it into a camera candidate above the actor and then the
recorded feet position. This avoids initially placing the camera inside the
rendered actor while retaining the actor position as a fallback. Every
expanded candidate still passes the normal safety checks.

The first valid candidate is mapped into the `automatic(World)` target world.
For the Example plugin this is the isolated per-viewer world, so no live world
is exposed or modified. Existing replay artifacts remain usable: the initial
checkpoint already contains the entity, spawn, and chunk packets needed for
candidate extraction, so no manifest, database, or replay wire-format change
is required.

The packet format currently does not preserve the source world key after a
checkpoint packet has been encoded. Consequently, the automatic resolver
chooses replay coordinates, while `ObserverPlacement` chooses the actual
target world. This is intentional and explicit. Integrations that need a
specific dimension must pass either `automatic(targetWorld)` or a fixed
location. The Example's private world will use the viewer's pre-playback world
as its environment template after `viewer.*` is removed.

If no candidate can be derived, opening fails with a placement-specific
exception instead of silently retaining an unrelated location.

## Safety rules

Before teleporting, a Paper-owned placement service performs all Bukkit world
access on the server thread and validates:

- the viewer is still online and still belongs to the opening request;
- the destination world is loaded;
- coordinates, yaw, and pitch are finite;
- the feet and eye position fit inside the world's min/max build height;
- the position is inside the world border;
- the viewer has neither a vehicle nor passengers;
- the destination feet/head volume is passable and not liquid or an immediate
  damage block in the server-side viewer world.

For a fixed location, failed validation rejects the request rather than moving
the developer's coordinates. For automatic placement, invalid candidates are
skipped until a valid candidate is found.

After the cheap world, border, and height checks, the destination chunk is
requested with `World.getChunkAtAsync(...)`. Its main-thread completion is used
for block and fluid checks. The player is then teleported with Paper's
`teleportAsync(..., PLUGIN)` API; the already loaded chunk makes that operation
non-blocking without relying on an implementation detail. No future is
synchronously awaited. A `false` result, cancellation, disconnect, world
unload, or exception fails the open operation and triggers the existing
restore path.

After completion, the actual player location is checked again on the main
thread. This catches a teleport listener changing the destination. The result
must still be in the intended viewer world and satisfy the safety rules.

## Playback opening order

`DefaultPlaybackService.open` will use this order:

1. reserve the viewer UUID;
2. load and verify the replay manifest and index;
3. load the initial checkpoint once;
4. create the version-specific playback bridge;
5. resolve fixed or automatic observer placement;
6. complete the safe asynchronous teleport;
7. activate outbound replay isolation after the teleport's live chunk traffic;
8. prepare the timeline from the cached initial checkpoint;
9. apply the initial checkpoint and register the paused session;
10. return the prepared `PlaybackSession`.

This ordering ensures that the observer is already at the correct replay
coordinates before replay chunks and entities are presented. The initial
checkpoint is cached for timeline preparation, avoiding a second storage
fetch. Every failure after bridge creation closes the bridge, releases cache
leases, releases the viewer reservation, and lets the Example restore the
player.

## Example plugin and configuration

The Example plugin will no longer read or require any of these paths:

```yaml
viewer:
  world: world
  x: 0.5
  y: 100.0
  z: 0.5
  yaw: 0.0
  pitch: 0.0
```

`ReplayExamplePlugin.viewerLocation(...)` is removed. The Example viewer
configuration no longer stores a fixed replay location. It creates the
isolated viewer world from the player's pre-playback world environment and
opens playback with `ObserverPlacement.automatic(viewerWorld)`.

The Example must not perform a second teleport before
`PlaybackService.open`. Its player-state preparation remains responsible for
inventory, controls, flight, invulnerability, collision isolation, and
restoration. The original location is still captured before the viewer world
is created.

Restoring the original location will also become asynchronous so an unloaded
original chunk cannot cause a synchronous load. The private viewer world is
unloaded and deleted only after restoration has completed. Shutdown and
disconnect races retain the existing idempotent restore ownership.

## Affected components

- `replay-api`
  - add `ObserverPlacement`;
  - extend `PlaybackRequest` and its builder;
  - document validation, defaults, and asynchronous failure behavior.
- `replay-adapter:api`
  - add the version-neutral observer-anchor value and resolver contract;
  - expose it through `AbstractReplayAdapter` without leaking NMS types.
- `replay-adapter:paper-26_2`
  - decode and rank 26.2 checkpoint anchor candidates.
- `replay-adapter:paper-26_3`
  - decode and rank 26.3 checkpoint anchor candidates.
- `replay-core`
  - load the initial checkpoint before timeline preparation;
  - select explicit or automatic placement;
  - invoke the Paper placement port and preserve failure cleanup.
- `replay-runtime-paper`
  - provide the main-thread and safe asynchronous teleport implementation.
- `replay-example-plugin`
  - remove `viewer.*` parsing and fixed-location configuration;
  - pass its private viewer world as the automatic placement target;
  - restore the original location asynchronously.
- `docs/playback.md` and Example configuration documentation
  - describe fixed and automatic placement and remove the claim that the
    framework never teleports a viewer.

## Tests and verification

### Generic API and core tests

- explicit automatic placement is retained by the immutable request;
- the compatibility constructor leaves placement to the runtime default;
- non-finite version-neutral anchors are rejected;
- the existing playback lifecycle suite continues to cover bridge, buffer,
  cache lease, and viewer-reservation cleanup.

### Adapter-specific tests

Both Paper adapters receive independent tests proving that their native
checkpoint resolver:

- recognizes a player spawn and preserves its coordinates;
- prefers a player over another entity regardless of frame order;
- accepts an eligible living-entity fallback;
- ignores an unsuitable non-living entity;
- keeps live teleport/chunk traffic open until replay isolation is activated.

### Paper/Example tests

- Example tests cover configuration without `viewer.*` while retaining the
  existing lifecycle and neutral-observer fixtures;
- the existing neutral-observer tests continue to prove that the viewer cannot
  damage, interact with, or alter the replay environment.

### Manual Paper 26.3 acceptance

1. start a replay whose activity is around `(500, 90, -200)` while the viewer
   begins around spawn;
2. open it without an explicit placement and verify the observer enters the
   replay area's chunks before playback starts;
3. open it through an API call with a fixed safe location and verify that the
   fixed location wins;
4. try an unloaded target chunk and confirm the server thread does not stall;
5. cancel the teleport from a test listener and verify open fails and the
   original player state is restored;
6. leave playback and verify restoration happens before the private world is
   unloaded.

Run the generic suite, both adapter test suites, and the Example MockBukkit
suite for 26.2 and 26.3. A Paper 26.3 `runServer` smoke test remains the final
integration check.

## Compatibility and impact

- This is a source-compatible builder extension for ordinary
  `PlaybackRequest.builder()` callers because placement has a default.
- The canonical record constructor changes and is therefore a source break for
  callers constructing `PlaybackRequest` directly; provide a compatibility
  constructor with the old three arguments.
- Replay artifacts and database schema remain unchanged.
- `viewer.*` is intentionally removed from the Example configuration. Stale
  keys have no effect and may be deleted by operators.
- The Example's prior fixed spawn behavior is intentionally replaced by
  replay-derived placement.

## Implementation sequence

After approval, refresh `origin/master`, ensure the prerequisite Paper 26.3
adapter work is available on the base, and create a dedicated
`codex/safe-observer-placement` branch. Implement in this order:

1. public API and compatibility constructor;
2. abstract anchor resolver contract;
3. Paper 26.2 and 26.3 resolver implementations and tests;
4. Paper placement service and core opening order;
5. Example lifecycle/configuration changes;
6. documentation, complete tests, and Paper 26.3 smoke verification.
