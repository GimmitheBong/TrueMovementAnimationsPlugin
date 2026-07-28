package com.truetileanimationmovement;

import net.runelite.api.*;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.config.ConfigItem;

import javax.inject.Inject;
import java.util.HashSet;
import java.util.Set;

public class CustomMovementHandler
{
    private static final int BASE_MOVEMENT_TWEEN_MILLIS = 600;
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
    // Client/game tick scheduling is not perfectly aligned with overlay
    // rendering. A small, route-qualified grace prevents a completed 600 ms
    // segment from selecting idle for one frame immediately before the next
    // segment is published.
    private static final int MOVEMENT_ANIMATION_CONTINUITY_GRACE_MILLIS = 100;

    // General
    private final Client client;
    private final TrueTileMovementPlugin plugin;
    private final TrueTileMovementConfig config;
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
    // [TMA-IDLE-CATCH-UP] The hidden player is still using a locomotion clock
    // after the visible model stops. A separate idle controller prevents that
    // faster clock from driving the visible breathing/head-turn pose.
    private AnimationController WalkStopIdleController = null;
    private int LastWalkStopIdleGameCycle = -1;
    private boolean bUsingWalkStopIdleController = false;
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
    private int NO_ANIMATION = -1;
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
    //   Preserve    = catch-up ended; keep the released facing until native
    //                 code issues a different orientation command.
    //   HoldThisFrame = the derived per-frame decision used by rendering.
    private boolean bWalkStopFacingHoldArmed = false;
    private boolean bWalkMovementObserved = false;
    private boolean bPreserveReleasedWalkFacing = false;
    private boolean bHoldWalkStopFacingThisFrame = false;
    private int NativeOrientationAtWalkFacingRelease = 0;


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
        UniqueAnimationExceptionList.add(714); // Teleport
        UniqueAnimationExceptionList.add(878); // Teleport
        UniqueAnimationExceptionList.add(1816); // Teleport
        UniqueAnimationExceptionList.add(1979); // Teleport
        UniqueAnimationExceptionList.add(3872); // Teleport
        UniqueAnimationExceptionList.add(13811); // Teleport
        UniqueAnimationExceptionList.add(4069); // Teleport
        UniqueAnimationExceptionList.add(4071); // Teleport
        UniqueAnimationExceptionList.add(3869); // Teleport
        UniqueAnimationExceptionList.add(3865); // Teleport
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
        if (Distance <= 0 || BaseVelocity <= 0)
        {
            return Math.max(1L, FallbackDurationMilliseconds);
        }

        return Math.max(1L, (long) Math.ceil(
                Distance / BaseVelocity));
    }

    static boolean ShouldKeepMovementAnimationDuringRouteGap(
            int MillisecondsSinceTileChange,
            boolean WasMoving,
            boolean WalkRouteArmed,
            boolean WalkMovementObserved,
            LocalPoint CurrentSegmentDestination,
            LocalPoint RouteDestination)
    {
        return WasMoving &&
                WalkRouteArmed &&
                WalkMovementObserved &&
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
                // Reset animation (loop)
                InController.setFrame(0);
                bTargetWasKilled = false;
            });
        }

        if (Model == null || bReplaceSceneObjects)
        {
            RuneLiteObject OldModel = Model;
            Model = client.createRuneLiteObject();

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
                        // Reset animation (loop)
                        InController.setFrame(0);
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
        ReleaseWalkStopIdleController(false);
        WalkStopIdleController = null;
        bSceneRebasePending = false;
        bSceneRecoveryRetargetPending = false;
        bSceneBoundaryBridgeActive = false;
        bScenePresentationClockActive = false;
        ScenePresentationTimeDebtMilliseconds = 0;
        SceneRecoveryBaseVelocity = 0;
        SceneRecoveryTweenDurationOverride = 0;
        bNativeSceneLoadHandoffPresented = false;
        bLastSceneRebaseUsedNativeHandoffAnchor = false;

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

        bWalkStopFacingHoldArmed = true;
        bWalkMovementObserved = false;
        // If the previous route is already preserving its released facing,
        // keep that stable during the short click-to-movement delay. The new
        // route clears it as soon as visible movement actually begins. It is
        // important that Observed remains false here: a click alone must not
        // restart the catch-up idle renderer before the player moves.
    }

    void CancelWalkStopFacingHold()
    {
        bWalkStopFacingHoldArmed = false;
        bWalkMovementObserved = false;
        bPreserveReleasedWalkFacing = false;
        bHoldWalkStopFacingThisFrame = false;
    }

    private void UpdateWalkStopFacingHold()
    {
        bHoldWalkStopFacingThisFrame = false;
        if (!IsPlayerOwner())
        {
            return;
        }

        // The custom model is considered moving while its rendered tile has
        // changed recently. This also catches forced movement, which may not
        // have a preceding yellow click.
        boolean VisibleModelIsMoving = MillisecondsSinceTileChange < 600;
        if (VisibleModelIsMoving)
        {
            if (bWalkStopFacingHoldArmed)
            {
                bWalkMovementObserved = true;
            }

            // Forced movement or another route can begin without a fresh
            // yellow click. It must not inherit a completed route's facing.
            bPreserveReleasedWalkFacing = false;
            return;
        }

        if (bPreserveReleasedWalkFacing)
        {
            // The catch-up block itself has ended. Ignore only the exact stale
            // native target which existed at release; any later native facing
            // command restores ordinary turning.
            if (Owner.getOrientation() == NativeOrientationAtWalkFacingRelease)
            {
                bHoldWalkStopFacingThisFrame = true;
            }
            else
            {
                bPreserveReleasedWalkFacing = false;
            }
            return;
        }

        if (!bWalkStopFacingHoldArmed ||
                !bWalkMovementObserved ||
                !IsAtFinalWalkDestination())
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
            bWalkStopFacingHoldArmed = false;
            bWalkMovementObserved = false;
            bPreserveReleasedWalkFacing = true;
            NativeOrientationAtWalkFacingRelease = Owner.getOrientation();
        }
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

    static boolean ShouldUseWalkStopIdleController(
            boolean HoldFacingThisFrame,
            boolean CatchUpStillActive,
            boolean MovementWasObserved,
            int OwnerActionAnimation,
            int IdlePoseAnimation)
    {
        // Idle smoothing is valid only during the actual catch-up interval.
        // In particular, Preserve/Armed without MovementWasObserved means a
        // new click is waiting to start and must remain on the native idle
        // animation instead of reviving an old controller frame.
        return HoldFacingThisFrame &&
                CatchUpStillActive &&
                MovementWasObserved &&
                OwnerActionAnimation == -1 &&
                IdlePoseAnimation != -1;
    }

    private boolean TrySetModel(
            net.runelite.api.Model SourceModel)
    {
        if (SourceModel == null)
        {
            return false;
        }

        net.runelite.api.Model MergedModel =
                client.mergeModels(SourceModel);
        if (MergedModel == null)
        {
            return false;
        }

        Model.setModel(MergedModel);
        return true;
    }

    private boolean RenderWalkStopIdleAnimation()
    {
        int IdlePoseAnimation = OldAnimationSet.IdlePoseAnimation;
        if (!ShouldUseWalkStopIdleController(
                bHoldWalkStopFacingThisFrame,
                bWalkStopFacingHoldArmed,
                bWalkMovementObserved,
                Owner.getAnimation(),
                IdlePoseAnimation))
        {
            ReleaseWalkStopIdleController(true);
            return false;
        }

        Animation IdleAnimation = client.loadAnimation(IdlePoseAnimation);
        if (IdleAnimation == null)
        {
            ReleaseWalkStopIdleController(false);
            return false;
        }

        int CurrentGameCycle = client.getGameCycle();
        if (!bUsingWalkStopIdleController ||
                WalkStopIdleController == null ||
                WalkStopIdleController.getAnimation() == null ||
                WalkStopIdleController.getAnimation().getId() != IdlePoseAnimation)
        {
            // [TMA-IDLE-CATCH-UP] Each genuine stop gets its own idle clock.
            // Reusing the previous stop's controller made the model snap back
            // to an unrelated old frame when catch-up began again.
            WalkStopIdleController =
                    new AnimationController(client, IdleAnimation);
            int NativeIdleFrame =
                    Owner.getPoseAnimation() == IdlePoseAnimation
                            ? Owner.getPoseAnimationFrame()
                            : 0;
            if (NativeIdleFrame >= 0 &&
                    NativeIdleFrame < IdleAnimation.getNumFrames())
            {
                WalkStopIdleController.setFrame(NativeIdleFrame);
            }
            LastWalkStopIdleGameCycle = CurrentGameCycle;
        }
        else if (LastWalkStopIdleGameCycle >= 0 &&
                CurrentGameCycle >= LastWalkStopIdleGameCycle)
        {
            WalkStopIdleController.tick(
                    CurrentGameCycle - LastWalkStopIdleGameCycle);
            LastWalkStopIdleGameCycle = CurrentGameCycle;
        }
        else
        {
            LastWalkStopIdleGameCycle = CurrentGameCycle;
        }

        // Build an unposed equipment model, then apply the independent idle
        // controller. AnimationController supplies RuneLite's packed
        // interpolation frame whenever Animation Smoothing is enabled. The
        // hidden actor can continue advancing its locomotion animation while
        // it catches up, so its pose is deliberately not used as the source.
        SetAllIdlePosesNoAnimation();
        Owner.setPoseAnimation(NO_ANIMATION);
        Owner.setPoseAnimationFrame(0);
        net.runelite.api.Model OwnerModel = Owner.getModel();
        if (OwnerModel == null ||
                !TrySetModel(
                        WalkStopIdleController.animate(
                                OwnerModel)))
        {
            // [TMA-STEADY-PRESENTATION] A transient native model miss must
            // not replace the last valid custom frame with null. Restore the
            // ordinary animation fields and let the fallback branch below
            // retry on the next rendered frame.
            SetAllIdlePosesDefault();
            ReleaseWalkStopIdleController(false);
            return false;
        }

        bUsingWalkStopIdleController = true;
        bResetCurrentAnimation = false;
        CurrentPoseAnimation = NO_ANIMATION;
        return true;
    }

    private void ReleaseWalkStopIdleController(
            boolean PreserveIdlePhase)
    {
        if (!bUsingWalkStopIdleController)
        {
            return;
        }

        // [TMA-IDLE-CATCH-UP] Hand the final idle frame back to RuneLite once
        // the hidden actor reaches the rendered tile. This preserves breathing
        // and head-turn phase without leaving the custom controller active.
        if (PreserveIdlePhase &&
                WalkStopIdleController != null &&
                WalkStopIdleController.getAnimation() != null &&
                !bMovingThisAction &&
                CurrentAnimationRequest != null &&
                CurrentAnimationRequest.PoseAnimationToPlay ==
                        OldAnimationSet.IdlePoseAnimation)
        {
            Owner.setPoseAnimation(
                    WalkStopIdleController.getAnimation().getId());
            Owner.setPoseAnimationFrame(
                    WalkStopIdleController.getFrame());
        }

        bUsingWalkStopIdleController = false;
        LastWalkStopIdleGameCycle = -1;
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

            if (ScenePresentationTimeDebtMilliseconds == 0 &&
                    !bSceneRecoveryRetargetPending &&
                    SceneRecoveryTweenDurationOverride == 0)
            {
                bScenePresentationClockActive = false;
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
            double DistanceInTiles =
                    RenderedLocation.distanceTo(CurrentTrueLocation) /
                            Perspective.LOCAL_TILE_SIZE;
            bRealDiscontinuity = DistanceInTiles >
                    Math.max(1, config.PlayerModelSnapDistance());
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
        ScenePresentationTimeDebtMilliseconds = 0;
        SceneRecoveryTweenDurationOverride = 0;
        SceneRecoveryBaseVelocity = bHasRecoveryDistance
                ? RenderedLocation.distanceTo(CurrentTrueLocation) /
                        BASE_MOVEMENT_TWEEN_MILLIS
                : 0;
        bScenePresentationClockActive =
                bHasRecoveryDistance;
        MillisecondsSinceTileChange =
                GetSceneRebaseElapsedMilliseconds(
                        CurrentFrameDelta,
                        bHasRecoveryDistance,
                        bUseNativeHandoffAnchor);
        bSceneRecoveryRetargetPending =
                bHasRecoveryDistance &&
                        MillisecondsSinceTileChange <
                                BASE_MOVEMENT_TWEEN_MILLIS;

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
                boolean bReanchorBoundaryBridge =
                        bSceneBoundaryBridgeActive &&
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
                    // [TMA-SCENE-LOAD-CONTINUITY] The next route step arrived
                    // before a recovery/scene-edge bridge finished. Continue
                    // from the displayed point; snapping to the authoritative
                    // endpoint is the skip visible in the scene-load traces.
                    LastLerpPosition = NewLocalPointToDraw;
                    LastLerpPositionWorldPoint =
                            WorldPoint.fromLocal(
                                    client,
                                    LastLerpPosition);
                }
                else if (NextLerpPoint != null &&
                        DistanceInTilesToNextLerp <= config.PlayerModelSnapDistance() &&
                        DistanceInTilesToLast <= config.PlayerModelSnapDistance())
                {
                    LastLerpPosition = NextLerpPosition;
                    LastLerpPositionWorldPoint = WorldPoint.fromLocal(client, LastLerpPosition);
                }
                // Lerp point does not exist! Teleport or something like that
                else
                {
                    if (NextLerpPoint == null)
                    {
                        NextLerpPoint = RequestedLerpPoint;
                    }
                    LastLerpPosition = NextLerpPoint;
                    LastLerpPositionWorldPoint = WorldPoint.fromLocal(client, LastLerpPosition);
                    LastTrueTilePosition = CurrentTrueTilePosition;

                }
                bSceneRecoveryRetargetPending = false;

                NextLerpPosition = RequestedLerpPoint;

                NextLerpPositionWorldPoint = CurrentWorldPoint;

                if (bReanchorRecovery)
                {
                    SceneRecoveryTweenDurationOverride =
                            GetSceneRecoveryTweenDuration(
                                    LastLerpPosition.distanceTo(
                                            NextLerpPosition),
                                    SceneRecoveryBaseVelocity,
                                    CurrentTweenDuration);
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
                        bWalkStopFacingHoldArmed,
                        bWalkMovementObserved,
                        NextLerpPosition,
                        client.getLocalDestinationLocation());

        // Override all animations
        //if (devConfig.DebugAnimation() != 0)
        //{
        //    CurrentAnimationRequest = AnimationRequestMoveset.GetDefaultIdleMoveAnimationRequest(config);
        //    CurrentAnimationRequest.AnimationToPlay = devConfig.DebugAnimation();
        //}
        //else

        // Currently moving
        if (MillisecondsSinceTileChange < BASE_MOVEMENT_TWEEN_MILLIS ||
                bSceneBoundaryBridgeActive ||
                bKeepMovementAnimationDuringRouteGap)
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

            // [TMA-CAST-MOVEMENT-ORDERING] Owner.getAnimation() is the single
            // authority for an active cast. The old implementation also
            // remembered selected cast/teleport animation IDs and later
            // synthesized HUMAN_CASTTELEPORT_REVERSE (animation 715). If the
            // player clicked to move first, locomotion could begin and that
            // delayed animation would then replay the cast and request a
            // false positional snap. Keep locomotion advancing underneath
            // the real action animation instead. Genuine teleports and other
            // authoritative discontinuities still use the normal movement
            // request path; only the delayed duplicate authority was removed.
            if (config.AllowLeaping() &&
                    bCurrentlyWooxWalking &&
                    config.AllowWooxWalkDetection() &&
                    bIsDefaultHumanAnimationSet)
            {
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
                        Owner.getOrientation(),
                        Model.getOrientation()),
                config.OriginalModelProximityDistanceThreshold(),
                config.OriginalModelProximityOrientationThreshold());
    }

    private long GetMovementTweenDurationMilliseconds()
    {
        if (SceneRecoveryTweenDurationOverride > 0)
        {
            return SceneRecoveryTweenDurationOverride;
        }

        double RequestSpeedMultiplier =
                CurrentAnimationRequest == null
                        ? 1.0
                        : CurrentAnimationRequest.MovementSpeedMultiplier;
        double MovementSpeedMultiplier =
                config.MovementSpeedMultiplier() *
                        RequestSpeedMultiplier;
        MovementSpeedMultiplier =
                Math.max(MovementSpeedMultiplier, 1);
        return Math.max(1L, (long)
                (BASE_MOVEMENT_TWEEN_MILLIS /
                        MovementSpeedMultiplier));
    }

    private boolean ShouldBridgeSceneBoundaryMovement(
            long TweenDurationMilliseconds)
    {
        if (!IsPlayerOwner() ||
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
        }
        else
        {
            TweenValue = quadraticTween(
                    0L,
                    TweenDurationMilliseconds,
                    MillisecondsSinceTileChange);
        }

        NewLocalPointToDraw = new LocalPoint((int) (LastLerpPosition.getX() + (NextLerpPosition.getX() - LastLerpPosition.getX()) * TweenValue),
                (int) (LastLerpPosition.getY() + (NextLerpPosition.getY() - LastLerpPosition.getY()) * TweenValue),
                LastLerpPosition.getWorldView());

        if (MillisecondsSinceTileChange >=
                TweenDurationMilliseconds)
        {
            bSceneRecoveryRetargetPending = false;
            if (SceneRecoveryTweenDurationOverride > 0)
            {
                SceneRecoveryTweenDurationOverride = 0;
                if (ScenePresentationTimeDebtMilliseconds == 0)
                {
                    bScenePresentationClockActive = false;
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
                    Model.setLocation(NewLocalPointToDraw, Owner.getWorldView().getPlane());
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

            // [TMA-IDLE-CATCH-UP] This branch is evaluated before the general
            // animation controller so the catch-up idle pose cannot be
            // overwritten by the hidden actor's faster locomotion clock.
            boolean bUsedCustomAnimation =
                    RenderWalkStopIdleAnimation();
            boolean bControllerAnimationRequested =
                    !bUsedCustomAnimation &&
                            ((UniqueAnimationExceptionList.contains(
                                    Owner.getAnimation()) &&
                                    bMovingThisAction) ||
                                    CurrentAnimationRequest.AnimationToPlay !=
                                            -1);
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
                        (Owner.getPoseAnimation() != CurrentAnimationRequest.PoseAnimationToPlay || bResetCurrentAnimation))
                {
                    Animation CustomAnim = client.loadAnimation(CurrentAnimationRequest.PoseAnimationToPlay);

                    if (CustomAnim != null &&
                            (Owner.getPoseAnimationFrame() >=
                                    CustomAnim.getNumFrames() ||
                                    bResetCurrentAnimation))
                    {
                        Owner.setPoseAnimationFrame(CurrentAnimationRequest.StartingFrame);
                    }

                    if (CustomAnim != null)
                    {
                        Owner.setPoseAnimation(
                                CurrentAnimationRequest.PoseAnimationToPlay);
                        CurrentPoseAnimation = NO_ANIMATION;
                        bResetCurrentAnimation = false;
                    }
                }
                // [TMA-STEADY-PRESENTATION] Owner.getModel() can be
                // momentarily unavailable while RuneLite rebuilds equipment
                // or animation state. Keep the last complete model for that
                // frame instead of assigning null and making the player pop.
                TrySetModel(Owner.getModel());
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

            // The native presentation is still useful while genuinely idle,
            // but never switch authorities at a movement tile boundary.
            if (RenderedModel != null &&
                    ShouldRenderOriginalOwner(
                            bUsedCustomAnimation))
            {
                if (Model.isActive())
                {
                    Model.setActive(false);
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

        UpdateWalkStopFacingHold();

        UpdateAnimationSelection();

        UpdateMovementType();

        ApplyTweening();

        UpdateModelVisibleState();
    }
}
