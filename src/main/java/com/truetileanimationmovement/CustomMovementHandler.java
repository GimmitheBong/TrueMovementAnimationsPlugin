package com.truetileanimationmovement;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.AnimationID;

import javax.inject.Inject;
import java.util.HashSet;
import java.util.Set;

@Slf4j
public class CustomMovementHandler
{
    // General
    private final Client client;
    private final TrueTileMovementPlugin plugin;
    private final TrueTileMovementConfig config;
    TrueMovementOverlay overlay;

    // Time management
    private long CurrentTime;
    public int CurrentFrameDelta;
    private static final int MAX_FRAME_DELTA_MILLISECONDS = 100;
    private long CurrentFrameNanos = 0;
    private long LastFrameNanos = 0;
    private long TileMovementStartNanos = 0;
    private int LastAnimationGameCycle = -1;
    private int MillisecondsSinceTileChange = 0;

    // Runelite object management
    private Actor Owner = null;
    public AnimationController AnimController = null;
    public RuneLiteObject Model = null;

    // Targeting
    public Actor currentTarget = null;
    private int NotInteractingTimer = 0;
    private long LastInteractionNanos = 0;
    private long TargetModelUnavailableSinceNanos = 0;

    // Rendering owner
    public boolean bShouldRenderOwner = true;
    public boolean bAttemptToRenderOwner = true;
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

    // Animation Handling
    private static final int NO_ANIMATION = -1;
    private int CurrentAnimationIDPlaying = NO_ANIMATION;
    private int CurrentAnimationStartingFrame = NO_ANIMATION;
    private int CurrentAnimationEndingFrame = NO_ANIMATION;
    private int CurrentAnimationSpeed = NO_ANIMATION;
    Set<Integer> UniqueAnimationLocationAndOrientationExceptionList = new HashSet<Integer>();
    private long LastTimeUniqueAnimationLocationOrientationWasUsed = 0;
    private int LastOwnerActionAnimation = NO_ANIMATION;
    private boolean bPlayingKillCelebration = false;
    private boolean bOwnerMovementAnimationsSuppressed = false;


    // Original true animations
    private AnimationRequestDetails CurrentAnimationRequest;
    private IdleAnimationSet OldAnimationSet = new IdleAnimationSet();
    public int OldAnimationHeight = 0;
    private boolean bAnimationHeightRefreshPending = false;
    private boolean bIsDefaultHumanAnimationSet = true;

    // Rotation
    private int TargetOrientation = 0;
    private int CurrentOrientation = 0;


    // Player only
    private boolean bLastMovementDestinationPotentiallyDirty = false;
    private boolean bTooFarToSpecialMove = false;
    private boolean bLastTickTooFarToSpecialMove = false;
    private LocalPoint LastMovementDestination;
    public boolean bCurrentlyWooxWalking = false;
    public int FramesSinceIdle = 0;
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

        UniqueAnimationLocationAndOrientationExceptionList.add(AnimationID.HUMAN_DOUBLEPIPESQUEEZE);
        UniqueAnimationLocationAndOrientationExceptionList.add(AnimationID.HUMAN_ROPESWING_LONG);
        UniqueAnimationLocationAndOrientationExceptionList.add(AnimationID.HUMAN_WALK_CRUMBLEDWALL);
        UniqueAnimationLocationAndOrientationExceptionList.add(AnimationID.HUMAN_WALK_STYLE);
        UniqueAnimationLocationAndOrientationExceptionList.add(AnimationID.HUMAN_LOWWALL);
        UniqueAnimationLocationAndOrientationExceptionList.add(AnimationID.HUMAN_REACHFORLADDER);
        UniqueAnimationLocationAndOrientationExceptionList.add(AnimationID.HUMAN_CLIMBING_DOWN);
        UniqueAnimationLocationAndOrientationExceptionList.add(AnimationID.HUMAN_WALK_LOGBALANCE_LOOP);
        UniqueAnimationLocationAndOrientationExceptionList.add(AnimationID.HUMAN_CRAWLING);
        UniqueAnimationLocationAndOrientationExceptionList.add(AnimationID.HUMAN_STEPPINGSTONEJUMP);
        UniqueAnimationLocationAndOrientationExceptionList.add(AnimationID.AGILITY_PYRAMID_LEDGE_ON_RIGHT);
        UniqueAnimationLocationAndOrientationExceptionList.add(AnimationID.AGILITY_PYRAMID_LEDGE_OFF_RIGHT);
        UniqueAnimationLocationAndOrientationExceptionList.add(AnimationID.AGILITY_PYRAMID_GAP_JUMP);
        UniqueAnimationLocationAndOrientationExceptionList.add(AnimationID.AGILITY_PYRAMID_GAP_JUMP_FALL);
        UniqueAnimationLocationAndOrientationExceptionList.add(AnimationID.AGILITYARENA_DIVE_PLAYER);
        UniqueAnimationLocationAndOrientationExceptionList.add(AnimationID.PENG_JUMP_A);
        UniqueAnimationLocationAndOrientationExceptionList.add(AnimationID.PENG_JUMP_B);
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

    static int ShortestAngleDifference(int from, int to)
    {
        return ((to - from + 1024) & 2047) - 1024;
    }

    static int MoveOrientationTowards(int Current, int Target, int MaximumStep)
    {
        if (MaximumStep <= 0)
        {
            return Current & 2047;
        }

        int Difference = ShortestAngleDifference(Current, Target);
        int AppliedStep = Math.max(-MaximumStep, Math.min(MaximumStep, Difference));
        return (Current + AppliedStep) & 2047;
    }

    static int getOrientationBetweenPoints(double point1X, double point1Y, double point2X, double point2Y, int OffsetAngle)
    {
        // Calculate the difference in X and Y coordinates
        double deltaX = point2X - point1X;
        double deltaY = point2Y - point1Y;

        // Calculate the angle in radians
        double angleInRadians = Math.atan2(deltaY, deltaX);

        // Convert to the client's 0-2047 orientation range. Normalize after
        // applying the offset and inversion so offsets above 360 degrees can
        // never produce a negative orientation.
        double angleInDegrees = (360.0 - (Math.toDegrees(angleInRadians) + OffsetAngle)) % 360.0;
        if (angleInDegrees < 0.0)
        {
            angleInDegrees += 360.0;
        }
        return ((int) (angleInDegrees / 360.0 * 2048.0)) & 2047;
    }

    private boolean IsPlayerOwner()
    {
        return (Owner instanceof Player);
    }

    boolean IsOwner(Actor actor)
    {
        return Owner == actor;
    }

    boolean HasRenderableModel()
    {
        return Model != null && Model.isActive() && Model.getModel() != null;
    }

    public void Initialize(boolean bRuneliteObjectsStale)
    {
        if (AnimController == null)
        {
            AnimController = new AnimationController(client, NO_ANIMATION);
            AnimController.setOnFinished((AnimationController InController) ->
            {
                if (bPlayingKillCelebration)
                {
                    bTargetWasKilled = false;
                    bPlayingKillCelebration = false;
                }
                InController.loop();
            });
        }

        if (Model == null || bRuneliteObjectsStale)
        {
            RuneLiteObject OldModel = Model;
            Model = client.createRuneLiteObject();

            if (OldModel != null)
            {
                Model.setLocation(OldModel.getLocation(), OldModel.getLevel());
                Model.setOrientation(CurrentOrientation);
                Model.setAnimationController(OldModel.getAnimationController());
                OldModel.setActive(false);
                OldModel.setModel(null);
            }
            else
            {
                LocalPoint OwnerLocation = Owner.getLocalLocation();
                if (OwnerLocation != null)
                {
                    CurrentOrientation = Owner.getCurrentOrientation();
                    TargetOrientation = CurrentOrientation;
                    Model.setLocation(OwnerLocation, Owner.getWorldView().getPlane());
                    Model.setOrientation(CurrentOrientation);
                }
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

                if (cameraModel == null || bRuneliteObjectsStale)
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
                        OldModel.setActive(false);
                        OldModel.setModel(null);
                    }
                }
            }
        }
    }

    public void Cleanup()
    {
        bShouldRenderOwner = true;
        bAttemptToRenderOwner = true;
        try
        {
            RestoreOwnerAnimations();
        }
        catch (RuntimeException ex)
        {
            log.debug("Unable to restore actor animations during True Tile cleanup", ex);
        }

        RuneLiteObject OldModel = Model;
        Model = null;
        CleanupRuneLiteObject(OldModel, "movement");

        RuneLiteObject OldCameraModel = cameraModel;
        cameraModel = null;
        CleanupRuneLiteObject(OldCameraModel, "camera");

        AnimController = null;
        cameraModelAnimController = null;
        LastFrameNanos = 0;
        CurrentFrameNanos = 0;
        TileMovementStartNanos = 0;
        LastAnimationGameCycle = -1;
        InvalidateCustomAnimation();
        LastOwnerActionAnimation = NO_ANIMATION;
        bPlayingKillCelebration = false;
        bAnimationHeightRefreshPending = false;
        LastInteractionNanos = 0;
        TargetModelUnavailableSinceNanos = 0;
        NotInteractingTimer = 0;
        bOwnerMovementAnimationsSuppressed = false;
    }

    private void CleanupRuneLiteObject(RuneLiteObject Object, String Description)
    {
        if (Object == null)
        {
            return;
        }

        try
        {
            Object.setActive(false);
        }
        catch (RuntimeException ex)
        {
            log.debug("Unable to deactivate True Tile {} object", Description, ex);
        }
        try
        {
            Object.setModel(null);
            Object.setAnimationController(null);
        }
        catch (RuntimeException ex)
        {
            log.debug("Unable to clear True Tile {} object", Description, ex);
        }
    }

    private void RestoreOwnerAnimations()
    {
        if (Owner == null)
        {
            return;
        }

        if (!bOwnerMovementAnimationsSuppressed)
        {
            return;
        }

        Owner.setIdleRotateLeft(OldAnimationSet.IdleRotateLeft);
        Owner.setIdleRotateRight(OldAnimationSet.IdleRotateRight);
        Owner.setWalkAnimation(OldAnimationSet.WalkAnimation);
        Owner.setWalkRotateLeft(OldAnimationSet.WalkRotateLeft);
        Owner.setWalkRotateRight(OldAnimationSet.WalkRotateRight);
        Owner.setWalkRotate180(OldAnimationSet.WalkRotate180);
        Owner.setIdlePoseAnimation(OldAnimationSet.IdlePoseAnimation);
        Owner.setPoseAnimation(OldAnimationSet.PoseAnimation);
        Owner.setRunAnimation(OldAnimationSet.RunAnimation);
        bOwnerMovementAnimationsSuppressed = false;
    }

    private void UpdateOldIdleAnimations()
    {
        boolean bAnyChanges = false;
        if ((!bOwnerMovementAnimationsSuppressed || Owner.getIdleRotateLeft() != NO_ANIMATION) &&
                OldAnimationSet.IdleRotateLeft != Owner.getIdleRotateLeft())
        {
            OldAnimationSet.IdleRotateLeft = Owner.getIdleRotateLeft();
            bAnyChanges = true;
        }

        if ((!bOwnerMovementAnimationsSuppressed || Owner.getIdleRotateRight() != NO_ANIMATION) &&
                OldAnimationSet.IdleRotateRight != Owner.getIdleRotateRight())
        {
            OldAnimationSet.IdleRotateRight = Owner.getIdleRotateRight();
            bAnyChanges = true;
        }

        if ((!bOwnerMovementAnimationsSuppressed || Owner.getWalkAnimation() != NO_ANIMATION) &&
                OldAnimationSet.WalkAnimation != Owner.getWalkAnimation())
        {
            OldAnimationSet.WalkAnimation = Owner.getWalkAnimation();
            bAnyChanges = true;
        }

        if ((!bOwnerMovementAnimationsSuppressed || Owner.getWalkRotateLeft() != NO_ANIMATION) &&
                OldAnimationSet.WalkRotateLeft != Owner.getWalkRotateLeft())
        {
            OldAnimationSet.WalkRotateLeft = Owner.getWalkRotateLeft();
            bAnyChanges = true;
        }

        if ((!bOwnerMovementAnimationsSuppressed || Owner.getWalkRotateRight() != NO_ANIMATION) &&
                OldAnimationSet.WalkRotateRight != Owner.getWalkRotateRight())
        {
            OldAnimationSet.WalkRotateRight = Owner.getWalkRotateRight();
            bAnyChanges = true;
        }

        if ((!bOwnerMovementAnimationsSuppressed || Owner.getWalkRotate180() != NO_ANIMATION) &&
                OldAnimationSet.WalkRotate180 != Owner.getWalkRotate180())
        {
            OldAnimationSet.WalkRotate180 = Owner.getWalkRotate180();
            bAnyChanges = true;
        }

        if ((!bOwnerMovementAnimationsSuppressed || Owner.getIdlePoseAnimation() != NO_ANIMATION) &&
                OldAnimationSet.IdlePoseAnimation != Owner.getIdlePoseAnimation())
        {
            OldAnimationSet.IdlePoseAnimation = Owner.getIdlePoseAnimation();
            bAnyChanges = true;
        }

        if ((!bOwnerMovementAnimationsSuppressed || Owner.getPoseAnimation() != NO_ANIMATION) &&
                OldAnimationSet.PoseAnimation != Owner.getPoseAnimation())
        {
            OldAnimationSet.PoseAnimation = Owner.getPoseAnimation();
            bAnyChanges = true;
        }

        if ((!bOwnerMovementAnimationsSuppressed || Owner.getRunAnimation() != NO_ANIMATION) &&
                OldAnimationSet.RunAnimation != Owner.getRunAnimation())
        {
            OldAnimationSet.RunAnimation = Owner.getRunAnimation();
            bAnyChanges = true;
        }

        if (bAnyChanges)
        {
            OldAnimationSet.CacheUniqueLabel();

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

        // Never cache a transient attack/cast/flinch height as the locomotion
        // baseline. Equipment changes are common during combat, and the old
        // value otherwise survives after the action animation ends.
        if (bAnyChanges && Owner.getAnimation() != NO_ANIMATION)
        {
            bAnimationHeightRefreshPending = true;
        }
        if (Owner.getAnimation() == NO_ANIMATION &&
                (bAnyChanges || bShouldRenderOwner || bAnimationHeightRefreshPending))
        {
            OldAnimationHeight = Owner.getAnimationHeightOffset();
            bAnimationHeightRefreshPending = false;
        }
    }
    private void UpdateFrameTimer()
    {
        CurrentFrameNanos = System.nanoTime();
        CurrentTime = CurrentFrameNanos / 1_000_000L;
        CurrentFrameDelta = CalculateFrameDeltaMilliseconds(LastFrameNanos, CurrentFrameNanos);
        LastFrameNanos = CurrentFrameNanos;

        if (TileMovementStartNanos == 0)
        {
            MillisecondsSinceTileChange = 600;
        }
        else
        {
            long ElapsedNanos = Math.max(0, CurrentFrameNanos - TileMovementStartNanos);
            MillisecondsSinceTileChange = (int) Math.min(
                    Integer.MAX_VALUE,
                    ElapsedNanos / 1_000_000L);
        }
    }

    static int CalculateFrameDeltaMilliseconds(long PreviousFrameNanos, long CurrentFrameNanos)
    {
        if (PreviousFrameNanos == 0 || CurrentFrameNanos <= PreviousFrameNanos)
        {
            return 0;
        }

        long ElapsedMilliseconds = (CurrentFrameNanos - PreviousFrameNanos) / 1_000_000L;
        return (int) Math.min(MAX_FRAME_DELTA_MILLISECONDS, ElapsedMilliseconds);
    }

    private void ResetTileMovementTimer()
    {
        TileMovementStartNanos = CurrentFrameNanos;
        MillisecondsSinceTileChange = 0;
    }

    private boolean UpdateTrueTileLocation()
    {
        CurrentWorldPoint = Owner.getWorldLocation();

        LocalPoint LocalCurrentTrueTilePosition = LocalPoint.fromWorld(client, CurrentWorldPoint);
        if (LocalCurrentTrueTilePosition == null)
        {
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

    private void UpdateTargetStatus()
    {
        Actor InteractingActor = Owner.getInteracting();
        if (InteractingActor instanceof NPC || InteractingActor instanceof Player)
        {
            // Temporary action changes can clear interaction for a few frames.
            // A confirmed interaction always resets the grace timer, including
            // when it resumes against the same target.
            NotInteractingTimer = 0;
            LastInteractionNanos = CurrentFrameNanos;
            currentTarget = InteractingActor;
            bTargetWasKilled = false;
            TargetModelUnavailableSinceNanos = 0;
        }
        else if (currentTarget != null && LastInteractionNanos != 0)
        {
            // Start the disengage grace period only after both actors have
            // actually stopped interacting and the owner's current action has
            // ended. Eating, casting, and flinching can temporarily clear the
            // owner's interaction without ending combat.
            if (currentTarget.getInteracting() == Owner || Owner.getAnimation() != NO_ANIMATION)
            {
                LastInteractionNanos = CurrentFrameNanos;
                NotInteractingTimer = 0;
            }
            else
            {
                long ElapsedNanos = Math.max(0, CurrentFrameNanos - LastInteractionNanos);
                NotInteractingTimer = (int) Math.min(
                        Integer.MAX_VALUE,
                        ElapsedNanos / 1_000_000L);
            }
        }

        if (currentTarget == null)
        {
            return;
        }

        LocalPoint TargetLocalLocation = currentTarget.getLocalLocation();
        if (TargetLocalLocation == null || currentTarget.getWorldView() != Owner.getWorldView())
        {
            ClearCurrentTarget(false);
            return;
        }

        int TileDistanceFromTarget = currentTarget.getWorldLocation().distanceTo(Owner.getWorldLocation());
        boolean NeitherActorIsInteracting = currentTarget.getInteracting() != Owner &&
                Owner.getInteracting() != currentTarget;

        if (NeitherActorIsInteracting && currentTarget.getModel() == null)
        {
            if (TargetModelUnavailableSinceNanos == 0)
            {
                TargetModelUnavailableSinceNanos = CurrentFrameNanos;
            }
        }
        else
        {
            TargetModelUnavailableSinceNanos = 0;
        }

        boolean TargetModelUnavailableTooLong = TargetModelUnavailableSinceNanos != 0 &&
                CurrentFrameNanos - TargetModelUnavailableSinceNanos >= 600_000_000L;
        int DisengageTime = TileDistanceFromTarget > 3
                ? config.StopEngagingInCombatTime()
                : config.StopEngagingInCombatTimeFromCloseDistance();

        if (!config.CombatModeEnabled())
        {
            DisengageTime = Math.min(DisengageTime, 1200);
        }
        if (currentTarget.isDead() ||
                TileDistanceFromTarget > 10 ||
                TargetModelUnavailableTooLong ||
                (NeitherActorIsInteracting && NotInteractingTimer > DisengageTime))
        {
            ClearCurrentTarget(currentTarget.isDead());
        }
    }

    private void ClearCurrentTarget(boolean TargetWasKilled)
    {
        if (currentTarget != null)
        {
            LastNPCCombatLevel = currentTarget.getCombatLevel();
        }
        bTargetWasKilled = TargetWasKilled;
        currentTarget = null;
        LastInteractionNanos = 0;
        TargetModelUnavailableSinceNanos = 0;
        NotInteractingTimer = 0;

        if (bTargetWasKilled)
        {
            LastTimeEnemyKilled = CurrentTime;
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

                ResetTileMovementTimer();
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
                if (NextLerpPoint != null &&
                        !((Math.abs(NextLerpPoint.getX() - LastLerpPosition.getX()) <= 1024) &&
                                (Math.abs(NextLerpPoint.getY() - LastLerpPosition.getY()) <= 1024)))
                {
                    LastLerpPosition = NextLerpPoint;
                    LastLerpPositionWorldPoint = WorldPoint.fromLocal(client, LastLerpPosition);
                }

                else if (NextLerpPoint != null &&
                        (Math.abs(NextLerpPoint.getX() - RequestedLerpPoint.getX()) <= 1024) &&
                        (Math.abs(NextLerpPoint.getY() - RequestedLerpPoint.getY()) <= 1024))
                {
                    // Rebase from the position that was actually shown last frame.
                    // Server ticks are not guaranteed to arrive exactly 600 ms
                    // after the prior one, so rebasing from the old destination
                    // creates a visible snap at each early/late retarget.
                    LastLerpPosition = SelectTweenStart(NewLocalPointToDraw, NextLerpPosition);
                    LastLerpPositionWorldPoint = WorldPoint.fromLocal(client, LastLerpPosition);
                }
                // Lerp point does not exist! Teleport or something like that
                else if (IsPlayerOwner())
                {
                    LastLerpPosition = RequestedLerpPoint;
                    LastLerpPositionWorldPoint = WorldPoint.fromLocal(client, LastLerpPosition);
                    LastTrueTilePosition = CurrentTrueTilePosition;

                    // Teleport fallback (Not covered by animation in plugin)
                    if (IsPlayerOwner() && CurrentTime - overlay.LastTimeTeleport >= 1800)
                    {
                        overlay.LastTimeTeleport = System.nanoTime() / 1_000_000L - 600; // (We are at this location already, offset expected 1 tick animation time)
                        overlay.bShouldPlayTeleportAnimation = false; // Fallback, do not play animation
                    }
                }

                NextLerpPosition = RequestedLerpPoint;

                NextLerpPositionWorldPoint = CurrentWorldPoint;

                ResetTileMovementTimer();
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

    static LocalPoint SelectTweenStart(LocalPoint LastRenderedPosition, LocalPoint PreviousDestination)
    {
        if (LastRenderedPosition == null ||
                PreviousDestination == null ||
                LastRenderedPosition.getWorldView() != PreviousDestination.getWorldView() ||
                Math.abs(LastRenderedPosition.getX() - PreviousDestination.getX()) > 1024 ||
                Math.abs(LastRenderedPosition.getY() - PreviousDestination.getY()) > 1024)
        {
            return PreviousDestination;
        }

        return LastRenderedPosition;
    }
    private boolean bShouldUseTrueLocationOrientation = false;
    private void UpdateAnimationSelection()
    {
        bShouldUseTrueLocationOrientation = false;
        bPlayingKillCelebration = false;

        // Quick and dirty teleport to location
        boolean bApplyQuickAndDirtyTeleport = LastLerpPosition.equals(NextLerpPosition);


        // Override all animations
        //if (devConfig.DebugAnimation() != 0)
        //{
        //    CurrentAnimationRequest = AnimationRequestMoveset.GetDefaultIdleMoveAnimationRequest(config);
        //    CurrentAnimationRequest.AnimationToPlay = devConfig.DebugAnimation();
        //}
        //else

        // Currently moving
        if (MillisecondsSinceTileChange < 600 ) // 1 tick
        {

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

            // Just teleported
            if (IsPlayerOwner() &&
                    overlay.LastTimeTeleport != 0 &&
                    CurrentTime - overlay.LastTimeTeleport < 1800)
            {
                if (overlay.bShouldPlayTeleportAnimation && bIsDefaultHumanAnimationSet)
                {
                    if (CurrentTime - overlay.LastTimeTeleport < 600) // Blend with the first tick
                    {
                        // Handle normal walking
                        CurrentAnimationRequest = AnimationRequestDetails.NewObject(AnimationRequestMovesetCache.GetAnimationRequestMovesetFromAnimationSet(OldAnimationSet, config).MovesetArray[2 + RotatedDirectionX][2 + RotatedDirectionY]);
                        CurrentAnimationRequest.bShouldTeleportToLocation = false;
                    }
                    else
                    {
                        CurrentAnimationRequest.bShouldTeleportToLocation = true;
                        CurrentAnimationRequest.AnimationToPlay = AnimationID.HUMAN_CASTTELEPORT_REVERSE;

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
                    LocalPoint PreviousTrueTile = LastTrueTilePosition == null
                            ? CurrentTrueTilePosition
                            : LastTrueTilePosition;
                    int DirectionX = Owner.getLocalLocation().getX() - PreviousTrueTile.getX();
                    int DirectionY = Owner.getLocalLocation().getY() - PreviousTrueTile.getY();

                    if (Owner.getLocalLocation().getX() == CurrentTrueTilePosition.getX() &&
                            Owner.getLocalLocation().getY() == CurrentTrueTilePosition.getY() )
                    {
                        CurrentAnimationRequest.AnimationToPlay = OldAnimationSet.IdlePoseAnimation;
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
            else if (bCurrentlyWooxWalking && config.AllowWooxWalkDetection() && bIsDefaultHumanAnimationSet)
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
            else if ((config.AlwaysHoppingMode() || FramesSinceIdle > config.TickPerfectMovesUntilJumping()) && bIsDefaultHumanAnimationSet)
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
            bPlayingKillCelebration = true;
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
            CurrentAnimationRequest = AnimationRequestMoveset.GetDefaultIdleMoveAnimationRequest(config);
            CurrentAnimationRequest.bUseLinearTween = true;
            CurrentAnimationRequest.MovementSpeedMultiplier = 1.0;
            CurrentAnimationRequest.AnimationSpeed = 1;
            CurrentAnimationRequest.StartingFrame = 0;
            ChangeLastLerpPointForRotation();
            int ShortestAngle = ShortestAngleDifference(CurrentOrientation, TargetOrientation);
            if (ShortestAngle >= 10)
            {;
                CurrentAnimationRequest.AnimationToPlay = OldAnimationSet.IdleRotateRight;
            }
            else if (ShortestAngle <= -10)
            {
                CurrentAnimationRequest.AnimationToPlay = OldAnimationSet.IdleRotateLeft;
            }
            else
            {;
                CurrentAnimationRequest.AnimationToPlay = OldAnimationSet.IdlePoseAnimation;
            }

            bWooxWalkBroken = true;
            FramesSinceIdle = 0;

            // We can transition to render the owner
            if (bAttemptToRenderOwner)
            {
                bShouldRenderOwner = true;
            }
        }

        if (bApplyQuickAndDirtyTeleport)
        {
            CurrentAnimationRequest.bShouldTeleportToLocation = true;
            CurrentAnimationRequest.OrientationSpeed = 10000;
        }

        if (CurrentAnimationRequest.bResetAnimationOnNewTile && bNewTileMovementStarted)
        {
            InvalidateCustomAnimation();
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
                    LocalPoint CurrentDestination = client.getLocalDestinationLocation();
                    if (!CurrentDestination.equals(LastMovementDestination)) {
                        LastMovementDestination = CurrentDestination;

                        // Next position isnt the next lerp position, this means it'll take 2+ moves to actually get there because of an obstacle
                        if (!LastMovementDestination.equals(NextLerpPosition))
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

    private void ApplyTweening()
    {
        // 600ms a tick, interpolate between true local point and last true tile position
        double TweenValue = 0;
        if (CurrentAnimationRequest.bShouldTeleportToLocation)
        {
            TweenValue = 1.0;
        }
        else if (CurrentAnimationRequest.bUseLinearTween)
        {
            TweenValue = linearTween(0L, (long) (600 / CurrentAnimationRequest.MovementSpeedMultiplier), MillisecondsSinceTileChange);
        }
        else
        {
            TweenValue = quadraticTween(0L, (long) (600 / CurrentAnimationRequest.MovementSpeedMultiplier), MillisecondsSinceTileChange);
        }

        NewLocalPointToDraw = new LocalPoint((int) (LastLerpPosition.getX() + (NextLerpPosition.getX() - LastLerpPosition.getX()) * TweenValue),
                (int) (LastLerpPosition.getY() + (NextLerpPosition.getY() - LastLerpPosition.getY()) * TweenValue),
                LastLerpPosition.getWorldView());

        if (currentTarget != null)
        {
            TargetOrientation = (getOrientationBetweenPoints(NewLocalPointToDraw.getX(), NewLocalPointToDraw.getY(), currentTarget.getLocalLocation().getX(), currentTarget.getLocalLocation().getY(), 90));
        }
        else if (Owner.getAnimation() != -1 || // Not walking animation, face towards wherever the client is
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
    }

    private void UpdateCamera()
    {
        if (IsPlayerOwner())
        {
            if (config.SpawnModelAtCameraTile())
            {
                // Find best direction to go, offset by 10000 for comparison to avoid negatives
                int CameraTargetOrientation = (getOrientationBetweenPoints(Owner.getLocalLocation().getX(), Owner.getLocalLocation().getY(),
                        NewLocalPointToDraw.getX(), NewLocalPointToDraw.getY(), 270));

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
                    cameraModel.setModel(null);
                }
                else
                {
                    cameraModel.setModel(client.mergeModels(/*cameraModelAnimController.animate*/(client.loadModel(CurrentCameraModelIndex))));
                }

                CurrentCameraObjectOrientation = MoveOrientationTowards(
                        CurrentCameraObjectOrientation,
                        CameraTargetOrientation,
                        config.CameraObjectOrientationRotationSpeed());

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
                cameraModel.setModel(null);
                cameraModel.setActive(false);
            }
        }
    }

    static boolean ShouldUseNativeActionModel(int Animation)
    {
        return Animation != NO_ANIMATION;
    }

    static int CalculateElapsedClientCycles(int PreviousGameCycle, int CurrentGameCycle)
    {
        if (PreviousGameCycle < 0 || CurrentGameCycle <= PreviousGameCycle)
        {
            return 0;
        }

        // Avoid pathological catch-up work after a long pause or world change.
        return Math.min(100, CurrentGameCycle - PreviousGameCycle);
    }

    private int ConsumeElapsedClientCycles()
    {
        int CurrentGameCycle = client.getGameCycle();
        int ElapsedCycles = CalculateElapsedClientCycles(LastAnimationGameCycle, CurrentGameCycle);
        LastAnimationGameCycle = CurrentGameCycle;
        return ElapsedCycles;
    }

    private void HideOwnerMovementAnimations()
    {
        Owner.setIdleRotateLeft(NO_ANIMATION);
        Owner.setIdleRotateRight(NO_ANIMATION);
        Owner.setWalkAnimation(NO_ANIMATION);
        Owner.setWalkRotateLeft(NO_ANIMATION);
        Owner.setWalkRotateRight(NO_ANIMATION);
        Owner.setWalkRotate180(NO_ANIMATION);
        Owner.setIdlePoseAnimation(NO_ANIMATION);
        Owner.setRunAnimation(NO_ANIMATION);
        Owner.setPoseAnimation(NO_ANIMATION);
        bOwnerMovementAnimationsSuppressed = true;
    }

    private void HideCustomModels()
    {
        if (Model != null)
        {
            Model.setModel(null);
            Model.setActive(false);
        }
        if (cameraModel != null)
        {
            cameraModel.setModel(null);
            cameraModel.setActive(false);
        }
    }

    private boolean FailOpenToOwner()
    {
        bShouldRenderOwner = true;
        bAttemptToRenderOwner = true;
        RestoreOwnerAnimations();
        HideCustomModels();
        return false;
    }

    private void AdvanceCustomAnimation(int ElapsedClientCycles)
    {
        if (CurrentAnimationIDPlaying != CurrentAnimationRequest.AnimationToPlay ||
                CurrentAnimationStartingFrame != CurrentAnimationRequest.StartingFrame ||
                CurrentAnimationEndingFrame != CurrentAnimationRequest.EndingFrame ||
                CurrentAnimationSpeed != CurrentAnimationRequest.AnimationSpeed)
        {
            CurrentAnimationIDPlaying = CurrentAnimationRequest.AnimationToPlay;
            CurrentAnimationStartingFrame = CurrentAnimationRequest.StartingFrame;
            CurrentAnimationEndingFrame = CurrentAnimationRequest.EndingFrame;
            CurrentAnimationSpeed = CurrentAnimationRequest.AnimationSpeed;
            AnimController.setAnimation(client.loadAnimation(CurrentAnimationIDPlaying));
            AnimController.setFrame(CurrentAnimationStartingFrame);
            return;
        }

        if (AnimController.getAnimation() == null || ElapsedClientCycles <= 0)
        {
            return;
        }

        int CurrentFrame = AnimController.getFrame();
        if (CurrentFrame < CurrentAnimationStartingFrame)
        {
            AnimController.setFrame(CurrentAnimationStartingFrame);
            CurrentFrame = CurrentAnimationStartingFrame;
        }
        if (CurrentFrame >= CurrentAnimationEndingFrame)
        {
            AnimController.setFrame(CurrentAnimationEndingFrame);
            return;
        }

        AnimController.tick(ElapsedClientCycles * CurrentAnimationSpeed);
        if (AnimController.getAnimation() != null)
        {
            if (AnimController.getFrame() < CurrentAnimationStartingFrame)
            {
                AnimController.setFrame(CurrentAnimationStartingFrame);
            }
            else if (AnimController.getFrame() > CurrentAnimationEndingFrame)
            {
                AnimController.setFrame(CurrentAnimationEndingFrame);
            }
        }
    }

    private void InvalidateCustomAnimation()
    {
        CurrentAnimationIDPlaying = NO_ANIMATION;
        CurrentAnimationStartingFrame = NO_ANIMATION;
        CurrentAnimationEndingFrame = NO_ANIMATION;
        CurrentAnimationSpeed = NO_ANIMATION;
    }

    private boolean UpdateModelVisibleState()
    {
        int ElapsedClientCycles = ConsumeElapsedClientCycles();

        if (!bAttemptToRenderOwner)
        {
            bShouldRenderOwner = false;
        }

        if (bShouldRenderOwner)
        {
            RestoreOwnerAnimations();
            HideCustomModels();
            InvalidateCustomAnimation();
            LastOwnerActionAnimation = Owner.getAnimation();
            return true;
        }

        int OwnerAnimation = Owner.getAnimation();
        boolean UseNativeActionModel = ShouldUseNativeActionModel(OwnerAnimation);

        if (UseNativeActionModel)
        {
            // Capture the action with its native pose/movement layer intact.
            // The real actor is suppressed at draw time, so restoring these
            // fields cannot expose a duplicate model.
            RestoreOwnerAnimations();
        }
        else if (!bAttemptToRenderOwner)
        {
            HideOwnerMovementAnimations();
        }

        if (LastOwnerActionAnimation != NO_ANIMATION && OwnerAnimation == NO_ANIMATION)
        {
            // Re-enter locomotion from its configured starting frame instead of
            // exposing a stale custom frame that advanced behind the action.
            InvalidateCustomAnimation();
        }
        LastOwnerActionAnimation = OwnerAnimation;

        bShouldUseTrueLocationOrientation |= (OwnerAnimation != NO_ANIMATION &&
                currentTarget == null &&
                UniqueAnimationLocationAndOrientationExceptionList.contains(OwnerAnimation));

        LocalPoint RenderLocation;
        int RenderOrientation;
        if (bShouldUseTrueLocationOrientation ||
                (CurrentTime - LastTimeUniqueAnimationLocationOrientationWasUsed) < 600)
        {
            RenderLocation = Owner.getLocalLocation();
            RenderOrientation = Owner.getCurrentOrientation();
            CurrentOrientation = RenderOrientation;

            if (bShouldUseTrueLocationOrientation)
            {
                LastTimeUniqueAnimationLocationOrientationWasUsed = CurrentTime;
            }
        }
        else
        {
            RenderLocation = NewLocalPointToDraw;
            if (UseNativeActionModel)
            {
                RenderOrientation = Owner.getCurrentOrientation();
                CurrentOrientation = RenderOrientation;
            }
            else
            {
                int OrientationStep = (int) Math.round(
                        CurrentAnimationRequest.OrientationSpeed *
                                (CurrentFrameDelta / 16.667));
                CurrentOrientation = MoveOrientationTowards(
                        CurrentOrientation,
                        TargetOrientation,
                        OrientationStep);
                RenderOrientation = CurrentOrientation;
            }
        }

        if (RenderLocation == null || Model == null || AnimController == null)
        {
            return FailOpenToOwner();
        }

        Model OwnerModel = Owner.getModel();
        if (OwnerModel == null)
        {
            return FailOpenToOwner();
        }

        Model RenderedModel;
        if (UseNativeActionModel)
        {
            // OwnerModel already contains the authoritative attack/cast/flinch/eat
            // pose. Applying locomotion again double-transforms it and is the main
            // source of combat flicker.
            RenderedModel = client.mergeModels(OwnerModel);
        }
        else
        {
            AdvanceCustomAnimation(ElapsedClientCycles);
            Model AnimatedModel = AnimController.getAnimation() == null
                    ? OwnerModel
                    : AnimController.animate(OwnerModel);
            RenderedModel = client.mergeModels(AnimatedModel);
        }

        if (RenderedModel == null)
        {
            return FailOpenToOwner();
        }

        RenderedModel.setModelHeight(OwnerModel.getModelHeight());
        RenderedModel.setUvBufferOffset(OwnerModel.getUvBufferOffset());
        RenderedModel.setBufferOffset(OwnerModel.getBufferOffset());
        RenderedModel.setSceneId(OwnerModel.getSceneId());

        Model.setLocation(RenderLocation, Owner.getWorldView().getPlane());
        Model.setOrientation(RenderOrientation);
        Model.setModel(RenderedModel);

        int FootprintHeight = Perspective.getFootprintTileHeight(
                client,
                RenderLocation,
                Owner.getWorldView().getPlane(),
                Owner.getFootprintSize());
        FootprintHeight -= UseNativeActionModel
                ? Owner.getAnimationHeightOffset()
                : OldAnimationHeight;
        Model.setZ(FootprintHeight);

        if (!Model.isActive())
        {
            Model.setActive(true);
        }

        bShouldRenderOwner = false;
        UpdateCamera();
        return true;
    }

    public boolean Update()
    {
        if (Owner == null || Model == null)
        {
            return FailOpenToOwner();
        }

        UpdateFrameTimer();
        UpdateOldIdleAnimations();
        UpdateTargetStatus();

        if (!UpdateTrueTileLocation())
        {
            return FailOpenToOwner();
        }

        UpdateLerpDestinations();
        if (LastLerpPosition == null || NextLerpPosition == null)
        {
            return FailOpenToOwner();
        }

        UpdateAnimationSelection();
        UpdateMovementType();
        ApplyTweening();
        return UpdateModelVisibleState();
    }
}
