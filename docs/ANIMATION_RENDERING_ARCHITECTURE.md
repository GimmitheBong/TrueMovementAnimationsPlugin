# True Movement animation rendering architecture

This is the maintainer's map for the rendering-stability work on the
`animation-render-improvements` branch. The matching `[TMA-R##]` labels in the
Java source are not issue numbers that can be deleted after a release. They name
the rendering invariants that must remain true when this code changes.

## What the renderer is responsible for

RuneScape owns the real local `Player`. True Movement normally hides that actor
and displays a `RuneLiteObject` at an interpolated true-tile position. The hard
part is that position, pose, equipment geometry, action animation, spot
animation, orientation, terrain height, overheads, and camera position all have
to describe the same rendered instant.

The renderer therefore has two explicit model sources:

1. **Custom locomotion** — a detached, appearance-matched snapshot of a walk,
   run, or idle pose is displayed at the plugin's interpolated location.
2. **Native composite** — `Player.getModel()` is cloned after the game has
   composed the current action, equipment overrides, spot animation, and other
   actor state. It is displayed through a native-motion anchor when necessary.

There is no third path which applies a plugin animation to the current native
player model. That old pattern was the source of double transforms, broken legs,
incorrect one- and two-handed item placement, and action flicker.

## Frame pipeline and ownership

Every visible frame follows this order:

1. `TrueTileMovementPlugin.onBeforeRender` asks
   `TrueMovementOverlay.PrepareFrame` for one prepared movement handler.
2. `CustomMovementHandler.Update` samples time, appearance, true tile, actor
   movement, action state, route state, and target state.
3. The handler chooses custom locomotion or the native composite, calculates the
   matching location/orientation/height, and publishes the complete frame to its
   `RuneLiteObject`.
4. Only after that succeeds does the plugin hide the original player. If it
   fails, the original player remains visible.
5. The adaptive camera and custom overheads use that same prepared object's
   location and height. They do not independently recalculate player motion.

The core invariant is: **never hide the native player unless a complete,
same-world-view replacement frame is already renderable.**

## Fix register

### TMA-R01 — Atomic render ownership

**Symptoms addressed:** one-frame invisibility, duplicate models, flickering
when prayers or actions change, overlays appearing detached, stale objects being
hidden after a world-view change.

**Cause:** hiding the player and preparing the replacement were previously
independent operations. A null/stale replacement could therefore be published
after the native player had already been suppressed.

**Implementation:** `onBeforeRender` prepares exactly one frame, validates the
replacement model and world view, then publishes scene/UI hiding state. Scene
and UI suppression identify the exact local-player object/id/world view. A
failed preparation leaves the native player visible. The camera and overheads
consume the same prepared object.

**Primary code:** `TrueTileMovementPlugin.onBeforeRender`,
`PublishLocalPlayerRenderState`, `ShouldHidePreparedPlayer`,
`TrueMovementOverlay.PrepareFrame`, and
`CustomMovementHandler.UpdateModelVisibleState`.

**Automated coverage:** `RenderStateTest` and
`AdaptiveCameraRenderingTest`.

### TMA-R02 — One bounded timing model

**Symptoms addressed:** animation speed varying with overlay FPS, large frame
jumps after stalls, skipped animation states, and tween/pose clocks drifting.

**Cause:** elapsed wall-clock time and client-cycle progression could advance
different pieces of one visual frame by unrelated amounts.

**Implementation:** movement uses monotonic `System.nanoTime()` frame deltas,
bounded to 100 ms after a stall. Animation progression mirrors RuneLite's
client-cycle clock and bounds catch-up to 100 cycles. Classic and Maya
animation states use the same packed frame keys as `AnimationController`.

**Primary code:** `UpdateFrameTimer`, `CalculateFrameDeltaMilliseconds`,
`ConsumeElapsedClientCycles`, `CalculateAnimationElapsedTicks`, and
`BuildAnimationFrameKey`.

**Automated coverage:** the timing, frame-key, state-count, and classic-looping
tests in `CustomMovementHandlerTest`.

### TMA-R03 — Bounded neutral locomotion capture

**Symptoms addressed:** prayer-change flickers, tiny idle/run stalls, repeated
animation resets, and locomotion applied on top of a pose that the game had
already transformed.

**Cause:** `Player.getModel()` is not a stable unanimated skeleton. It is a
native, shared/composed model whose hidden movement selectors are consumed by a
client update. Clearing public animation fields in `BeforeRender` is too late
and mutating/retransforming that model is unsafe.

**Implementation:** needed idle/walk/run states are captured in a bounded
`GameTick -> ClientTick` transaction. The low-priority game-tick callback saves
and temporarily suppresses only the movement selectors. Native actor update
consumes that state. The high-priority client-tick callback captures a limited
batch of poses and restores the selectors before normal client-tick observers
or rendering. Every abort/cleanup path restores the saved values. The visible
renderer holds its last valid frame during the transaction.

The auxiliary `NeutralModelCaptureSubscriber` exists because RuneLite event
subscribers are discovered by their conventional method names and the plugin
already owns `onGameTick`/`onClientTick`. It is explicitly registered with the
same unscoped `TrueMovementOverlay` instance that renders the frame; injecting a
second overlay would populate a cache that is never displayed.

**Primary code:** `NeutralModelCaptureSubscriber`,
`BeginNeutralOwnerModelCaptureOnGameTick`,
`CompleteNeutralOwnerModelCaptureOnClientTick`, and the neutral-capture methods
in `CustomMovementHandler`.

**Automated coverage:** `NeutralModelCaptureSubscriberTest` and the exact-cycle
capture test in `CustomMovementHandlerTest`.

### TMA-R04 — Appearance-safe, detached pose cache

**Symptoms addressed:** distorted legs, weapons held incorrectly, broken
two-handed items, old equipment flashing after a gear/prayer/action change, and
mutating one cached frame when another model is built.

**Cause:** RuneLite can reuse model arrays and sequence scratch models. A shallow
or generic reference to those arrays is not a reusable animation base. Cache
identity also has to include every input that changes player geometry.

**Implementation:** `PlayerAppearanceKey` owns deep copies of gender, NPC
transformation, equipment, body colours, recolours, and retextures. Cached poses
own copies of vertex and transparency arrays. Each output frame is reconstructed
on a detached topology clone after array independence and topology counts have
been checked. Cache entries are LRU-bounded. Appearance changes invalidate old
poses, and animations with left/right hand overrides always use the native
composite instead of the custom cache.

If RuneLite cannot produce an independent compatible clone, that animation
variant is marked uncacheable and safely remains native; the renderer does not
keep retrying it every frame.

**Primary code:** `PlayerAppearanceKey`, `CachedModelPose`,
`CachedAnimationFrames`, `CachedAnimationFramesBuilder`,
`HasHandItemOverride`, and `ShouldUseCustomLocomotionModel`.

**Automated coverage:** `PlayerAppearanceKeyTest` and the custom-locomotion,
frame-state, and cache-key tests.

### TMA-R05 — Native composite owns every live action

**Symptoms addressed:** casts appearing after movement, casts replaying after a
click-away, flinches/eating/attacks flickering, spot effects separating from the
body, and item transforms being applied twice.

**Cause:** selecting a small hard-coded action list misses valid animations, and
applying custom locomotion to an already-composed action model corrupts both
animation ordering and equipment transforms.

**Implementation:** any live actor action (`getAnimation() != -1`), any spot
animation, and accelerated/forced movement select the game's native composite.
The native clone receives no second skeletal transform. Custom locomotion does
not advance invisibly behind the action; when custom locomotion resumes it starts
from its configured entry frame, so an old mid-stride frame cannot flash.

This also fixes spell ordering: the cast becomes authoritative on the first
frame RuneScape exposes it. The plugin does not queue a second cast or play a
teleport-style substitute after movement.

**Primary code:** `ShouldUseNativeActionModel`, `PrepareNativeMotionState`, and
the model-source branch in `UpdateModelVisibleState`.

**Automated coverage:** `everyNativeActionWinsWhileItIsActive` and
`customLocomotionRequiresANeutralModelWithoutCompositionOverrides`.

### TMA-R06 — Anchored accelerated and forced movement

**Symptoms addressed:** snapping when casting then moving, knockbacks and pushes
teleporting, movement faster than normal skipping segments, and a jump when
returning from native to custom motion.

**Cause:** the native actor and the interpolated visible model can be at
different positions when an action or forced displacement begins. Switching
directly to the actor position exposes that accumulated offset.

**Implementation:** displacement is detected from both actor movement per
client cycle and unusually large destination changes. At handoff, the plugin
records the last displayed point and the native actor point. While native motion
is active it renders:

`render anchor + (current native position - native anchor)`

This preserves native displacement without exposing the pre-existing visual
offset. A true spatial discontinuity snaps exactly once. When native motion
settles, interpolation rebases from the last position actually displayed.

**Primary code:** `ObserveOwnerMovement`, `MarkAcceleratedMovement`,
`BeginNativeMotion`, `ApplyNativeMotionDelta`, and `RebaseFromNativeMotion`.

**Automated coverage:** the native-anchor and accelerated-movement tests in
`CustomMovementHandlerTest`.

### TMA-R07 — Rendered endpoint is authoritative when stopping

**Symptoms addressed:** spinning after stopping, walking/running on the spot
until the hidden player catches up, and a one-frame idle flash between continuing
route segments.

**Cause:** the hidden native actor can still be traversing its server-side path
after the faster visible tween reaches its endpoint. Using its current walk pose
at the visible endpoint makes the model run in place; using its orientation for
correction makes the stopped model spin.

**Implementation:** the visible model stops on the exact frame its tween reaches
the displayed destination, even if this is earlier than 600 ms. Its current
orientation is preserved. A final idle pose comes only from an appearance- and
idle-animation-matched detached stationary cache. A missing idle cache holds the
last safe detached locomotion/idle frame; it never samples the hidden moving
actor. For a route that genuinely continues, the 600–699 ms late-tick grace
freezes the last locomotion phase instead of advancing legs at zero velocity.
Native action/spot/forced composites bypass this freeze and remain authoritative.

**Primary code:** `ShouldSelectMovingAnimation`,
`ShouldStopAtRenderedDestination`, `ShouldHoldContinuingRouteBoundary`,
`ShouldHoldStableStationaryFrame`, `HoldStableStationaryFrame`, and
`HoldContinuingRouteBoundaryFrame`.

**Automated coverage:** `movementSelectionBridgesOnlyAContinuingRoute` and
`stationaryCacheMissNeverUsesHiddenWalkingPose`.

### TMA-R08 — Stable orientation rules

**Symptoms addressed:** long-way rotation across the 0/2047 boundary, undefined
rotation when source and destination coincide, and stop-time correction spins.

**Cause:** orientation is circular, so ordinary integer subtraction can choose
the long arc. A zero-length direction vector has no new facing direction.

**Implementation:** orientation deltas normalize into the shortest signed arc.
Coincident points preserve current facing. Stationary selection pins the target
to the current visible orientation. Native actions use native orientation while
active; their exit rebases position without adding a spin correction.

**Primary code:** `ShortestAngleDifference`, `MoveOrientationTowards`,
`getOrientationBetweenPoints`, `ApplyTweening`, and `SelectIdleAnimation`.

**Automated coverage:** the three orientation tests in
`CustomMovementHandlerTest`.

### TMA-R09 — Scene-load continuity

**Symptoms addressed:** disappearing during region loads and teleporting to the
destination once the area finishes loading.

**Cause:** `RuneLiteObject` scene metadata belongs to the old scene, but throwing
away interpolation history also throws away the last displayed world position.
The `Player` wrapper itself can also be replaced during a rebuild.

**Implementation:** `LOADING` invalidates scene-owned RuneLite objects and
neutral model data without treating the load as logout. The handler records the
last rendered world tile plus sub-tile offsets, creates a replacement object,
and rebases that world position into the new local scene. It rebinds a replaced
player wrapper only when identity/world-view checks are safe. If conversion is
impossible, it falls back to the current owner location instead of hiding a
stale replacement.

**Primary code:** `onGameStateChanged`, the `LOADING` branch of `onClientTick`,
`TrueMovementOverlay.InvalidateRuneLiteObjects`, `PrepareFrame`,
`CustomMovementHandler.Initialize`, and `RebaseAfterSceneLoad`.

**Automated coverage:** world-view suppression tests plus the scene-offset part
of `nativeMotionStartsAtRenderedAnchorAndPreservesDisplacement`.

### TMA-R10 — Camera follows the rendered frame

**Symptoms addressed:** camera Y-axis bouncing during hits/actions/PvP, camera
movement depending on overlay FPS, and camera jumps over uneven terrain or scene
transitions.

**Cause:** the old camera could sample the hidden owner while the replacement
model was elsewhere, use action-dependent model height, or update on a different
clock from the rendered model.

**Implementation:** camera preparation runs in `BeforeRender` from the same
prepared model location. Vertical target height comes from the stable footprint
terrain height and RuneScape follow-height calculation, not transient animation
height. Y changes are time-normalized and smoothed over 100 ms; large positional
discontinuities snap intentionally. Native camera mode is restored after each
draw so input and menus retain native click geometry.

**Primary code:** `UpdateAdaptiveCamera`, `SmoothCameraFocalPointY`,
`GetCameraFootprintTileHeight`, `onBeforeRender`, and
`PostDrawCameraModeHandoff`.

**Automated coverage:** `RenderStateTest` and
`AdaptiveCameraRenderingTest`.

### TMA-R11 — Explicit leaping-animation override

**Symptoms addressed:** users who prefer normal movement could not globally
disable plugin-selected hops/leaps.

**Implementation:** `DisableLeapingAnimations` is a normal tickable RuneLite
configuration item. `ShouldUsePluginLeap` is the single gate, so disabling it
wins over every plugin leap request without disabling ordinary movement.

**Primary code:** `TrueTileMovementConfig.DisableLeapingAnimations`,
`ShouldUsePluginLeap`, and `UpdateAnimationSelection`.

**Automated coverage:** `disablingLeapsOverridesEveryPluginLeapRequest`.

### TMA-R12 — Bounded hold, fail-open, and complete cleanup

**Symptoms addressed:** stale custom models remaining active, the player staying
hidden after an exception/cache miss, state leaking across plugin toggles, and
cleanup races.

**Cause:** a replacement renderer cannot assume every model resource and scene
conversion succeeds. Cleanup must restore any temporarily modified actor state
before disposing renderer state.

**Implementation:** short transient failures may hold the last successful frame
for at most five client cycles and only in the same world view. After that—or
when no valid frame exists—the renderer deactivates custom objects and exposes
the native player. Shutdown unregisters the auxiliary subscriber, restores any
pending neutral-capture state, clears callbacks/overlays/caches, deactivates both
RuneLite objects, and restores native camera mode. Expected recovery diagnostics
use `log.debug()`.

**Primary code:** `HoldLastRenderedFrame`, `FailOpenToOwner`, all `Cleanup`
methods, `TrueMovementOverlay.InvalidateNeutralOwnerModels`, and plugin
`shutDown`.

**Automated coverage:** the render-state fail-open tests and lifecycle-sensitive
neutral capture tests. In-game toggle/reload testing remains required.

### TMA-R13 - Interaction facing happens after arrival

**Symptoms addressed:** objects not receiving a turn, the first interaction
turning by only a tiny amount, a slow turn fighting the walk/run direction, and
the visible player spinning again when an approach ends.

**Cause:** RuneScape exposes two orientations. `Actor.getCurrentOrientation()`
is the actor's intermediate, currently-rendered turn; `Actor.getOrientation()`
is the exact desired heading. Using the former as the target produces the
one-degree/fractional-turn symptom. Also, `Client.getLocalDestinationLocation()`
is populated for some object interactions even when the server accepts the
interaction from the current tile. Treating that value as proof that movement
is required cancels valid object-facing requests.

**Implementation:** `TrueTileMovementPlugin` records only NPC/object world
actions (attack and cast are intentionally excluded so combat-facing and native
action rendering remain authoritative). A new world action replaces the pending
request; widget clicks do not clear it. `CustomMovementHandler` keeps the
request while the player approaches, but `UpdateStationaryInteractionFacing`
resets confirmation on every moving frame so the target cannot steer the
locomotion model. After the rendered model becomes stationary, the handler
waits briefly for a route/action decision, confirms from the native interacting
actor, a changed action animation, or the bounded fallback clock, and
`ApplyTweening` eases toward `Owner.getOrientation()`.

Movement completion still uses the last movement heading, but only when the
remaining circular difference is at most 128 orientation units (22.5 degrees).
This finishes a genuinely incomplete cardinal turn without resurrecting the
old full-spin correction. Requests are bounded and are cleared by a new world
action, scene invalidation, or timeout.

**Primary code:** `STATIONARY_INTERACTION_ACTIONS` and
`ShouldUseStationaryInteractionFacing` in `TrueTileMovementPlugin`, the request
forwarding/cleanup methods in `TrueMovementOverlay`, and
`RequestStationaryInteractionFacing`, `UpdateStationaryInteractionFacing`,
`ApplyTweening`, and `ShouldFinishMovementOrientationAfterStop` in
`CustomMovementHandler`.

**Maintenance rules:** never use a non-null local destination as a standalone
movement test; never steer an interaction target while `bStationaryThisFrame`
is false; and never replace the exact native target orientation with the
intermediate current orientation. If any of those rules changes, reproduce both
an adjacent object interaction and a multi-tile approach before accepting the
change.

**Automated coverage:** `stationaryFacingWaitsForNativeInteractionOrOneStableTick`,
`onlySmallIncompleteMovementTurnsFinishAfterStopping`, the cardinal direction
assertions in `CustomMovementHandlerTest`, and
`stationaryFacingOnlyTracksNonCombatNpcAndObjectActions` in `RenderStateTest`.
In-game confirmation remains required because menu timing and object reach
rules are revision-dependent.

## Why upper/lower body splitting was not implemented

The public RuneLite model/animation API used here does not provide a stable,
supported skeletal mask that identifies “upper body”, “lower body”, held-item
bones, and animation-specific transform groups for arbitrary player equipment
and transformations. Combining two full-mesh transforms is not equivalent to
layering masked skeletal animations; it was the mechanism behind distorted legs
and incorrectly held weapons.

The robust behavior is therefore to preserve RuneScape's full native composite
while a spell/action is live and move that composite continuously with the
native-motion anchor. Visually, the cast remains intact and movement continues,
but the plugin does **not** claim to independently animate native running legs
under a casting torso. Implementing true body splitting would require a new,
supported RuneLite API for skeletal masks and attachment transforms; it should
not be approximated by editing vertex groups in this plugin.

## Constants and their intent

| Constant | Value | Reason |
| --- | ---: | --- |
| `MAX_FRAME_DELTA_MILLISECONDS` | 100 ms | Prevent one paused frame from advancing an entire tween. |
| `MOVEMENT_SELECTION_GRACE_MILLISECONDS` | 100 ms | Cover a slightly late continuing route tick without creating a final-segment run-in-place. |
| `LAST_GOOD_RENDER_HOLD_CYCLES` | 5 cycles | Bridge a very short resource/capture gap, then expose the native player. |
| `MIN_NEUTRAL_APPEARANCE_STABLE_CYCLES` | 5 cycles | Avoid caching geometry during an appearance race. |
| `MAX_CACHED_ANIMATION_STATES` | 128 states | Reject unexpectedly large per-animation caches. |
| `MAX_TOTAL_CACHED_ANIMATION_STATES` | 512 states | Bound total pose-cache memory. |
| `MAX_NEUTRAL_CAPTURE_STATES_PER_WINDOW` | 32 states | Bound work and actor-suppression scope in one capture transaction. |
| `NATIVE_MOTION_SETTLE_CYCLES` | 30 cycles | Keep forced/native motion authoritative until native updates have settled. |
| `STATIONARY_FACING_CONFIRM_CYCLES` | 30 cycles | Allow an object route/action decision to settle before confirming a stationary turn. |
| `STATIONARY_FACING_SETTLE_TIMEOUT_CYCLES` | 180 cycles | Bound the stationary interaction-facing window (about 3.6 seconds). |
| `STATIONARY_FACING_REQUEST_TIMEOUT_CYCLES` | 3000 cycles | Bound a request that is still approaching its target (about 60 seconds). |
| `MAX_NORMAL_LOCAL_UNITS_PER_CLIENT_CYCLE` | 16 units | Separate normal run interpolation from accelerated displacement. |
| `MAX_NORMAL_DESTINATION_DELTA` | 2 tiles | Detect unusually large route changes. |
| `SPATIAL_DISCONTINUITY_DELTA` | 8 tiles | Treat a very large displacement as an intentional one-time snap. |

These are policy boundaries, not arbitrary tuning knobs. Change them with a
test that states the new boundary and with in-game testing of normal walking,
running, PvP actions, forced movement, and loading transitions.

## Tests and what they can prove

The unit suite verifies deterministic decisions: render suppression identity,
camera smoothing, timing bounds, frame packing, appearance-key invalidation,
native/custom ownership, forced-motion anchoring, route-boundary stopping,
stationary fallback, leap override, cache keys, and subscriber registration.

The suite cannot prove that a particular RuneScape revision's model looks
correct. Only in-game observation can validate visual composition, resource
availability, real event order, and GPU rendering. A clean build is necessary,
but it is not acceptance of visual behavior.

## Maintainer checklist for future rendering changes

Before merging a change, answer all of these:

1. Which `[TMA-R##]` invariant does it affect?
2. Does the native player remain visible until a complete same-world-view
   replacement exists?
3. Is the chosen model source either a detached appearance-matched locomotion
   pose or an unmodified native composite clone?
4. Can any cached array still alias a RuneLite scratch/shared model?
5. Are action, spot-animation, hand-override, and accelerated-motion paths still
   native-authoritative?
6. Does a handoff start at the last position actually rendered?
7. At a visible endpoint, can the code accidentally sample the hidden actor's
   remaining walking pose or orientation?
8. Does every capture/exception/shutdown path restore actor selectors and expose
   the native player?
9. Do camera and overheads use the same prepared frame?
10. Is there a focused unit test and a stated in-game test for the change?
11. For interaction-facing changes, does the request survive approach movement,
    remain inactive while moving, and use `getOrientation()` only after the
    rendered model stops?

## In-game acceptance matrix

After a clean development-client start, test at minimum:

- idle, walking, running, changing direction, and stopping on final and
  continuing route segments;
- one-handed, off-hand, and two-handed equipment while idle and moving;
- overhead-prayer changes while idle, running, casting, attacking, eating, and
  taking damage;
- casting then immediately clicking away, including repeated casts;
- hits/flinches and attacks while already moving in PvP;
- knockbacks, pushes, throws, fast movement, and genuine teleports;
- loading a new region while stationary and while moving;
- adaptive camera over flat ground, slopes, actions, forced movement, and PvP;
- leaping enabled and disabled;
- plugin off/on, logout/login, and GPU/plugin availability failure.

Expected failure behavior is also part of acceptance: if a custom pose cannot be
built, the normal player should appear correctly rather than leaving an
invisible, stale, or malformed replacement.
