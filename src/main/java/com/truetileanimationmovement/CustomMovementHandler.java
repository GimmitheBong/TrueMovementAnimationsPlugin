package com.truetileanimationmovement;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.AnimationID;

import javax.inject.Inject;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

@Slf4j
public class CustomMovementHandler
{
    private static final int BASE_MOVEMENT_TWEEN_MILLIS = 600;
    // [TMA-CONTINUOUS-MOVEMENT-SPEED] A multiplier cannot make the visible
    // model move faster than RuneScape publishes collision-valid route steps
    // indefinitely without either waiting at the endpoint or predicting a
    // path through walls/objects. Keep the published 600 ms segment clock and
    // use the user-facing multiplier only as a bounded within-segment lead.
    // The cap keeps the C1 progress curve strictly monotonic even when a user
    // enters a value above the range that can be represented safely.
    private static final double MAX_CONTINUOUS_MOVEMENT_LEAD_MULTIPLIER =
            1.75;
    private static final double CONTINUOUS_MOVEMENT_LEAD_STRENGTH_SCALE =
            3.0 * Math.sqrt(3.0);
    // RuneScape rebuilds the scene as the player crosses the 16-tile margin
    // on either side of the 104-tile scene. Route data can disappear for the
    // last fraction of a tick at exactly that boundary.
    private static final int SCENE_REBUILD_MARGIN_TILES = 16;
    private static final int SCENE_BOUNDARY_BRIDGE_MAX_MILLIS = 180;
    private static final int SCENE_BOUNDARY_BRIDGE_MAX_DISTANCE =
            Perspective.LOCAL_TILE_SIZE / 2;
    private static final int SCENE_PRESENTATION_MAX_FRAME_DELTA_MILLIS = 34;
    private static final int SCENE_PRESENTATION_MAX_TIME_DEBT_MILLIS = 600;
    private static final int SCENE_PRESENTATION_DEBT_PAYBACK_DIVISOR = 5;
    // [TMA-UNFINISHED-ROUTE-ANIMATION-CONTINUITY] Client/game tick scheduling
    // is not perfectly aligned with overlay rendering. The 2026-07-30 traces
    // captured 72 cases where the custom model stayed ready, active and
    // authoritative, but an unfinished route selected idle/turn immediately
    // after its 600 ms segment expired. Most next segments arrived at
    // 608-619 ms. A later area-transition capture found five valid routes
    // selecting idle at 810-816 ms, including one clean post-load case where
    // the next authoritative segment arrived about 22 ms later. The next
    // validation run found five more valid publications at 852-858 ms.
    //
    // Keep only the locomotion animation alive during this bounded feed gap,
    // and only while the yellow-walk continuity arm remains active. Red-click
    // interactions cancel that arm. ApplyTweening remains clamped to the
    // completed segment, so this cannot extrapolate through scenery or move
    // the model onto an interaction object.
    private static final int MOVEMENT_ANIMATION_CONTINUITY_GRACE_MILLIS = 300;
    // [TMA-PENDING-RECLICK-POSE-HANDOFF] A yellow click made during an
    // already-visible segment can be acknowledged one ClientTick before the
    // replacement segment is published. Preserve locomotion for only that
    // tiny publication seam. This is intentionally much shorter than route
    // grace so an unavailable destination cannot revive running in place.
    private static final int PENDING_RECLICK_POSE_GRACE_MILLIS = 50;
    // [TMA-STOP-FACING-SETTLE] Reaching the final tile and settling the
    // native actor's facing are separate client events. Give the hidden
    // actor one game tick with no orientation changes before a later target
    // orientation is treated as a genuinely new facing command.
    private static final int WALK_STOP_NATIVE_FACING_SETTLE_MILLIS =
            Constants.GAME_TICK_LENGTH;
    private static final int STOP_IDLE_TRACE_PRE_SAMPLES = 8;
    private static final int STOP_IDLE_TRACE_POST_SAMPLES = 10;
    private static final int STOP_IDLE_TRACE_MODEL_VERTEX_SAMPLES = 96;
    // [TMA-TELEPORT] Restored original teleport presentation state. A genuine
    // teleport is identified by the teleport animation the client publishes in
    // onGameTick. Timestamps are compared in System.nanoTime() domain like the
    // original so the millisecond-based frame timer never scales the windows.
    private static final long TELEPORT_ANIMATION_WINDOW_NANOS =
            1_800_000_000L; // 1.8s: full teleport-in presentation window
    private static final long TELEPORT_ANIMATION_FIRST_TICK_NANOS =
            600_000_000L; // 0.6s: blend with the true-tile movement first tick

    private long GetTeleportElapsedNanoseconds()
    {
        return System.nanoTime() - overlay.LastTimeTeleport;
    }
    // General
    private final Client client;
    private final TrueTileMovementPlugin plugin;
    private final TrueTileMovementConfig config;
    // [TMA-VALID-POSE-FRAME-PUBLICATION] RuneLite can briefly publish pose
    // frame -1 at a locomotion handoff while leaving the animation ID intact.
    // Remember the last drawable frame for that same pose so Owner.getModel()
    // is never asked to build the visible character from an invalid frame.
    private int LastValidOwnerPoseAnimation = NO_ANIMATION;
    private int LastValidOwnerPoseFrame = 0;
    // [TMA-STOP-IDLE-DIAGNOSTICS] Opt-in, client-thread-only snapshots. The
    // render callback never reads a RuneLiteObject for diagnostics.
    private final Deque<String> StopIdleTraceHistory = new ArrayDeque<>();
    private boolean bStopIdleTraceInitialized = false;
    private boolean bStopIdleTraceLastMoving = false;
    private int StopIdleTracePostSamplesRemaining = 0;
    private int StopIdleTraceEvent = 0;
    TrueMovementOverlay overlay;

    // Time management
    private long CurrentTime;
    public int CurrentFrameDelta;
    private long LastTimeMilliseconds = 0;
    private long LastAnimationTickTime = 0;
    private int MillisecondsSinceTileChange = 1000;
    // Runelite object management
    public Actor Owner = null;
    public AnimationController AnimController = null; // Used to blend additional animations
    public RuneLiteObject Model = null;

    // Targeting
    public Actor currentTarget = null;
    private int NotInteractingTimer = 0;

    // Rendering owner
    public boolean bRenderOriginalOwnerDueToProximity = false;
    public boolean bShouldRenderOwner = false;
    public boolean bAttemptToRenderOwner = false;
    public boolean bTransitioningToBattleMode = false;

    // Local caches
    private WorldPoint CurrentWorldPoint;
    private LocalPoint CurrentTrueTilePosition;
    private LocalPoint LastTrueTilePosition;
    private LocalPoint LastLerpPosition;
    private LocalPoint NextLerpPosition;
    private LocalPoint NewLocalPointToDraw; // Current frame draw
    private WorldPoint NextLerpPositionWorldPoint;
    private WorldPoint LastLerpPositionWorldPoint;
    // [TMA-MOTION-CONTINUITY] Keep the last displayed world-space point so a
    // scene rebuild can restore the visible sub-tile position, not just the
    // hidden actor's current tile.
    private WorldPoint LastRenderedWorldPoint;
    private int LastRenderedWorldOffsetX = 0;
    private int LastRenderedWorldOffsetY = 0;

    private boolean bSceneRebasePending = false;
    private int LastInitializedSceneGeneration = -1;
    // [TMA-SCENE-LOAD-CONTINUITY] A region rebuild can consume part of the
    // current movement tick. The first route change after the rebuild must
    // start at the point actually drawn, not an endpoint the recovery tween
    // has not reached yet.
    private boolean bSceneRecoveryRetargetPending = false;
    // [TMA-SCENE-LOAD-CONTINUITY] Derived every frame. This is deliberately
    // limited to an outward yellow-click route at the scene rebuild margin;
    // red-click interactions and ordinary movement can never extrapolate.
    private boolean bSceneBoundaryBridgeActive = false;
    // [TMA-SCENE-PRESENTATION-CLOCK] A scene can be prepared at
    // BeforeRender and then spend ~200 ms finishing its first drawable frame.
    // Defer that missing time and repay it gradually instead of applying the
    // whole interval as one position/orientation jump on the following frame.
    private boolean bScenePresentationClockActive = false;
    private boolean bSceneLoadFramePresentationPending = false;
    private int ScenePresentationTimeDebtMilliseconds = 0;
    // When a newer route point arrives while presentation is intentionally
    // behind, preserve the established movement velocity by extending the
    // tween duration rather than accelerating toward the newest point.
    private double SceneRecoveryBaseVelocity = 0;
    private long SceneRecoveryTweenDurationOverride = 0;
    // The native owner is deliberately shown while a replacement
    // RuneLiteObject is being prepared. Once that frame is presented, its
    // position becomes the authoritative visual anchor for the rebase.
    private boolean bNativeSceneLoadHandoffPresented = false;
    private boolean bLastSceneRebaseUsedNativeHandoffAnchor = false;
    // Animation Handling
    private static final int NO_ANIMATION = -1;
    private int CurrentAnimation = 0;
    private int CurrentPoseAnimation = 0;
    private boolean bResetCurrentAnimation = true;
    Set<Integer> UniqueAnimationExceptionList = new HashSet<Integer>();
    Set<Integer> UniqueAnimationLocationAndOrientationExceptionList = new HashSet<Integer>();
    private long LastTimeUniqueAnimationLocationOrientationWasUsed = 0;


    // Original true animations
    private AnimationRequestDetails CurrentAnimationRequest;
    private IdleAnimationSet OldAnimationSet = new IdleAnimationSet();
    public int OldAnimationHeight = 0;
    private boolean bIsDefaultHumanAnimationSet = true;

    // Rotation
    private int TargetOrientation = 0;
    private int CurrentOrientation = 0;
    // [TMA-STOP-FACING] Yellow Walk clicks may leave the hidden native player
    // finishing its route behind the visible model. During that catch-up only,
    // retain the visible model's final movement direction instead of adopting
    // the native actor's stale orientation and spinning at the destination.
    //
    // State meanings are intentionally kept separate:
    //   Armed       = a yellow-click route is eligible for the hold.
    //   Observed    = visible movement actually started for that route.
    //   Pending     = a yellow route is waiting for an authoritative visible
    //                 segment, either after a re-click or a scene rebuild.
    //   AwaitingSegment = a destination is published before its authoritative
    //                 segment, after either a fresh click or a scene rebuild;
    //                 do not treat that delay as an animation-only route gap.
    //   Preserve    = catch-up ended; keep the released facing until native
    //                 code issues a different orientation command.
    //   HoldThisFrame = the derived per-frame decision used by rendering.
    private boolean bWalkStopFacingHoldArmed = false;
    private boolean bWalkMovementObserved = false;
    private boolean bWalkStartPendingDuringCatchUp = false;
    private boolean bWalkSegmentAwaitingMovement = false;
    private boolean bWalkReclickOccurredDuringVisibleMovement = false;
    private boolean bPreserveReleasedWalkFacing = false;
    private boolean bHoldWalkStopFacingThisFrame = false;
    private boolean bNativeWalkFacingSettled = false;
    // [TMA-YELLOW-RECLICK-ROUTE-GRACE] Route-gap animation continuity belongs
    // only to the yellow click which produced the displayed segment. A newer
    // click can publish its destination before its first movement segment;
    // letting the old segment inherit that destination produces up to 300 ms
    // of locomotion at a stationary endpoint.
    private long WalkClickRevision = 0;
    private long WalkClickRevisionAtMovementSegment = 0;
    private int NativeTargetOrientationAtWalkFacingRelease = 0;
    private int NativeCurrentOrientationAtWalkFacingRelease = 0;
    private long LastNativeWalkFacingChangeTime = 0;


    // Player only
    private boolean bLastMovementDestinationPotentiallyDirty = false;
    private boolean bTooFarToSpecialMove = false;
    private boolean bLastTickTooFarToSpecialMove = false;
    private LocalPoint LastMovementDestination;
    public boolean bCurrentlyWooxWalking = false;
    public int FramesSinceIdle = 0;
    public boolean bMovingThisAction = false;
    public boolean bWooxWalkBroken = false;
    public boolean bTargetWasKilled = false;
    public long LastTimeEnemyKilled = 0;
    public long LastTimeRecentlyClicked = 0;
    private int LastNPCCombatLevel = 0;

    // Camera (Player Only)
    public AnimationController cameraModelAnimController = null;
    public RuneLiteObject cameraModel = null;
    private int CurrentCameraObjectOrientation = 0;
    private int CurrentCameraModelIndex = 0;
    private double CurrentArrowPointingAnimationFrame = 0.0f;

    @Inject
    CustomMovementHandler(Client client, TrueTileMovementPlugin plugin, TrueTileMovementConfig config, TrueMovementOverlay overlay, Actor Owner)
    {
        this.client = client;
        this.plugin = plugin;
        this.config = config;
        this.overlay = overlay;
        this.Owner = Owner;

        // Initialize all animations we do want to lerp
        UniqueAnimationExceptionList.add(2588); // Agility
        UniqueAnimationExceptionList.add(2586); // Agility
        UniqueAnimationExceptionList.add(2583); // Agility
        // [TMA-TELEPORT-CORRECT] Only genuine teleport animations are treated
        // as unique lerped animations. The original list also contained
        // ZAROS_VERTICAL_CASTING (1979, Ancient Magick cast) and
        // ARCEUUS_NECROMANCY_ANIM (3865, Arceuus spell cast), which are
        // ordinary spell casts and falsely armed the teleport-in snap during
        // PvP combat. Combat spells (entangle, fire surge, Flames of Zamorak,
        // Claws of Guthix, etc.) were never in this list and are unaffected.
        UniqueAnimationExceptionList.add(714); // Teleport
        UniqueAnimationExceptionList.add(878); // Teleport
        UniqueAnimationExceptionList.add(1816); // Teleport
        UniqueAnimationExceptionList.add(3872); // Teleport
        UniqueAnimationExceptionList.add(13811); // Teleport
        UniqueAnimationExceptionList.add(4069); // Teleport
        UniqueAnimationExceptionList.add(4071); // Teleport
        UniqueAnimationExceptionList.add(3869); // Teleport
        UniqueAnimationExceptionList.add(2881); // Teleport

        UniqueAnimationLocationAndOrientationExceptionList.add(749); // crawl pipe
        UniqueAnimationLocationAndOrientationExceptionList.add(751); // rope swing
        UniqueAnimationLocationAndOrientationExceptionList.add(840); // climb over
        UniqueAnimationLocationAndOrientationExceptionList.add(839); // climb over
        UniqueAnimationLocationAndOrientationExceptionList.add(1252); // climb over
        UniqueAnimationLocationAndOrientationExceptionList.add(828); // climb up
        UniqueAnimationLocationAndOrientationExceptionList.add(740); // climb up
        UniqueAnimationLocationAndOrientationExceptionList.add(7134); // slide down
        UniqueAnimationLocationAndOrientationExceptionList.add(844); // crawl
        UniqueAnimationLocationAndOrientationExceptionList.add(769); // long hop
        UniqueAnimationLocationAndOrientationExceptionList.add(3057); // Wall climb
        UniqueAnimationLocationAndOrientationExceptionList.add(3058); // Wall climb
        UniqueAnimationLocationAndOrientationExceptionList.add(3067); // long jump
        UniqueAnimationLocationAndOrientationExceptionList.add(3068); // long jump
        UniqueAnimationLocationAndOrientationExceptionList.add(1115); // jump and cover
        UniqueAnimationLocationAndOrientationExceptionList.add(5708); // penguin
        UniqueAnimationLocationAndOrientationExceptionList.add(5709); // penguin
    }

    double quadraticTween(long startTime, long endTime, long currentTime)
    {
        double t = (double) (currentTime - startTime) / (endTime - startTime);
        t = Math.max(0, Math.min(1, t)); // clamp

        // Quadratic
        if (t < 0.5)
        {
            return 2 * t * t;
        }

        double k = t * 2;
        return -0.5 * ((k - 1) * (k - 3) - 1);
    }

    double linearTween(long startTime, long endTime, long currentTime)
    {
        double t = (double) (currentTime - startTime) / (endTime - startTime);
        t = Math.max(0, Math.min(1, t)); // clamp

        // Linear easing
        return t;
    }

    static double GetConfiguredMovementLeadMultiplier(
            double ConfiguredMultiplier)
    {
        if (!Double.isFinite(ConfiguredMultiplier))
        {
            return 1.0;
        }

        return Math.min(
                Math.max(1.0, ConfiguredMultiplier),
                MAX_CONTINUOUS_MOVEMENT_LEAD_MULTIPLIER);
    }

    static long GetRequestMovementTweenDurationMilliseconds(
            double RequestMultiplier)
    {
        // Animation-request multipliers are authored choreography for the
        // optional leap/Woox animations. Preserve their established travel
        // timing; only the user-facing config multiplier is reinterpreted as
        // a continuous within-segment lead.
        if (!Double.isFinite(RequestMultiplier) ||
                RequestMultiplier <= 1.0)
        {
            return BASE_MOVEMENT_TWEEN_MILLIS;
        }
        return Math.max(
                1L,
                (long) (BASE_MOVEMENT_TWEEN_MILLIS /
                        RequestMultiplier));
    }

    static boolean ShouldApplyContinuousMovementSpeedLead(
            boolean ShouldTeleport,
            boolean SceneBoundaryBridgeActive,
            boolean SceneRebasePending,
            long SceneRecoveryDurationOverride,
            boolean SceneRecoveryRetargetPending,
            boolean ScenePresentationClockActive,
            boolean SceneLoadFramePresentationPending)
    {
        return !ShouldTeleport &&
                !SceneBoundaryBridgeActive &&
                !SceneRebasePending &&
                SceneRecoveryDurationOverride == 0 &&
                !SceneRecoveryRetargetPending &&
                !ScenePresentationClockActive &&
                !SceneLoadFramePresentationPending;
    }

    static double ApplyContinuousMovementSpeedLead(
            double BaseProgress,
            double CombinedMultiplier)
    {
        if (!Double.isFinite(BaseProgress))
        {
            return 0.0;
        }
        double Progress = Math.max(
                0.0,
                Math.min(1.0, BaseProgress));
        if (!Double.isFinite(CombinedMultiplier) ||
                CombinedMultiplier <= 1.0 ||
                Progress == 0.0 ||
                Progress == 1.0)
        {
            return Progress;
        }

        double EffectiveMultiplier = Math.min(
                CombinedMultiplier,
                MAX_CONTINUOUS_MOVEMENT_LEAD_MULTIPLIER);
        double CurveStrength =
                (EffectiveMultiplier - 1.0) *
                        CONTINUOUS_MOVEMENT_LEAD_STRENGTH_SCALE;
        double Remaining = 1.0 - Progress;
        double LedProgress = Progress +
                CurveStrength *
                        Progress * Progress *
                        Remaining * Remaining;
        return Math.max(0.0, Math.min(1.0, LedProgress));
    }

    static boolean IsSceneBoundaryExitSegment(
            LocalPoint From,
            LocalPoint To,
            LocalPoint Destination)
    {
        if (From == null ||
                To == null ||
                Destination == null ||
                From.getWorldView() != To.getWorldView() ||
                To.getWorldView() != Destination.getWorldView())
        {
            return false;
        }

        int LowBoundary =
                SCENE_REBUILD_MARGIN_TILES *
                        Perspective.LOCAL_TILE_SIZE +
                        Perspective.LOCAL_TILE_SIZE / 2;
        int HighBoundary =
                (Constants.SCENE_SIZE -
                        SCENE_REBUILD_MARGIN_TILES - 1) *
                        Perspective.LOCAL_TILE_SIZE +
                        Perspective.LOCAL_TILE_SIZE / 2;
        int DirectionX = To.getX() - From.getX();
        int DirectionY = To.getY() - From.getY();

        return (To.getX() == LowBoundary &&
                        DirectionX < 0 &&
                        Destination.getX() < To.getX()) ||
                (To.getX() == HighBoundary &&
                        DirectionX > 0 &&
                        Destination.getX() > To.getX()) ||
                (To.getY() == LowBoundary &&
                        DirectionY < 0 &&
                        Destination.getY() < To.getY()) ||
                (To.getY() == HighBoundary &&
                        DirectionY > 0 &&
                        Destination.getY() > To.getY());
    }

    static double GetSceneBoundaryBridgeTweenValue(
            long ElapsedMilliseconds,
            long TweenDurationMilliseconds,
            double SegmentDistance)
    {
        if (TweenDurationMilliseconds <= 0 ||
                SegmentDistance <= 0 ||
                ElapsedMilliseconds <= TweenDurationMilliseconds)
        {
            return 1.0;
        }

        long BridgeMilliseconds = Math.min(
                ElapsedMilliseconds - TweenDurationMilliseconds,
                SCENE_BOUNDARY_BRIDGE_MAX_MILLIS);
        double TimeFraction =
                (double) BridgeMilliseconds /
                        TweenDurationMilliseconds;
        double DistanceFraction =
                SCENE_BOUNDARY_BRIDGE_MAX_DISTANCE /
                        SegmentDistance;
        return 1.0 + Math.min(TimeFraction, DistanceFraction);
    }

    static int GetScenePresentationImmediateFrameDelta(
            int RawDeltaMilliseconds)
    {
        return Math.max(
                0,
                Math.min(
                        RawDeltaMilliseconds,
                        SCENE_PRESENTATION_MAX_FRAME_DELTA_MILLIS));
    }

    static int GetScenePresentationDebtPayback(
            int ImmediateDeltaMilliseconds,
            int TimeDebtMilliseconds)
    {
        if (ImmediateDeltaMilliseconds <= 0 ||
                TimeDebtMilliseconds <= 0)
        {
            return 0;
        }

        int MaximumPaybackThisFrame =
                (ImmediateDeltaMilliseconds +
                        SCENE_PRESENTATION_DEBT_PAYBACK_DIVISOR - 1) /
                        SCENE_PRESENTATION_DEBT_PAYBACK_DIVISOR;
        return Math.min(
                TimeDebtMilliseconds,
                MaximumPaybackThisFrame);
    }

    static long GetSceneRecoveryTweenDuration(
            double Distance,
            double BaseVelocity,
            long FallbackDurationMilliseconds)
    {
        long SafeFallbackDuration =
                Math.max(1L, FallbackDurationMilliseconds);
        long MaximumRecoveryDuration =
                SafeFallbackDuration > Long.MAX_VALUE / 2
                        ? Long.MAX_VALUE
                        : SafeFallbackDuration * 2;
        if (!Double.isFinite(Distance) ||
                Distance <= 0 ||
                !Double.isFinite(BaseVelocity) ||
                BaseVelocity <= 0)
        {
            return SafeFallbackDuration;
        }

        double CalculatedDuration = Math.ceil(
                Distance / BaseVelocity);
        if (!Double.isFinite(CalculatedDuration) ||
                CalculatedDuration >= MaximumRecoveryDuration)
        {
            return MaximumRecoveryDuration;
        }

        return Math.max(1L, (long) CalculatedDuration);
    }

    static double GetPreservedSceneMovementVelocity(
            boolean MovementCanContinue,
            LocalPoint SegmentStart,
            LocalPoint SegmentEnd,
            long TweenDurationMilliseconds)
    {
        if (!MovementCanContinue ||
                TweenDurationMilliseconds <= 0 ||
                SegmentStart == null ||
                SegmentEnd == null ||
                !IsSameWorldView(SegmentStart, SegmentEnd) ||
                SegmentStart.equals(SegmentEnd))
        {
            return 0;
        }

        // Keep this division explicitly floating point. The old production
        // calculation divided two integers, which reduced every normal
        // sub-600-local-unit recovery velocity to zero.
        return SegmentStart.distanceTo(SegmentEnd) /
                (double) TweenDurationMilliseconds;
    }

    static double GetSceneRecoveryBaseVelocity(
            double PreservedMovementVelocity,
            double RecoveryDistance,
            long FallbackDurationMilliseconds)
    {
        if (!Double.isFinite(RecoveryDistance) ||
                RecoveryDistance <= 0)
        {
            return 0;
        }

        long SafeFallbackDuration =
                Math.max(1L, FallbackDurationMilliseconds);
        double MinimumRecoveryVelocity =
                Perspective.LOCAL_TILE_SIZE /
                        (double) SafeFallbackDuration;
        if (Double.isFinite(PreservedMovementVelocity) &&
                PreservedMovementVelocity > 0)
        {
            return Math.max(
                    PreservedMovementVelocity,
                    MinimumRecoveryVelocity);
        }

        // A fractional native handoff offset can be only one or two local
        // units. Do not treat that tiny offset as the speed of the next real
        // segment or its recovery could take several seconds.
        return Math.max(
                RecoveryDistance /
                        (double) SafeFallbackDuration,
                MinimumRecoveryVelocity);
    }

    static double GetRetainedSceneRecoveryBaseVelocity(
            double PreservedMovementVelocity,
            double RecoveryDistance,
            long FallbackDurationMilliseconds)
    {
        return Double.isFinite(PreservedMovementVelocity) &&
                PreservedMovementVelocity > 0
                ? GetSceneRecoveryBaseVelocity(
                        PreservedMovementVelocity,
                        RecoveryDistance,
                        FallbackDurationMilliseconds)
                : 0;
    }

    static boolean CanUseSceneBoundaryBridge(
            boolean SceneRecoveryPending,
            boolean ScenePresentationClockActive,
            long SceneRecoveryDurationOverride)
    {
        return !SceneRecoveryPending &&
                !ScenePresentationClockActive &&
                SceneRecoveryDurationOverride <= 0;
    }

    static boolean ShouldReleaseScenePresentationClock(
            int ScenePresentationTimeDebtMilliseconds,
            boolean SceneRecoveryPending,
            long SceneRecoveryDurationOverride,
            boolean SceneLoadFramePresentationPending)
    {
        return ScenePresentationTimeDebtMilliseconds <= 0 &&
                !SceneRecoveryPending &&
                SceneRecoveryDurationOverride <= 0 &&
                !SceneLoadFramePresentationPending;
    }

    static boolean IsSceneMovementDiscontinuity(
            int OwnerAnimation,
            int RequestedAnimation,
            boolean RequestedTeleport,
            Set<Integer> AnimationExceptions,
            Set<Integer> LocationAndOrientationExceptions)
    {
        if (RequestedTeleport)
        {
            return true;
        }

        return OwnerAnimation != NO_ANIMATION &&
                        (AnimationExceptions.contains(OwnerAnimation) ||
                                LocationAndOrientationExceptions.contains(
                                        OwnerAnimation)) ||
                RequestedAnimation != NO_ANIMATION &&
                        (AnimationExceptions.contains(RequestedAnimation) ||
                                LocationAndOrientationExceptions.contains(
                                        RequestedAnimation));
    }

    static boolean CanPreserveSceneMovementVelocity(
            boolean BoundaryBridgeActive,
            boolean YellowWalkRouteActive,
            boolean SamePlane,
            boolean SpecialMovementDiscontinuity)
    {
        return BoundaryBridgeActive &&
                YellowWalkRouteActive &&
                SamePlane &&
                !SpecialMovementDiscontinuity;
    }

    static boolean IsPlausibleAuthoritativeSceneSegment(
            boolean YellowWalkRouteActive,
            boolean SamePlane,
            boolean SpecialMovementDiscontinuity,
            LocalPoint SegmentStart,
            LocalPoint SegmentEnd)
    {
        if (!YellowWalkRouteActive ||
                !SamePlane ||
                SpecialMovementDiscontinuity ||
                SegmentStart == null ||
                SegmentEnd == null ||
                !IsSameWorldView(SegmentStart, SegmentEnd) ||
                SegmentStart.equals(SegmentEnd))
        {
            return false;
        }

        int MaximumNativeStep =
                Perspective.LOCAL_TILE_SIZE * 2;
        return Math.abs(
                SegmentEnd.getX() - SegmentStart.getX()) <=
                        MaximumNativeStep &&
                Math.abs(
                        SegmentEnd.getY() - SegmentStart.getY()) <=
                        MaximumNativeStep;
    }

    static double SelectSceneRecoveryVelocity(
            double AuthoritativeSegmentVelocity,
            double PreservedMovementVelocity)
    {
        if (Double.isFinite(AuthoritativeSegmentVelocity) &&
                AuthoritativeSegmentVelocity > 0)
        {
            return AuthoritativeSegmentVelocity;
        }
        return Double.isFinite(PreservedMovementVelocity) &&
                PreservedMovementVelocity > 0
                ? PreservedMovementVelocity
                : 0;
    }

    static boolean ExceedsSceneRecoverySnapDistance(
            LocalPoint DisplayedLocation,
            LocalPoint AuthoritativeLocation,
            int SnapDistanceInTiles)
    {
        if (!IsSameWorldView(
                DisplayedLocation,
                AuthoritativeLocation))
        {
            return true;
        }

        // [TMA-SCENE-RECOVERY-TILE-DISTANCE] RuneScape movement distance is
        // measured in tile steps, where a diagonal (2, 2) displacement is
        // still two tiles. Euclidean distance classified that valid run as
        // sqrt(8) tiles and snapped the model forward whenever the configured
        // threshold was two. Keep the setting authoritative on each axis so
        // ordinary diagonal scene advances recover while a displacement that
        // exceeds the configured tile count in either direction still snaps.
        long MaximumLocalDifference =
                (long) Math.max(1, SnapDistanceInTiles) *
                        Perspective.LOCAL_TILE_SIZE;
        long DifferenceX = Math.abs(
                (long) AuthoritativeLocation.getX() -
                        DisplayedLocation.getX());
        long DifferenceY = Math.abs(
                (long) AuthoritativeLocation.getY() -
                        DisplayedLocation.getY());
        return DifferenceX > MaximumLocalDifference ||
                DifferenceY > MaximumLocalDifference;
    }

    static long GetSceneMovementAnimationDuration(
            long SceneRecoveryDurationOverride)
    {
        return Math.max(
                BASE_MOVEMENT_TWEEN_MILLIS,
                SceneRecoveryDurationOverride);
    }

    static boolean ShouldCompleteSceneRecoveryOverride(
            int ElapsedMilliseconds,
            long SceneRecoveryDurationOverride)
    {
        return SceneRecoveryDurationOverride > 0 &&
                ElapsedMilliseconds >=
                        SceneRecoveryDurationOverride;
    }

    static boolean ShouldKeepMovementAnimationDuringRouteGap(
            int MillisecondsSinceTileChange,
            boolean WasMoving,
            boolean WalkRouteContinuityArmed,
            boolean WalkSegmentAwaitingMovement,
            LocalPoint CurrentSegmentDestination,
            LocalPoint RouteDestination)
    {
        // A route destination alone is not enough: red-click interactions
        // publish destinations too. Their synchronous cancel clears this
        // yellow-walk arm so object/NPC stops cannot inherit locomotion grace
        // and appear to run in place.
        return WasMoving &&
                WalkRouteContinuityArmed &&
                !WalkSegmentAwaitingMovement &&
                MillisecondsSinceTileChange >=
                        BASE_MOVEMENT_TWEEN_MILLIS &&
                MillisecondsSinceTileChange <
                        BASE_MOVEMENT_TWEEN_MILLIS +
                                MOVEMENT_ANIMATION_CONTINUITY_GRACE_MILLIS &&
                CurrentSegmentDestination != null &&
                RouteDestination != null &&
                IsSameWorldView(
                        CurrentSegmentDestination,
                        RouteDestination) &&
                !CurrentSegmentDestination.equals(RouteDestination);
    }

    static boolean ShouldKeepMovementAnimationDuringRouteGap(
            int MillisecondsSinceTileChange,
            boolean WasMoving,
            boolean WalkRouteContinuityArmed,
            boolean WalkSegmentAwaitingMovement,
            boolean HoldingStopFacing,
            LocalPoint CurrentSegmentDestination,
            LocalPoint RouteDestination)
    {
        // A deliberate stop-facing handoff takes priority over animation-only
        // route grace. Combining both states leaves the position clamped while
        // locomotion continues, which is the post-scene run-in-place seam.
        return !HoldingStopFacing &&
                ShouldKeepMovementAnimationDuringRouteGap(
                        MillisecondsSinceTileChange,
                        WasMoving,
                        WalkRouteContinuityArmed,
                        WalkSegmentAwaitingMovement,
                        CurrentSegmentDestination,
                        RouteDestination);
    }

    static boolean HasUnfinishedRoute(
            LocalPoint SegmentDestination,
            LocalPoint RouteDestination)
    {
        return SegmentDestination != null &&
                RouteDestination != null &&
                IsSameWorldView(SegmentDestination, RouteDestination) &&
                !SegmentDestination.equals(RouteDestination);
    }

    static boolean ShouldAwaitPostSceneWalkSegment(
            boolean RealDiscontinuity,
            boolean SpecialMovementDiscontinuity,
            boolean WalkRouteArmed,
            boolean MovementWasObserved,
            LocalPoint CurrentSegmentDestination,
            LocalPoint RouteDestination)
    {
        return !RealDiscontinuity &&
                !SpecialMovementDiscontinuity &&
                WalkRouteArmed &&
                MovementWasObserved &&
                HasUnfinishedRoute(
                        CurrentSegmentDestination,
                        RouteDestination);
    }

    static boolean ShouldUseOriginalOwnerPresentation(
            boolean AllowOriginalModel,
            boolean Moving,
            boolean HoldingStopFacing,
            boolean UsedCustomAnimation,
            int OwnerActionAnimation,
            int DistanceX,
            int DistanceY,
            int OrientationDifference,
            int DistanceThreshold,
            int OrientationThreshold)
    {
        // [TMA-STEADY-PRESENTATION] Never change render authority in the
        // middle of locomotion or an action. At tile boundaries the native and
        // custom locations can briefly coincide; swapping there produces a
        // one-frame phase/visibility seam before the next segment starts.
        return AllowOriginalModel &&
                !Moving &&
                !HoldingStopFacing &&
                !UsedCustomAnimation &&
                OwnerActionAnimation == -1 &&
                Math.abs(DistanceX) <= DistanceThreshold &&
                Math.abs(DistanceY) <= DistanceThreshold &&
                Math.abs(OrientationDifference) <=
                        OrientationThreshold;
    }

    static boolean ShouldReplaceAnimationController(
            int CurrentAnimationId,
            int RequestedAnimationId,
            boolean ExplicitReset)
    {
        // RuneLite may replace an Animation wrapper without changing the
        // underlying animation. Object identity must not restart its phase.
        return ExplicitReset ||
                CurrentAnimationId != RequestedAnimationId;
    }

    private int ShortestAngleDifference(int from, int to)
    {
        return ((to - from + 3095) % 2047) - 1048;
    }

    private int getOrientationBetweenPoints(double point1X, double point1Y, double point2X, double point2Y, int OffsetAngle)
    {
        // Calculate the difference in X and Y coordinates
        double deltaX = point2X - point1X;
        double deltaY = point2Y - point1Y;

        // Calculate the angle in radians
        double angleInRadians = Math.atan2(deltaY, deltaX);

        // Convert to degrees and normalize to a 0-2047 range
        double angleInDegrees = Math.toDegrees(angleInRadians);
        angleInDegrees += OffsetAngle;

        if (angleInDegrees < 0)
        {
            angleInDegrees += 360;
        }

        angleInDegrees = 360 - angleInDegrees; // Inverted

        return (int) ((angleInDegrees / 360) * 2047);
    }

    private boolean IsPlayerOwner()
    {
        return (Owner instanceof Player);
    }

    public void Initialize(
            boolean bRuneliteObjectsStale,
            int SceneGeneration)
    {
        // A stale flag can survive more than one overlay pass while the new
        // scene is not ready for world/local conversion. Replace the
        // scene-owned objects once per scene generation, then retain them
        // until rebase succeeds.
        boolean bReplaceSceneObjects =
                bRuneliteObjectsStale &&
                        LastInitializedSceneGeneration !=
                                SceneGeneration;

        if (AnimController == null)
        {
            AnimController = new AnimationController(client, NO_ANIMATION);
            AnimController.setOnFinished((AnimationController InController) ->
            {
                // [TMA-NATIVE-ANIMATION-LOOPS] Animation frame 0 may be a
                // one-time lead-in. RuneLite's loop() honours frameStep and
                // returns to the sequence's authored loop point. Forcing 0
                // can replay that lead-in and seam controller-driven action or
                // locomotion cycles. (Native idle 808 has no authored loop
                // point and is documented separately.)
                InController.loop();
                bTargetWasKilled = false;
            });
        }

        if (Model == null || bReplaceSceneObjects)
        {
            RuneLiteObject OldModel = Model;
            Model = client.createRuneLiteObject();
            // [TMA-TRANSPARENCY-DEPTH] Force depth-sorted rendering
            // so transparent faces draw after body faces.
            Model.setRenderMode(Renderable.RENDERMODE_SORTED);
            LastValidOwnerPoseAnimation = NO_ANIMATION;
            LastValidOwnerPoseFrame = 0;

            if (OldModel != null)
            {
                Model.setLocation(OldModel.getLocation(), OldModel.getLevel());
                Model.setOrientation(CurrentOrientation);
                Model.setAnimationController(OldModel.getAnimationController());
                client.removeRuneLiteObject(OldModel);
            }
        }

        if (IsPlayerOwner())
        {
            if (config.SpawnModelAtCameraTile())
            {
                if (cameraModelAnimController == null)
                {
                    cameraModelAnimController = new AnimationController(client, NO_ANIMATION);
                    cameraModelAnimController.setOnFinished((AnimationController InController) ->
                    {
                        // Keep auxiliary model loops consistent with the
                        // animation's authored frameStep as well.
                        InController.loop();
                    });
                }

                if (cameraModel == null || bReplaceSceneObjects)
                {
                    RuneLiteObject OldModel = cameraModel;

                    // Potential decent models->
                    // 1742-> obelisk
                    // 2,318->portal entrance
                    // 3,022->butterfly
                    // 3,023->butterfly
                    // 3,115->fire wave
                    // 3,176->orb!
                    // 3,351->little purple orb
                    // 3,393->POINTING ARROW!
                    // 3,397->smaller pointing arrow
                    // 3,403->sun icon
                    // 3,404-3,406->more arrows!
                    // 3,405-> best arrow?
                    cameraModel = client.createRuneLiteObject();

                    if (OldModel != null)
                    {
                        cameraModel.setLocation(OldModel.getLocation(), OldModel.getLevel());
                        cameraModel.setOrientation(OldModel.getOrientation());
                        cameraModel.setAnimationController(OldModel.getAnimationController());
                        client.removeRuneLiteObject(OldModel);
                    }
                }
            }
        }

        // RuneLiteObjects belong to the scene. Preserve handler state and
        // remap it after a replacement instead of starting from the hidden
        // player's local point (which causes the visible pop).
        if (bReplaceSceneObjects)
        {
            LastInitializedSceneGeneration = SceneGeneration;
            bSceneRebasePending = true;
        }
    }

    public void Cleanup()
    {
        // Render once with should render owner back on
        bShouldRenderOwner = true;
        bAttemptToRenderOwner = true;
        CancelWalkStopFacingHold();
        bSceneRebasePending = false;
        bSceneRecoveryRetargetPending = false;
        bSceneBoundaryBridgeActive = false;
        bScenePresentationClockActive = false;
        bSceneLoadFramePresentationPending = false;
        ScenePresentationTimeDebtMilliseconds = 0;
        SceneRecoveryBaseVelocity = 0;
        SceneRecoveryTweenDurationOverride = 0;
        bNativeSceneLoadHandoffPresented = false;
        bLastSceneRebaseUsedNativeHandoffAnchor = false;
        LastValidOwnerPoseAnimation = NO_ANIMATION;
        LastValidOwnerPoseFrame = 0;
        if (AnimController != null)
        {
            AnimController = null;
        }

        if (Model != null)
        {
            Model.setActive(false);

            if (Owner.getIdleRotateLeft() != OldAnimationSet.IdleRotateLeft)
            {
                Owner.setIdleRotateLeft(OldAnimationSet.IdleRotateLeft);
            }

            if (Owner.getIdleRotateRight() != OldAnimationSet.IdleRotateRight)
            {
                Owner.setIdleRotateRight(OldAnimationSet.IdleRotateRight);
            }

            if (Owner.getWalkAnimation() != OldAnimationSet.WalkAnimation)
            {
                Owner.setWalkAnimation(OldAnimationSet.WalkAnimation);
            }

            if (Owner.getWalkRotateLeft() != OldAnimationSet.WalkRotateLeft)
            {
                Owner.setWalkRotateLeft(OldAnimationSet.WalkRotateLeft);
            }

            if (Owner.getWalkRotateRight() != OldAnimationSet.WalkRotateRight)
            {
                Owner.setWalkRotateRight(OldAnimationSet.WalkRotateRight);
            }

            if (Owner.getWalkRotate180() != OldAnimationSet.WalkRotate180)
            {
                Owner.setWalkRotate180(OldAnimationSet.WalkRotate180);
            }

            if (Owner.getIdlePoseAnimation() != OldAnimationSet.IdlePoseAnimation)
            {
                Owner.setIdlePoseAnimation(OldAnimationSet.IdlePoseAnimation);
            }

            if (Owner.getRunAnimation() != OldAnimationSet.RunAnimation)
            {
                Owner.setRunAnimation(OldAnimationSet.RunAnimation);
            }

        }

        if (cameraModel != null)
        {
            cameraModel.setActive(false);
            client.removeRuneLiteObject(cameraModel);
        }
    }

    void ArmWalkStopFacingHold()
    {
        if (!IsPlayerOwner())
        {
            return;
        }

        boolean bContinueActiveCatchUp =
                ShouldContinueActiveWalkStopCatchUp(
                        bWalkStopFacingHoldArmed,
                        bWalkMovementObserved,
                        bHoldWalkStopFacingThisFrame);
        boolean bVisibleMovementSegmentInProgress =
                IsVisibleMovementSegmentInProgress(
                        bMovingThisAction,
                        MillisecondsSinceTileChange,
                        GetMovementTweenDurationMilliseconds(),
                        LastLerpPosition,
                        NextLerpPosition);
        ++WalkClickRevision;
        // Every yellow click waits for a segment published after that click.
        // The already-visible segment continues normally until its endpoint;
        // only stale route-gap locomotion is excluded.
        bWalkSegmentAwaitingMovement = true;
        bWalkStopFacingHoldArmed = true;
        bWalkMovementObserved =
                bContinueActiveCatchUp ||
                        bVisibleMovementSegmentInProgress;
        bWalkStartPendingDuringCatchUp =
                bContinueActiveCatchUp ||
                        bVisibleMovementSegmentInProgress;
        bWalkReclickOccurredDuringVisibleMovement =
                bVisibleMovementSegmentInProgress;
        // If the previous route is already preserving its released facing,
        // keep that stable during the short click-to-movement delay. A click
        // during a genuinely moving segment also retains its observed state
        // through that segment's endpoint. The first segment published after
        // this click clears both pending flags. A click made from established
        // idle still cannot revive an old controller.
    }

    void CancelWalkStopFacingHold()
    {
        bWalkStopFacingHoldArmed = false;
        bWalkMovementObserved = false;
        bWalkStartPendingDuringCatchUp = false;
        bWalkSegmentAwaitingMovement = false;
        bWalkReclickOccurredDuringVisibleMovement = false;
        bPreserveReleasedWalkFacing = false;
        bHoldWalkStopFacingThisFrame = false;
        bNativeWalkFacingSettled = false;
        WalkClickRevision = 0;
        WalkClickRevisionAtMovementSegment = 0;
    }

    private void UpdateWalkStopFacingHold()
    {
        bHoldWalkStopFacingThisFrame = false;
        if (!IsPlayerOwner())
        {
            return;
        }

        // Scene recovery can deliberately take longer than one normal game
        // tick. Use the same duration as animation selection so stop-facing
        // cannot begin while the visible model is still traversing recovery.
        boolean VisibleModelIsMoving =
                MillisecondsSinceTileChange <
                        GetSceneMovementAnimationDuration(
                                SceneRecoveryTweenDurationOverride);
        if (VisibleModelIsMoving)
        {
            if (bWalkStopFacingHoldArmed)
            {
                bWalkMovementObserved = true;
            }

            // Forced movement or another route can begin without a fresh
            // yellow click. It must not inherit a completed route's facing.
            bPreserveReleasedWalkFacing = false;
            bNativeWalkFacingSettled = false;
            return;
        }

        if (bPreserveReleasedWalkFacing)
        {
            int NativeTargetOrientation = Owner.getOrientation();
            int NativeCurrentOrientation = Owner.getCurrentOrientation();

            if (ShouldPreserveReleasedWalkFacing(
                    bNativeWalkFacingSettled,
                    NativeTargetOrientationAtWalkFacingRelease,
                    NativeTargetOrientation))
            {
                bHoldWalkStopFacingThisFrame = true;

                if (!bNativeWalkFacingSettled)
                {
                    // [TMA-STOP-FACING-SETTLE] The native route can publish a
                    // final target orientation after its position has already
                    // caught up. Absorb that route-end churn while it is
                    // hidden; otherwise it is mistaken for a new command and
                    // the custom model turns immediately after stopping.
                    if (NativeTargetOrientation !=
                            NativeTargetOrientationAtWalkFacingRelease ||
                            NativeCurrentOrientation !=
                                    NativeCurrentOrientationAtWalkFacingRelease)
                    {
                        NativeTargetOrientationAtWalkFacingRelease =
                                NativeTargetOrientation;
                        NativeCurrentOrientationAtWalkFacingRelease =
                                NativeCurrentOrientation;
                        LastNativeWalkFacingChangeTime = CurrentTime;
                    }
                    else if (HasNativeWalkFacingSettled(
                            CurrentTime,
                            LastNativeWalkFacingChangeTime))
                    {
                        bNativeWalkFacingSettled = true;
                    }
                }
            }
            else
            {
                // A target-orientation change after the native actor has
                // settled is a new command, so ordinary smooth turning can
                // resume. Red interactions still cancel synchronously in the
                // menu-click handler and do not wait for this branch.
                bPreserveReleasedWalkFacing = false;
            }
            return;
        }

        if (!bWalkStopFacingHoldArmed ||
                !bWalkMovementObserved)
        {
            return;
        }

        // [TMA-YELLOW-RECLICK-HANDOFF] [TMA-POST-SCENE-YELLOW-HANDOFF]
        // A second yellow click or replacement scene can publish a route
        // destination before the client publishes its authoritative movement
        // segment. During that delay, IsAtFinalWalkDestination() is false even
        // though the visible model is stopped. Keep the existing facing and
        // native pose presentation until movement genuinely begins.
        if (ShouldHoldPendingWalkStart(
                bWalkStartPendingDuringCatchUp,
                bWalkStopFacingHoldArmed,
                bWalkMovementObserved,
                VisibleModelIsMoving))
        {
            bHoldWalkStopFacingThisFrame = true;
            if (HasHiddenOwnerCaughtUp(
                    Owner.getLocalLocation(),
                    NextLerpPosition))
            {
                BeginWalkStopFacingPreservation(true);
            }
            return;
        }

        if (!IsAtFinalWalkDestination())
        {
            return;
        }

        // The visible model has stopped at the final requested tile while the
        // hidden native actor is still approaching it. Freeze only this
        // short-lived orientation mismatch; normal turning resumes afterward.
        bHoldWalkStopFacingThisFrame = true;
        if (HasHiddenOwnerCaughtUp(
                Owner.getLocalLocation(),
                NextLerpPosition))
        {
            // Release the temporary catch-up block without immediately
            // reapplying the stale native orientation on the next frame.
            BeginWalkStopFacingPreservation(false);
        }
    }

    private void BeginWalkStopFacingPreservation(
            boolean RetainPendingWalkArm)
    {
        bWalkStopFacingHoldArmed = RetainPendingWalkArm;
        bWalkMovementObserved = false;
        bWalkStartPendingDuringCatchUp = false;
        bWalkReclickOccurredDuringVisibleMovement = false;
        bPreserveReleasedWalkFacing = true;
        bNativeWalkFacingSettled = false;
        NativeTargetOrientationAtWalkFacingRelease =
                Owner.getOrientation();
        NativeCurrentOrientationAtWalkFacingRelease =
                Owner.getCurrentOrientation();
        LastNativeWalkFacingChangeTime = CurrentTime;
    }

    private boolean IsAtFinalWalkDestination()
    {
        LocalPoint WalkDestination = client.getLocalDestinationLocation();
        return WalkDestination == null ||
                (NextLerpPosition != null &&
                        NextLerpPosition.equals(WalkDestination));
    }

    static boolean HasHiddenOwnerCaughtUp(
            LocalPoint OwnerLocation,
            LocalPoint RenderDestination)
    {
        return OwnerLocation != null &&
                RenderDestination != null &&
                OwnerLocation.equals(RenderDestination);
    }

    static boolean ShouldPreserveReleasedWalkFacing(
            boolean NativeFacingSettled,
            int SettledNativeTargetOrientation,
            int CurrentNativeTargetOrientation)
    {
        return !NativeFacingSettled ||
                SettledNativeTargetOrientation ==
                        CurrentNativeTargetOrientation;
    }

    static boolean HasNativeWalkFacingSettled(
            long CurrentTime,
            long LastNativeFacingChangeTime)
    {
        return CurrentTime - LastNativeFacingChangeTime >=
                WALK_STOP_NATIVE_FACING_SETTLE_MILLIS;
    }

    static boolean ShouldContinueActiveWalkStopCatchUp(
            boolean WalkRouteArmed,
            boolean MovementWasObserved,
            boolean HoldFacingThisFrame)
    {
        return WalkRouteArmed &&
                MovementWasObserved &&
                HoldFacingThisFrame;
    }

    static boolean ShouldHoldPendingWalkStart(
            boolean WalkStartPendingDuringCatchUp,
            boolean WalkRouteArmed,
            boolean PreviousMovementWasObserved,
            boolean VisibleModelIsMoving)
    {
        return WalkStartPendingDuringCatchUp &&
                WalkRouteArmed &&
                PreviousMovementWasObserved &&
                !VisibleModelIsMoving;
    }

    static boolean IsMovementSegmentFromLatestWalkClick(
            long LatestWalkClickRevision,
            long MovementSegmentWalkClickRevision)
    {
        return LatestWalkClickRevision ==
                MovementSegmentWalkClickRevision;
    }

    static boolean IsVisibleMovementSegmentInProgress(
            boolean MovementSelected,
            int MillisecondsSinceTileChange,
            long MovementTweenDurationMilliseconds,
            LocalPoint SegmentStart,
            LocalPoint SegmentEnd)
    {
        return MovementSelected &&
                MillisecondsSinceTileChange <
                        MovementTweenDurationMilliseconds &&
                IsSameWorldView(SegmentStart, SegmentEnd) &&
                !SegmentStart.equals(SegmentEnd);
    }

    static boolean ShouldPreservePendingReclickMovementPose(
            int MillisecondsSinceTileChange,
            long MovementTweenDurationMilliseconds,
            boolean PreviousFrameSelectedMovement,
            boolean ReclickOccurredDuringVisibleMovement,
            boolean WalkStartPending,
            boolean WalkSegmentAwaitingMovement,
            long LatestWalkClickRevision,
            long MovementSegmentWalkClickRevision,
            LocalPoint SegmentStart,
            LocalPoint SegmentEnd)
    {
        return PreviousFrameSelectedMovement &&
                IsPendingReclickMovementHandoff(
                        MillisecondsSinceTileChange,
                        MovementTweenDurationMilliseconds,
                        ReclickOccurredDuringVisibleMovement,
                        WalkStartPending,
                        WalkSegmentAwaitingMovement,
                        LatestWalkClickRevision,
                        MovementSegmentWalkClickRevision,
                        SegmentStart,
                        SegmentEnd) &&
                MillisecondsSinceTileChange <
                        MovementTweenDurationMilliseconds +
                                PENDING_RECLICK_POSE_GRACE_MILLIS;
    }

    private static boolean IsPendingReclickMovementHandoff(
            int MillisecondsSinceTileChange,
            long MovementTweenDurationMilliseconds,
            boolean ReclickOccurredDuringVisibleMovement,
            boolean WalkStartPending,
            boolean WalkSegmentAwaitingMovement,
            long LatestWalkClickRevision,
            long MovementSegmentWalkClickRevision,
            LocalPoint SegmentStart,
            LocalPoint SegmentEnd)
    {
        return ReclickOccurredDuringVisibleMovement &&
                WalkStartPending &&
                WalkSegmentAwaitingMovement &&
                !IsMovementSegmentFromLatestWalkClick(
                        LatestWalkClickRevision,
                        MovementSegmentWalkClickRevision) &&
                MillisecondsSinceTileChange >=
                        MovementTweenDurationMilliseconds &&
                IsSameWorldView(SegmentStart, SegmentEnd) &&
                !SegmentStart.equals(SegmentEnd);
    }

    static int SelectPoseFrameForPublication(
            int CurrentPoseAnimation,
            int CurrentPoseFrame,
            int RequestedPoseAnimation,
            int LastValidPoseAnimation,
            int LastValidPoseFrame,
            int AnimationFrameCount,
            int StartingFrame,
            boolean ResetRequested,
            boolean ForceRequestedEntryFrame)
    {
        if (!ResetRequested &&
                !ForceRequestedEntryFrame &&
                CurrentPoseFrame >= 0 &&
                CurrentPoseFrame < AnimationFrameCount)
        {
            return CurrentPoseFrame;
        }

        if (!ResetRequested &&
                !ForceRequestedEntryFrame &&
                CurrentPoseFrame < 0 &&
                CurrentPoseAnimation == RequestedPoseAnimation &&
                LastValidPoseAnimation == RequestedPoseAnimation &&
                LastValidPoseFrame >= 0 &&
                LastValidPoseFrame < AnimationFrameCount)
        {
            return LastValidPoseFrame;
        }

        return StartingFrame >= 0 &&
                StartingFrame < AnimationFrameCount
                ? StartingFrame
                : 0;
    }

    static boolean ShouldRestartStationaryIdlePose(
            boolean Moving,
            int OwnerActionAnimation,
            int CurrentPoseAnimation,
            int RequestedPoseAnimation,
            int IdlePoseAnimation)
    {
        // [TMA-STATIONARY-IDLE-ENTRY] The hidden actor can still publish a
        // locomotion pose after the responsive custom model has stopped. This
        // happens after both yellow movement and red-click approaches, so the
        // correction is keyed to the actual stationary run-to-idle mismatch,
        // not to click colour. Directional walk/run changes remain untouched
        // because cross-animation phase is rejected only after movement ends.
        return !Moving &&
                OwnerActionAnimation == NO_ANIMATION &&
                IdlePoseAnimation != NO_ANIMATION &&
                RequestedPoseAnimation == IdlePoseAnimation &&
                CurrentPoseAnimation != RequestedPoseAnimation;
    }

    private boolean TrySetModel(net.runelite.api.Model SourceModel)
    {
        if (SourceModel == null)
        {
            return false;
        }
        net.runelite.api.Model MergedModel = client.mergeModels(SourceModel);
        if (MergedModel == null)
        {
            return false;
        }

        Model.setModel(MergedModel);
        return true;
    }

    private void UpdateOldIdleAnimations()
    {
        boolean bAnyChanges = false;
        if (Owner.getIdleRotateLeft() != NO_ANIMATION &&
                Owner.getIdleRotateLeft() != CurrentPoseAnimation &&
                OldAnimationSet.IdleRotateLeft != Owner.getIdleRotateLeft())
        {
            OldAnimationSet.IdleRotateLeft = Owner.getIdleRotateLeft();
            bAnyChanges = true;
        }

        if (Owner.getIdleRotateRight() != NO_ANIMATION &&
                Owner.getIdleRotateRight() != CurrentPoseAnimation &&
                OldAnimationSet.IdleRotateRight != Owner.getIdleRotateRight())
        {
            OldAnimationSet.IdleRotateRight = Owner.getIdleRotateRight();
            bAnyChanges = true;
        }

        if (Owner.getWalkAnimation() != NO_ANIMATION &&
                Owner.getWalkAnimation() != CurrentPoseAnimation &&
                OldAnimationSet.WalkAnimation != Owner.getWalkAnimation())
        {
            OldAnimationSet.WalkAnimation = Owner.getWalkAnimation();
            bAnyChanges = true;
        }

        if (Owner.getWalkRotateLeft() != NO_ANIMATION &&
                Owner.getWalkRotateLeft() != CurrentPoseAnimation &&
                OldAnimationSet.WalkRotateLeft != Owner.getWalkRotateLeft())
        {
            OldAnimationSet.WalkRotateLeft = Owner.getWalkRotateLeft();
            bAnyChanges = true;
        }

        if (Owner.getWalkRotateRight() != NO_ANIMATION &&
                Owner.getWalkRotateRight() != CurrentPoseAnimation &&
                OldAnimationSet.WalkRotateRight != Owner.getWalkRotateRight())
        {
            OldAnimationSet.WalkRotateRight = Owner.getWalkRotateRight();
            bAnyChanges = true;
        }

        if (Owner.getWalkRotate180() != NO_ANIMATION &&
                Owner.getWalkRotate180() != CurrentPoseAnimation &&
                OldAnimationSet.WalkRotate180 != Owner.getWalkRotate180())
        {
            OldAnimationSet.WalkRotate180 = Owner.getWalkRotate180();
            bAnyChanges = true;
        }

        if (Owner.getIdlePoseAnimation() != NO_ANIMATION &&
                Owner.getIdlePoseAnimation() != CurrentPoseAnimation &&
                OldAnimationSet.IdlePoseAnimation != Owner.getIdlePoseAnimation())
        {
            OldAnimationSet.IdlePoseAnimation = Owner.getIdlePoseAnimation();
            bAnyChanges = true;
        }

        if (Owner.getRunAnimation() != NO_ANIMATION &&
                Owner.getRunAnimation() != CurrentPoseAnimation &&
                OldAnimationSet.RunAnimation != Owner.getRunAnimation())
        {
            OldAnimationSet.RunAnimation = Owner.getRunAnimation();
            bAnyChanges = true;
        }

        if (bAnyChanges)
        {
            OldAnimationSet.CacheUniqueLabel();
            OldAnimationHeight = Owner.getAnimationHeightOffset();

            // Monkey or penguin
            if (OldAnimationSet.IdlePoseAnimation == 1386 ||
                    OldAnimationSet.IdlePoseAnimation == 222 ||
                    OldAnimationSet.IdlePoseAnimation == 1401 ||
                    OldAnimationSet.IdlePoseAnimation == 5668)
            {
                bIsDefaultHumanAnimationSet = false;
            }
            else
            {
                bIsDefaultHumanAnimationSet = true;
            }
        }
    }
    private void UpdateFrameTimer()
    {
        CurrentTime = System.currentTimeMillis();
        int RawFrameDelta = (int) Math.max(
                0,
                Math.min(
                        Integer.MAX_VALUE,
                        CurrentTime - LastTimeMilliseconds));
        LastTimeMilliseconds = CurrentTime;
        CurrentFrameDelta = RawFrameDelta;

        if (bScenePresentationClockActive)
        {
            int ImmediateDelta =
                    GetScenePresentationImmediateFrameDelta(
                            RawFrameDelta);
            ScenePresentationTimeDebtMilliseconds =
                    Math.min(
                            SCENE_PRESENTATION_MAX_TIME_DEBT_MILLIS,
                            ScenePresentationTimeDebtMilliseconds +
                                    Math.max(
                                            0,
                                            RawFrameDelta -
                                                    ImmediateDelta));
            int DebtPayback =
                    GetScenePresentationDebtPayback(
                            ImmediateDelta,
                            ScenePresentationTimeDebtMilliseconds);
            CurrentFrameDelta = ImmediateDelta + DebtPayback;
            ScenePresentationTimeDebtMilliseconds -= DebtPayback;

            if (ShouldReleaseScenePresentationClock(
                    ScenePresentationTimeDebtMilliseconds,
                    bSceneRecoveryRetargetPending,
                    SceneRecoveryTweenDurationOverride,
                    bSceneLoadFramePresentationPending))
            {
                bScenePresentationClockActive = false;
                SceneRecoveryBaseVelocity = 0;
            }
        }

        if (CurrentFrameDelta > 0)
        {
            MillisecondsSinceTileChange += CurrentFrameDelta;
        }
    }

    void MarkSceneLoadFramePresented()
    {
        if (!bScenePresentationClockActive ||
                LastTimeMilliseconds <= 0)
        {
            return;
        }

        long PresentationTime = System.currentTimeMillis();
        int DeferredMilliseconds = (int) Math.max(
                0,
                Math.min(
                        SCENE_PRESENTATION_MAX_TIME_DEBT_MILLIS,
                        PresentationTime - LastTimeMilliseconds));
        ScenePresentationTimeDebtMilliseconds =
                Math.min(
                        SCENE_PRESENTATION_MAX_TIME_DEBT_MILLIS,
                        ScenePresentationTimeDebtMilliseconds +
                                DeferredMilliseconds);
        bSceneLoadFramePresentationPending = false;
        // The prepared state has now actually reached the screen. Start the
        // next delta here; the elapsed scene-build time is represented by the
        // debt above and will be repaid at a bounded rate.
        CurrentTime = PresentationTime;
        LastTimeMilliseconds = PresentationTime;
    }

    private boolean UpdateTrueTileLocation()
    {
        CurrentWorldPoint = Owner.getWorldLocation();
        if (CurrentWorldPoint == null)
        {
            return false;
        }

        LocalPoint LocalCurrentTrueTilePosition = LocalPoint.fromWorld(client, CurrentWorldPoint);
        if (LocalCurrentTrueTilePosition == null)
        {
            // Region loading may temporarily have no local conversion for a
            // valid world point. Keep the previous fully rendered frame until
            // the new scene can be rebased.
            return false;
        }
        if (!LocalCurrentTrueTilePosition.equals(CurrentTrueTilePosition))
        {
            // Also record the last one
            LastTrueTilePosition = CurrentTrueTilePosition;
            CurrentTrueTilePosition = LocalCurrentTrueTilePosition;
        }
        return true;
    }

    private static boolean IsSameWorldView(
            LocalPoint First,
            LocalPoint Second)
    {
        return First != null &&
                Second != null &&
                First.getWorldView() ==
                        Second.getWorldView();
    }

    private boolean HasSceneMovementDiscontinuity()
    {
        int OwnerAnimation = Owner == null
                ? NO_ANIMATION
                : Owner.getAnimation();
        int RequestedAnimation =
                CurrentAnimationRequest == null
                        ? NO_ANIMATION
                        : CurrentAnimationRequest.AnimationToPlay;
        boolean RequestedTeleport =
                CurrentAnimationRequest != null &&
                        CurrentAnimationRequest
                                .bShouldTeleportToLocation;
        return IsSceneMovementDiscontinuity(
                OwnerAnimation,
                RequestedAnimation,
                RequestedTeleport,
                UniqueAnimationExceptionList,
                UniqueAnimationLocationAndOrientationExceptionList);
    }

    static int GetSceneRebaseElapsedMilliseconds(int FrameDelta)
    {
        return Math.max(0, Math.min(
                BASE_MOVEMENT_TWEEN_MILLIS,
                FrameDelta));
    }

    static int GetSceneRebaseElapsedMilliseconds(
            int FrameDelta,
            boolean bHasRecoveryDistance)
    {
        return bHasRecoveryDistance
                ? GetSceneRebaseElapsedMilliseconds(FrameDelta)
                : BASE_MOVEMENT_TWEEN_MILLIS;
    }

    static int GetSceneRebaseElapsedMilliseconds(
            int FrameDelta,
            boolean bHasRecoveryDistance,
            boolean bUseNativeHandoffAnchor)
    {
        if (!bHasRecoveryDistance)
        {
            return BASE_MOVEMENT_TWEEN_MILLIS;
        }
        if (bUseNativeHandoffAnchor)
        {
            // The native owner was just presented. Advancing before the first
            // custom draw would create a smaller version of the same seam.
            return 0;
        }
        return GetSceneRebaseElapsedMilliseconds(FrameDelta);
    }

    static int GetSceneRebaseImmediateElapsedMilliseconds(
            int FrameDelta,
            boolean bHasRecoveryDistance,
            boolean bUseNativeHandoffAnchor)
    {
        int RebaseElapsedMilliseconds =
                GetSceneRebaseElapsedMilliseconds(
                        FrameDelta,
                        bHasRecoveryDistance,
                        bUseNativeHandoffAnchor);
        return bHasRecoveryDistance &&
                !bUseNativeHandoffAnchor
                ? GetScenePresentationImmediateFrameDelta(
                        RebaseElapsedMilliseconds)
                : RebaseElapsedMilliseconds;
    }

    static int GetSceneRebaseDeferredMilliseconds(
            int FrameDelta,
            boolean bHasRecoveryDistance,
            boolean bUseNativeHandoffAnchor)
    {
        if (!bHasRecoveryDistance ||
                bUseNativeHandoffAnchor)
        {
            return 0;
        }

        int RebaseElapsedMilliseconds =
                GetSceneRebaseElapsedMilliseconds(
                        FrameDelta,
                        true,
                        false);
        return Math.max(
                0,
                RebaseElapsedMilliseconds -
                        GetSceneRebaseImmediateElapsedMilliseconds(
                                FrameDelta,
                                true,
                                false));
    }

    static int AddScenePresentationDebt(
            int ExistingDebtMilliseconds,
            int AdditionalDebtMilliseconds)
    {
        return (int) Math.min(
                SCENE_PRESENTATION_MAX_TIME_DEBT_MILLIS,
                (long) Math.max(0, ExistingDebtMilliseconds) +
                        Math.max(0, AdditionalDebtMilliseconds));
    }

    static int SelectScenePresentationDebtAfterRebase(
            int ExistingDebtMilliseconds,
            int AdditionalDebtMilliseconds,
            boolean bHasRecoveryDistance,
            boolean bUseNativeHandoffAnchor)
    {
        // Debt describes time still owed to a retained custom movement path.
        // A native handoff or a zero-distance rebase has no such path, so
        // carrying old debt into it would leave stale debt with no active
        // presentation clock.
        return bHasRecoveryDistance &&
                !bUseNativeHandoffAnchor
                ? AddScenePresentationDebt(
                        ExistingDebtMilliseconds,
                        AdditionalDebtMilliseconds)
                : 0;
    }

    static boolean ShouldReanchorSceneRecovery(
            boolean bRecoveryPending,
            int ElapsedMilliseconds,
            long TweenDurationMilliseconds,
            boolean bHasDisplayedPoint)
    {
        return bRecoveryPending &&
                bHasDisplayedPoint &&
                ElapsedMilliseconds < TweenDurationMilliseconds;
    }

    static LocalPoint SelectSceneRebaseAnchor(
            boolean bNativeHandoffPresented,
            LocalPoint OwnerLocation,
            LocalPoint RetainedCustomLocation)
    {
        return bNativeHandoffPresented &&
                OwnerLocation != null
                ? OwnerLocation
                : RetainedCustomLocation;
    }

    void MarkNativeSceneLoadHandoffPresented()
    {
        bNativeSceneLoadHandoffPresented = true;
    }

    boolean DidLastSceneRebaseUseNativeHandoffAnchor()
    {
        return bLastSceneRebaseUsedNativeHandoffAnchor;
    }

    private boolean RebaseAfterSceneLoad()
    {
        LocalPoint OwnerLocation = Owner.getLocalLocation();
        WorldPoint OwnerWorldPoint = Owner.getWorldLocation();
        LocalPoint CurrentTrueLocation = OwnerWorldPoint == null
                ? null
                : LocalPoint.fromWorld(client, OwnerWorldPoint);
        if (OwnerLocation == null ||
                OwnerWorldPoint == null ||
                CurrentTrueLocation == null)
        {
            return false;
        }

        long PreservedTweenDuration =
                GetMovementTweenDurationMilliseconds();
        boolean bSpecialMovementDiscontinuity =
                HasSceneMovementDiscontinuity();
        boolean bRetainedSegmentOnCurrentPlane =
                LastLerpPositionWorldPoint != null &&
                        NextLerpPositionWorldPoint != null &&
                        LastLerpPositionWorldPoint.getPlane() ==
                                NextLerpPositionWorldPoint.getPlane() &&
                        NextLerpPositionWorldPoint.getPlane() ==
                                OwnerWorldPoint.getPlane();
        boolean bCanPreserveMovementVelocity =
                CanPreserveSceneMovementVelocity(
                        bSceneBoundaryBridgeActive,
                        bWalkStopFacingHoldArmed &&
                                bWalkMovementObserved,
                        bRetainedSegmentOnCurrentPlane,
                        bSpecialMovementDiscontinuity);
        // [TMA-SCENE-RECOVERY-VELOCITY] LOADING soft-suspends Update(), so
        // these endpoints still describe the last pre-load segment. Capture
        // its scalar speed before the rebase overwrites them. Direction is
        // deliberately not retained or extrapolated.
        double PreservedMovementVelocity =
                GetPreservedSceneMovementVelocity(
                        bCanPreserveMovementVelocity,
                        LastLerpPosition,
                        NextLerpPosition,
                        PreservedTweenDuration);

        LocalPoint RetainedCustomLocation =
                LastRenderedWorldPoint == null
                ? null
                : LocalPoint.fromWorld(client, LastRenderedWorldPoint);
        if (RetainedCustomLocation != null)
        {
            RetainedCustomLocation = new LocalPoint(
                    RetainedCustomLocation.getX() +
                            LastRenderedWorldOffsetX,
                    RetainedCustomLocation.getY() +
                            LastRenderedWorldOffsetY,
                    RetainedCustomLocation.getWorldView());
        }
        boolean bUseNativeHandoffAnchor =
                bNativeSceneLoadHandoffPresented;
        bLastSceneRebaseUsedNativeHandoffAnchor =
                bUseNativeHandoffAnchor;
        LocalPoint RenderedLocation = SelectSceneRebaseAnchor(
                bUseNativeHandoffAnchor,
                OwnerLocation,
                RetainedCustomLocation);
        boolean bRealDiscontinuity =
                !IsSameWorldView(RenderedLocation, CurrentTrueLocation) ||
                (!bUseNativeHandoffAnchor &&
                        (LastRenderedWorldPoint == null ||
                                LastRenderedWorldPoint.getPlane() !=
                                        OwnerWorldPoint.getPlane()));
        if (!bRealDiscontinuity &&
                !bUseNativeHandoffAnchor)
        {
            bRealDiscontinuity =
                    ExceedsSceneRecoverySnapDistance(
                            RenderedLocation,
                            CurrentTrueLocation,
                            config.PlayerModelSnapDistance());
        }
        if (bRealDiscontinuity)
        {
            // A real teleport/instance change has no shared scene location.
            // It is correct to snap once to the new native point in that case.
            RenderedLocation = CurrentTrueLocation;
            LastRenderedWorldOffsetX = 0;
            LastRenderedWorldOffsetY = 0;
        }

        LastLerpPosition = RenderedLocation;
        NewLocalPointToDraw = RenderedLocation;
        // Use the true-tile center here. Seeding this with OwnerLocation (the
        // hidden actor's fractional position) made generic destination logic
        // overwrite RenderedLocation during this same update.
        NextLerpPosition = CurrentTrueLocation;
        LastLerpPositionWorldPoint = WorldPoint.fromLocal(client,
                RenderedLocation);
        NextLerpPositionWorldPoint = OwnerWorldPoint;
        LastTrueTilePosition = RenderedLocation;
        CurrentTrueTilePosition = CurrentTrueLocation;
        CurrentWorldPoint = OwnerWorldPoint;
        // Carry only time for which rendering was missed. This prevents the
        // model pausing at the restored point and then skipping when the next
        // server route step arrives.
        boolean bHasRecoveryDistance =
                !RenderedLocation.equals(CurrentTrueLocation);
        boolean bAwaitingPostSceneWalkSegment =
                ShouldAwaitPostSceneWalkSegment(
                        bRealDiscontinuity,
                        bSpecialMovementDiscontinuity,
                        bWalkStopFacingHoldArmed,
                        bWalkMovementObserved,
                        CurrentTrueLocation,
                        client.getLocalDestinationLocation());
        if (bRealDiscontinuity ||
                bSpecialMovementDiscontinuity)
        {
            // A teleport, plane/view replacement, or animation-authoritative
            // displacement must not inherit a yellow route from the old scene.
            CancelWalkStopFacingHold();
        }
        else if (bAwaitingPostSceneWalkSegment)
        {
            // [TMA-POST-SCENE-YELLOW-HANDOFF] The replacement scene may expose
            // the old destination before its first new route segment. Reuse
            // the pending-yellow state so the endpoint keeps a stable idle and
            // facing instead of running in place and then chasing the hidden
            // actor's orientation. No position is invented; the state releases
            // as soon as UpdateLerpDestinations sees a real segment.
            bWalkStartPendingDuringCatchUp = true;
            bWalkSegmentAwaitingMovement = true;
            // A scene rebuild is not a user re-click during an actively
            // displayed segment; leave its existing continuity path intact.
            bWalkReclickOccurredDuringVisibleMovement = false;
        }
        double RecoveryDistance = bHasRecoveryDistance
                ? RenderedLocation.distanceTo(CurrentTrueLocation)
                : 0;
        // [TMA-SCENE-REBASE-FIRST-DELTA] UpdateFrameTimer runs before the
        // scene presentation clock becomes active. Without applying the same
        // cap here, the first recovered frame could consume a raw 20-49 ms
        // delta while every later frame was capped at 34 ms. Preserve that
        // time as debt instead of turning it into a larger first position and
        // camera step after an unavoidable native scene pause.
        ScenePresentationTimeDebtMilliseconds =
                SelectScenePresentationDebtAfterRebase(
                        ScenePresentationTimeDebtMilliseconds,
                        GetSceneRebaseDeferredMilliseconds(
                                CurrentFrameDelta,
                                bHasRecoveryDistance,
                                bUseNativeHandoffAnchor),
                        bHasRecoveryDistance,
                        bUseNativeHandoffAnchor);
        SceneRecoveryBaseVelocity =
                GetRetainedSceneRecoveryBaseVelocity(
                        PreservedMovementVelocity,
                        RecoveryDistance,
                        PreservedTweenDuration);
        // A partial rebased segment must take a proportional fraction of the
        // original segment time. Previously it always took a full 600 ms,
        // visibly slowing after the scene-edge bridge before catching up.
        SceneRecoveryTweenDurationOverride =
                bHasRecoveryDistance &&
                        PreservedMovementVelocity > 0
                        ? GetSceneRecoveryTweenDuration(
                                RecoveryDistance,
                                SceneRecoveryBaseVelocity,
                                PreservedTweenDuration)
                        : 0;
        bScenePresentationClockActive =
                bHasRecoveryDistance;
        bSceneLoadFramePresentationPending =
                bHasRecoveryDistance;
        MillisecondsSinceTileChange =
                GetSceneRebaseImmediateElapsedMilliseconds(
                        CurrentFrameDelta,
                        bHasRecoveryDistance,
                        bUseNativeHandoffAnchor);
        bSceneRecoveryRetargetPending =
                bHasRecoveryDistance &&
                        MillisecondsSinceTileChange <
                                GetMovementTweenDurationMilliseconds();

        if (Model != null)
        {
            Model.setLocation(RenderedLocation,
                    Owner.getWorldView().getPlane());
            Model.setOrientation(CurrentOrientation);
        }

        bNativeSceneLoadHandoffPresented = false;
        return true;
    }

    boolean IsSceneLoadVisualReady()
    {
        if (bSceneRebasePending ||
                Owner == null ||
                Owner.getLocalLocation() == null)
        {
            return false;
        }

        if (bShouldRenderOwner)
        {
            return true;
        }

        return Model != null &&
                Model.getModel() != null &&
                Model.getLocation() != null &&
                IsSameWorldView(
                        Model.getLocation(),
                        Owner.getLocalLocation());
    }

    boolean CanSuppressOwnerInCurrentScene()
    {
        return !bShouldRenderOwner &&
                !bRenderOriginalOwnerDueToProximity &&
                IsSceneLoadVisualReady() &&
                Model.isActive();
    }

    private void RecordLastRenderedLocation(LocalPoint RenderLocation)
    {
        if (RenderLocation == null)
        {
            return;
        }

        LastRenderedWorldPoint = WorldPoint.fromLocal(client, RenderLocation);
        LocalPoint TileLocation = LastRenderedWorldPoint == null
                ? null
                : LocalPoint.fromWorld(client, LastRenderedWorldPoint);
        if (!IsSameWorldView(TileLocation, RenderLocation))
        {
            LastRenderedWorldOffsetX = 0;
            LastRenderedWorldOffsetY = 0;
            return;
        }

        LastRenderedWorldOffsetX = RenderLocation.getX() -
                TileLocation.getX();
        LastRenderedWorldOffsetY = RenderLocation.getY() -
                TileLocation.getY();
    }

    private void UpdateTargetStatus()
    {
        // Potentially disconnect from current fight
        int TileDistanceFromTarget = 0;
        if (currentTarget != null)
        {
            TileDistanceFromTarget = currentTarget.getWorldLocation().distanceTo(Owner.getWorldLocation());
        }

        if (currentTarget != null &&
                (currentTarget.isDead() ||
                        currentTarget.getModel() == null ||
                        // Not interacting with the owner and the engagement timer has ran out (Also a decent distance away)
                        (currentTarget.getInteracting() != Owner
                                && Owner.getInteracting() != currentTarget
                                && NotInteractingTimer > config.StopEngagingInCombatTime()
                                && TileDistanceFromTarget > 3) ||
                        (currentTarget.getInteracting() != Owner
                                && Owner.getInteracting() != currentTarget
                                && NotInteractingTimer > config.StopEngagingInCombatTimeFromCloseDistance()
                                && TileDistanceFromTarget <= 3)
                        ||
                        // Very far
                        TileDistanceFromTarget > 10 ||
                        !config.CombatModeEnabled() && Owner.getInteracting() != currentTarget))
        {
            bTargetWasKilled = currentTarget.isDead();
            LastNPCCombatLevel = currentTarget.getCombatLevel();
            currentTarget = null;


            if (bTargetWasKilled)
            {
                LastTimeEnemyKilled = CurrentTime;
            }
        }

        Actor InteractingActor = Owner.getInteracting();
        if (InteractingActor instanceof NPC || InteractingActor instanceof Player)
        {
            if (currentTarget != InteractingActor)
            {
                NotInteractingTimer = 0;
            }

            currentTarget = InteractingActor;
            bTargetWasKilled = false;
        } else
        {
            NotInteractingTimer += CurrentFrameDelta;
        }

    }

    private boolean ShouldOnlyEnablePluginInCombat()
    {
        return (IsPlayerOwner() && config.OnlyEnabledInCombat());
    }

    private LocalPoint GetOwnerLocalLocation()
    {
        // Only allow players or NPCs
        if (IsPlayerOwner())
        {
            return client.getLocalPlayer().getLocalLocation();
        }
        else
        {
            return ((NPC) Owner).getLocalLocation();
        }
    }
    private void ChangeLastLerpPointForRotation()
    {
        int RealOrientation = Owner.getOrientation();

        // South
        if (RealOrientation < 256)
        {
            LastLerpPosition = new LocalPoint(NextLerpPosition.getX(), NextLerpPosition.getY() + 128, NextLerpPosition.getWorldView());
        }
        // South-west
        else if (RealOrientation < 512)
        {
            LastLerpPosition = new LocalPoint(NextLerpPosition.getX() + 128, NextLerpPosition.getY() + 128, NextLerpPosition.getWorldView());
        }
        // West
        else if (RealOrientation < 768)
        {
            LastLerpPosition = new LocalPoint(NextLerpPosition.getX() + 128, NextLerpPosition.getY(), NextLerpPosition.getWorldView());
        }
        // North-west
        else if (RealOrientation < 1024)
        {
            LastLerpPosition = new LocalPoint(NextLerpPosition.getX() + 128, NextLerpPosition.getY() - 128, NextLerpPosition.getWorldView());
        }
        // North
        else if (RealOrientation < 1280)
        {
            LastLerpPosition = new LocalPoint(NextLerpPosition.getX(), NextLerpPosition.getY() - 128, NextLerpPosition.getWorldView());
        }
        // North-east
        else if (RealOrientation < 1536)
        {
            LastLerpPosition = new LocalPoint(NextLerpPosition.getX() - 128, NextLerpPosition.getY() - 128, NextLerpPosition.getWorldView());
        }
        // East
        else if (RealOrientation < 1792)
        {
            LastLerpPosition = new LocalPoint(NextLerpPosition.getX() - 128, NextLerpPosition.getY(), NextLerpPosition.getWorldView());
        }
        // South-east
        else if (RealOrientation < 2049)
        {
            LastLerpPosition = new LocalPoint(NextLerpPosition.getX() - 128, NextLerpPosition.getY() + 128, NextLerpPosition.getWorldView());
        }
        LastLerpPositionWorldPoint = WorldPoint.fromLocal(client, LastLerpPosition);
    }

    public static double euclideanDistance(int x1, int y1, int x2, int y2)
    {
        int dx = x2 - x1;
        int dy = y2 - y1;
        return Math.sqrt(dx * dx + dy * dy);
    }
    boolean bNewTileMovementStarted = false;
    int RotatedDirectionX = 0;
    int RotatedDirectionY = 0;
    private void UpdateLerpDestinations()
    {
        bNewTileMovementStarted = false;
        if (plugin.bForceEarlyOut || !plugin.bIsPluginSupportedCurrently || (currentTarget == null && ShouldOnlyEnablePluginInCombat()))
        {
            if (!bAttemptToRenderOwner)
            {
                LastLerpPosition = Model.getLocation();
                LastLerpPositionWorldPoint = WorldPoint.fromLocal(client, LastLerpPosition);

                MillisecondsSinceTileChange = 0;
                bNewTileMovementStarted = true;
                bLastMovementDestinationPotentiallyDirty = true;
            }
            NextLerpPosition = GetOwnerLocalLocation();

            bAttemptToRenderOwner = true;
            bTransitioningToBattleMode = false;
        }
        else
        {
            // Resume from the last true tile
            if (bAttemptToRenderOwner)
            {
                NextLerpPosition = LastTrueTilePosition;
                bTransitioningToBattleMode = true;
            }
            else
            {
                bTransitioningToBattleMode = false;
            }

            LocalPoint RequestedLerpPoint = LocalPoint.fromWorld(client, CurrentWorldPoint);
            if (RequestedLerpPoint == null)
            {
                return;
            }
            if (LastLerpPosition == null)
            {
                NextLerpPosition = RequestedLerpPoint;

                LastLerpPosition = NextLerpPosition;
                LastLerpPositionWorldPoint = WorldPoint.fromLocal(client, LastLerpPosition);

                NextLerpPositionWorldPoint = CurrentWorldPoint;
            }
            if (NextLerpPosition == null)
            {
                NextLerpPosition = RequestedLerpPoint;
            }

            if (NextLerpPositionWorldPoint == null)
            {
                NextLerpPositionWorldPoint = CurrentWorldPoint;
            }
            if (!NextLerpPosition.equals(RequestedLerpPoint))
            {
                // Try all planes and use whichever one is the closest
                double ClosestPlaneDistance = 10000000;
                LocalPoint NextLerpPoint = null;
                for (int PlaneIter = CurrentWorldPoint.getPlane(); PlaneIter < CurrentWorldPoint.getPlane() + 4; ++PlaneIter)
                {
                    int CurrentIndex = PlaneIter % 4;

                    WorldPoint ConvertedWorldPoint = new WorldPoint(NextLerpPositionWorldPoint.getX(), NextLerpPositionWorldPoint.getY(), CurrentIndex);

                    LocalPoint TempNextLerpPoint = LocalPoint.fromWorld(client, ConvertedWorldPoint);
                    if (TempNextLerpPoint != null)
                    {
                        double DistToPoint = TempNextLerpPoint.distanceTo(LastLerpPosition);
                        if (DistToPoint < ClosestPlaneDistance)
                        {
                            ClosestPlaneDistance = DistToPoint;
                            NextLerpPoint = TempNextLerpPoint;
                        }
                    }
                }



                if (IsPlayerOwner() && !bWooxWalkBroken && LastLerpPosition.equals(RequestedLerpPoint))
                {
                    bCurrentlyWooxWalking = true;
                }
                else
                {
                    bCurrentlyWooxWalking = false;
                    bWooxWalkBroken = false;
                }
                ++FramesSinceIdle;

                // [TMA-TELEPORT] Restored original teleport interrupt. If a
                // route change arrives while the visible model is still
                // performing the teleport-in presentation, cancel that
                // presentation so the newer movement takes over. Only a
                // genuine teleport can have armed it.
                if (IsPlayerOwner() &&
                        overlay.bShouldPlayTeleportAnimation &&
                        FramesSinceIdle > 1)
                {
                    overlay.bTeleportInterrupted = true;
                }

                // Fallback to quick and dirty move

                int DistanceInTilesToLast = 0;
                int DistanceInTilesToNextLerp = 0;

                if (NextLerpPoint != null)
                {
                    DistanceInTilesToLast = (int) (euclideanDistance(NextLerpPoint.getX(), NextLerpPoint.getY(), LastLerpPosition.getX(), LastLerpPosition.getY()) / 128);
                    DistanceInTilesToNextLerp = (int) (euclideanDistance(NextLerpPoint.getX(), NextLerpPoint.getY(), RequestedLerpPoint.getX(), RequestedLerpPoint.getY()) / 128);
                }

                long CurrentTweenDuration =
                        GetMovementTweenDurationMilliseconds();
                long NormalTweenDuration =
                        GetNormalMovementTweenDurationMilliseconds();
                boolean bSameAuthoritativeSegmentPlane =
                        NextLerpPositionWorldPoint != null &&
                                CurrentWorldPoint != null &&
                                NextLerpPositionWorldPoint.getPlane() ==
                                        CurrentWorldPoint.getPlane();
                boolean bSpecialMovementDiscontinuity =
                        HasSceneMovementDiscontinuity();
                boolean bPlausibleAuthoritativeSegment =
                        IsPlausibleAuthoritativeSceneSegment(
                                bWalkStopFacingHoldArmed &&
                                        bWalkMovementObserved,
                                bSameAuthoritativeSegmentPlane,
                                bSpecialMovementDiscontinuity,
                                NextLerpPoint,
                                RequestedLerpPoint);
                double AuthoritativeSegmentVelocity =
                        GetPreservedSceneMovementVelocity(
                                bPlausibleAuthoritativeSegment,
                                NextLerpPoint,
                                RequestedLerpPoint,
                                NormalTweenDuration);
                boolean bReanchorBoundaryBridge =
                        bSceneBoundaryBridgeActive &&
                                CanUseSceneBoundaryBridge(
                                        bSceneRecoveryRetargetPending,
                                        bScenePresentationClockActive,
                                        SceneRecoveryTweenDurationOverride) &&
                                NewLocalPointToDraw != null;
                boolean bReanchorRecovery =
                        bReanchorBoundaryBridge ||
                                ShouldReanchorSceneRecovery(
                                bSceneRecoveryRetargetPending ||
                                        bScenePresentationClockActive ||
                                        SceneRecoveryTweenDurationOverride > 0,
                                MillisecondsSinceTileChange,
                                CurrentTweenDuration,
                                NewLocalPointToDraw != null);
                if (bReanchorRecovery)
                {
                    // Recovery/scene-edge bridge: continue from the displayed
                    // point. The flag was set by a prior catch-up frame.
                    bSceneRecoveryRetargetPending = false;
                }

                // [TMA-VISUAL-CONTINUITY] Every re-route must seed the
                // interpolation origin from the last rendered position, not
                // from the previous segment's authoritative destination tile.
                // The old path computed distance heuristics (normal / far
                // / teleport) and used them only to decide where LastLerp-
                // Position should point. Those heuristics still determine
                // recovery velocity and the catch-up flag, but the anchor
                // itself is always the visible member.
                if (NewLocalPointToDraw != null)
                {
                    LastLerpPosition = NewLocalPointToDraw;
                    LastLerpPositionWorldPoint =
                            WorldPoint.fromLocal(client, LastLerpPosition);
                    LastTrueTilePosition = NewLocalPointToDraw;
                }
                else if (NextLerpPoint != null)
                {
                    LastLerpPosition = NextLerpPoint;
                    LastLerpPositionWorldPoint =
                            WorldPoint.fromLocal(client, LastLerpPosition);
                }
                else
                {
                    LastLerpPosition = NextLerpPosition;
                    LastLerpPositionWorldPoint =
                            WorldPoint.fromLocal(client, LastLerpPosition);
                    LastTrueTilePosition = CurrentTrueTilePosition;
                }

                // [TMA-CATCH-UP-FLAG] The far-branch is the only one that
                // needs extended tween recovery. Mark it so the next frame
                // uses recovery velocity and does not restart the clock.
                if (!bReanchorRecovery &&
                        !(NextLerpPoint != null &&
                                DistanceInTilesToNextLerp <=
                                        config.PlayerModelSnapDistance() &&
                                DistanceInTilesToLast <=
                                        config.PlayerModelSnapDistance()) &&
                        NewLocalPointToDraw != null)
                {
                    bSceneRecoveryRetargetPending = true;
                }

                NextLerpPosition = RequestedLerpPoint;

                NextLerpPositionWorldPoint = CurrentWorldPoint;

                if (bReanchorRecovery)
                {
                    // Prefer the newly published native segment. This matters
                    // when the player changes from walking to running (or the
                    // reverse) across the load. The pre-load scalar is only a
                    // fallback when the old endpoint cannot be converted in
                    // the replacement scene.
                    boolean bCanUsePreLoadVelocity =
                            NextLerpPoint == null &&
                                    bWalkStopFacingHoldArmed &&
                                    bWalkMovementObserved &&
                                    bSameAuthoritativeSegmentPlane &&
                                    !bSpecialMovementDiscontinuity;
                    double SelectedVelocity =
                            SelectSceneRecoveryVelocity(
                                    AuthoritativeSegmentVelocity,
                                    bCanUsePreLoadVelocity
                                            ? SceneRecoveryBaseVelocity
                                            : 0);
                    double RecoveryDistance =
                            LastLerpPosition.distanceTo(
                                    NextLerpPosition);
                    if (SelectedVelocity > 0)
                    {
                        SceneRecoveryBaseVelocity =
                                GetSceneRecoveryBaseVelocity(
                                        SelectedVelocity,
                                        RecoveryDistance,
                                        NormalTweenDuration);
                        SceneRecoveryTweenDurationOverride =
                                GetSceneRecoveryTweenDuration(
                                        RecoveryDistance,
                                        SceneRecoveryBaseVelocity,
                                        NormalTweenDuration);
                    }
                    else
                    {
                        SceneRecoveryBaseVelocity = 0;
                        SceneRecoveryTweenDurationOverride = 0;
                    }
                }
                else
                {
                    SceneRecoveryTweenDurationOverride = 0;
                    if (!bScenePresentationClockActive)
                    {
                        SceneRecoveryBaseVelocity = 0;
                    }
                }

                MillisecondsSinceTileChange = bReanchorRecovery
                        ? (int) Math.max(
                                0,
                                Math.min(
                                        CurrentFrameDelta,
                                        CurrentTweenDuration))
                        : 0;
                bNewTileMovementStarted = true;
                bLastMovementDestinationPotentiallyDirty = true;
            }

            // Decay bLastMovementDestinationPotentiallyDirty flag
            if (MillisecondsSinceTileChange > 5)
            {
                bLastMovementDestinationPotentiallyDirty = false;
            }

            bAttemptToRenderOwner = false;

            // Determine what tile movement we are doing
            LocalPoint MovementPatternTestEnd;
            if (currentTarget != null)
            {
                MovementPatternTestEnd = currentTarget.getLocalLocation();
            }
            else
            {
                MovementPatternTestEnd = NextLerpPosition;
            }

            assert MovementPatternTestEnd != null;
            if (LastTrueTilePosition != null)
            {
                int OrientationToTest = (getOrientationBetweenPoints(LastTrueTilePosition.getX(), LastTrueTilePosition.getY(), MovementPatternTestEnd.getX(), MovementPatternTestEnd.getY(), 270));

                // Use orientation to identify which of the tile we are moving to
                double radians = OrientationToTest * Math.PI / 1024.0;
                double cos = Math.cos(radians);
                double sin = Math.sin(radians);

                // Get vector between true tile last and next;
                // Rotate vector by orientation
                int DirectionX = NextLerpPosition.getX() - LastTrueTilePosition.getX();
                int DirectionY = NextLerpPosition.getY() - LastTrueTilePosition.getY();


                RotatedDirectionX = Math.max(-2, Math.min(2, Math.toIntExact(Math.round((DirectionX * cos - DirectionY * sin) / 128.0))));
                RotatedDirectionY = Math.max(-2, Math.min(2, Math.toIntExact(Math.round((DirectionX * sin + DirectionY * cos) / 128.0))));
            }
            else
            {
                RotatedDirectionX = 0;
                RotatedDirectionY = 1; // Face ahead of wherever you are facing
            }
        }

    }
    private boolean bShouldUseTrueLocationOrientation = false;
    private void UpdateAnimationSelection()
    {
        bShouldUseTrueLocationOrientation = false;
        bSceneBoundaryBridgeActive =
                ShouldBridgeSceneBoundaryMovement(
                        GetMovementTweenDurationMilliseconds());
        boolean bKeepMovementAnimationDuringRouteGap =
                ShouldKeepMovementAnimationDuringRouteGap(
                        MillisecondsSinceTileChange,
                        bMovingThisAction,
                        bWalkStopFacingHoldArmed &&
                                bWalkMovementObserved &&
                                IsMovementSegmentFromLatestWalkClick(
                                        WalkClickRevision,
                                        WalkClickRevisionAtMovementSegment),
                        bWalkSegmentAwaitingMovement,
                        bHoldWalkStopFacingThisFrame,
                        NextLerpPosition,
                        client.getLocalDestinationLocation());
        boolean bPreservePendingReclickMovementPose =
                ShouldPreservePendingReclickMovementPose(
                        MillisecondsSinceTileChange,
                        GetMovementTweenDurationMilliseconds(),
                        bMovingThisAction,
                        bWalkReclickOccurredDuringVisibleMovement,
                        bWalkStartPendingDuringCatchUp,
                        bWalkSegmentAwaitingMovement,
                        WalkClickRevision,
                        WalkClickRevisionAtMovementSegment,
                        LastLerpPosition,
                        NextLerpPosition);

        // Override all animations
        //if (devConfig.DebugAnimation() != 0)
        //{
        //    CurrentAnimationRequest = AnimationRequestMoveset.GetDefaultIdleMoveAnimationRequest(config);
        //    CurrentAnimationRequest.AnimationToPlay = devConfig.DebugAnimation();
        //}
        //else

        // Currently moving
        if (MillisecondsSinceTileChange <
                        GetSceneMovementAnimationDuration(
                                SceneRecoveryTweenDurationOverride) ||
                bSceneBoundaryBridgeActive ||
                bKeepMovementAnimationDuringRouteGap ||
                bPreservePendingReclickMovementPose)
        {
            bMovingThisAction = true;

            // Analyze the type of movement we're doing
            CurrentAnimationRequest = AnimationRequestMoveset.GetDefaultIdleMoveAnimationRequest(config);

            // Only do special moves if actually attacking an NPC
            // TODO: Disable experimental feature for now
            boolean bSpecialMoveAnimation = false;// IsPlayerOwner() && !(bTooFarToSpecialMove || (devConfig.SpecialMovesOnlyInCombat() && currentTarget == null));

            // Did not click within the last time
            if (CurrentTime - LastTimeRecentlyClicked > 1199)
            {
                FramesSinceIdle = 0;
            }

        // [TMA-TELEPORT] Restored original teleport animation selection.
        // A genuine teleport plays the teleport-in presentation within the
        // nanoTime window set by onGameTick/UpdateLerpDestinations. This
        // branch is gated on bShouldPlayTeleportAnimation being explicitly
        // set by a genuine teleport detection in onGameTick. Spell casting
        // animations are no longer in the teleport detection list, so this
        // branch can never be armed by ordinary PvP combat casting.
        if (IsPlayerOwner() &&
                overlay.bShouldPlayTeleportAnimation &&
                GetTeleportElapsedNanoseconds() <
                        TELEPORT_ANIMATION_WINDOW_NANOS &&
                !overlay.bTeleportInterrupted)
            {
                if (overlay.bShouldPlayTeleportAnimation &&
                        bIsDefaultHumanAnimationSet)
                {
                    if (GetTeleportElapsedNanoseconds() <
                            TELEPORT_ANIMATION_FIRST_TICK_NANOS)
                    {
                        // Blend with the first tick
                        CurrentAnimationRequest = AnimationRequestDetails.NewObject(AnimationRequestMovesetCache.GetAnimationRequestMovesetFromAnimationSet(OldAnimationSet, config).MovesetArray[2 + RotatedDirectionX][2 + RotatedDirectionY]);
                        CurrentAnimationRequest.bShouldTeleportToLocation = false;
                    }
                    else
                    {
                        CurrentAnimationRequest.bShouldTeleportToLocation = true;
                        CurrentAnimationRequest.AnimationToPlay = AnimationID.HUMAN_CASTTELEPORT_REVERSE; // Teleport in. 715

                        ChangeLastLerpPointForRotation();
                    }
                }
                else
                {
                    // Get true animation and rotation
                    // Use orientation to identify which of the tile we are moving to
                    double radians = Owner.getOrientation() * Math.PI / 1024.0;
                    double cos = Math.cos(radians);
                    double sin = Math.sin(radians);

                    // Get vector between true tile last and next;
                    // Rotate vector by orientation
                    int DirectionX = Owner.getLocalLocation().getX() - LastTrueTilePosition.getX();
                    int DirectionY = Owner.getLocalLocation().getY() - LastTrueTilePosition.getY();

                    if (Owner.getLocalLocation().getX() == CurrentTrueTilePosition.getX() &&
                            Owner.getLocalLocation().getY() == CurrentTrueTilePosition.getY())
                    {
                        CurrentAnimationRequest.PoseAnimationToPlay = OldAnimationSet.IdlePoseAnimation;
                    }
                    else
                    {
                        int TempRotatedDirectionX = Math.max(-2, Math.min(2, Math.toIntExact(Math.round((DirectionX * cos - DirectionY * sin) / 128.0))));
                        int TempRotatedDirectionY = Math.max(-2, Math.min(2, Math.toIntExact(Math.round((DirectionX * sin + DirectionY * cos) / 128.0))));

                        CurrentAnimationRequest = AnimationRequestDetails.NewObject(AnimationRequestMovesetCache.GetAnimationRequestMovesetFromAnimationSet(OldAnimationSet, config).MovesetArray[2 + TempRotatedDirectionX][2 + TempRotatedDirectionY]);
                    }
                    bShouldUseTrueLocationOrientation = true;
                    CurrentAnimationRequest.bShouldTeleportToLocation = true;

                    ChangeLastLerpPointForRotation();
                }
                CurrentAnimationRequest.bUseLinearTween = true;
                CurrentAnimationRequest.MovementSpeedMultiplier = 1.0;
                CurrentAnimationRequest.StartingFrame = 0;
                CurrentAnimationRequest.AnimationSpeed = 1;
            }
            else if (config.AllowLeaping() &&
                    bCurrentlyWooxWalking &&
                    config.AllowWooxWalkDetection() &&
                    bIsDefaultHumanAnimationSet)
            {
                // [TMA-CAST-MOVEMENT-ORDERING] Owner.getAnimation() is the
                // single authority for an active cast. The old implementation
                // also remembered selected cast/teleport animation IDs and
                // later synthesized HUMAN_CASTTELEPORT_REVERSE (animation
                // 715). If the player clicked to move first, locomotion could
                // begin and that delayed animation would then replay the cast
                // and request a false positional snap. Keep locomotion
                // advancing underneath the real action animation instead.
                // Genuine teleports and other authoritative discontinuities
                // still use the normal movement request path; only the
                // delayed duplicate authority was removed.
                // Handle woox walking
                CurrentAnimationRequest = AnimationRequestDetails.NewObject(AnimationRequestMovesetCache.GetAnimationRequestMovesetFromUniqueKey(OldAnimationSet,"WooxWalk", config).MovesetArray[2 + RotatedDirectionX][2 + RotatedDirectionY]);

                // No turning if no target
                if (currentTarget == null)
                {
                    CurrentAnimationRequest.OrientationSpeed = 0;
                }
                else
                {
                    // Slower turn when woox walking
                    CurrentAnimationRequest.OrientationSpeed /= 2;
                }
            }
            else if (config.AllowLeaping() &&
                    (config.AlwaysHoppingMode() ||
                            FramesSinceIdle > config.TickPerfectMovesUntilJumping()) &&
                    bIsDefaultHumanAnimationSet)
            {
                // Handle tick perfect moving
                CurrentAnimationRequest = AnimationRequestDetails.NewObject(AnimationRequestMovesetCache.GetAnimationRequestMovesetFromUniqueKey(OldAnimationSet,"TickPerfectMovement", config).MovesetArray[2 + RotatedDirectionX][2 + RotatedDirectionY]);
            }
            else
            {
                // Special move activated
                if (bSpecialMoveAnimation && bIsDefaultHumanAnimationSet)
                {
                    // Handle normal walking
                    CurrentAnimationRequest = AnimationRequestDetails.NewObject(AnimationRequestMovesetCache.GetAnimationRequestMovesetFromUniqueKey(OldAnimationSet,"SpecialMoves", config).MovesetArray[2 + RotatedDirectionX][2 + RotatedDirectionY]);
                }
                else
                {
                    // Handle normal walking
                    CurrentAnimationRequest = AnimationRequestDetails.NewObject(AnimationRequestMovesetCache.GetAnimationRequestMovesetFromAnimationSet(OldAnimationSet, config).MovesetArray[2 + RotatedDirectionX][2 + RotatedDirectionY]);
                }
            }
        }
        // Killed the target (not moving)
        else if (bTargetWasKilled && config.AllowNPCKilledCelebrationEmote() && LastNPCCombatLevel > 50 && bIsDefaultHumanAnimationSet)
        {
            CurrentAnimationRequest = AnimationRequestMoveset.GetDefaultIdleMoveAnimationRequest(config);

            if (LastNPCCombatLevel > 300)
            {
                // 2,387->Fist pump
                CurrentAnimationRequest.AnimationToPlay = 2106; // Jig
            }
            else if (LastNPCCombatLevel > 200)
            {
                // 2,387->Fist pump
                CurrentAnimationRequest.AnimationToPlay = 866; // Dance
            }
            else if (LastNPCCombatLevel > 150)
            {
                // 2,387->Fist pump
                CurrentAnimationRequest.AnimationToPlay = 8917; // Flex
            }
            else if (LastNPCCombatLevel > 100)
            {
                // 2,387->Fist pump
                CurrentAnimationRequest.AnimationToPlay = 862; // Cheer
            }
            // > 50
            else
            {
                // 2,387->Fist pump
                CurrentAnimationRequest.AnimationToPlay = 2387; // Fist pump
            }

            CurrentAnimationRequest.bUseLinearTween = true;
            CurrentAnimationRequest.MovementSpeedMultiplier = 1;
            CurrentAnimationRequest.AnimationSpeed = 1;
            CurrentAnimationRequest.StartingFrame = 0;
            ChangeLastLerpPointForRotation();
            bWooxWalkBroken = true;
            FramesSinceIdle = 0;
        }
        // Not moving
        else
        {
            bMovingThisAction = false;

            CurrentAnimationRequest = AnimationRequestMoveset.GetDefaultIdleMoveAnimationRequest(config);
            CurrentAnimationRequest.bUseLinearTween = true;
            CurrentAnimationRequest.MovementSpeedMultiplier = 1.0;
            CurrentAnimationRequest.AnimationSpeed = 1;
            CurrentAnimationRequest.StartingFrame = 0;
            if (bHoldWalkStopFacingThisFrame)
            {
                // [TMA-STOP-FACING] Do not select a turn-in-place pose while
                // the hidden actor is completing a yellow-click route.
                CurrentAnimationRequest.PoseAnimationToPlay = OldAnimationSet.IdlePoseAnimation;
            }
            else
            {
                ChangeLastLerpPointForRotation();
                int ShortestAngle = ShortestAngleDifference(CurrentOrientation, TargetOrientation);
                if (ShortestAngle >= 10)
                {
                    CurrentAnimationRequest.PoseAnimationToPlay = OldAnimationSet.IdleRotateRight;
                }
                else if (ShortestAngle <= -10)
                {
                    CurrentAnimationRequest.PoseAnimationToPlay = OldAnimationSet.IdleRotateLeft;
                }
                else
                {
                    CurrentAnimationRequest.PoseAnimationToPlay = OldAnimationSet.IdlePoseAnimation;
                }
            }

            bWooxWalkBroken = true;
            FramesSinceIdle = 0;

            // We can transition to render the owner
            if (bAttemptToRenderOwner)
            {
                bShouldRenderOwner = true;
            }
        }

        if (CurrentAnimationRequest.bResetAnimationOnNewTile && bNewTileMovementStarted)
        {
            bResetCurrentAnimation = true; // Reset animation
        }

    }

    private void UpdateMovementType()
    {
        // Clicking close by or far away (special moves)
        if (IsPlayerOwner()) {
            // Decide movement type
            // Only allow checking destination if we are at
            if (bLastMovementDestinationPotentiallyDirty)
            {
                bLastTickTooFarToSpecialMove = bTooFarToSpecialMove;
                if (client.getLocalDestinationLocation() != null) {
                    if (client.getLocalDestinationLocation() != LastMovementDestination) {
                        LastMovementDestination = client.getLocalDestinationLocation();

                        // Next position isnt the next lerp position, this means it'll take 2+ moves to actually get there because of an obstacle
                        if (LastMovementDestination != NextLerpPosition)
                        {
                            bTooFarToSpecialMove = true;
                        }
                        else
                        {
                            bTooFarToSpecialMove = false;
                        }

                        bLastMovementDestinationPotentiallyDirty = false;
                    }
                }
                // Such short distance that it never registers
                else if (overlay.bRecentlyClickedEvent)
                {
                    bTooFarToSpecialMove = false;
                    bLastMovementDestinationPotentiallyDirty = false;
                }

                if (overlay.bRecentlyClickedEvent)
                {
                    LastTimeRecentlyClicked = CurrentTime;
                }
                // Handled
                overlay.bRecentlyClickedEvent = false;
            }

        }
    }

    private boolean ShouldRenderOriginalOwner(
            boolean bUsedCustomAnimation)
    {
        LocalPoint OwnerLocation = Owner.getLocalLocation();
        LocalPoint ModelLocation = Model.getLocation();
        if (OwnerLocation == null ||
                ModelLocation == null ||
                !IsSameWorldView(
                        OwnerLocation,
                        ModelLocation))
        {
            return false;
        }

        return ShouldUseOriginalOwnerPresentation(
                config.AllowOriginalModelWhenCloseProximity(),
                bMovingThisAction,
                bHoldWalkStopFacingThisFrame,
                bUsedCustomAnimation,
                Owner.getAnimation(),
                OwnerLocation.getX() - ModelLocation.getX(),
                OwnerLocation.getY() - ModelLocation.getY(),
                ShortestAngleDifference(
                        Owner.getCurrentOrientation(),
                        Model.getOrientation()),
                config.OriginalModelProximityDistanceThreshold(),
                config.OriginalModelProximityOrientationThreshold());
    }

    private long GetNormalMovementTweenDurationMilliseconds()
    {
        // [TMA-CONTINUOUS-MOVEMENT-SPEED] Authoritative walking/running route
        // steps arrive on the game-tick clock. Shortening this duration made
        // the model reach NextLerpPosition early and wait there with its legs
        // still moving. ApplyTweening now expresses the user-facing
        // multiplier as bounded progress lead over the ordinary duration.
        // Animation-specific request multipliers retain their authored
        // leap/Woox choreography.
        double RequestMultiplier =
                CurrentAnimationRequest == null
                        ? 1.0
                        : CurrentAnimationRequest.MovementSpeedMultiplier;
        return GetRequestMovementTweenDurationMilliseconds(
                RequestMultiplier);
    }

    private double GetCurrentMovementLeadMultiplier()
    {
        return GetConfiguredMovementLeadMultiplier(
                config.MovementSpeedMultiplier());
    }

    private long GetMovementTweenDurationMilliseconds()
    {
        return SceneRecoveryTweenDurationOverride > 0
                ? SceneRecoveryTweenDurationOverride
                : GetNormalMovementTweenDurationMilliseconds();
    }

    private boolean ShouldBridgeSceneBoundaryMovement(
            long TweenDurationMilliseconds)
    {
        if (!CanUseSceneBoundaryBridge(
                bSceneRecoveryRetargetPending,
                bScenePresentationClockActive,
                SceneRecoveryTweenDurationOverride) ||
                !IsPlayerOwner() ||
                !bWalkStopFacingHoldArmed ||
                !bWalkMovementObserved ||
                MillisecondsSinceTileChange <
                        TweenDurationMilliseconds)
        {
            return false;
        }

        return IsSceneBoundaryExitSegment(
                LastLerpPosition,
                NextLerpPosition,
                client.getLocalDestinationLocation());
    }

    private void ApplyTweening()
    {
        // 600ms a tick, interpolate between true local point and last true tile position
        double TweenValue = 0;
        long TweenDurationMilliseconds =
                GetMovementTweenDurationMilliseconds();
        boolean bApplyNormalMovementLead =
                ShouldApplyContinuousMovementSpeedLead(
                        CurrentAnimationRequest
                                .bShouldTeleportToLocation,
                        bSceneBoundaryBridgeActive,
                        bSceneRebasePending,
                        SceneRecoveryTweenDurationOverride,
                        bSceneRecoveryRetargetPending,
                        bScenePresentationClockActive,
                        bSceneLoadFramePresentationPending);
        if (CurrentAnimationRequest.bShouldTeleportToLocation)
        {
            TweenValue = 1.0;
        }
        else if (bSceneBoundaryBridgeActive)
        {
            // [TMA-SCENE-LOAD-CONTINUITY] At a rebuild boundary the final
            // route point can be overdue before the next scene publishes its
            // continuation. Extend the already-authoritative segment for at
            // most 180 ms / half a tile so the model and adaptive camera do
            // not visibly stop. The strict yellow-click + boundary test above
            // prevents this from reviving interaction-object overshoot.
            TweenValue = GetSceneBoundaryBridgeTweenValue(
                    MillisecondsSinceTileChange,
                    TweenDurationMilliseconds,
                    LastLerpPosition.distanceTo(NextLerpPosition));
        }
        else if (CurrentAnimationRequest.bUseLinearTween)
        {
            TweenValue = linearTween(
                    0L,
                    TweenDurationMilliseconds,
                    MillisecondsSinceTileChange);
            if (bApplyNormalMovementLead)
            {
                TweenValue = ApplyContinuousMovementSpeedLead(
                        TweenValue,
                        GetCurrentMovementLeadMultiplier());
            }
        }
        else
        {
            TweenValue = quadraticTween(
                    0L,
                    TweenDurationMilliseconds,
                    MillisecondsSinceTileChange);
            if (bApplyNormalMovementLead)
            {
                TweenValue = ApplyContinuousMovementSpeedLead(
                        TweenValue,
                        GetCurrentMovementLeadMultiplier());
            }
        }

        NewLocalPointToDraw = new LocalPoint((int) (LastLerpPosition.getX() + (NextLerpPosition.getX() - LastLerpPosition.getX()) * TweenValue),
                (int) (LastLerpPosition.getY() + (NextLerpPosition.getY() - LastLerpPosition.getY()) * TweenValue),
                LastLerpPosition.getWorldView());

        if (MillisecondsSinceTileChange >=
                TweenDurationMilliseconds)
        {
            bSceneRecoveryRetargetPending = false;
            if (ShouldCompleteSceneRecoveryOverride(
                    MillisecondsSinceTileChange,
                    SceneRecoveryTweenDurationOverride))
            {
                // [TMA-SCENE-RECOVERY-COMPLETION] A short proportional
                // recovery may finish before the normal 600 ms movement
                // duration. Collapse the completed segment before clearing
                // its override; otherwise the next frame would reinterpret
                // the same elapsed time against 600 ms and move backward.
                LastLerpPosition = NextLerpPosition;
                LastLerpPositionWorldPoint =
                        NextLerpPositionWorldPoint;
                NewLocalPointToDraw = NextLerpPosition;
                MillisecondsSinceTileChange =
                        BASE_MOVEMENT_TWEEN_MILLIS;
                SceneRecoveryTweenDurationOverride = 0;
                if (!bScenePresentationClockActive)
                {
                    SceneRecoveryBaseVelocity = 0;
                }
            }
        }

        if (currentTarget != null)
        {
            TargetOrientation = (getOrientationBetweenPoints(NewLocalPointToDraw.getX(), NewLocalPointToDraw.getY(), currentTarget.getLocalLocation().getX(), currentTarget.getLocalLocation().getY(), 90));
        }
        else if (!bMovingThisAction || // Not walking animation, face towards wherever the client is
                config.OnlyEnabledInCombat())
        {
            // Target is toward the real player now
            TargetOrientation = Owner.getOrientation();
        }
        // Face towards where you are moving
        else if (!LastLerpPosition.equals(NextLerpPosition))
        {
            TargetOrientation = (getOrientationBetweenPoints(LastLerpPosition.getX(), LastLerpPosition.getY(), NextLerpPosition.getX(), NextLerpPosition.getY(), 90));
        }

        if (bHoldWalkStopFacingThisFrame)
        {
            TargetOrientation = CurrentOrientation;
        }
    }

    private void UpdateCamera()
    {
        if (IsPlayerOwner())
        {
            if (config.SpawnModelAtCameraTile())
            {
                // Find best direction to go, offset by 10000 for comparison to avoid negatives
                int CameraTargetOrientation = (getOrientationBetweenPoints(Owner.getLocalLocation().getX(), Owner.getLocalLocation().getY(),
                        NewLocalPointToDraw.getX(), NewLocalPointToDraw.getX(), 270));
                int CameraTargetShortestAngle = ShortestAngleDifference(CurrentCameraObjectOrientation, CameraTargetOrientation);

                int NextCameraModelIndex = 0;
                if (Owner.getLocalLocation().equals(NewLocalPointToDraw) )
                {
                    if (config.StationaryCameraModelIndex() != 0)
                    {
                        NextCameraModelIndex = config.StationaryCameraModelIndex();
                    }
                }
                else
                {
                    NextCameraModelIndex = config.MovingCameraModelIndex();
                }

                // Snap to direction of travel
                if (CurrentCameraModelIndex != NextCameraModelIndex)
                {
                    int SnapToOrientation = (getOrientationBetweenPoints(Owner.getLocalLocation().getX(), Owner.getLocalLocation().getY(),
                            NextLerpPosition.getX(), NextLerpPosition.getY(), 270));
                    CurrentCameraModelIndex = NextCameraModelIndex;
                    CurrentCameraObjectOrientation = SnapToOrientation;
                }

                if (CurrentCameraModelIndex == 0)
                {
                    cameraModel.setActive(false);
                }
                else
                {
                    cameraModel.setModel(client.mergeModels(/*cameraModelAnimController.animate*/(client.loadModel(CurrentCameraModelIndex))));
                }

                // Need to rotate to our target rotation smoothly
                if (CameraTargetShortestAngle > 0)
                {
                    CurrentCameraObjectOrientation += Math.min(CameraTargetShortestAngle, config.CameraObjectOrientationRotationSpeed());
                }
                else if (CameraTargetShortestAngle != 0)
                {
                    CurrentCameraObjectOrientation -= Math.min(-CameraTargetShortestAngle, config.CameraObjectOrientationRotationSpeed());
                }

                if (CurrentCameraObjectOrientation < 0)
                {
                    CurrentCameraObjectOrientation += 2047;
                }
                else if (CurrentCameraObjectOrientation > 2047)
                {
                    CurrentCameraObjectOrientation -= 2047;
                }

                cameraModel.setOrientation(CurrentCameraObjectOrientation);

                // Apply a sinusoidal movement animation
                // Direction Vector
                double radians = CurrentCameraObjectOrientation * Math.PI / 1024.0;
                double DirectionVectorX = -Math.sin(radians);
                double DirectionVectorY = Math.cos(radians);

                CurrentArrowPointingAnimationFrame += CurrentFrameDelta * config.ArrowPointingAnimationSpeed() * 0.0001;
                int AnimationOffsetStrength = (int) (Math.sin(CurrentArrowPointingAnimationFrame) * config.ArrowPointingAnimationStrength());

                LocalPoint CameraFinalLocation = new LocalPoint(
                        (int) (Owner.getLocalLocation().getX() + DirectionVectorX * AnimationOffsetStrength)
                        , (int) (Owner.getLocalLocation().getY() + DirectionVectorY * AnimationOffsetStrength), Owner.getLocalLocation().getWorldView());

                cameraModel.setLocation(CameraFinalLocation, Math.min(4, Owner.getWorldView().getPlane() + config.CameraModelHeight()));

                if (!cameraModel.isActive())
                {
                    cameraModel.setActive(true);
                }
            }
            else if (cameraModel != null)
            {
                cameraModel.setActive(false);
            }
        }
    }

    private void SetAllIdlePosesDefault()
    {
        if (Owner.getIdleRotateLeft() != OldAnimationSet.IdleRotateLeft)
        {
            Owner.setIdleRotateLeft(OldAnimationSet.IdleRotateLeft);
        }

        if (Owner.getIdleRotateRight() != OldAnimationSet.IdleRotateRight)
        {
            Owner.setIdleRotateRight(OldAnimationSet.IdleRotateRight);
        }

        if (Owner.getWalkAnimation() != OldAnimationSet.WalkAnimation)
        {
            Owner.setWalkAnimation(OldAnimationSet.WalkAnimation);
        }

        if (Owner.getWalkRotateLeft() != OldAnimationSet.WalkRotateLeft)
        {
            Owner.setWalkRotateLeft(OldAnimationSet.WalkRotateLeft);
        }

        if (Owner.getWalkRotateRight() != OldAnimationSet.WalkRotateRight)
        {
            Owner.setWalkRotateRight(OldAnimationSet.WalkRotateRight);
        }

        if (Owner.getWalkRotate180() != OldAnimationSet.WalkRotate180)
        {
            Owner.setWalkRotate180(OldAnimationSet.WalkRotate180);
        }

        if (Owner.getIdlePoseAnimation() != OldAnimationSet.IdlePoseAnimation)
        {
            Owner.setIdlePoseAnimation(OldAnimationSet.IdlePoseAnimation);
        }

        if (Owner.getRunAnimation() != OldAnimationSet.RunAnimation)
        {
            Owner.setRunAnimation(OldAnimationSet.RunAnimation);
        }
    }
    private void SetAllIdlePosesNoAnimation()
    {

        if (Owner.getIdleRotateLeft() != NO_ANIMATION)
        {
            Owner.setIdleRotateLeft(NO_ANIMATION);
        }

        if (Owner.getIdleRotateRight() != NO_ANIMATION)
        {
            Owner.setIdleRotateRight(NO_ANIMATION);
        }

        if (Owner.getWalkAnimation() != NO_ANIMATION)
        {
            Owner.setWalkAnimation(NO_ANIMATION);
        }

        if (Owner.getWalkRotateLeft() != NO_ANIMATION)
        {
            Owner.setWalkRotateLeft(NO_ANIMATION);
        }

        if (Owner.getWalkRotateRight() != NO_ANIMATION)
        {
            Owner.setWalkRotateRight(NO_ANIMATION);
        }

        if (Owner.getWalkRotate180() != NO_ANIMATION)
        {
            Owner.setWalkRotate180(NO_ANIMATION);
        }

        if (Owner.getIdlePoseAnimation() != NO_ANIMATION)
        {
            Owner.setIdlePoseAnimation(NO_ANIMATION);
        }

        if (Owner.getRunAnimation() != NO_ANIMATION)
        {
            Owner.setRunAnimation(NO_ANIMATION);
        }

    }

    private void UpdateModelVisibleState()
    {
        // Enter combat mode
        if (!bAttemptToRenderOwner)
        {
            bShouldRenderOwner = false;
        }

        if (!bShouldRenderOwner)
        {
            // Animation has opted to use the true location/orientation (probably agility obstacle)
            int OwnerAnimation = Owner.getAnimation();
            bShouldUseTrueLocationOrientation |= (OwnerAnimation != -1 &&
                    currentTarget == null &&
                    UniqueAnimationLocationAndOrientationExceptionList.contains(OwnerAnimation));

            if (bShouldUseTrueLocationOrientation || (CurrentTime - LastTimeUniqueAnimationLocationOrientationWasUsed) < 600) // A little bit of time before going to other animation
            {
                if (Model.getLocation() != Owner.getLocalLocation())
                {
                    Model.setLocation(Owner.getLocalLocation(), Owner.getWorldView().getPlane());
                }
                if (Model.getOrientation() != Owner.getOrientation())
                {
                    Model.setOrientation(Owner.getOrientation());
                }

                CurrentOrientation = Owner.getOrientation();

                if (bShouldUseTrueLocationOrientation)
                {
                    LastTimeUniqueAnimationLocationOrientationWasUsed = CurrentTime;
                }
            }
            else
            {
                if (Model.getLocation() != NewLocalPointToDraw)
                {
                    Model.setLocation(NewLocalPointToDraw,
                            Owner.getWorldView().getPlane());
                }
                // Find best direction to go, offset by 10000 for comparison to avoid negatives
                int ShortestAngle = ShortestAngleDifference(CurrentOrientation, TargetOrientation);

                // Need to rotate to our target rotation smoothly
                double AdjustedOrientationSpeed = CurrentAnimationRequest.OrientationSpeed * (CurrentFrameDelta / 16.667);// Speed value centered at 60FPS
                if (ShortestAngle > 0)
                {
                    CurrentOrientation += Math.min(ShortestAngle, AdjustedOrientationSpeed);
                }
                else if (ShortestAngle != 0)
                {
                    CurrentOrientation -= Math.min(-ShortestAngle, AdjustedOrientationSpeed);
                }

                if (CurrentOrientation < 0)
                {
                    CurrentOrientation += 2047;
                }
                else if (CurrentOrientation > 2047)
                {
                    CurrentOrientation -= 2047;
                }

                // Don't rotate if we are at the destination when we are not in battle mode
                if (Model.getOrientation() != CurrentOrientation)
                {
                    Model.setOrientation(CurrentOrientation);
                }
            }

            // [TMA-STOP-POSE-SEPARATION] Yellow-click stop handling owns only
            // orientation. Body geometry deliberately uses the same ordinary
            // animation publication path as a red-click stop. A separate idle
            // controller produced a malformed transition pose, while copying
            // the hidden player exposed its remaining locomotion as running on
            // the spot. Keeping both out of this branch avoids either seam.
            boolean bUsedCustomAnimation = false;
            boolean bControllerAnimationRequested =
                    (UniqueAnimationExceptionList.contains(
                                     Owner.getAnimation()) &&
                                    bMovingThisAction) ||
                                    CurrentAnimationRequest.AnimationToPlay !=
                                            -1;
            if (bControllerAnimationRequested)
            {
                // Anim controller takes control over the pose animation or custom anim
                Animation CustomAnim = null;

                boolean bUsingPoseAnim = false;
                if (CurrentAnimationRequest.PoseAnimationToPlay != -1)
                {
                    bUsingPoseAnim = true;
                    CustomAnim = client.loadAnimation(CurrentAnimationRequest.PoseAnimationToPlay);
                }
                else
                {
                    CustomAnim = client.loadAnimation(CurrentAnimationRequest.AnimationToPlay);
                }

                if (CustomAnim != null)
                {
                    bUsedCustomAnimation = true;
                    int CurrentControllerAnimationId =
                            AnimController.getAnimation() == null
                                    ? NO_ANIMATION
                                    : AnimController.getAnimation().getId();
                    if (ShouldReplaceAnimationController(
                            CurrentControllerAnimationId,
                            CustomAnim.getId(),
                            bResetCurrentAnimation))
                    {
                        AnimController.setAnimation(CustomAnim);

                        if (bUsingPoseAnim &&
                                Owner.getPoseAnimationFrame() >= 0 &&
                                Owner.getPoseAnimationFrame() <
                                        CustomAnim.getNumFrames() &&
                                !bResetCurrentAnimation)
                        {
                            AnimController.setFrame(
                                    Owner.getPoseAnimationFrame());
                        }
                        else
                        {
                            AnimController.setFrame(
                                    CurrentAnimationRequest.StartingFrame);
                        }
                        bResetCurrentAnimation = false;
                    }

                    SetAllIdlePosesNoAnimation();
                    Owner.setPoseAnimation(NO_ANIMATION);
                    Owner.setPoseAnimationFrame(0);

                    if (CurrentTime - LastAnimationTickTime >= 17) // 17ms per frame->60FPS
                    {
                        LastAnimationTickTime = CurrentTime;
                        int CurrentFrame = AnimController.getFrame();
                        if (CurrentFrame >=
                                CurrentAnimationRequest.EndingFrame)
                        {
                            AnimController.setFrame(
                                    CurrentAnimationRequest.EndingFrame);
                        }
                        else
                        {
                            AnimController.tick(
                                    CurrentAnimationRequest.AnimationSpeed);
                        }
                    }

                    net.runelite.api.Model OwnerModel = Owner.getModel();
                    if (OwnerModel == null ||
                            !TrySetModel(
                                    AnimController.animate(
                                            OwnerModel)))
                    {
                        // Preserve the last drawable model and use the normal
                        // controller for this frame. Loading/model replacement
                        // should never create a null-frame hole.
                        bUsedCustomAnimation = false;
                    }
                }
            }
            if (!bUsedCustomAnimation)
            {
                // Normal controller takes back over
                bTargetWasKilled = false; // If normal controller is taking it, cancel target killed animation
                SetAllIdlePosesDefault();
                if (AnimController.getAnimation() != null)
                {
                    Owner.setPoseAnimation(AnimController.getAnimation().getId());
                    Owner.setPoseAnimationFrame(AnimController.getFrame());
                    CurrentPoseAnimation = AnimController.getAnimation().getId();
                    AnimController.setAnimation(null);
                    AnimController.setFrame(0);
                }

                if (CurrentAnimationRequest.PoseAnimationToPlay != -1 &&
                        (Owner.getPoseAnimation() !=
                                CurrentAnimationRequest.PoseAnimationToPlay ||
                                Owner.getPoseAnimationFrame() < 0 ||
                                bResetCurrentAnimation))
                {
                    int RequestedPoseAnimation =
                            CurrentAnimationRequest.PoseAnimationToPlay;
                    Animation CustomAnim =
                            client.loadAnimation(RequestedPoseAnimation);

                    if (CustomAnim != null)
                    {
                        int SafePoseFrame = SelectPoseFrameForPublication(
                                Owner.getPoseAnimation(),
                                Owner.getPoseAnimationFrame(),
                                RequestedPoseAnimation,
                                LastValidOwnerPoseAnimation,
                                LastValidOwnerPoseFrame,
                                CustomAnim.getNumFrames(),
                                CurrentAnimationRequest.StartingFrame,
                                bResetCurrentAnimation,
                                ShouldRestartStationaryIdlePose(
                                        bMovingThisAction,
                                        Owner.getAnimation(),
                                        Owner.getPoseAnimation(),
                                        RequestedPoseAnimation,
                                        OldAnimationSet.IdlePoseAnimation));
                        if (Owner.getPoseAnimationFrame() != SafePoseFrame)
                        {
                            Owner.setPoseAnimationFrame(SafePoseFrame);
                        }

                        if (Owner.getPoseAnimation() != RequestedPoseAnimation)
                        {
                            Owner.setPoseAnimation(RequestedPoseAnimation);
                        }
                        CurrentPoseAnimation = NO_ANIMATION;
                        bResetCurrentAnimation = false;
                    }
                }
                // [TMA-STEADY-PRESENTATION] Owner.getModel() can be
                // momentarily unavailable while RuneLite rebuilds equipment
                // or animation state. Keep the last complete model for that
                // frame instead of assigning null and making the player pop.
                if (TrySetModel(Owner.getModel()))
                {
                    int PublishedPoseAnimation = Owner.getPoseAnimation();
                    int PublishedPoseFrame = Owner.getPoseAnimationFrame();
                    if (PublishedPoseAnimation != NO_ANIMATION &&
                            PublishedPoseFrame >= 0)
                    {
                        LastValidOwnerPoseAnimation = PublishedPoseAnimation;
                        LastValidOwnerPoseFrame = PublishedPoseFrame;
                    }
                }
            }

            net.runelite.api.Model RenderedModel = Model.getModel();
            net.runelite.api.Model OwnerModel = Owner.getModel();
            if (RenderedModel != null &&
                    OwnerModel != null &&
                    RenderedModel.getModelHeight() !=
                            OwnerModel.getModelHeight())
            {
                RenderedModel.setModelHeight(
                        OwnerModel.getModelHeight());
            }

            if (RenderedModel != null &&
                    OwnerModel != null &&
                    RenderedModel.getUvBufferOffset() !=
                            OwnerModel.getUvBufferOffset())
            {
                RenderedModel.setUvBufferOffset(
                        OwnerModel.getUvBufferOffset());
            }

            if (RenderedModel != null &&
                    OwnerModel != null &&
                    RenderedModel.getBufferOffset() !=
                            OwnerModel.getBufferOffset())
            {
                RenderedModel.setBufferOffset(
                        OwnerModel.getBufferOffset());
            }

            if (RenderedModel != null &&
                    OwnerModel != null &&
                    RenderedModel.getSceneId() !=
                            OwnerModel.getSceneId())
            {
                RenderedModel.setSceneId(
                        OwnerModel.getSceneId());
            }

            int FootprintHeight = Perspective.getFootprintTileHeight(client, Model.getLocation(), Owner.getWorldView().getPlane(), Owner.getFootprintSize());
            if (Owner.getAnimation() != -1)
            {
                FootprintHeight -= Owner.getAnimationHeightOffset();
            }
            else
            {
                FootprintHeight -= OldAnimationHeight;
            }

            if (Model.getZ() != FootprintHeight)
            {
                Model.setZ(FootprintHeight);
            }

            // [TMA-NO-SPAWN-IN] The native presentation is still useful
            // while genuinely idle, but do NOT toggle the custom model
            // inactive during proximity. setActive(false) → setActive(true)
            // triggers RuneLite's entity spawn-in animation (small → normal
            // scale), and the model may be reactivated at a stale segment
            // destination rather than its last rendered location. Instead,
            // keep the custom model active in the background and let the
            // native player draw on top when within proximity thresholds.
            // This prevents every stationary → moving transition from
            // producing a visible shrink/grow at the wrong tile.
            if (RenderedModel != null &&
                    ShouldRenderOriginalOwner(
                            bUsedCustomAnimation))
            {
                if (!Model.isActive())
                {
                    Model.setActive(true);
                }
                bRenderOriginalOwnerDueToProximity = true;
            }
            else
            {
                if (RenderedModel != null &&
                        !Model.isActive())
                {
                    Model.setActive(true);
                }
                bRenderOriginalOwnerDueToProximity =
                        RenderedModel == null;
            }

            RecordLastRenderedLocation(Model.getLocation());
            UpdateCamera();
        }
        else
        {
            SetAllIdlePosesDefault();
            Model.setActive(false);
            if (cameraModel != null)
            {
                cameraModel.setActive(false);
            }
        }

    }

    /**
     * Capture a bounded stop/idle trace. This method must be called only from
     * RuneLite's client thread; it is the sole diagnostic path which inspects
     * the scene-owned RuneLiteObject or its prepared model.
     */
    void CaptureStopIdleDiagnosticsOnClientThread(
            boolean Enabled)
    {
        if (!Enabled || !IsPlayerOwner() ||
                client.getGameState() != GameState.LOGGED_IN)
        {
            ResetStopIdleDiagnostics();
            return;
        }

        boolean bYellowStopContext =
                bWalkStopFacingHoldArmed ||
                        bWalkMovementObserved ||
                        bPreserveReleasedWalkFacing ||
                        bHoldWalkStopFacingThisFrame;
        String Snapshot;
        try
        {
            Snapshot = BuildStopIdleDiagnosticSnapshot(
                    bYellowStopContext);
        }
        catch (RuntimeException SnapshotFailure)
        {
            // Diagnostics are observational and must never be able to disable
            // rendering or crash the client. Keep the transition marker and
            // record the failed read without retrying live scene state.
            Snapshot = "time=" + System.currentTimeMillis() +
                    " cycle=" + client.getGameCycle() +
                    " snapshotFailure=" +
                    SnapshotFailure.getClass().getSimpleName() +
                    " moving=" + bMovingThisAction +
                    " yellowContext=" + bYellowStopContext;
        }
        boolean bStoppedThisSample = ShouldOpenStopIdleTrace(
                bStopIdleTraceInitialized,
                bStopIdleTraceLastMoving,
                bMovingThisAction,
                bYellowStopContext);

        if (bStoppedThisSample)
        {
            if (StopIdleTracePostSamplesRemaining > 0)
            {
                log.debug(
                        "[StopIdleTrace] event={} phase=end cause=next-stop",
                        StopIdleTraceEvent);
            }

            ++StopIdleTraceEvent;
            log.debug(
                    "[StopIdleTrace] event={} phase=begin preSamples={} note=client-thread-only",
                    StopIdleTraceEvent,
                    StopIdleTraceHistory.size());
            for (String PreviousSnapshot : StopIdleTraceHistory)
            {
                log.debug(
                        "[StopIdleTrace] event={} phase=pre {}",
                        StopIdleTraceEvent,
                        PreviousSnapshot);
            }
            log.debug(
                    "[StopIdleTrace] event={} phase=stop {}",
                    StopIdleTraceEvent,
                    Snapshot);
            client.addChatMessage(
                    ChatMessageType.GAMEMESSAGE,
                    "",
                    "True Movement: stop-idle trace #" +
                            StopIdleTraceEvent +
                            " recorded",
                    null);
            StopIdleTracePostSamplesRemaining =
                    STOP_IDLE_TRACE_POST_SAMPLES;
        }
        else if (StopIdleTracePostSamplesRemaining > 0)
        {
            log.debug(
                    "[StopIdleTrace] event={} phase=post remaining={} {}",
                    StopIdleTraceEvent,
                    StopIdleTracePostSamplesRemaining,
                    Snapshot);
            --StopIdleTracePostSamplesRemaining;
            if (StopIdleTracePostSamplesRemaining == 0)
            {
                log.debug(
                        "[StopIdleTrace] event={} phase=end cause=window-complete",
                        StopIdleTraceEvent);
            }
        }

        StopIdleTraceHistory.addLast(Snapshot);
        while (StopIdleTraceHistory.size() >
                STOP_IDLE_TRACE_PRE_SAMPLES)
        {
            StopIdleTraceHistory.removeFirst();
        }
        bStopIdleTraceInitialized = true;
        bStopIdleTraceLastMoving = bMovingThisAction;
    }

    static boolean ShouldOpenStopIdleTrace(
            boolean Initialized,
            boolean WasMoving,
            boolean MovingNow,
            boolean YellowStopContext)
    {
        return Initialized &&
                WasMoving &&
                !MovingNow &&
                YellowStopContext;
    }

    private void ResetStopIdleDiagnostics()
    {
        StopIdleTraceHistory.clear();
        bStopIdleTraceInitialized = false;
        bStopIdleTraceLastMoving = false;
        StopIdleTracePostSamplesRemaining = 0;
        StopIdleTraceEvent = 0;
    }

    private String BuildStopIdleDiagnosticSnapshot(
            boolean YellowStopContext)
    {
        int RequestedAction = CurrentAnimationRequest == null
                ? NO_ANIMATION
                : CurrentAnimationRequest.AnimationToPlay;
        int RequestedPose = CurrentAnimationRequest == null
                ? NO_ANIMATION
                : CurrentAnimationRequest.PoseAnimationToPlay;
        int MainControllerAnimation =
                AnimController == null ||
                        AnimController.getAnimation() == null
                        ? NO_ANIMATION
                        : AnimController.getAnimation().getId();
        int MainControllerFrame = AnimController == null
                ? NO_ANIMATION
                : AnimController.getFrame();
        RuneLiteObject VisibleObject = Model;
        boolean VisibleObjectActive =
                VisibleObject != null && VisibleObject.isActive();
        LocalPoint VisibleLocation = VisibleObject == null
                ? null
                : VisibleObject.getLocation();
        net.runelite.api.Model VisibleModel = VisibleObject == null
                ? null
                : VisibleObject.getModel();
        net.runelite.api.Model OwnerModel = Owner.getModel();

        return "time=" + System.currentTimeMillis() +
                " cycle=" + client.getGameCycle() +
                " movement[moving=" + bMovingThisAction +
                " elapsed=" + MillisecondsSinceTileChange +
                " duration=" + GetMovementTweenDurationMilliseconds() +
                " from=" + DescribeLocalPoint(LastLerpPosition) +
                " to=" + DescribeLocalPoint(NextLerpPosition) +
                " drawn=" + DescribeLocalPoint(NewLocalPointToDraw) +
                "] request[action=" + RequestedAction +
                " pose=" + RequestedPose +
                "] owner[action=" + Owner.getAnimation() +
                " actionFrame=" + Owner.getAnimationFrame() +
                " pose=" + Owner.getPoseAnimation() +
                " poseFrame=" + Owner.getPoseAnimationFrame() +
                " idle=" + Owner.getIdlePoseAnimation() +
                " walk=" + Owner.getWalkAnimation() +
                " run=" + Owner.getRunAnimation() +
                " orientation=" + Owner.getOrientation() +
                " currentOrientation=" + Owner.getCurrentOrientation() +
                " location=" + DescribeLocalPoint(Owner.getLocalLocation()) +
                "] controller[main=" + MainControllerAnimation +
                ":" + MainControllerFrame +
                "] yellow[context=" + YellowStopContext +
                " armed=" + bWalkStopFacingHoldArmed +
                " observed=" + bWalkMovementObserved +
                " hold=" + bHoldWalkStopFacingThisFrame +
                " preserve=" + bPreserveReleasedWalkFacing +
                " nativeSettled=" + bNativeWalkFacingSettled +
                " awaiting=" + bWalkSegmentAwaitingMovement +
                "] presentation[owner=" + bShouldRenderOwner +
                " proximity=" + bRenderOriginalOwnerDueToProximity +
                " customActive=" + VisibleObjectActive +
                " customLocation=" + DescribeLocalPoint(VisibleLocation) +
                "] geometry[custom=" + DescribeModel(VisibleModel) +
                " owner=" + DescribeModel(OwnerModel) + "]";
    }

    private static String DescribeLocalPoint(LocalPoint Point)
    {
        return Point == null
                ? "null"
                : Point.getX() + "," + Point.getY() +
                        ",wv=" + Point.getWorldView();
    }

    private static String DescribeModel(net.runelite.api.Model SourceModel)
    {
        if (SourceModel == null)
        {
            return "null";
        }

        float[] VerticesX = SourceModel.getVerticesX();
        float[] VerticesY = SourceModel.getVerticesY();
        float[] VerticesZ = SourceModel.getVerticesZ();
        int VertexCount = Math.min(
                SourceModel.getVerticesCount(),
                Math.min(
                        VerticesX == null ? 0 : VerticesX.length,
                        Math.min(
                                VerticesY == null ? 0 : VerticesY.length,
                                VerticesZ == null ? 0 : VerticesZ.length)));
        if (VertexCount <= 0)
        {
            return "vertices=0 faces=" + SourceModel.getFaceCount();
        }

        int Step = Math.max(
                1,
                VertexCount /
                        STOP_IDLE_TRACE_MODEL_VERTEX_SAMPLES);
        long Hash = 0xcbf29ce484222325L;
        int Samples = 0;
        for (int Index = 0;
             Index < VertexCount;
             Index += Step)
        {
            Hash ^= Float.floatToIntBits(VerticesX[Index]);
            Hash *= 0x100000001b3L;
            Hash ^= Float.floatToIntBits(VerticesY[Index]);
            Hash *= 0x100000001b3L;
            Hash ^= Float.floatToIntBits(VerticesZ[Index]);
            Hash *= 0x100000001b3L;
            ++Samples;
        }

        return "vertices=" + VertexCount +
                " faces=" + SourceModel.getFaceCount() +
                " samples=" + Samples +
                " hash=" + Long.toUnsignedString(Hash, 16) +
                " scene=" + SourceModel.getSceneId() +
                " buffer=" + SourceModel.getBufferOffset();
    }

    public void Update()
    {
        if (client.getGameState() == GameState.LOADING)
        {
            // [TMA-MOTION-CONTINUITY] Scene-owned objects may be replaced
            // during this interval, but the last valid handler state remains
            // authoritative. Initialize marks a pending rebase; wait until
            // world/local conversion is valid instead of resetting or moving
            // the visible player to a transient local coordinate.
            return;
        }

        UpdateFrameTimer();

        UpdateOldIdleAnimations();

        UpdateTargetStatus();

        if (bSceneRebasePending && RebaseAfterSceneLoad())
        {
            bSceneRebasePending = false;
        }

        if (!UpdateTrueTileLocation())
        {
            // Soft suspension while the client is rebuilding a scene: retain
            // the last valid model/interpolation state until conversion from
            // world coordinates is available again.
            return;
        }

        UpdateLerpDestinations();

        if (bNewTileMovementStarted)
        {
            // [TMA-FRESH-WALK-START] A real authoritative movement segment has
            // arrived. This clears both an ordinary re-click delay and the
            // first-segment wait carried through a scene rebuild. Recovery
            // tweening alone never clears either state.
            WalkClickRevisionAtMovementSegment = WalkClickRevision;
            bWalkSegmentAwaitingMovement = false;
            bWalkStartPendingDuringCatchUp = false;
            bWalkReclickOccurredDuringVisibleMovement = false;
        }

        UpdateWalkStopFacingHold();

        UpdateAnimationSelection();

        UpdateMovementType();

        ApplyTweening();

        UpdateModelVisibleState();
    }
}
