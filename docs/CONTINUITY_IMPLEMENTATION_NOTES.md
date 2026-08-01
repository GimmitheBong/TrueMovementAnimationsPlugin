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

## `[TMA-IDLE-CATCH-UP]`: smooth idle while the native player catches up

### Cause

The custom model could enter idle while its animation clock was still tied to
the hidden player's locomotion/catch-up state. Breathing and head movement then
looked accelerated or choppy, with a visible phase change when ordinary
animation smoothing resumed.

### Fix

The catch-up interval has an idle animation controller seeded from the native
idle frame. It advances on the visual frame clock, then hands its phase back
without resetting. A new click does not reuse the old catch-up controller, so
movement starts from the current displayed pose rather than producing a
one-frame jump.

`[TMA-IDLE-HANDOFF-CONTINUITY]` keeps that same controller through the short
native-facing settle period after positional catch-up. Diagnostics showed the
controller itself advancing animation 808 correctly (one frame after its
21-client-tick frame length), but the native pose could jump several frames
within a few client cycles when ownership was returned. The hidden locomotion
pose had retained elapsed phase which cannot be transferred through RuneLite's
public actor API. Keeping one controller for the complete stop-facing hold
avoids exposing that intermediate clock. It is released when real movement, a
red interaction, or a later native facing command ends the hold.

A second yellow click received during active catch-up retains the current
Observed/idle state until movement actually starts. `[TMA-YELLOW-RECLICK-HANDOFF]`
also records that this new route is pending: RuneLite can publish its
destination before the first visible movement frame, and that publication must
not make the old route fail its "final destination" check and release the model
early. If the hidden player finishes catching up during this delay, the code
moves directly into the normal preserved-facing state while keeping the new
route armed. A click during an already released state still cannot resurrect an
old controller.

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
published late. The opt-in recorder distinguishes that separate
`route-gap-clamp` case from an ordinary active-segment endpoint clamp, so it
can be changed from evidence rather than by reopening the earlier flicker bug.

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
- once the displayed recovery reaches its endpoint, facing and the independent
  idle controller remain stable;
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

## Diagnostics and tests

The earlier diagnostic loggers were temporary and were removed after their
original investigations:

- `[SceneLoadTrace]` compared scene generation, route/local/world coordinates,
  model presentation, camera presentation, and frame timing.
- `[VisibilityTrace]` exposed native/custom presentation changes and showed
  that a newly published yellow-click destination could arrive before its
  first visible movement segment.
- `[IdleCatchUpTrace]` separated the custom idle controller clock from the
  hidden native pose clock. It confirmed the custom clock was advancing
  normally and the visible speed-up came from handing back to the native
  actor's accumulated locomotion phase.
- `[LocomotionTrace]` recorded only movement transitions, animation request
  changes, and unexpected frame regressions. Final testing contained no
  unexpected regressions: changes between walk and run poses matched genuine
  one-tile and two-tile movement segments. No additional locomotion controller
  was added.
- `[MovementContinuityTrace]` isolated unfinished-route idle/turn requests from
  real model loss. It established the bounded route-gap grace and the delayed
  first-segment condition after scene replacement.
- `[SceneMotionTrace]` correlated scene generation, rebase/recovery ownership,
  movement speed, and retained camera state. It established which scene-load
  corrections were plugin-side and which pauses contained no drawable client
  frame at all.

Those investigation-specific instruments and their temporary area-load chat
marker were removed. They are not part of normal production execution. The
conclusions below are retained because they explain the otherwise non-obvious
timing bounds in production code.

### Opt-in player flicker recorder

`[TMA-PLAYER-FLICKER-DIAGNOSTICS]` is a separate, disabled-by-default tool for
the remaining rare one-frame player interruption. It exists because recording
at 60 fps showed a visual `run -> idle -> run` seam which is too brief to
identify reliably from a report made after the route has finished.

The recorder does not alter movement or rendering. Its safety boundary is
deliberate:

- `TrueTileMovementPlugin.onClientTick` is its only live sampling point;
- `CustomMovementHandler` reads the native actor and custom `RuneLiteObject`
  only there, while the scene is stable;
- the recorder itself receives immutable copied/value snapshots and has no
  access to live actors, the client, or `RuneLiteObject`; and
- render callbacks and overlays never inspect a `RuneLiteObject` for this
  diagnostic. This avoids the crash previously caused by reading a scene-owned
  object while RuneLite was replacing it.

The detector derives expected movement independently from the selected
animation. A same-scene interpolation segment is expected to move until its
tween ends; an observed yellow-click route with a different published
destination is also expected to continue while it is not waiting for its first
authoritative segment. This independence matters: using
`bMovingThisAction` as both the expected value and the value under test would
hide the exact idle-selection bug being investigated.

A single suspicious sample is not reported immediately. It becomes an
incident only when movement/custom presentation recovers within the next few
client ticks (`moving -> suspicious -> moving`), or when the bad state persists
for three samples. Genuine route completion, stop-facing handoffs, new-click
first-segment waits, teleports/special movement, scene rebase/recovery, loading,
hopping, renderer fallback, and logout cancel the candidate. During a real
spell/attack/eat animation, native idle-pose fields are not considered proof of
a flicker, but a missing custom presentation is still reportable. This keeps
combat visibility failures observable without misclassifying the action itself
as idle. A three-second monotonic cooldown and a single active post-window
prevent one visual interruption from producing repeated messages.

When `(Debug) Auto-Log Player Flickers` is enabled, a confirmed incident:

- writes up to 12 pre-trigger samples, the candidate/confirmation, and 20
  post-trigger samples under `[PlayerFlickerTrace]`;
- records animation requests and frames, interpolation timing and endpoints,
  route state, orientations, scene-continuity state, and native/custom render
  authority; and
- posts `True Movement: possible flicker #N recorded` locally. The same
  `incident=N` appears in the debug log, so the player can identify and export
  the exact event they saw.

The recorder recognises three independent incident reasons:

- `idle-selected-during-movement` for a locomotion-to-idle/turn selection seam;
- `custom-presentation-missing` when the custom model should own the frame but
  is not ready; and
- `position-stalled-during-movement` when locomotion remains selected while
  the model stays at the current segment endpoint for at least two consecutive
  client ticks (roughly 40 ms or longer).

For a positional stall, the trace includes the configured multiplier, request
multiplier, applied lead, consecutive endpoint samples, and `pacingCause`.
`route-gap-clamp` means the bounded unfinished-yellow-route grace owns the
animation after a completed segment; `active-segment-endpoint-clamp` means the
ordinary segment clock still considers movement active. This keeps the
diagnostic useful for both the default setting and values such as 1.1, 1.2,
and 1.3 without logging every frame.

The yellow-route fields also include the latest click revision, the revision
owned by the displayed movement segment, and whether they match. This makes a
future stale re-click handoff distinguishable from a delayed segment belonging
to an unchanged long route.

The option is diagnostic and should be turned off after evidence is collected;
when disabled it performs no per-tick snapshot formatting or model inspection.
When enabled, the rolling samples retain copied values and a lazy formatter;
the relatively expensive human-readable log line is built only after an
incident is confirmed, not on every client tick.

Sampling is intentionally limited to `ClientTick` for thread safety. A visual
frame that begins and ends entirely between two client ticks can therefore be
missed. The numbered chat marker is the explicit confirmation that the
detector captured a reported visual event; no marker means the log should not
be assumed to contain that particular flicker.

The first full capture set contained 72 unfinished-route idle transitions.
They consistently occurred just after a 600 ms movement segment expired:
most at 608-619 ms and five at 751-758 ms. A later current-build capture found
five more at 810-816 ms around area transitions. The cleanest example resumed
an authoritative movement segment roughly 22 ms after requesting idle. The
next validation run found five additional publications at 852-858 ms.
Sample spacing remained normal, the custom model remained
ready/active/authoritative, controller state did not reset, and there was no
native/custom presentation swap. The visible "flicker" in these captured
events was therefore a one- or multi-frame request for idle animation 808 or
turn animation 823 between two valid locomotion requests, not a missing model.

`[TMA-UNFINISHED-ROUTE-ANIMATION-CONTINUITY]` fixes that underlying selection
seam. Locomotion remains selected for at most 300 ms when:

- the previous frame was moving;
- an observed yellow-click route still owns continuity;
- the current segment has reached its 600 ms endpoint;
- the client still publishes a different, same-world-view route destination;
  and
- this is not the pre-movement delay after a fresh yellow click.

Only animation selection is preserved. Position tweening remains clamped to
the completed segment, so the fix cannot cut corners, overshoot, enter an
object, or invent a route. Red-click interactions synchronously cancel the
yellow-walk arm, so object/NPC routes cannot inherit this grace and run in
place. Equal/null/different-world-view destinations still stop normally. The
300 ms limit covers the supplied 852-858 ms traces with 42 ms of scheduling
margin while preventing an indefinitely stale destination from causing
running in place.

The latest capture set also verified what was *not* failing. During stable
frames there were no model-readiness losses, custom-authority drops,
native/custom presentation swaps, controller resets, recovery rewinds, or
large retained-camera rebases. Moving scene recoveries remained monotonic and
the native handoff was used when it had already been presented. Consequently
the post-scene yellow fix changes only the pose/facing choice while awaiting a
real segment; it does not replace the already-valid position, scene-anchor,
recovery, or camera paths.

The focused tests cover:

- world/local rebasing and scene identity;
- atomic scene generation acknowledgement;
- bounded scene-edge bridging;
- scene presentation time deferral and repayment;
- recovery tween duration/velocity preservation;
- adaptive camera handoff, delta limiting, and vertical easing;
- route-gap grace versus a fresh post-stop yellow click;
- delayed first-segment handoff after a same-view yellow-click scene rebase;
- native-facing settlement and second-click handoff; and
- idle-controller ownership across positional catch-up.

These tests validate the rendering math and state transitions. In-game
validation is still required for visual behavior because a JVM test cannot
reproduce RuneLite's complete scene-render scheduling.
