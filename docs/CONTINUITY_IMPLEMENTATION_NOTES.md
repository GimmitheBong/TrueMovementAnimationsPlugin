# True Movement continuity implementation notes

This document records why the continuity fixes exist and the boundaries they
must preserve. It is intended to keep the rendering path understandable for
future maintainers rather than turning a set of visual fixes into a black box.

## Core design rule

The native player is authoritative for game state. The plugin only presents a
smoothed visual copy. During a scene rebuild or delayed client frame, it is
preferable for that copy to remain one visual frame behind authoritative data
than to jump, stall, reverse, or advance through collision geometry.

The continuity code must not inject input, alter the route, predict a red-click
interaction destination, or change server-visible state.

## `[TMA-STEADY-PRESENTATION]`: uninterrupted movement ownership

### Causes

Three independent handoffs could interrupt otherwise ordinary movement:

1. At every tile boundary, the native and custom locations can coincide for a
   frame. The "original model when close" path could therefore hide the custom
   model for that frame and switch back on the following segment. Even when
   both models occupy the same coordinates, their animation phases and render
   scheduling need not match.
2. Movement was classified as idle at exactly 600 ms. Overlay frames and game
   ticks are not phase-locked, so the next route point can be published a few
   milliseconds later and produce a one-frame run-to-idle-to-run transition.
3. Six client ticks without a GPU draw callback were treated as loss of GPU
   support. A normal render hitch could therefore clean up the model and
   animation controller before callbacks resumed.

Animation wrappers and native player models can also be replaced temporarily
while their underlying animation/model remains logically unchanged. Comparing
wrapper identity or assigning a transient null model turned those harmless
replacements into visible restarts or blank frames.

### Fix

- The custom model remains the sole presentation authority throughout movement
  and action animations. "Original model when close" still applies once the
  player is genuinely idle, close, and facing within the configured threshold.
- An uninterrupted yellow-click route receives up to 300 ms of animation-only
  grace between segments. It does not extrapolate position, does not apply to
  red-click interactions, and is disabled at the final route destination.
- `[TMA-FRESH-WALK-START]` distinguishes that continuation from a new yellow
  click made after the visible segment has completed. RuneLite can publish the
  new route before its first authoritative movement segment; the animation
  grace is suppressed during that delay so the completed model cannot run in
  place. The flag clears on the first real segment and therefore does not
  affect animation continuity during the subsequent run.
- Animation controllers compare animation IDs instead of Java wrapper
  instances.
- If RuneLite cannot provide a complete model for one render update, the last
  complete custom frame remains visible rather than being replaced with null.
- GPU callback loss must persist for approximately one second while
  `LOGGED_IN` before the plugin is considered unsupported. Scene loading and a
  brief long frame no longer tear down animation state.

### Maintenance invariant

During uninterrupted movement, never alternate render authority merely because
the native and custom coordinates coincide. Any future native/custom handoff
must happen from a stable idle state, and a missing transient resource should
retain the last drawable frame.

## `[TMA-CAST-MOVEMENT-ORDERING]`: casts followed by movement

### Cause

The old path had two authorities for a cast:

1. the player's live `Owner.getAnimation()` value; and
2. a delayed system which recognised selected cast/teleport animation IDs,
   stored timing flags, and later synthesized
   `HUMAN_CASTTELEPORT_REVERSE` (animation 715).

If the player clicked away during a cast, movement could start first and the
stored animation could replay afterward. That also set a teleport-style
position flag, producing a false snap or apparent teleport.

### Fix

The live player animation is authoritative immediately. The duplicate delayed
cast detector, interrupt flags, and synthetic reverse-teleport replay were
removed. Custom locomotion continues to advance in the background while the
action animation is visible, so movement can resume without replaying the cast.

This does **not** remove legitimate teleports. The generic
`bShouldTeleportToLocation` request remains for a real authoritative
discontinuity. The removed behavior was only the later, inferred cast replay.

### Maintenance invariant

Do not add a second timer which replays a cast after `Owner.getAnimation()` has
already exposed it. If a particular spell needs visual treatment, derive it
from the current authoritative animation without queuing another positional
authority.

## `[TMA-STOP-FACING]`: yellow-click stop spin

### Cause

After the visible model had reached its destination, the hidden native player
could still be catching up. Copying the native orientation during that interval
made the visible model spin while it searched for the native facing direction.

### Fix

A yellow walk click may arm a short orientation hold. Once visible movement
stops, the last visible facing is retained only while the hidden player catches
up. A red world interaction cancels that hold immediately, so NPC and object
facing remains controlled by the game.

The hold is deliberately conditional: it is not a general orientation override
and must not apply to red-click interactions.

`[TMA-STOP-FACING-SETTLE]` covers the smaller handoff after positional catch-up.
RuneLite exposes both a native target orientation (`getOrientation()`) and the
orientation currently displayed by the native actor
(`getCurrentOrientation()`). Reaching the destination does not guarantee those
values have finished processing the final route update. The hidden actor is
therefore allowed one stable game tick to settle before a later target change
is considered a new facing command. Route-end orientation churn remains hidden;
a red interaction still cancels immediately, and real movement clears the
released hold.

Native/custom proximity handoff compares the two orientations actually being
displayed. Comparing the custom orientation with the native *target*
orientation could approve a handoff while the native model was still turning,
which made the visible player turn or pop at the end of a yellow-click route.

## `[TMA-STOP-POSE-SEPARATION]`: keep facing ownership separate from animation

### Cause

Yellow-click stop handling must keep the custom object active briefly so the
hidden player's route-end orientation cannot spin the visible character. An
independent stopped-idle controller was added to give that object a separate
breathing clock. In-game captures later proved that this coupled two unrelated
jobs: the orientation hold worked, but the synthetic animation path published a
legs-out/arms-raised transition pose immediately before proper idle.

### Fix

The synthetic stopped-idle controller and all of its phase, first-frame, raw
transform, and priming paths have been removed. A later attempt copied the
hidden player's complete model during the hold. That avoided the malformed
synthetic frame, but it also copied the hidden player's unfinished locomotion
after the custom model had reached its responsive endpoint, visibly producing
walking or running on the spot. Slowing the final custom segment to wait for the
hidden player only disguised that mismatch and undermined true-tile response;
that positional synchronization path was removed as well.

There is now no yellow-stop-specific body-model path. Yellow and red stops both
use the ordinary idle animation publication and model-building code. The yellow
state owns only the custom object's orientation while the hidden actor settles.
Position still completes on the original custom movement clock, so stopping is
not delayed and no hidden locomotion pose is exposed at the endpoint.

The ordinary publication path deliberately retains a valid frame index across
compatible pose-animation changes. Directional run and walk variants can
change animation IDs between route segments; resetting each such change to
frame zero made the last tiles and occasional mid-route transitions visibly
jolt. The one exception is `[TMA-STATIONARY-IDLE-ENTRY]`: after visible movement
has ended and no action is playing, a still-published native locomotion frame is
not reused as the requested idle frame. This is based on the actual pose state,
not click colour, because both a yellow destination and a red-click approach can
leave the hidden actor catching up. That exact mismatch restarts from idle's
authored entry frame, preventing the hidden player's faster locomotion clock
from making the stationary breathing pose race or glitch. Once the native actor
publishes idle, its same-animation phase is retained normally. Invalid-frame
recovery remains stricter and reuses a remembered frame only when it belongs to
that same animation.

Actions still use their existing custom animation handling. The stop-facing
state continues to own orientation only, so the proven no-spin behaviour
remains independent from animation timing and body geometry.

A second yellow click received during active catch-up retains the current
observed stop-facing state until movement actually starts. `[TMA-YELLOW-RECLICK-HANDOFF]`
also records that this new route is pending: RuneLite can publish its
destination before the first visible movement frame, and that publication must
not make the old route fail its "final destination" check and release the model
early. If the hidden player finishes catching up during this delay, the code
moves directly into the normal preserved-facing state while keeping the new
route armed. A click during an already released state still cannot resurrect an
old route.

`[TMA-YELLOW-RECLICK-ROUTE-GRACE]` covers the same publication ordering while
an ordinary movement segment is still active. The focused recorder captured a
new yellow destination appearing at 533 ms of the old segment. Because the old
segment had not yet reached 600 ms, it was not marked as awaiting movement.
When it completed, the newer destination was mistaken for continuation of the
old route and the 300 ms route-gap grace played locomotion at a stationary
endpoint from roughly 630 through 891 ms.

Each yellow click now receives a revision. A movement segment records the
latest revision only when RuneScape actually publishes that segment. Route-gap
animation grace is permitted only when those revisions match. A mid-segment
re-click therefore leaves the already-visible segment untouched, but if that
segment finishes before a newer one arrives, the existing stable idle/facing
handoff owns the endpoint instead of stale locomotion. The first genuine new
segment clears the wait immediately. Red interactions still cancel the whole
yellow-click state, and no route position is predicted or extrapolated.

## `[TMA-CONTINUOUS-MOVEMENT-SPEED]`: do not arrive before the route clock

### Cause

`Movement Speed Multiplier` used to shorten the positional tween from the
normal 600 ms route-step interval. Animation selection and route publication
still followed the 600 ms game-tick clock. The visible model therefore reached
the authoritative segment endpoint early, stayed on that exact point, and
continued playing locomotion until the next route step arrived.

The size of that stationary interval was deterministic. With an ordinary
request multiplier of 1.0, settings 1.1, 1.2, and 1.3 completed position at
approximately 545, 500, and 461 ms, leaving about 55, 100, and 139 ms of
running or walking in place. Running commonly publishes a two-tile segment,
which made the pause appear every second or third tile.

There is no safe way to sustain a visible speed above RuneScape's
authoritative route publication rate. At the end of the known `Last -> Next`
segment, the plugin must either wait for RuneScape or predict an unpublished
tile. Predicting toward the final clicked destination previously caused wall
crossing, corner cutting, and movement onto interaction objects, so it is not
used here.

### Fix

Ordinary route segments keep their complete 600 ms clock. The user-facing
multiplier now produces a bounded lead *inside* that known segment:

`f(t) = t + k * t^2 * (1 - t)^2`

The curve is deliberately constrained:

- `1.0` is exactly normal progress;
- values above `1.0` keep the model closer to the true tile during the middle
  of the segment;
- every rendered point remains between the two authoritative endpoints;
- progress stays monotonic and cannot reach `Next` before 600 ms;
- the added lead has normal velocity at both endpoints, so consecutive linear
  movement segments join without a stop or speed jump; and
- the effective lead is capped at 1.75, keeping the curve strictly monotonic
  even if a larger value is entered manually.

The animation-request multipliers used by optional leap, Woox-walk, and
tick-perfect animations remain separate. Those values are authored
choreography and retain their existing `600 / request multiplier` duration;
folding them into the new cap would make distinct jump animations travel at
the same rate and could desynchronise their landing frames.

Teleport snaps, scene-boundary bridging, scene rebase/recovery, and the scene
presentation clock retain their specialised timing and bypass this curve. The
unfinished-route animation grace is also retained because captures proved it
prevents real `run -> idle -> run` seams when the next route segment is
published late. It remains a separate, bounded route-publication correction;
it must not be widened to compensate for ordinary active-segment pacing.

### Maintenance invariant

Do not restore multiplication of the user setting into positional duration or
extrapolate toward `client.getLocalDestinationLocation()`. A user movement
speed adjustment must stay inside the currently published segment and must not
arrive at its endpoint before the authoritative route-step clock. Preserve
animation-request timing unless that animation is being deliberately retuned.

## `[TMA-MOTION-CONTINUITY]`: use the last displayed state at handoff

### Cause

Local scene coordinates and `WorldView` wrapper objects are not stable across a
region rebuild. Resetting because a wrapper instance changed, or rebasing from
new local coordinates before the new scene was drawable, discarded the last
displayed position and animation phase.

### Fix

The handler retains the last rendered world tile, sub-tile offset, animation
controller, orientation, and interpolation state. Equivalent world views are
compared by their stable identity rather than Java wrapper identity. When a new
scene becomes usable, its local coordinates are derived from that retained
world-space presentation state.

This is a render handoff only. Normal movement interpolation remains the
authority after the handoff; it was intentionally not replaced with
native-delta interpolation because that caused models to overshoot interaction
tiles and cross objects.

## `[TMA-SCENE-LOAD-CONTINUITY]`: atomic scene replacement

### Cause

`RuneLiteObject` instances belong to a scene. Destroying the old object as soon
as `LOADING` began left a blank frame, while creating or rebasing the new one
later in the overlay allowed the native and custom presentations to disagree.

### Fix

- `LOADING` is a soft suspension, not a movement reset.
- A scene generation counter ensures the custom object is recreated once for
  the new scene.
- `onBeforeRender` prepares and rebases the object before native-player
  suppression is allowed for that frame.
- If preparation is not complete, the native player remains the fallback
  instead of rendering neither player.
- The new custom object starts from the last successfully displayed world
  position and sub-tile offset.

For yellow-click running at an exact outward scene edge, a small bounded
boundary bridge may preserve forward momentum until the next route point is
available. It is excluded from red-click interactions and is capped so it
cannot become route prediction or recreate the “run onto the object” bug.

## `[TMA-PLAYER-ONLY-RENDER-FILTER]`: never filter native scene objects

### Cause

RuneLite's `RenderCallback.drawObject` is not a player-only callback. It runs
for native walls, decorations, ground/game objects, dynamic transforms, and
temporary actors. It also runs during GPU scene upload on the map-loader
thread. The old callback queried `MovementHandlerCache` with every
`TileObject.getId()`. A native object definition ID and the local player's
index are unrelated values but can contain the same integer.

An accidental collision therefore treated a tree, stump, herb patch, large
room component, or another native object as the hidden local player and
returned `false`. For dynamic objects this could leave the wrong transform
visible; during scene upload it could omit enough geometry to expose only the
skybox. The same path could also inspect the scene-owned custom
`RuneLiteObject` from the map-loader thread.

### Fix

The client thread now decides whether the current custom model is ready to
replace the native local player and publishes only a tiny render snapshot:
the local player reference, player index, world-view ID, and scene/UI hide
flags. Scene invalidation, native handoff, logout, renderer fallback, startup,
and shutdown clear the snapshot before any stale custom object can be used.

`drawObject` reads only that published snapshot plus the tile
object's ID and packed hash. It returns `false` only when all three identity
parts match: the hash says the entity is a player, its ID equals the published
local-player index, and its world-view ID equals the published view. A tree or
herb patch can share the same integer ID and still cannot pass the player-type
check. A player from another view cannot pass the world-view check. With the
snapshot disabled, the predicate fails open and draws everything.

The separate UI callback compares the renderable with the published local
player by object identity. It no longer compares `toString()` output or reads
the movement-handler cache while rendering. A player entry in the GPU-only
`drawObject` callback updates the renderer-support heartbeat using two volatile
scalar writes. Static map-loader entries do not even update that heartbeat,
and the callback performs no live client, handler, model, or `RuneLiteObject`
reads. The more general `addEntity` callback cannot be used as the heartbeat
because RuneLite invokes it even without the GPU plugin.

This leaves RuneScape and the GPU plugin fully authoritative for object
spawn/despawn, tree/stump and farming-patch transforms, world entities, and
scene upload. It changes no movement, camera, interpolation, or scene-recovery
timing.

No object spawn/despawn listener, scene scan, or replacement-object cache was
added. The plugin does not own those native lifecycles and does not override
`drawTile`; attempting to refresh or recreate objects here would duplicate the
game/GPU renderer and introduce a second source of truth. The correct repair is
to stop vetoing native content and let each authoritative transform be uploaded
and drawn normally.

### Maintenance invariant

Never infer actor identity from a bare `TileObject` ID. Any render callback
which can execute during scene upload must use the published primitive/value
snapshot only. It must never touch `MovementHandlerCache`, a
`CustomMovementHandler`, live client/player state, or a scene-owned
`RuneLiteObject`. The predicate must remain fail-open for every object which
is not the exact local player in the exact current world view.

## `[TMA-POST-SCENE-YELLOW-HANDOFF]`: wait for the first replacement-scene segment

### Cause

A scene can finish rebasing before the client publishes the first movement
segment belonging to that replacement scene. In the clearest captured case,
the displayed segment, draw point, and hidden owner all coincided while the old
yellow-click destination still appeared one segment ahead. The generic
unfinished-route grace therefore kept locomotion active against a clamped
position. Once that grace expired, the destination still looked unfinished,
so the ordinary final-stop facing hold was denied and the visible orientation
started chasing the hidden player's new target. This produced the reported
run-in-place followed by a spin.

### Fix

After a successful same-view, non-teleport rebase, an already observed yellow
route with an unfinished destination is marked as waiting for its first
authoritative post-scene segment. This deliberately reuses the existing
pending-yellow handoff:

- scene-recovery movement may continue, but cannot clear the pending state;
- once the displayed recovery reaches its endpoint, facing and the native pose
  presentation remain stable;
- route-gap locomotion cannot run at the same time as that stop-facing hold;
- the first real tile segment clears the wait immediately and resumes ordinary
  locomotion; and
- a red interaction or real discontinuity clears the state synchronously.

No destination is predicted and no position is extrapolated. The handoff only
selects the stable pose/facing to display while RuneLite has not yet published
the next segment. Stop detection also uses the active scene-recovery duration,
so a recovery longer than the normal 600 ms cannot be misclassified as idle
while it is still visibly moving.

## `[TMA-SCENE-RECOVERY-TILE-DISTANCE]`: do not mistake diagonal running for a teleport

### Cause

`Player Model Snap Distance` is expressed in RuneScape tiles, but the scene
rebase path compared the straight-line Euclidean distance between the last
displayed point and the new authoritative point. With the setting at two, an
ordinary two-tile diagonal scene advance measured `sqrt(2^2 + 2^2) = 2.83`
and was classified as a discontinuity.

The later capture set made the cutoff deterministic. Of 36 ordinary
non-teleport transitions, all 22 retained displacements at or below 2.00 kept
continuity without snapping. All 14 ordinary displacements between 2.24 and
2.83 snapped directly to the hidden player, disabled recovery/presentation
timing, and commonly
selected idle before the next route segment. Genuine teleports in the same
captures were hundreds or thousands of tiles away.

### Fix

Scene snap distance now uses tile-step distance: the absolute X and Y
differences are checked independently against the configured tile count. A
two-by-two diagonal is therefore two RuneScape tiles, while a displacement
that exceeds the setting on either axis still snaps.

This changes only the scene-rebase discontinuity test. World-view and plane
changes still snap, while teleport/special-animation guards continue to reject
preserved pre-load velocity. Recovery still targets only RuneScape's current
authoritative true tile. It does not interpolate toward the farther route
destination and does not alter ordinary movement or object-interaction
interpolation.

## `[TMA-SCENE-PRESENTATION-CLOCK]`: pay back scene-build time gradually

### Cause

Scene logs showed that the object could be prepared roughly 180–190 ms before
its first drawable frame. Charging all of that elapsed time to the first
visible update produced an abrupt player/camera jump. Freezing the timer instead
caused a stop followed by a fast catch-up.

### Fix

The first drawable frame marks the actual presentation time. Time consumed by
scene preparation is deferred as visual time debt:

- the first recovery delta is capped at 34 ms;
- deferred time is repaid at no more than 20% of each current frame delta;
- debt is capped at 600 ms; and
- route retarget duration is lengthened to preserve the model's visible
  pre-load velocity.

This creates a gradual ease into current authoritative state. It does not
discard movement or change the route; the rendered model may simply trail it by
a small amount while continuity is restored.

The later traces also establish the hard limit of this mechanism. Six
ordinary moving transitions had complete client/presentation gaps of
142-225 ms, and a stationary transition also paused for 142 ms. Most of that
interval appeared as presentation debt between pre-render preparation and the
completed scene draw. No plugin can synthesize frames while RuneLite's
client/render thread is not drawing. The continuity path can, however,
prevent a second visible fault after that native pause: the corrected
tile-distance check resumes from the retained presentation and eases toward
the current true tile instead of snapping several tiles forward.

`[TMA-SCENE-REBASE-FIRST-DELTA]` closes the final smaller timing asymmetry.
`UpdateFrameTimer()` runs before a replacement scene activates the
presentation clock, so the first recovered update previously consumed its raw
20-49 ms delta even though later updates were capped at 34 ms. The initial
rebase now uses the same 34 ms cap and moves any excess into presentation
debt. Consecutive retained-custom recoveries saturating-add that new debt
instead of discarding time still owed by the preceding recovery. A native
handoff or zero-distance rebase resets it because no custom recovery clock
remains to repay it. This can soften the first position/camera step after
rendering resumes; it cannot fill the preceding interval in which RuneLite
drew no frames.

`[TMA-SCENE-RECOVERY-VELOCITY]` corrects the timing of that authoritative
recovery. The 2026-07-30 captures showed `SceneRecoveryBaseVelocity=0.0` for
all 23 moving scene transitions. Production calculated the value with integer
division, so an ordinary recovery shorter than 600 local units was truncated
to zero; whenever proportional duration was requested,
`GetSceneRecoveryTweenDuration()` therefore fell back to a complete 600 ms
tick.

This was most visible in the six captures where the scene-edge bridge was
active. Their shorter rebased segments were still stretched over 600 ms,
slowing by 10-25% (20.6% on average) before deferred-time repayment
accelerated them again.
Immediately before rebasing overwrites the old endpoints, the handler now
retains the last segment's floating-point scalar velocity. A partial recovery
uses a proportional duration—for example, 192 remaining local units at a
256-units-per-600-ms run rate takes 450 ms. The six measured bridge recoveries
now calculate to 450-541 ms instead of all taking 600 ms.

The retained value is deliberately narrow:

- it is scalar timing only; old direction and route destinations are never
  extrapolated;
- it is retained only when the scene-edge bridge proves that an observed
  yellow-click route was still moving out through the rebuild margin;
- red-click object/NPC/player interactions do not opt into this timing change;
- owner and requested teleport/agility exceptions, plane changes, and real
  discontinuities reject it;
- when the first plausible one/two-step authoritative segment arrives, that
  segment's actual walk/run velocity replaces the pre-load value; and
- a segment that is too large to be a normal native step cannot provide a
  recovery velocity.

Interpolation therefore remains between the last presented position and
RuneScape's current authoritative tile. The scene-edge bridge is disabled while
recovery, its presentation clock, or a duration override owns the frame, so the
two continuity mechanisms cannot compose into an overshoot. Recovery duration
has a one-tile-per-normal-tween minimum velocity and a two-tween maximum
duration, preventing tiny offsets or malformed values from creating long
tails.

When a proportional recovery is shorter than 600 ms, completion collapses its
endpoints before clearing the duration override. Without that state transition,
the following frame would reinterpret (for example) 466 elapsed milliseconds
against 600 ms and visibly move the model backward. A longer recovery also
keeps locomotion selected for its full duration.

The presentation clock also remains alive until the prepared scene frame is
actually marked as presented. Finishing a short positional recovery inside the
pre-render update no longer drops deferred render time before it can be
recorded.

The 18 supplied attachments were cumulative rolling captures, not 18
independent transitions. Deduplication produced 198 unique scene events:
23 moving transitions and 14 stationary transitions. The eight Corrupted
Gauntlet room openings were all stationary. They showed 16-49 ms native
`LOGGED_IN` gaps while bridge, recovery, clock, and debt were all disabled.
No safe plugin-generated motion exists for those pauses because the
client/render thread is not presenting frames. The velocity correction
therefore changes moving recovery only and does not synthesize movement for
stationary room openings.

## Adaptive camera continuity

The camera follows the same presented model state, expressed in pseudo-world
space across scene changes. Camera handoff uses the exact last presented
position and the same bounded frame delta. It only synchronizes to the native
player when the native player was actually the presented fallback. This avoids
using a newer native position for the camera while the visible model is still
recovering.

`[TMA-ADAPTIVE-CAMERA-VERTICAL-CONTINUITY]` applies the same continuity rule to
camera height. X/Z were already eased, but focal Y was assigned directly from
terrain and animation height every frame. Area-load traces showed a continuous
horizontal camera with `snapped=false` while focal Y changed by roughly 48-56
height units on the first destination frame. The retained last-presented focal
height now approaches the new target with an 80 ms, frame-rate-independent
half-life. Native-camera presentations refresh that retained source value, so
the easing begins from the camera the user actually saw rather than a stale
custom value.

## Investigation evidence and production boundary

The movement and area-load work used several temporary, narrowly scoped
recorders. They compared scene generation, local/world coordinates, route
publication, model ownership, camera state, animation requests, prepared-model
outcomes, and timing around the reported frame. They established four important
facts:

- scene rebuilds can contain a real 16-75 ms interval in which the client
  presents no frame. The plugin cannot render through that pause, but it can
  avoid adding a second jump when rendering resumes;
- some uninterrupted routes publish their next segment 8-258 ms after the
  previous 600 ms segment ends. Selecting idle during that feed gap creates a
  visible `run -> idle -> run` seam even though the model never disappeared;
- the five-second idle bump after a yellow click came from retaining the former
  synthetic idle controller after positional and facing settlement, not from
  the native idle loop itself; and
- the repeated-yellow-click defect that was finally isolated was malformed
  custom geometry. The requested, native, and prepared pose IDs all remained
  run 1661 while one frame showed legs spread and arms raised. Animation IDs
  therefore could not classify that frame correctly.

The investigation recorders, chat markers, queues, background logging,
preparation counters, model hashing, and log-formatting paths were removed after
their investigations. The final stop-idle trace established that position,
presentation ownership, controller state, and model availability remained
stable while the intentional `[TMA-STATIONARY-IDLE-ENTRY]` rule restarted idle
from its authored entry frame. A follow-up trial which instead carried the
locomotion frame into idle restored the previously documented sped-up/jumpy idle
until the hidden actor caught up, so that separation must remain. Locomotion
smoothness is handled independently by `[TMA-INTERPOLATION-CONTINUITY]`. The
evidence is retained here rather than keeping diagnostic work in the production
client path.

If diagnostics are needed again, never add an extra `Owner.getModel()` call or
inspect scene-owned model geometry from an overlay/render callback: scene
replacement can invalidate it between callbacks and this previously caused
client crashes. A render-rate recorder may copy only primitive values already
read by the normal path into a bounded queue; all formatting and log writes must
remain on the client thread.

## `[TMA-AUTHORITATIVE-ROUTE-ORIGIN]`: keep rendered and route coordinates separate

A later teleport-preservation change accidentally applied its recovery anchor
to every ordinary route update. It seeded both interpolation and
`LastTrueTilePosition` from the fractional point last drawn on screen and could
also mark a same-scene route as pending scene recovery. A bounded locomotion
trace then showed that recovery state leaking into ordinary same-scene segments.
The same capture also contained repeated run/walk pose publication and presented
`run -> idle -> run` seams while the custom model remained the sole render
authority. It showed no null model, controller takeover, invalid accepted frame,
or native/custom presentation swap. The trace did not prove that every short
route-publication gap or authored one-tile animation change came from the leaked
recovery flag, so those visual cases still require in-game validation after the
confirmed regression is removed.

The confirmed pre-regression invariant is restored: an ordinary route update
starts at the previous authoritative endpoint, and its exact true-tile baseline
is not overwritten by the fractional rendered point. Only an explicitly armed
scene-recovery or boundary bridge may continue from the displayed coordinate.
Generic route updates clear recovery-pending state; the POH arrival guard keeps
its own destination-scoped native-coordinate synchronization before this code.
This does not invent movement, retain run through an authored one-tile segment,
or broaden yellow-route grace to red interactions.

## `[TMA-UNFINISHED-ROUTE-ANIMATION-CONTINUITY]`: bridge late route publication

Captures showed valid next route segments arriving after the ordinary 600 ms
segment clock, most commonly at 608-619 ms and in area-transition cases as late
as 852-858 ms. The custom object remained active, drawable, and authoritative;
the interruption was an idle or turn request between two valid locomotion
requests.

Locomotion is therefore retained for a bounded 300 ms feed gap only when the
previous frame was moving, a previously observed yellow-click route still owns
continuity, the known segment has ended, and RuneScape still publishes a
different same-world-view destination. Position remains clamped to the
collision-valid endpoint. The code does not predict a tile, cross scenery, or
move onto an interaction object. A fresh click that has not produced its first
segment does not receive this general grace, and a red interaction cancels it
immediately.

## `[TMA-NATIVE-ANIMATION-LOOPS]`: honour authored loop points

The plugin formerly forced controller-driven animations back to frame zero on
completion. RuneLite's `AnimationController.loop()` honours the sequence's
authored `frameStep`, which may separate a one-time lead-in from the part that
is intended to repeat. The main and camera-model controllers now use
`loop()`; target-killed cleanup and all animation selection/timing remain
unchanged.

This was a valid general correction but not the human idle-808 fix. Idle 808
has `frameStep=-1` and naturally wraps from frame 11 to frame 0, while the main
controller is inactive during established idle. Keeping that distinction in
the notes prevents the loop correction from being mistaken for the later
yellow-click ownership fix.

## `[TMA-PENDING-RECLICK-POSE-HANDOFF]`: retain locomotion across the click-to-route seam

A captured failure showed this exact sequence:

1. a new yellow click arrived while the old movement segment was visibly active;
2. the old segment ended at 609 ms;
3. the new click revision was still waiting for its first authoritative route
   segment, so the normal fresh-click guard correctly rejected the broader
   300 ms route grace;
4. the former synthetic walk-stop idle controller was selected for one client
   tick; and
5. the replacement route segment arrived and locomotion resumed.

The handler now records whether the click occurred during a genuinely visible,
non-zero movement segment. Only that case may retain the existing locomotion
pose for up to 50 ms while the newer click revision awaits its first segment.
Position stays at the last valid endpoint and orientation remains held. The
state clears on the first new segment, a red interaction, scene recovery, a
click made after stopping, or expiry. This is intentionally separate from the
native stop-pose path: it prevents an evidenced
`movement -> idle -> movement` selection seam, whereas a genuinely completed
route now follows RuneLite's continuously advancing body pose.

## `[TMA-VALID-POSE-FRAME-PUBLICATION]`: never build visible geometry from frame -1

The remaining video-confirmed frame was neither a proper idle pose nor a
native/custom overlap. It was unposed or bind-like custom geometry: legs spread
and arms slightly raised. The surrounding state continued to report run 1661,
the custom object stayed active, and source/merge preparation reported success.
A generic animation-ID transition rule cannot protect a bad frame when the ID
is identical before, during, and after it.

The crash produced by the later controller experiment supplied the missing
scalar evidence. At the affected route handoff, the pose animation still
reported run 1661 but `Owner.getPoseAnimationFrame()` was `-1`. Passing that
value to `AnimationController.tick()` caused an `ArrayIndexOutOfBoundsException`.
The interrupted controller render then left the actor's pose fields invalid;
subsequent `Owner.getModel()` calls attempted frame 65535 and the game client
crashed. That advancing controller bridge was removed completely.

The ordinary owner-pose path now fixes the invalid state directly. After a
model is successfully prepared, the handler remembers its non-negative pose
frame together with its animation ID. Before the next `Owner.getModel()` call:

- a valid in-range current frame remains authoritative;
- a negative frame reuses the last valid frame only when it belongs to the
  same requested pose animation; and
- a reset, out-of-range frame, different animation, or unavailable cache uses
  the request's validated starting frame.

The existing animation-controller path also refuses to seed a controller from
a negative owner frame. This prevents the same array-index failure in action
or exception animations.

The invalid-frame correction is retained as crash hardening, but later in-game
testing established that the visible bind-like pose was not specifically a
re-click/start-of-route problem. It occurred at the genuine movement-to-idle
edge, often on every yellow-click stop. That distinction matters: rebuilding a
locomotion frame when a replacement route starts cannot repair a model exposed
when the previous route finishes.

## Removed experiments

The cleanup deliberately removed approaches that did not solve the
reported frame:

- making the hidden native walk/run pose authoritative produced a walk-to-run
  change halfway through a two-tile segment when the native actor caught up;
- deferring every changed locomotion pose ID for one client tick still allowed
  the defect because the malformed frame retained the same run ID;
- wall-clock and update-scoped static model holds hid the bad pose by repeating
  visible geometry, producing the reported mid-run/deceleration freeze; and
- forcing normal locomotion through an advancing `AnimationController` accepted
  frame `-1` and crashed the client. That ticking path was removed completely.
- creating a separate stopped-idle controller remained the common failure path
  after first-stop ownership, raw frame zero, retained non-zero frames, and a
  primed interpolation sample were each tested in game. The bad pose survived
  every variant, so the entire controller—not merely the latest attempted
  workaround—was removed.

Those removed rules do not remain in production. Removing them avoids animation
latency and keeps the working fix tied to the actual movement-to-idle handoff.
The earlier 50 ms pending-reclick pose handoff remains because it addresses a
different, directly captured idle-selection seam.

The one-update no-tick pose rebuild originally aimed at the first replacement
segment was also removed after repeated stopping showed that it targeted the
wrong lifecycle edge. Keeping it would add a second geometry path without
protecting the frame that was actually visible.

An experiment that restored the hidden actor's pose fields immediately after
model construction was also removed after it broke arm and leg positioning.
Those fields remain part of the current model-rendering contract and must not be
treated as disposable scratch state.

## Focused tests and validation boundary

The retained tests cover:

- world/local rebasing and scene identity;
- atomic scene generation acknowledgement and bounded scene-edge bridging;
- presentation-time deferral, debt repayment, recovery duration, and velocity;
- adaptive camera handoff, delta limiting, and vertical easing;
- late-route animation grace versus a genuinely fresh click;
- post-scene and repeated-yellow-click segment ownership;
- native-facing settlement and native-pose selection during a held stop;
- hash-qualified local-player suppression versus same-ID game objects, other
  players, and other world views; and
- valid, invalid, stale, and reset pose-frame publication boundaries.

These tests validate rendering math and state transitions. Only the user can
confirm the final visual behaviour in game because a JVM test cannot reproduce
RuneLite's complete client/render scheduling.

## `[TMA-TELEPORT-CORRECT]`: do not treat PvP spell casts as teleports

### Cause

The restored teleport detection in `onGameTick` inherited a list of animation
IDs from the original plugin. Two of those IDs were not teleports:

- `ZAROS_VERTICAL_CASTING` (1979) is the Ancient Magick combat cast
  animation (Ice Barrage, Blood Barrage, etc.).
- `ARCEUUS_NECROMANCY_ANIM` (3865) is an Arceuus spellbook cast animation.

Every time a player cast one of these spells during PvP, `onGameTick` armed
`LastTimeTeleport` / `bShouldPlayTeleportAnimation`. The teleport-in branch in
`UpdateAnimationSelection` then set `bShouldTeleportToLocation = true`, and
`ApplyTweening` produced `TweenValue = 1.0` — an instant snap to the native
position at the end of the segment. The visible model skipped 1–3 tiles on
every spell cast while moving, and in some cases the reactivation produced the
entity spawn-in scale effect.

Frame-to-frame position diagnostics confirmed the pattern: all PvP-related
jumps logged `teleport=true` with `moving=true` and a tiny elapsed time, while
the death-scene rebuild logged `teleport=false` with a large frame delta.

### Fix

- `onGameTick` now detects only genuine teleport animations:
  714, 878, 1816, 3872, 13811, 4069, 4071, 3869 and 2881.
- `UpdateAnimationSelection` gates the teleport-in branch on
  `overlay.bShouldPlayTeleportAnimation` so a stale time window can never
  arm the position snap unless a genuine teleport animation was observed.
- `CustomMovementHandler`'s unique-animation exception list was also pruned
  of the two spell-cast IDs so no other lerp path treats them as teleports.
- Combat spells (entangle, fire surge, Flames of Zamorak, Claws of Guthix,
  etc.) were never in the teleport list and remain unaffected.

### Maintenance invariant

Do not add spell-cast animations to `onGameTick`'s teleport detection. A
teleport-in presentation may only be armed by an animation that actually
moves the player to another location. If a new spell's animation ID is ever
needed for lerp treatment, it belongs in the movement-animation path, never
in the teleport list.

## `[TMA-INTERPOLATION-CONTINUITY]`: do not reset the client interpolation timer on transient -1 pose frames

### Cause

RuneLite's built-in animation interpolation (enabled by the Animation Smoothing
plugin via `client.setAnimationInterpolationFilter()`) tracks the time since
the last `setPoseAnimationFrame()` call to calculate sub-frame vertex blending.
Every explicit call resets that timer.

The game engine occasionally publishes `Owner.getPoseAnimationFrame() == -1`
at route handoffs, even during continuous straight-line running.  The old
pose-publication block treated *any* invalid frame as a reason to call
`SelectPoseFrameForPublication` → `Owner.setPoseAnimationFrame()`, which
reset the interpolation clock mid-stride.  The result was a visible stutter
every few frames during locomotion while the idle breathing cycle remained
smooth (idle 808 is never touched after its initial set).

### Fix

The `Owner.getPoseAnimationFrame() < 0` guard was removed from the
`if (!bUsedCustomAnimation)` pose-publication block.  The block now only
enters when the pose animation ID changes or an explicit reset is requested.

A transient -1 frame is harmless for one update: the `TrySetModel` fallback
already preserves the last drawable model when `Owner.getModel()` cannot
supply one.  On the next client tick the game engine publishes a valid frame
again, the model updates, and the interpolation timer has never been
interrupted.

The `SelectPoseFrameForPublication` helper and the `LastValidOwnerPoseFrame`
fallback are retained — they still protect the case where the animation ID
*has* changed and the naive frame read would be stale or invalid.

### Maintenance invariant

Do not add `Owner.getPoseAnimationFrame() < 0` back as a pose-block trigger.
If an invalid frame must be corrected, do it through the model-building path
(`TrySetModel` / `Owner.getModel()` null-guard) rather than by explicitly
calling `Owner.setPoseAnimationFrame()` outside of an animation-ID change.
