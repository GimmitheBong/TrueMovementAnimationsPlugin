package com.truetileanimationmovement;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.AnimationID;

import javax.inject.Inject;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.IntPredicate;

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
    private static final int MOVEMENT_SELECTION_GRACE_MILLISECONDS = 100;
    private static final int LAST_GOOD_RENDER_HOLD_CYCLES = 5;
    private static final int MIN_NEUTRAL_APPEARANCE_STABLE_CYCLES = 5;
    private static final int MAX_CACHED_ANIMATION_STATES = 128;
    private static final int MAX_TOTAL_CACHED_ANIMATION_STATES = 512;
    private static final int MAX_NEUTRAL_CAPTURE_STATES_PER_WINDOW = 32;
    private static final int NATIVE_MOTION_SETTLE_CYCLES = 30;
    private static final int MAX_NORMAL_LOCAL_UNITS_PER_CLIENT_CYCLE = 16;
    private static final int MAX_NORMAL_DESTINATION_DELTA = Perspective.LOCAL_TILE_SIZE * 2;
    private static final int SPATIAL_DISCONTINUITY_DELTA = Perspective.LOCAL_TILE_SIZE * 8;
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
    private WorldPoint LastRenderedWorldPoint;
    private int LastRenderedWorldOffsetX = 0;
    private int LastRenderedWorldOffsetY = 0;

    // Animation Handling
    private static final int NO_ANIMATION = -1;
    private int CurrentAnimationIDPlaying = NO_ANIMATION;
    private int CurrentAnimationStartingFrame = NO_ANIMATION;
    private int CurrentAnimationEndingFrame = NO_ANIMATION;
    private int CurrentAnimationSpeed = NO_ANIMATION;
    private int AnimationLoadFailureStartGameCycle = -1;
    private int CurrentAnimationElapsedTicks = 0;
    Set<Integer> UniqueAnimationLocationAndOrientationExceptionList = new HashSet<Integer>();
    private long LastTimeUniqueAnimationLocationOrientationWasUsed = 0;
    private boolean bPlayingKillCelebration = false;
    private int LastSuccessfulRenderGameCycle = -1;
    private PlayerAppearanceKey LastRenderedAppearance;
    private int LastRenderedAnimationHeightOffset = 0;
    private Model StableStationaryModel;
    private PlayerAppearanceKey StableStationaryAppearance;
    private int StableStationaryAnimationId = NO_ANIMATION;
    private int StableStationaryAnimationHeightOffset = 0;
    private boolean bLastRenderedFrameSafeForStationaryHold = false;
    private boolean bHoldingStableStationaryFrame = false;
    private boolean bHoldContinuingRouteBoundaryThisFrame = false;
    private boolean bHoldingContinuingRouteBoundaryFrame = false;
    private boolean bCurrentSegmentCompletedEarly = false;

    // Custom locomotion must start from an actually neutral player-composition
    // model. The client keeps hidden pose/transition controllers which are only
    // refreshed during a client tick, so clearing public fields inside
    // BeforeRender cannot produce that model reliably. A bounded GameTick ->
    // ClientTick capture refreshes this owned cache only when appearance
    // data changes; ordinary render frames never mutate the actor. A generic
    // merged-model copy drops the skin-group tables required for later skeletal
    // transforms, so the bounded window stores immutable posed vertex/alpha
    // snapshots plus one detached topology template per animation. It never
    // retains an unsafe shared base model.
    private final LinkedHashMap<Integer, CachedAnimationFrames> NeutralAnimationFrameCache =
            new LinkedHashMap<>(16, 0.75f, true);
    private final LinkedHashMap<Integer, Boolean> RequestedNeutralAnimations =
            new LinkedHashMap<>();
    private final Set<Long> UncacheableNeutralAnimationVariants = new HashSet<>();
    private PlayerAppearanceKey NeutralOwnerModelAppearance;
    private PlayerAppearanceKey RequestedNeutralOwnerAppearance;
    private OwnerMovementAnimationState PendingNeutralCaptureState;
    private Actor PendingNeutralCaptureOwner;
    private int PendingNeutralCaptureWorldViewId = -1;
    private int PendingNeutralCaptureGameCycle = -1;
    private LocalPoint PendingNeutralCaptureLocalLocation;
    private int PendingNeutralCaptureAnimationId = NO_ANIMATION;
    private boolean PendingNeutralCaptureInterpolated = false;
    private int NeutralCaptureRetryAfterGameCycle = -1;
    private int NeutralCaptureRequestGameCycle = -1;
    private int NeutralCaptureHoldGameCycle = -1;
    private boolean bNeutralCaptureRequested = false;
    private boolean bCustomLocomotionModelLastFrame = false;
    private int NeutralAnimationFrameStateCount = 0;
    private CachedAnimationFramesBuilder PartialNeutralCaptureBuilder;
    private PlayerAppearanceKey PartialNeutralCaptureAppearance;
    private int PartialNeutralCaptureAnimationId = NO_ANIMATION;
    private boolean PartialNeutralCaptureInterpolated = false;
    private int[] PartialNeutralCaptureFrameKeys;
    private int PartialNeutralCaptureFrameIndex = 0;
    private int PartialNeutralCaptureFailureCount = 0;

    private static final class CachedModelPose
    {
        private final float[] VerticesX;
        private final float[] VerticesY;
        private final float[] VerticesZ;
        private final byte[] FaceTransparencies;
        private final int VerticesCount;
        private final int FaceCount;
        private final int ModelHeight;

        private CachedModelPose(
                float[] VerticesX,
                float[] VerticesY,
                float[] VerticesZ,
                byte[] FaceTransparencies,
                int VerticesCount,
                int FaceCount,
                int ModelHeight)
        {
            this.VerticesX = VerticesX;
            this.VerticesY = VerticesY;
            this.VerticesZ = VerticesZ;
            this.FaceTransparencies = FaceTransparencies;
            this.VerticesCount = VerticesCount;
            this.FaceCount = FaceCount;
            this.ModelHeight = ModelHeight;
        }

        private static CachedModelPose From(Model Model)
        {
            int VerticesCount = Model.getVerticesCount();
            int FaceCount = Model.getFaceCount();
            float[] SourceX = Model.getVerticesX();
            float[] SourceY = Model.getVerticesY();
            float[] SourceZ = Model.getVerticesZ();
            byte[] SourceTransparencies = Model.getFaceTransparencies();
            if (VerticesCount < 0 || FaceCount < 0 ||
                    SourceX == null || SourceX.length < VerticesCount ||
                    SourceY == null || SourceY.length < VerticesCount ||
                    SourceZ == null || SourceZ.length < VerticesCount ||
                    (SourceTransparencies != null &&
                            SourceTransparencies.length < FaceCount))
            {
                return null;
            }

            Model.calculateBoundsCylinder();
            return new CachedModelPose(
                    Arrays.copyOf(SourceX, VerticesCount),
                    Arrays.copyOf(SourceY, VerticesCount),
                    Arrays.copyOf(SourceZ, VerticesCount),
                    SourceTransparencies == null
                            ? null
                            : Arrays.copyOf(SourceTransparencies, FaceCount),
                    VerticesCount,
                    FaceCount,
                    Model.getModelHeight());
        }
    }

    private static final class CachedAnimationFrames
    {
        private final boolean Interpolated;
        private final Model Template;
        private final Map<Integer, CachedModelPose> Poses;

        private CachedAnimationFrames(
                boolean Interpolated,
                Model Template,
                Map<Integer, CachedModelPose> Poses)
        {
            this.Interpolated = Interpolated;
            this.Template = Template;
            this.Poses = Poses;
        }

        private int GetStateCount()
        {
            return Poses.size();
        }

        private Model CreateModel(
                Client Client,
                int Frame,
                int ElapsedTicks)
        {
            CachedModelPose Pose = Poses.get(BuildAnimationFrameKey(
                    Interpolated,
                    Frame,
                    ElapsedTicks));
            if (Pose == null || Template == null)
            {
                return null;
            }

            Model RenderedModel = Client.mergeModels(Template);
            if (RenderedModel == null ||
                    RenderedModel.getVerticesCount() != Pose.VerticesCount ||
                    RenderedModel.getFaceCount() != Pose.FaceCount)
            {
                return null;
            }

            float[] DestinationX = RenderedModel.getVerticesX();
            float[] DestinationY = RenderedModel.getVerticesY();
            float[] DestinationZ = RenderedModel.getVerticesZ();
            if (DestinationX == null || DestinationX.length < Pose.VerticesCount ||
                    DestinationY == null || DestinationY.length < Pose.VerticesCount ||
                    DestinationZ == null || DestinationZ.length < Pose.VerticesCount ||
                    DestinationX == Template.getVerticesX() ||
                    DestinationY == Template.getVerticesY() ||
                    DestinationZ == Template.getVerticesZ())
            {
                return null;
            }

            System.arraycopy(Pose.VerticesX, 0, DestinationX, 0, Pose.VerticesCount);
            System.arraycopy(Pose.VerticesY, 0, DestinationY, 0, Pose.VerticesCount);
            System.arraycopy(Pose.VerticesZ, 0, DestinationZ, 0, Pose.VerticesCount);

            byte[] DestinationTransparencies = RenderedModel.getFaceTransparencies();
            if (Pose.FaceTransparencies != null)
            {
                if (DestinationTransparencies == null ||
                        DestinationTransparencies.length < Pose.FaceCount ||
                        DestinationTransparencies == Template.getFaceTransparencies())
                {
                    return null;
                }
                System.arraycopy(
                        Pose.FaceTransparencies,
                        0,
                        DestinationTransparencies,
                        0,
                        Pose.FaceCount);
            }
            else if (DestinationTransparencies != null)
            {
                Arrays.fill(DestinationTransparencies, 0, Pose.FaceCount, (byte) 0);
            }

            RenderedModel.calculateBoundsCylinder();
            if (RenderedModel.getModelHeight() <= 0 && Pose.ModelHeight > 0)
            {
                RenderedModel.setModelHeight(Pose.ModelHeight);
            }
            return RenderedModel;
        }
    }

    private static final class CachedAnimationFramesBuilder
    {
        private final boolean Interpolated;
        private final Map<Integer, CachedModelPose> Poses;
        private Model Template;

        private CachedAnimationFramesBuilder(boolean Interpolated, int StateCount)
        {
            this.Interpolated = Interpolated;
            Poses = new HashMap<>(StateCount);
        }

        private boolean Add(Client Client, int FrameKey, Model AnimatedModel)
        {
            CachedModelPose Pose = CachedModelPose.From(AnimatedModel);
            if (Pose == null)
            {
                return false;
            }

            boolean NeedsTransparencyTemplate =
                    Pose.FaceTransparencies != null &&
                    (Template == null || Template.getFaceTransparencies() == null);
            if (Template == null || NeedsTransparencyTemplate)
            {
                Model Candidate = Client.mergeModels(AnimatedModel);
                if (!IsDetachedCompatibleModel(Candidate, AnimatedModel, Pose))
                {
                    return false;
                }
                Candidate.calculateBoundsCylinder();
                Template = Candidate;
            }
            else if (Template.getVerticesCount() != Pose.VerticesCount ||
                    Template.getFaceCount() != Pose.FaceCount)
            {
                return false;
            }

            Poses.put(FrameKey, Pose);
            return true;
        }

        private CachedAnimationFrames Build()
        {
            return Template == null || Poses.isEmpty()
                    ? null
                    : new CachedAnimationFrames(Interpolated, Template, Poses);
        }

        private static boolean IsDetachedCompatibleModel(
                Model Candidate,
                Model Source,
                CachedModelPose Pose)
        {
            if (Candidate == null ||
                    Candidate.getVerticesCount() != Pose.VerticesCount ||
                    Candidate.getFaceCount() != Pose.FaceCount ||
                    Candidate.getVerticesX() == null ||
                    Candidate.getVerticesY() == null ||
                    Candidate.getVerticesZ() == null ||
                    Candidate.getVerticesX().length < Pose.VerticesCount ||
                    Candidate.getVerticesY().length < Pose.VerticesCount ||
                    Candidate.getVerticesZ().length < Pose.VerticesCount ||
                    Candidate.getVerticesX() == Source.getVerticesX() ||
                    Candidate.getVerticesY() == Source.getVerticesY() ||
                    Candidate.getVerticesZ() == Source.getVerticesZ())
            {
                return false;
            }

            byte[] CandidateTransparencies = Candidate.getFaceTransparencies();
            byte[] SourceTransparencies = Source.getFaceTransparencies();
            return Pose.FaceTransparencies == null ||
                    (CandidateTransparencies != null &&
                            CandidateTransparencies.length >= Pose.FaceCount &&
                            CandidateTransparencies != SourceTransparencies);
        }
    }

    // Native actor motion is authoritative while an action or accelerated
    // displacement is active. Anchoring its delta to the last rendered point
    // avoids snapping from the interpolated model to the server-side actor.
    private boolean bUsingNativeMotion = false;
    private boolean bAcceleratedMovementActive = false;
    private LocalPoint NativeMotionAnchor;
    private LocalPoint PendingNativeMotionAnchor;
    private LocalPoint RenderMotionAnchor;
    private LocalPoint LastNativeMotionRenderLocation;
    private LocalPoint AcceleratedMovementDestination;
    private int LastAcceleratedMovementGameCycle = -1;
    private LocalPoint LastObservedOwnerLocalLocation;
    private int LastOwnerObservationGameCycle = -1;
    private int LastOwnerMovementGameCycle = -1;
    private int LastNativeCompositeModelGameCycle = -1;
    private boolean bSceneRebasePending = false;
    private boolean bSnapNativeMotionToOwner = false;
    private int AcceleratedMovementObservationCount = 0;


    // Original true animations
    private AnimationRequestDetails CurrentAnimationRequest;
    private IdleAnimationSet OldAnimationSet = new IdleAnimationSet();
    public int OldAnimationHeight = 0;
    private boolean bAnimationHeightRefreshPending = false;
    private boolean bIsDefaultHumanAnimationSet = true;

    // Rotation
    private int TargetOrientation = 0;
    private int CurrentOrientation = 0;
    private boolean bStationaryThisFrame = false;


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

    private static final class OwnerMovementAnimationState
    {
        private final int IdleRotateLeft;
        private final int IdleRotateRight;
        private final int WalkAnimation;
        private final int WalkRotateLeft;
        private final int WalkRotateRight;
        private final int WalkRotate180;
        private final int IdlePoseAnimation;
        private final int RunAnimation;
        private final int PoseAnimation;
        private final int PoseAnimationFrame;

        private OwnerMovementAnimationState(Actor Owner)
        {
            IdleRotateLeft = Owner.getIdleRotateLeft();
            IdleRotateRight = Owner.getIdleRotateRight();
            WalkAnimation = Owner.getWalkAnimation();
            WalkRotateLeft = Owner.getWalkRotateLeft();
            WalkRotateRight = Owner.getWalkRotateRight();
            WalkRotate180 = Owner.getWalkRotate180();
            IdlePoseAnimation = Owner.getIdlePoseAnimation();
            RunAnimation = Owner.getRunAnimation();
            PoseAnimation = Owner.getPoseAnimation();
            PoseAnimationFrame = Owner.getPoseAnimationFrame();
        }

        private void Suppress(Actor Owner)
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
        }

        private boolean HasRepopulatedSelector(Actor Owner)
        {
            return HasRepopulatedMovementSelector(Owner) ||
                    Owner.getPoseAnimation() != NO_ANIMATION;
        }

        private boolean HasRepopulatedMovementSelector(Actor Owner)
        {
            return Owner.getIdleRotateLeft() != NO_ANIMATION ||
                    Owner.getIdleRotateRight() != NO_ANIMATION ||
                    Owner.getWalkAnimation() != NO_ANIMATION ||
                    Owner.getWalkRotateLeft() != NO_ANIMATION ||
                    Owner.getWalkRotateRight() != NO_ANIMATION ||
                    Owner.getWalkRotate180() != NO_ANIMATION ||
                    Owner.getIdlePoseAnimation() != NO_ANIMATION ||
                    Owner.getRunAnimation() != NO_ANIMATION;
        }

        private void Restore(Actor Owner, boolean PreserveRepopulatedSelectors)
        {
            // The eight movement selectors form one animation-set tuple. If a
            // player/composition update installed a fresh tuple while capture
            // was armed, preserve it as a whole rather than mixing old and new
            // values. Pose is a separate live controller: a hidden transition
            // can repopulate it without replacing the movement animation set.
            boolean PreserveLiveMovementSet = PreserveRepopulatedSelectors &&
                    HasRepopulatedMovementSelector(Owner);
            if (PreserveLiveMovementSet)
            {
                // A fresh animation set is authoritative as a whole, including
                // a deliberately empty pose controller that the next native
                // update will populate.
                return;
            }

            Owner.setIdleRotateLeft(IdleRotateLeft);
            Owner.setIdleRotateRight(IdleRotateRight);
            Owner.setWalkAnimation(WalkAnimation);
            Owner.setWalkRotateLeft(WalkRotateLeft);
            Owner.setWalkRotateRight(WalkRotateRight);
            Owner.setWalkRotate180(WalkRotate180);
            Owner.setIdlePoseAnimation(IdlePoseAnimation);
            Owner.setRunAnimation(RunAnimation);

            if (!PreserveRepopulatedSelectors || Owner.getPoseAnimation() == NO_ANIMATION)
            {
                Owner.setPoseAnimation(PoseAnimation);
                Owner.setPoseAnimationFrame(PoseAnimationFrame);
            }
        }

        private void RestoreAfterAppearanceChange(Actor Owner)
        {
            if (HasRepopulatedMovementSelector(Owner))
            {
                return;
            }

            // The new composition did not publish its movement set yet. Give
            // the next native update a valid selector set, but never install
            // the old appearance's dynamic pose/frame onto the new model.
            Owner.setIdleRotateLeft(IdleRotateLeft);
            Owner.setIdleRotateRight(IdleRotateRight);
            Owner.setWalkAnimation(WalkAnimation);
            Owner.setWalkRotateLeft(WalkRotateLeft);
            Owner.setWalkRotateRight(WalkRotateRight);
            Owner.setWalkRotate180(WalkRotate180);
            Owner.setIdlePoseAnimation(IdlePoseAnimation);
            Owner.setRunAnimation(RunAnimation);
        }
    }

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
        return getOrientationBetweenPoints(point1X, point1Y, point2X, point2Y, OffsetAngle, 0);
    }

    static int getOrientationBetweenPoints(
            double point1X,
            double point1Y,
            double point2X,
            double point2Y,
            int OffsetAngle,
            int FallbackOrientation)
    {
        // Calculate the difference in X and Y coordinates
        double deltaX = point2X - point1X;
        double deltaY = point2Y - point1Y;
        if (deltaX == 0.0 && deltaY == 0.0)
        {
            return FallbackOrientation & 2047;
        }

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

    boolean RebindOwnerAfterSceneLoad(Player NewOwner)
    {
        if (!(Owner instanceof Player) || NewOwner == null)
        {
            return false;
        }

        InvalidateNeutralOwnerModel();
        Owner = NewOwner;
        currentTarget = null;
        NotInteractingTimer = 0;
        LastInteractionNanos = 0;
        TargetModelUnavailableSinceNanos = 0;
        LastObservedOwnerLocalLocation = null;
        LastOwnerObservationGameCycle = -1;
        LastOwnerMovementGameCycle = -1;
        LastNativeCompositeModelGameCycle = -1;
        LastRenderedAppearance = null;
        LastRenderedAnimationHeightOffset = 0;
        ClearStableStationaryModel();
        bAcceleratedMovementActive = false;
        AcceleratedMovementDestination = null;
        AcceleratedMovementObservationCount = 0;
        ClearNativeMotion();
        bSceneRebasePending = true;
        return true;
    }

    boolean HasRenderableModel()
    {
        return Model != null && Model.isActive() && Model.getModel() != null;
    }

    private PlayerAppearanceKey GetCurrentPlayerAppearance()
    {
        if (!(Owner instanceof Player))
        {
            return null;
        }

        PlayerComposition Composition = ((Player) Owner).getPlayerComposition();
        if (NeutralOwnerModelAppearance != null && NeutralOwnerModelAppearance.Matches(Composition))
        {
            return NeutralOwnerModelAppearance;
        }
        if (RequestedNeutralOwnerAppearance != null && RequestedNeutralOwnerAppearance.Matches(Composition))
        {
            return RequestedNeutralOwnerAppearance;
        }
        return PlayerAppearanceKey.From(Composition);
    }

    private boolean HasActiveSpotAnimation()
    {
        IterableHashTable<ActorSpotAnim> SpotAnimations = Owner.getSpotAnims();
        if (SpotAnimations == null)
        {
            return false;
        }

        for (ActorSpotAnim SpotAnimation : SpotAnimations)
        {
            if (SpotAnimation != null && SpotAnimation.getId() != NO_ANIMATION)
            {
                return true;
            }
        }
        return false;
    }

    static boolean HasHandItemOverride(int LeftHandItem, int RightHandItem)
    {
        return LeftHandItem >= 0 || RightHandItem >= 0;
    }

    private static boolean HasHandItemOverride(Animation Animation)
    {
        return Animation != null && HasHandItemOverride(
                Animation.getLeftHandItem(),
                Animation.getRightHandItem());
    }

    static boolean ShouldUseCustomLocomotionModel(
            boolean UseNativeCompositeModel,
            boolean HasMatchingNeutralModel,
            boolean RequestedAnimationAvailable,
            boolean AnimationHasHandItemOverride)
    {
        return !UseNativeCompositeModel &&
                HasMatchingNeutralModel &&
                RequestedAnimationAvailable &&
                !AnimationHasHandItemOverride;
    }

    static boolean IsNeutralCaptureCompletionCycle(
            int CaptureGameCycle,
            int CurrentGameCycle)
    {
        // Intentional integer wraparound: the Java overflow result remains the
        // exact successor when RuneLite's cycle counter wraps.
        return CurrentGameCycle == CaptureGameCycle + 1;
    }

    static int BuildAnimationFrameKey(
            boolean Interpolated,
            int Frame,
            int ElapsedTicks)
    {
        if (!Interpolated)
        {
            return Frame;
        }

        return Integer.MIN_VALUE | (ElapsedTicks << 16) | Frame;
    }

    static int CountAnimationStates(
            boolean MayaAnimation,
            int Duration,
            int[] FrameLengths,
            boolean Interpolated)
    {
        if (MayaAnimation)
        {
            return Math.max(0, Duration);
        }
        if (FrameLengths == null)
        {
            return 0;
        }
        if (!Interpolated)
        {
            return FrameLengths.length;
        }

        long StateCount = 0;
        for (int FrameLength : FrameLengths)
        {
            StateCount += Math.max(0, FrameLength) + 1L;
            if (StateCount > Integer.MAX_VALUE)
            {
                return Integer.MAX_VALUE;
            }
        }
        return (int) StateCount;
    }

    static int[] BuildAnimationFrameKeys(
            boolean MayaAnimation,
            int Duration,
            int[] FrameLengths,
            boolean Interpolated)
    {
        int StateCount = CountAnimationStates(
                MayaAnimation,
                Duration,
                FrameLengths,
                Interpolated);
        if (StateCount <= 0 || StateCount > MAX_CACHED_ANIMATION_STATES)
        {
            return null;
        }

        int[] FrameKeys = new int[StateCount];
        int StateIndex = 0;
        if (MayaAnimation)
        {
            for (int Frame = 0; Frame < Duration; Frame++)
            {
                FrameKeys[StateIndex++] = BuildAnimationFrameKey(
                        Interpolated,
                        Frame,
                        0);
            }
            return FrameKeys;
        }

        for (int Frame = 0; Frame < FrameLengths.length; Frame++)
        {
            int MaximumElapsedTicks = Interpolated
                    ? Math.max(0, FrameLengths[Frame])
                    : 0;
            for (int ElapsedTicks = 0;
                 ElapsedTicks <= MaximumElapsedTicks;
                 ElapsedTicks++)
            {
                FrameKeys[StateIndex++] = BuildAnimationFrameKey(
                        Interpolated,
                        Frame,
                        ElapsedTicks);
            }
        }
        return FrameKeys;
    }

    private boolean IsAnimationInterpolated(Animation Animation)
    {
        IntPredicate Filter = client.getAnimationInterpolationFilter();
        return Filter != null && Filter.test(Animation.getId());
    }

    private static long GetAnimationVariantKey(int AnimationId, boolean Interpolated)
    {
        return (((long) AnimationId) << 1) | (Interpolated ? 1L : 0L);
    }

    private void QueueNeutralAnimation(Animation Animation)
    {
        if (Animation == null || HasHandItemOverride(Animation))
        {
            return;
        }

        boolean Interpolated = IsAnimationInterpolated(Animation);
        long VariantKey = GetAnimationVariantKey(Animation.getId(), Interpolated);
        if (UncacheableNeutralAnimationVariants.contains(VariantKey))
        {
            return;
        }

        int StateCount = CountAnimationStates(
                Animation.isMayaAnim(),
                Animation.getDuration(),
                Animation.getFrameLengths(),
                Interpolated);
        if (StateCount <= 0 || StateCount > MAX_CACHED_ANIMATION_STATES)
        {
            UncacheableNeutralAnimationVariants.add(VariantKey);
            RequestedNeutralAnimations.remove(Animation.getId());
            return;
        }

        CachedAnimationFrames CachedFrames =
                NeutralAnimationFrameCache.get(Animation.getId());
        if (CachedFrames != null && CachedFrames.Interpolated == Interpolated)
        {
            RequestedNeutralAnimations.remove(Animation.getId());
            return;
        }

        RequestedNeutralAnimations.put(Animation.getId(), Interpolated);
    }

    private void QueueNeutralAnimation(int AnimationId)
    {
        if (AnimationId >= 0)
        {
            QueueNeutralAnimation(client.loadAnimation(AnimationId));
        }
    }

    private void QueueCommonLocomotionAnimations(Animation RequestedAnimation)
    {
        // Preserve insertion order: make the frame currently needed visible
        // first, followed by the ordinary idle/run/walk set. Turn, leap, and
        // special variants fill only when requested, avoiding long capture
        // bursts and an oversized cache after every equipment change.
        QueueNeutralAnimation(RequestedAnimation);
        QueueNeutralAnimation(OldAnimationSet.IdlePoseAnimation);
        QueueNeutralAnimation(OldAnimationSet.RunAnimation);
        QueueNeutralAnimation(OldAnimationSet.WalkAnimation);
    }

    private void ClearNeutralAnimationFrameCache()
    {
        NeutralAnimationFrameCache.clear();
        NeutralAnimationFrameStateCount = 0;
    }

    private void RemoveNeutralAnimationFrames(int AnimationId)
    {
        CachedAnimationFrames RemovedFrames =
                NeutralAnimationFrameCache.remove(AnimationId);
        if (RemovedFrames != null)
        {
            NeutralAnimationFrameStateCount -= RemovedFrames.GetStateCount();
        }
    }

    private void ResetPartialNeutralCapture()
    {
        PartialNeutralCaptureBuilder = null;
        PartialNeutralCaptureAppearance = null;
        PartialNeutralCaptureAnimationId = NO_ANIMATION;
        PartialNeutralCaptureInterpolated = false;
        PartialNeutralCaptureFrameKeys = null;
        PartialNeutralCaptureFrameIndex = 0;
        PartialNeutralCaptureFailureCount = 0;
    }

    private void CacheNeutralAnimationFrames(
            int AnimationId,
            CachedAnimationFrames Frames)
    {
        RemoveNeutralAnimationFrames(AnimationId);

        while (!NeutralAnimationFrameCache.isEmpty() &&
                NeutralAnimationFrameStateCount + Frames.GetStateCount() >
                        MAX_TOTAL_CACHED_ANIMATION_STATES)
        {
            Map.Entry<Integer, CachedAnimationFrames> Oldest =
                    NeutralAnimationFrameCache.entrySet().iterator().next();
            NeutralAnimationFrameStateCount -= Oldest.getValue().GetStateCount();
            NeutralAnimationFrameCache.remove(Oldest.getKey());
        }

        if (Frames.GetStateCount() <= MAX_TOTAL_CACHED_ANIMATION_STATES)
        {
            NeutralAnimationFrameCache.put(AnimationId, Frames);
            NeutralAnimationFrameStateCount += Frames.GetStateCount();
        }
    }

    private void RequestNeutralAnimationFrames(
            PlayerAppearanceKey Appearance,
            Animation RequestedAnimation)
    {
        if (Appearance == null || RequestedAnimation == null ||
                HasHandItemOverride(RequestedAnimation))
        {
            return;
        }

        boolean AppearanceAlreadyCached =
                Appearance.equals(NeutralOwnerModelAppearance);
        boolean SameRequestedAppearance =
                Appearance.equals(RequestedNeutralOwnerAppearance);
        if (!AppearanceAlreadyCached && !SameRequestedAppearance)
        {
            ClearNeutralAnimationFrameCache();
            ResetPartialNeutralCapture();
            RequestedNeutralAnimations.clear();
            UncacheableNeutralAnimationVariants.clear();
            NeutralOwnerModelAppearance = null;
            RequestedNeutralOwnerAppearance = Appearance;
            NeutralCaptureRequestGameCycle = client.getGameCycle();
            QueueCommonLocomotionAnimations(RequestedAnimation);
        }
        else
        {
            if (!SameRequestedAppearance)
            {
                RequestedNeutralOwnerAppearance = Appearance;
                NeutralCaptureRequestGameCycle =
                        client.getGameCycle() - MIN_NEUTRAL_APPEARANCE_STABLE_CYCLES;
            }
            QueueNeutralAnimation(RequestedAnimation);
        }

        bNeutralCaptureRequested = !RequestedNeutralAnimations.isEmpty();
        if (!bNeutralCaptureRequested)
        {
            RequestedNeutralOwnerAppearance = null;
            NeutralCaptureRequestGameCycle = -1;
        }
    }

    private boolean IsNeutralCaptureSafe(PlayerAppearanceKey Appearance)
    {
        int CurrentGameCycle = client.getGameCycle();
        // Wait a full server tick after movement or native composition. This
        // keeps temporary attached/action models out of the neutral topology.
        boolean MovementSettled = LastOwnerMovementGameCycle < 0 ||
                CurrentGameCycle - LastOwnerMovementGameCycle >
                        NATIVE_MOTION_SETTLE_CYCLES;
        boolean NativeCompositionSettled = LastNativeCompositeModelGameCycle < 0 ||
                CurrentGameCycle - LastNativeCompositeModelGameCycle >
                        NATIVE_MOTION_SETTLE_CYCLES;
        return Appearance != null &&
                Owner instanceof Player &&
                Owner.getAnimation() == NO_ANIMATION &&
                !HasActiveSpotAnimation() &&
                !bAcceleratedMovementActive &&
                !bUsingNativeMotion &&
                !bSceneRebasePending &&
                bStationaryThisFrame &&
                MovementSettled &&
                NativeCompositionSettled;
    }

    private CachedAnimationFrames CaptureNextNeutralAnimationFrames(
            Actor CaptureOwner,
            PlayerAppearanceKey Appearance,
            Animation Animation,
            boolean Interpolated)
    {
        boolean MatchingPartialCapture =
                PartialNeutralCaptureBuilder != null &&
                Animation.getId() == PartialNeutralCaptureAnimationId &&
                Interpolated == PartialNeutralCaptureInterpolated &&
                Appearance.equals(PartialNeutralCaptureAppearance);
        if (!MatchingPartialCapture)
        {
            ResetPartialNeutralCapture();
            int[] FrameKeys = BuildAnimationFrameKeys(
                    Animation.isMayaAnim(),
                    Animation.getDuration(),
                    Animation.getFrameLengths(),
                    Interpolated);
            if (FrameKeys == null)
            {
                AbandonNeutralAnimationCapture(Animation.getId(), Interpolated);
                return null;
            }

            PartialNeutralCaptureBuilder =
                    new CachedAnimationFramesBuilder(Interpolated, FrameKeys.length);
            PartialNeutralCaptureAppearance = Appearance;
            PartialNeutralCaptureAnimationId = Animation.getId();
            PartialNeutralCaptureInterpolated = Interpolated;
            PartialNeutralCaptureFrameKeys = FrameKeys;
        }

        int EndingFrameIndex = Math.min(
                PartialNeutralCaptureFrameKeys.length,
                PartialNeutralCaptureFrameIndex +
                        MAX_NEUTRAL_CAPTURE_STATES_PER_WINDOW);
        while (PartialNeutralCaptureFrameIndex < EndingFrameIndex)
        {
            int FrameKey = PartialNeutralCaptureFrameKeys[
                    PartialNeutralCaptureFrameIndex];
            Model AnimatedModel = CaptureNeutralAnimationFrame(
                    CaptureOwner,
                    Animation,
                    FrameKey);
            if (AnimatedModel == null)
            {
                ++PartialNeutralCaptureFailureCount;
                if (PartialNeutralCaptureFailureCount >= 3)
                {
                    AbandonNeutralAnimationCapture(
                            Animation.getId(),
                            Interpolated);
                }
                return null;
            }

            if (!PartialNeutralCaptureBuilder.Add(
                    client,
                    FrameKey,
                    AnimatedModel))
            {
                // A non-detaching merge or incompatible topology is structural;
                // retrying it every GameTick would create a periodic hitch.
                AbandonNeutralAnimationCapture(
                        Animation.getId(),
                        Interpolated);
                return null;
            }

            ++PartialNeutralCaptureFrameIndex;
            PartialNeutralCaptureFailureCount = 0;
        }

        if (PartialNeutralCaptureFrameIndex <
                PartialNeutralCaptureFrameKeys.length)
        {
            return null;
        }

        CachedAnimationFrames CapturedFrames =
                PartialNeutralCaptureBuilder.Build();
        if (CapturedFrames == null)
        {
            AbandonNeutralAnimationCapture(Animation.getId(), Interpolated);
            return null;
        }

        ResetPartialNeutralCapture();
        return CapturedFrames;
    }

    private void AbandonNeutralAnimationCapture(
            int AnimationId,
            boolean Interpolated)
    {
        UncacheableNeutralAnimationVariants.add(
                GetAnimationVariantKey(AnimationId, Interpolated));
        RequestedNeutralAnimations.remove(AnimationId);
        bNeutralCaptureRequested = !RequestedNeutralAnimations.isEmpty();
        if (!bNeutralCaptureRequested)
        {
            RequestedNeutralOwnerAppearance = null;
            NeutralCaptureRequestGameCycle = -1;
        }
        ResetPartialNeutralCapture();
        log.debug(
                "Using native player model for incompatible True Tile animation {}",
                AnimationId);
    }

    private Model CaptureNeutralAnimationFrame(
            Actor CaptureOwner,
            Animation Animation,
            int FrameKey)
    {
        // A neutral Player.getModel() is RuneLite's shared sequence scratch
        // model. Consume it immediately: the next model build can overwrite
        // both its vertices and skin groups.
        Model NeutralModel = CaptureOwner.getModel();
        if (NeutralModel == null)
        {
            return null;
        }

        Model AnimatedModel = client.applyTransformations(
                NeutralModel,
                Animation,
                FrameKey,
                null,
                0);
        if (AnimatedModel == null)
        {
            return null;
        }

        // The caller snapshots the transformed vertices immediately, before
        // another getModel/applyTransformations call can reuse this scratch.
        return AnimatedModel;
    }

    void BeginNeutralOwnerModelCaptureOnGameTick()
    {
        if (PendingNeutralCaptureState != null)
        {
            // The same native cycle's ClientTick should always complete the
            // transaction. Recover an interrupted transaction, then wait for a
            // clean GameTick epoch instead of immediately arming it again.
            RestorePendingNeutralCapture(true);
            return;
        }

        if (!bNeutralCaptureRequested || RequestedNeutralAnimations.isEmpty() ||
                Owner == null ||
                client.getGameCycle() < NeutralCaptureRetryAfterGameCycle ||
                bShouldRenderOwner ||
                Model == null ||
                !Model.isActive() ||
                Model.getModel() == null ||
                Model.getLocation() == null)
        {
            return;
        }

        PlayerAppearanceKey CurrentAppearance = GetCurrentPlayerAppearance();
        if (CurrentAppearance == null)
        {
            return;
        }
        if (!CurrentAppearance.equals(RequestedNeutralOwnerAppearance))
        {
            // The render path will enqueue the locomotion animation belonging
            // to the new appearance. Do not guess using a stale request here.
            return;
        }
        if (NeutralCaptureRequestGameCycle < 0 ||
                client.getGameCycle() - NeutralCaptureRequestGameCycle <
                        MIN_NEUTRAL_APPEARANCE_STABLE_CYCLES)
        {
            // A composition can expose its new key before worn-model resources
            // are ready and temporarily return the previous cached geometry.
            // Keep the correct native fallback until the key has been stable.
            return;
        }
        if (!IsNeutralCaptureSafe(CurrentAppearance))
        {
            return;
        }

        Map.Entry<Integer, Boolean> RequestedVariant =
                RequestedNeutralAnimations.entrySet().iterator().next();
        int CaptureAnimationId = RequestedVariant.getKey();
        boolean CaptureInterpolated = RequestedVariant.getValue();
        Animation CaptureAnimation = client.loadAnimation(CaptureAnimationId);
        if (CaptureAnimation == null)
        {
            NeutralCaptureRetryAfterGameCycle =
                    client.getGameCycle() + LAST_GOOD_RENDER_HOLD_CYCLES;
            return;
        }
        if (HasHandItemOverride(CaptureAnimation) ||
                IsAnimationInterpolated(CaptureAnimation) != CaptureInterpolated)
        {
            RequestedNeutralAnimations.remove(CaptureAnimationId);
            bNeutralCaptureRequested = !RequestedNeutralAnimations.isEmpty();
            return;
        }

        Actor CaptureOwner = Owner;
        try
        {
            OwnerMovementAnimationState CaptureState =
                    new OwnerMovementAnimationState(CaptureOwner);
            int CaptureWorldViewId = CaptureOwner.getWorldView().getId();
            LocalPoint CaptureLocalLocation = CaptureOwner.getLocalLocation();

            PendingNeutralCaptureState = CaptureState;
            PendingNeutralCaptureOwner = CaptureOwner;
            PendingNeutralCaptureWorldViewId = CaptureWorldViewId;
            PendingNeutralCaptureGameCycle = client.getGameCycle();
            PendingNeutralCaptureLocalLocation = CaptureLocalLocation;
            PendingNeutralCaptureAnimationId = CaptureAnimationId;
            PendingNeutralCaptureInterpolated = CaptureInterpolated;

            // Low-priority GameTick runs before the native actor update and
            // high-priority ClientTick runs after it. No rendered frame occurs
            // between them, while the actor update clears hidden pose/transition
            // controllers that a same-call setter transaction cannot reach.
            CaptureState.Suppress(CaptureOwner);
        }
        catch (RuntimeException ex)
        {
            // Keep the request alive; a partially applied suppression must be
            // restored in full. This catch is synchronous, before a native
            // update can install a new animation set, so any non--1 selector is
            // the untouched part of our own partial write rather than fresh
            // client state.
            RestorePendingNeutralCapture(false);
            NeutralCaptureRetryAfterGameCycle = client.getGameCycle() + LAST_GOOD_RENDER_HOLD_CYCLES;
            log.debug("Unable to begin neutral True Tile player-model capture", ex);
        }
    }

    void CompleteNeutralOwnerModelCaptureOnClientTick()
    {
        OwnerMovementAnimationState CaptureState = PendingNeutralCaptureState;
        Actor CaptureOwner = PendingNeutralCaptureOwner;
        if (CaptureState == null || CaptureOwner == null)
        {
            return;
        }

        CachedAnimationFrames CapturedAnimationFrames = null;
        PlayerAppearanceKey CapturedAppearance = null;
        int CapturedAnimationId = PendingNeutralCaptureAnimationId;
        boolean CapturedInterpolation = PendingNeutralCaptureInterpolated;
        RuntimeException Failure = null;
        // Default to preserving live values if selector inspection itself
        // fails; stale restoration is the more damaging failure mode.
        boolean RepopulatedSelectors = true;
        boolean AppearanceChanged = false;
        try
        {
            RepopulatedSelectors = CaptureState.HasRepopulatedSelector(CaptureOwner);
            PlayerAppearanceKey CurrentAppearance = GetCurrentPlayerAppearance();
            AppearanceChanged = CurrentAppearance == null ||
                    RequestedNeutralOwnerAppearance == null ||
                    !CurrentAppearance.equals(RequestedNeutralOwnerAppearance);
            int CurrentGameCycle = client.getGameCycle();
            LocalPoint CurrentOwnerLocation = CaptureOwner.getLocalLocation();
            int ElapsedClientCycles = CurrentGameCycle - PendingNeutralCaptureGameCycle;
            boolean AcceleratedDuringCapture =
                    PendingNeutralCaptureLocalLocation == null ||
                    CurrentOwnerLocation == null ||
                    PendingNeutralCaptureLocalLocation.getWorldView() !=
                            CurrentOwnerLocation.getWorldView() ||
                    IsAcceleratedLocalMovement(
                            PendingNeutralCaptureLocalLocation,
                            CurrentOwnerLocation,
                            ElapsedClientCycles);
            boolean SameCaptureEpoch = Owner == CaptureOwner &&
                    CaptureOwner.getWorldView().getId() == PendingNeutralCaptureWorldViewId &&
                    IsNeutralCaptureCompletionCycle(
                            PendingNeutralCaptureGameCycle,
                            CurrentGameCycle);
            if (!RepopulatedSelectors &&
                    SameCaptureEpoch &&
                    !AcceleratedDuringCapture &&
                    CurrentAppearance != null &&
                    CurrentAppearance.equals(RequestedNeutralOwnerAppearance) &&
                    IsNeutralCaptureSafe(CurrentAppearance))
            {
                Animation CaptureAnimation = client.loadAnimation(CapturedAnimationId);
                Boolean RequestedInterpolation =
                        RequestedNeutralAnimations.get(CapturedAnimationId);
                if (CaptureAnimation != null &&
                        !HasHandItemOverride(CaptureAnimation) &&
                        RequestedInterpolation != null &&
                        RequestedInterpolation == CapturedInterpolation &&
                        IsAnimationInterpolated(CaptureAnimation) == CapturedInterpolation)
                {
                    CapturedAnimationFrames = CaptureNextNeutralAnimationFrames(
                            CaptureOwner,
                            CurrentAppearance,
                            CaptureAnimation,
                            CapturedInterpolation);
                    if (CapturedAnimationFrames != null)
                    {
                        CapturedAppearance = CurrentAppearance;
                    }
                }
            }
        }
        catch (RuntimeException ex)
        {
            Failure = ex;
        }
        finally
        {
            try
            {
                if (AppearanceChanged)
                {
                    CaptureState.RestoreAfterAppearanceChange(CaptureOwner);
                }
                else
                {
                    // A sequence update may have installed a new movement tuple
                    // while capture was armed. Restore atomically, preserving
                    // that live tuple if it was repopulated.
                    CaptureState.Restore(CaptureOwner, RepopulatedSelectors);
                }
            }
            catch (RuntimeException RestoreException)
            {
                if (Failure == null)
                {
                    Failure = RestoreException;
                }
                else
                {
                    Failure.addSuppressed(RestoreException);
                }
            }
            // Suppression also clears the client's hidden movement controller,
            // which public setters cannot restore. Never use Owner.getModel()
            // again in this completion cycle; hold the previous composed frame
            // until the next native actor update rebuilds that controller.
            NeutralCaptureHoldGameCycle = client.getGameCycle();
            ClearPendingNeutralCaptureState();
        }

        if (Failure != null)
        {
            NeutralCaptureRetryAfterGameCycle = client.getGameCycle() + LAST_GOOD_RENDER_HOLD_CYCLES;
            log.debug("Unable to complete neutral True Tile player-model capture", Failure);
            return;
        }

        if (CapturedAnimationFrames != null && CapturedAppearance != null)
        {
            if (!CapturedAppearance.equals(NeutralOwnerModelAppearance))
            {
                ClearNeutralAnimationFrameCache();
                UncacheableNeutralAnimationVariants.clear();
            }
            NeutralOwnerModelAppearance = CapturedAppearance;
            CacheNeutralAnimationFrames(
                    CapturedAnimationId,
                    CapturedAnimationFrames);
            Boolean RequestedInterpolation =
                    RequestedNeutralAnimations.get(CapturedAnimationId);
            if (RequestedInterpolation != null &&
                    RequestedInterpolation == CapturedInterpolation)
            {
                RequestedNeutralAnimations.remove(CapturedAnimationId);
            }

            bNeutralCaptureRequested = !RequestedNeutralAnimations.isEmpty();
            if (!bNeutralCaptureRequested)
            {
                RequestedNeutralOwnerAppearance = null;
                NeutralCaptureRequestGameCycle = -1;
            }
            NeutralCaptureRetryAfterGameCycle = -1;
        }
        else if (bNeutralCaptureRequested)
        {
            // Avoid retrying every client cycle if model resources, action
            // state, or an appearance update made this epoch unsuitable.
            NeutralCaptureRetryAfterGameCycle =
                    client.getGameCycle() + LAST_GOOD_RENDER_HOLD_CYCLES;
        }
    }

    private void ClearPendingNeutralCaptureState()
    {
        PendingNeutralCaptureState = null;
        PendingNeutralCaptureOwner = null;
        PendingNeutralCaptureWorldViewId = -1;
        PendingNeutralCaptureGameCycle = -1;
        PendingNeutralCaptureLocalLocation = null;
        PendingNeutralCaptureAnimationId = NO_ANIMATION;
        PendingNeutralCaptureInterpolated = false;
    }

    private void RestorePendingNeutralCapture(boolean PreserveRepopulatedSelectors)
    {
        OwnerMovementAnimationState CaptureState = PendingNeutralCaptureState;
        Actor CaptureOwner = PendingNeutralCaptureOwner;
        try
        {
            if (CaptureState != null && CaptureOwner != null)
            {
                CaptureState.Restore(CaptureOwner, PreserveRepopulatedSelectors);
            }
        }
        finally
        {
            ClearPendingNeutralCaptureState();
        }
    }

    private void CancelPendingNeutralCapture(boolean InvalidateNeutralModel)
    {
        try
        {
            RestorePendingNeutralCapture(true);
        }
        finally
        {
            ClearPendingNeutralCaptureState();
            bNeutralCaptureRequested = false;
            RequestedNeutralOwnerAppearance = null;
            NeutralCaptureRetryAfterGameCycle = -1;
            NeutralCaptureRequestGameCycle = -1;
            NeutralCaptureHoldGameCycle = -1;
            if (InvalidateNeutralModel)
            {
                ClearNeutralAnimationFrameCache();
                ResetPartialNeutralCapture();
                RequestedNeutralAnimations.clear();
                UncacheableNeutralAnimationVariants.clear();
                NeutralOwnerModelAppearance = null;
                bCustomLocomotionModelLastFrame = false;
            }
        }
    }

    void InvalidateNeutralOwnerModel()
    {
        CancelPendingNeutralCapture(true);
    }

    public void Initialize(boolean bRuneliteObjectsStale)
    {
        if (bRuneliteObjectsStale)
        {
            // Scene buffer metadata belongs to the old scene. The ordinary
            // idle/custom path will seed a fresh detached fallback.
            ClearStableStationaryModel();
        }

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
            RuneLiteObject NewModel = client.createRuneLiteObject();
            boolean ReplacementReady = false;
            try
            {
                if (OldModel != null)
                {
                    LocalPoint OldLocation = OldModel.getLocation();
                    int OldLevel = OldModel.getLevel();
                    AnimationController OldController = OldModel.getAnimationController();
                    Model OldRenderedModel = OldModel.getModel();
                    int OldZ = OldModel.getZ();

                    NewModel.setLocation(OldLocation, OldLevel);
                    NewModel.setOrientation(CurrentOrientation);
                    NewModel.setAnimationController(OldController);
                    NewModel.setModel(OldRenderedModel);
                    NewModel.setZ(OldZ);
                    if (OldRenderedModel != null)
                    {
                        NewModel.setActive(true);
                        LastSuccessfulRenderGameCycle = client.getGameCycle();
                    }
                }
                else
                {
                    LocalPoint OwnerLocation = Owner.getLocalLocation();
                    if (OwnerLocation != null)
                    {
                        CurrentOrientation = Owner.getCurrentOrientation();
                        TargetOrientation = CurrentOrientation;
                        NewModel.setLocation(OwnerLocation, Owner.getWorldView().getPlane());
                        NewModel.setOrientation(CurrentOrientation);
                    }
                }

                ReplacementReady = true;
            }
            finally
            {
                CleanupRuneLiteObject(OldModel, "stale movement");
                if (!ReplacementReady)
                {
                    CleanupRuneLiteObject(NewModel, "incomplete movement replacement");
                }
            }
            Model = NewModel;

            if (bRuneliteObjectsStale)
            {
                bSceneRebasePending = true;
            }
        }

        if (bSceneRebasePending && RebaseAfterSceneLoad())
        {
            bSceneRebasePending = false;
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
                    RuneLiteObject NewCameraModel = client.createRuneLiteObject();
                    boolean ReplacementReady = false;
                    try
                    {
                        if (OldModel != null)
                        {
                            NewCameraModel.setLocation(OldModel.getLocation(), OldModel.getLevel());
                            NewCameraModel.setOrientation(OldModel.getOrientation());
                            NewCameraModel.setAnimationController(OldModel.getAnimationController());
                        }
                        ReplacementReady = true;
                    }
                    finally
                    {
                        CleanupRuneLiteObject(OldModel, "stale camera");
                        if (!ReplacementReady)
                        {
                            CleanupRuneLiteObject(NewCameraModel, "incomplete camera replacement");
                        }
                    }
                    cameraModel = NewCameraModel;
                }
            }
        }
    }

    private boolean RebaseAfterSceneLoad()
    {
        LocalPoint OwnerLocation = Owner.getLocalLocation();
        if (OwnerLocation == null)
        {
            return false;
        }

        LocalPoint RenderedLocation = LastRenderedWorldPoint == null
                ? null
                : LocalPoint.fromWorld(client, LastRenderedWorldPoint);
        RenderedLocation = ApplySceneLocalOffset(
                RenderedLocation,
                LastRenderedWorldOffsetX,
                LastRenderedWorldOffsetY);
        if (RenderedLocation == null || RenderedLocation.getWorldView() != OwnerLocation.getWorldView())
        {
            RenderedLocation = OwnerLocation;
            LastRenderedWorldOffsetX = 0;
            LastRenderedWorldOffsetY = 0;
        }

        WorldPoint OwnerWorldPoint = Owner.getWorldLocation();
        LocalPoint Destination = OwnerWorldPoint == null
                ? null
                : LocalPoint.fromWorld(client, OwnerWorldPoint);
        if (Destination == null || Destination.getWorldView() != RenderedLocation.getWorldView())
        {
            Destination = OwnerLocation;
        }

        LastLerpPosition = RenderedLocation;
        NewLocalPointToDraw = RenderedLocation;
        NextLerpPosition = Destination;
        LastLerpPositionWorldPoint = WorldPoint.fromLocal(client, RenderedLocation);
        NextLerpPositionWorldPoint = OwnerWorldPoint;
        LastTrueTilePosition = RenderedLocation;
        CurrentTrueTilePosition = Destination;
        CurrentWorldPoint = OwnerWorldPoint;
        LastRenderedWorldPoint = LastLerpPositionWorldPoint;
        TileMovementStartNanos = System.nanoTime();
        MillisecondsSinceTileChange = 0;

        Model.setLocation(RenderedLocation, Owner.getWorldView().getPlane());
        Model.setOrientation(CurrentOrientation);

        bUsingNativeMotion = false;
        bAcceleratedMovementActive = false;
        NativeMotionAnchor = null;
        PendingNativeMotionAnchor = null;
        RenderMotionAnchor = null;
        LastNativeMotionRenderLocation = null;
        AcceleratedMovementDestination = null;
        LastObservedOwnerLocalLocation = OwnerLocation;
        LastOwnerObservationGameCycle = client.getGameCycle();
        LastOwnerMovementGameCycle = OwnerLocation.equals(Destination)
                ? -1
                : client.getGameCycle();
        bSnapNativeMotionToOwner = false;
        AcceleratedMovementObservationCount = 0;
        return true;
    }

    public void Cleanup()
    {
        bShouldRenderOwner = true;
        bAttemptToRenderOwner = true;
        try
        {
            InvalidateNeutralOwnerModel();
        }
        catch (RuntimeException ex)
        {
            log.debug("Unable to restore actor state during neutral model cleanup", ex);
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
        bPlayingKillCelebration = false;
        bAnimationHeightRefreshPending = false;
        LastInteractionNanos = 0;
        TargetModelUnavailableSinceNanos = 0;
        NotInteractingTimer = 0;
        LastSuccessfulRenderGameCycle = -1;
        LastRenderedAppearance = null;
        LastRenderedAnimationHeightOffset = 0;
        ClearStableStationaryModel();
        bUsingNativeMotion = false;
        bAcceleratedMovementActive = false;
        NativeMotionAnchor = null;
        PendingNativeMotionAnchor = null;
        RenderMotionAnchor = null;
        LastNativeMotionRenderLocation = null;
        AcceleratedMovementDestination = null;
        LastAcceleratedMovementGameCycle = -1;
        LastObservedOwnerLocalLocation = null;
        LastOwnerObservationGameCycle = -1;
        LastOwnerMovementGameCycle = -1;
        LastNativeCompositeModelGameCycle = -1;
        bSceneRebasePending = false;
        bSnapNativeMotionToOwner = false;
        AcceleratedMovementObservationCount = 0;
        LastRenderedWorldPoint = null;
        LastRenderedWorldOffsetX = 0;
        LastRenderedWorldOffsetY = 0;
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

    private void UpdateOldIdleAnimations()
    {
        boolean bAnyChanges = false;
        if (OldAnimationSet.IdleRotateLeft != Owner.getIdleRotateLeft())
        {
            OldAnimationSet.IdleRotateLeft = Owner.getIdleRotateLeft();
            bAnyChanges = true;
        }

        if (OldAnimationSet.IdleRotateRight != Owner.getIdleRotateRight())
        {
            OldAnimationSet.IdleRotateRight = Owner.getIdleRotateRight();
            bAnyChanges = true;
        }

        if (OldAnimationSet.WalkAnimation != Owner.getWalkAnimation())
        {
            OldAnimationSet.WalkAnimation = Owner.getWalkAnimation();
            bAnyChanges = true;
        }

        if (OldAnimationSet.WalkRotateLeft != Owner.getWalkRotateLeft())
        {
            OldAnimationSet.WalkRotateLeft = Owner.getWalkRotateLeft();
            bAnyChanges = true;
        }

        if (OldAnimationSet.WalkRotateRight != Owner.getWalkRotateRight())
        {
            OldAnimationSet.WalkRotateRight = Owner.getWalkRotateRight();
            bAnyChanges = true;
        }

        if (OldAnimationSet.WalkRotate180 != Owner.getWalkRotate180())
        {
            OldAnimationSet.WalkRotate180 = Owner.getWalkRotate180();
            bAnyChanges = true;
        }

        if (OldAnimationSet.IdlePoseAnimation != Owner.getIdlePoseAnimation())
        {
            OldAnimationSet.IdlePoseAnimation = Owner.getIdlePoseAnimation();
            bAnyChanges = true;
        }

        if (OldAnimationSet.RunAnimation != Owner.getRunAnimation())
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
        bCurrentSegmentCompletedEarly = false;
    }

    static boolean IsAcceleratedLocalMovement(
            LocalPoint PreviousLocation,
            LocalPoint CurrentLocation,
            int ElapsedClientCycles)
    {
        if (PreviousLocation == null || CurrentLocation == null ||
                PreviousLocation.getWorldView() != CurrentLocation.getWorldView())
        {
            return false;
        }

        int SafeElapsedCycles = Math.max(1, ElapsedClientCycles);
        int MaximumNormalDelta = MAX_NORMAL_LOCAL_UNITS_PER_CLIENT_CYCLE * SafeElapsedCycles;
        return Math.abs(CurrentLocation.getX() - PreviousLocation.getX()) > MaximumNormalDelta ||
                Math.abs(CurrentLocation.getY() - PreviousLocation.getY()) > MaximumNormalDelta;
    }

    static boolean IsAcceleratedDestinationChange(LocalPoint PreviousDestination, LocalPoint NextDestination)
    {
        return PreviousDestination != null &&
                NextDestination != null &&
                PreviousDestination.getWorldView() == NextDestination.getWorldView() &&
                (Math.abs(NextDestination.getX() - PreviousDestination.getX()) > MAX_NORMAL_DESTINATION_DELTA ||
                        Math.abs(NextDestination.getY() - PreviousDestination.getY()) > MAX_NORMAL_DESTINATION_DELTA);
    }

    static boolean IsSpatialDiscontinuity(LocalPoint PreviousLocation, LocalPoint CurrentLocation)
    {
        return PreviousLocation != null &&
                CurrentLocation != null &&
                PreviousLocation.getWorldView() == CurrentLocation.getWorldView() &&
                (Math.abs(CurrentLocation.getX() - PreviousLocation.getX()) > SPATIAL_DISCONTINUITY_DELTA ||
                        Math.abs(CurrentLocation.getY() - PreviousLocation.getY()) > SPATIAL_DISCONTINUITY_DELTA);
    }

    private void MarkAcceleratedMovement(LocalPoint Destination)
    {
        if (!bAcceleratedMovementActive)
        {
            AcceleratedMovementObservationCount = 0;
            if (!bUsingNativeMotion)
            {
                PendingNativeMotionAnchor = LastObservedOwnerLocalLocation == null
                        ? Owner.getLocalLocation()
                        : LastObservedOwnerLocalLocation;
            }
        }
        bAcceleratedMovementActive = true;
        AcceleratedMovementDestination = Destination;
        LastAcceleratedMovementGameCycle = client.getGameCycle();
    }

    private void ObserveOwnerMovement()
    {
        LocalPoint OwnerLocation = Owner.getLocalLocation();
        int CurrentGameCycle = client.getGameCycle();
        if (OwnerLocation == null)
        {
            return;
        }

        if (LastObservedOwnerLocalLocation != null &&
                LastObservedOwnerLocalLocation.getWorldView() == OwnerLocation.getWorldView() &&
                !LastObservedOwnerLocalLocation.equals(OwnerLocation))
        {
            if (IsSpatialDiscontinuity(LastObservedOwnerLocalLocation, OwnerLocation))
            {
                bSnapNativeMotionToOwner = true;
            }
            int ElapsedCycles = LastOwnerObservationGameCycle < 0
                    ? 1
                    : Math.max(1, CurrentGameCycle - LastOwnerObservationGameCycle);
            if (IsAcceleratedLocalMovement(
                    LastObservedOwnerLocalLocation,
                    OwnerLocation,
                    ElapsedCycles))
            {
                WorldPoint DestinationWorldPoint = Owner.getWorldLocation();
                LocalPoint Destination = DestinationWorldPoint == null
                        ? OwnerLocation
                        : LocalPoint.fromWorld(client, DestinationWorldPoint);
                MarkAcceleratedMovement(Destination);
                ++AcceleratedMovementObservationCount;
                if (AcceleratedMovementObservationCount == 1 &&
                        Destination != null &&
                        Destination.getWorldView() == OwnerLocation.getWorldView() &&
                        OwnerLocation.distanceTo(Destination) <= MAX_NORMAL_LOCAL_UNITS_PER_CLIENT_CYCLE)
                {
                    // A displacement which reaches its server destination in one
                    // client update is an instantaneous jump, not a forced-motion
                    // path to interpolate. Match the native snap exactly once.
                    bSnapNativeMotionToOwner = true;
                }
            }
            LastOwnerMovementGameCycle = CurrentGameCycle;
        }

        LastObservedOwnerLocalLocation = OwnerLocation;
        LastOwnerObservationGameCycle = CurrentGameCycle;
    }

    private boolean IsAcceleratedMovementStillActive()
    {
        if (!bAcceleratedMovementActive)
        {
            return false;
        }

        int CurrentGameCycle = client.getGameCycle();
        int LastRelevantMovementCycle = Math.max(
                LastAcceleratedMovementGameCycle,
                LastOwnerMovementGameCycle);
        LocalPoint OwnerLocation = Owner.getLocalLocation();
        boolean ReachedDestination = OwnerLocation != null &&
                AcceleratedMovementDestination != null &&
                OwnerLocation.getWorldView() == AcceleratedMovementDestination.getWorldView() &&
                OwnerLocation.distanceTo(AcceleratedMovementDestination) <= MAX_NORMAL_LOCAL_UNITS_PER_CLIENT_CYCLE;
        boolean HasSettled = LastRelevantMovementCycle >= 0 &&
                CurrentGameCycle - LastRelevantMovementCycle > NATIVE_MOTION_SETTLE_CYCLES;
        if (HasSettled || (ReachedDestination &&
                CurrentGameCycle - LastOwnerMovementGameCycle > NATIVE_MOTION_SETTLE_CYCLES))
        {
            bAcceleratedMovementActive = false;
            AcceleratedMovementDestination = null;
            AcceleratedMovementObservationCount = 0;
            return false;
        }

        return true;
    }

    static boolean ShouldSelectMovingAnimation(
            int MillisecondsSinceDestinationChanged,
            LocalPoint Start,
            LocalPoint Destination,
            LocalPoint RouteDestination,
            LocalPoint LastRenderedPosition,
            boolean SegmentCompletedEarly)
    {
        boolean DestinationPending = Start != null && Destination != null && !Start.equals(Destination);
        if (!DestinationPending)
        {
            return false;
        }

        boolean RouteContinues = DoesRouteContinue(Destination, RouteDestination);
        boolean RenderedAtDestination = IsRenderedAtDestination(
                LastRenderedPosition,
                Destination);
        if (RenderedAtDestination && SegmentCompletedEarly)
        {
            return false;
        }
        if (RenderedAtDestination &&
                (MillisecondsSinceDestinationChanged < 600 || !RouteContinues))
        {
            return false;
        }

        if (MillisecondsSinceDestinationChanged < 600)
        {
            return true;
        }

        // Bridge a late server-tick boundary only when the player's route
        // continues beyond this segment. On the final segment the visible
        // tween is already complete, and hidden-actor movement must not keep a
        // run request alive while the custom model stands on its destination.
        return RouteContinues && MillisecondsSinceDestinationChanged <
                600 + MOVEMENT_SELECTION_GRACE_MILLISECONDS;
    }

    private static boolean DoesRouteContinue(
            LocalPoint Destination,
            LocalPoint RouteDestination)
    {
        return Destination != null &&
                RouteDestination != null &&
                RouteDestination.getWorldView() == Destination.getWorldView() &&
                !RouteDestination.equals(Destination);
    }

    static boolean IsRenderedAtDestination(
            LocalPoint RenderedPosition,
            LocalPoint Destination)
    {
        return RenderedPosition != null &&
                Destination != null &&
                RenderedPosition.getWorldView() == Destination.getWorldView() &&
                RenderedPosition.equals(Destination);
    }

    static boolean ShouldHoldStableStationaryFrame(
            boolean Stationary,
            boolean IdleRequest,
            boolean UseNativeCompositeModel,
            boolean CustomAnimationAvailable,
            boolean NativeAtRenderedLocation,
            int RequestedAnimation,
            int NativePoseAnimation)
    {
        // A native action/spot/forced model is authoritative. Ordinary native
        // locomotion is not: once the visible tween has stopped, substituting
        // the hidden actor's still-moving pose makes it run in place.
        return Stationary &&
                IdleRequest &&
                !UseNativeCompositeModel &&
                !CustomAnimationAvailable &&
                (!NativeAtRenderedLocation ||
                        NativePoseAnimation != RequestedAnimation);
    }

    static boolean ShouldStopAtRenderedDestination(
            int MillisecondsSinceDestinationChanged,
            LocalPoint RenderedPosition,
            LocalPoint Destination,
            LocalPoint RouteDestination)
    {
        if (!IsRenderedAtDestination(RenderedPosition, Destination))
        {
            return false;
        }

        // Faster custom moves stop as soon as they arrive. A normal continuing
        // segment retains the short late-tick bridge to avoid an idle flash
        // between adjacent run segments.
        return MillisecondsSinceDestinationChanged < 600 ||
                !DoesRouteContinue(Destination, RouteDestination);
    }

    static boolean ShouldHoldContinuingRouteBoundary(
            int MillisecondsSinceDestinationChanged,
            LocalPoint RenderedPosition,
            LocalPoint Destination,
            LocalPoint RouteDestination,
            boolean UseNativeCompositeModel)
    {
        return !UseNativeCompositeModel &&
                MillisecondsSinceDestinationChanged >= 600 &&
                MillisecondsSinceDestinationChanged <
                        600 + MOVEMENT_SELECTION_GRACE_MILLISECONDS &&
                IsRenderedAtDestination(RenderedPosition, Destination) &&
                DoesRouteContinue(Destination, RouteDestination);
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
                if (IsAcceleratedDestinationChange(NextLerpPosition, RequestedLerpPoint))
                {
                    MarkAcceleratedMovement(RequestedLerpPoint);
                }

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
                int OrientationToTest = getOrientationBetweenPoints(
                        LastTrueTilePosition.getX(),
                        LastTrueTilePosition.getY(),
                        MovementPatternTestEnd.getX(),
                        MovementPatternTestEnd.getY(),
                        270,
                        CurrentOrientation);

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
    private boolean bUseTrueLocationGraceThisFrame = false;
    private boolean bUseNativeMotionThisFrame = false;
    private boolean bUseNativeCompositeModelThisFrame = false;
    private void UpdateAnimationSelection()
    {
        bStationaryThisFrame = false;
        bPlayingKillCelebration = false;


        // Override all animations
        //if (devConfig.DebugAnimation() != 0)
        //{
        //    CurrentAnimationRequest = AnimationRequestMoveset.GetDefaultIdleMoveAnimationRequest(config);
        //    CurrentAnimationRequest.AnimationToPlay = devConfig.DebugAnimation();
        //}
        //else

        // Currently moving
        if (ShouldSelectMovingAnimation(
                MillisecondsSinceTileChange,
                LastLerpPosition,
                NextLerpPosition,
                IsPlayerOwner() ? client.getLocalDestinationLocation() : null,
                NewLocalPointToDraw,
                bCurrentSegmentCompletedEarly))
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

            if (ShouldUsePluginLeap(
                    config.DisableLeapingAnimations(),
                    bCurrentlyWooxWalking && config.AllowWooxWalkDetection(),
                    bIsDefaultHumanAnimationSet))
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
            else if (ShouldUsePluginLeap(
                    config.DisableLeapingAnimations(),
                    config.AlwaysHoppingMode() || FramesSinceIdle > config.TickPerfectMovesUntilJumping(),
                    bIsDefaultHumanAnimationSet))
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
            bStationaryThisFrame = true;
            TargetOrientation = CurrentOrientation;
            bWooxWalkBroken = true;
            FramesSinceIdle = 0;
        }
        // Not moving
        else
        {
            SelectIdleAnimation();
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

        if (bStationaryThisFrame)
        {
            TargetOrientation = CurrentOrientation;
        }
        else if (currentTarget != null)
        {
            LocalPoint TargetLocation = currentTarget.getLocalLocation();
            if (TargetLocation != null)
            {
                TargetOrientation = getOrientationBetweenPoints(
                        NewLocalPointToDraw.getX(),
                        NewLocalPointToDraw.getY(),
                        TargetLocation.getX(),
                        TargetLocation.getY(),
                        90,
                        CurrentOrientation);
            }
        }
        else if (Owner.getAnimation() != -1 || // Not walking animation, face towards wherever the client is
                config.OnlyEnabledInCombat())
        {
            // Target is toward the real player now
            TargetOrientation = Owner.getCurrentOrientation();
        }
        // Face towards where you are moving
        else if (!LastLerpPosition.equals(NextLerpPosition))
        {
            TargetOrientation = getOrientationBetweenPoints(
                    LastLerpPosition.getX(),
                    LastLerpPosition.getY(),
                    NextLerpPosition.getX(),
                    NextLerpPosition.getY(),
                    90,
                    CurrentOrientation);
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

    private void SelectIdleAnimation()
    {
        CurrentAnimationRequest = AnimationRequestMoveset.GetDefaultIdleMoveAnimationRequest(config);
        CurrentAnimationRequest.bUseLinearTween = true;
        CurrentAnimationRequest.MovementSpeedMultiplier = 1.0;
        CurrentAnimationRequest.AnimationSpeed = 1;
        CurrentAnimationRequest.StartingFrame = 0;
        CurrentAnimationRequest.AnimationToPlay = OldAnimationSet.IdlePoseAnimation;
        bStationaryThisFrame = true;
        TargetOrientation = CurrentOrientation;

        bWooxWalkBroken = true;
        FramesSinceIdle = 0;

        // We can transition to render the owner.
        if (bAttemptToRenderOwner)
        {
            bShouldRenderOwner = true;
        }
    }

    static boolean ShouldUsePluginLeap(
            boolean DisableLeapingAnimations,
            boolean LeapRequested,
            boolean IsDefaultHumanAnimationSet)
    {
        return !DisableLeapingAnimations && LeapRequested && IsDefaultHumanAnimationSet;
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

    private void HideCustomModels()
    {
        bLastRenderedFrameSafeForStationaryHold = false;
        bHoldingStableStationaryFrame = false;
        bHoldContinuingRouteBoundaryThisFrame = false;
        bHoldingContinuingRouteBoundaryFrame = false;
        bCurrentSegmentCompletedEarly = false;
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
        if (Owner != null)
        {
            CurrentOrientation = Owner.getCurrentOrientation();
            TargetOrientation = CurrentOrientation;
        }
        ClearNativeMotion();
        bAcceleratedMovementActive = false;
        AcceleratedMovementDestination = null;
        bSnapNativeMotionToOwner = false;
        AcceleratedMovementObservationCount = 0;
        HideCustomModels();
        return false;
    }

    boolean HoldLastRenderedFrame()
    {
        if (bShouldRenderOwner || Owner == null || Model == null ||
                !Model.isActive() || Model.getModel() == null || Model.getLocation() == null)
        {
            return FailOpenToOwner();
        }

        int CurrentGameCycle = client.getGameCycle();
        int OwnerWorldViewId = Owner.getWorldView().getId();
        if (LastSuccessfulRenderGameCycle < 0 ||
                CurrentGameCycle < LastSuccessfulRenderGameCycle ||
                CurrentGameCycle - LastSuccessfulRenderGameCycle > LAST_GOOD_RENDER_HOLD_CYCLES ||
                Model.getLocation().getWorldView() != OwnerWorldViewId)
        {
            return FailOpenToOwner();
        }

        bShouldRenderOwner = false;
        return true;
    }

    private boolean HoldStableStationaryFrame(
            LocalPoint RenderLocation,
            int RenderOrientation,
            PlayerAppearanceKey CurrentAppearance)
    {
        if (RenderLocation == null ||
                CurrentAppearance == null ||
                LastRenderedAppearance == null ||
                !CurrentAppearance.equals(LastRenderedAppearance) ||
                Model == null ||
                !Model.isActive() ||
                Model.getModel() == null)
        {
            return false;
        }

        int RequestedAnimationId = CurrentAnimationRequest == null
                ? NO_ANIMATION
                : CurrentAnimationRequest.AnimationToPlay;
        if (StableStationaryModel != null &&
                StableStationaryAppearance != null &&
                CurrentAppearance.equals(StableStationaryAppearance) &&
                StableStationaryAnimationId == RequestedAnimationId)
        {
            Model.setModel(StableStationaryModel);
            LastRenderedAnimationHeightOffset =
                    StableStationaryAnimationHeightOffset;
        }
        else if (!bLastRenderedFrameSafeForStationaryHold)
        {
            return false;
        }
        else if (!bHoldingStableStationaryFrame)
        {
            Model DetachedModel = CreateDetachedRenderableCopy(Model.getModel());
            if (DetachedModel == null)
            {
                return false;
            }
            Model.setModel(DetachedModel);
        }

        // Keep the last correctly composed mesh, but move its render anchor to
        // the completed tween. Re-reading Owner.getModel() here would animate
        // the hidden actor's remaining path at this stationary location.
        Model.setLocation(RenderLocation, Owner.getWorldView().getPlane());
        Model.setOrientation(RenderOrientation);
        int FootprintHeight = Perspective.getFootprintTileHeight(
                client,
                RenderLocation,
                Owner.getWorldView().getPlane(),
                Owner.getFootprintSize());
        Model.setZ(FootprintHeight - LastRenderedAnimationHeightOffset);

        bShouldRenderOwner = false;
        bCustomLocomotionModelLastFrame = false;
        bLastRenderedFrameSafeForStationaryHold = true;
        bHoldingStableStationaryFrame = true;
        bHoldingContinuingRouteBoundaryFrame = false;
        LastSuccessfulRenderGameCycle = client.getGameCycle();
        RecordLastRenderedLocation(RenderLocation);
        UpdateCamera();
        return true;
    }

    private boolean HoldContinuingRouteBoundaryFrame(
            LocalPoint RenderLocation,
            int RenderOrientation,
            PlayerAppearanceKey CurrentAppearance)
    {
        if (RenderLocation == null ||
                CurrentAppearance == null ||
                LastRenderedAppearance == null ||
                !CurrentAppearance.equals(LastRenderedAppearance) ||
                !bLastRenderedFrameSafeForStationaryHold ||
                Model == null ||
                !Model.isActive() ||
                Model.getModel() == null)
        {
            return false;
        }

        if (!bHoldingContinuingRouteBoundaryFrame)
        {
            Model DetachedModel = CreateDetachedRenderableCopy(Model.getModel());
            if (DetachedModel == null)
            {
                return false;
            }
            Model.setModel(DetachedModel);
        }

        // Preserve the locomotion phase through a slightly late server tick,
        // but do not advance its legs while its rendered velocity is zero.
        Model.setLocation(RenderLocation, Owner.getWorldView().getPlane());
        Model.setOrientation(RenderOrientation);
        int FootprintHeight = Perspective.getFootprintTileHeight(
                client,
                RenderLocation,
                Owner.getWorldView().getPlane(),
                Owner.getFootprintSize());
        Model.setZ(FootprintHeight - LastRenderedAnimationHeightOffset);

        bShouldRenderOwner = false;
        bHoldingStableStationaryFrame = false;
        bHoldingContinuingRouteBoundaryFrame = true;
        LastSuccessfulRenderGameCycle = client.getGameCycle();
        RecordLastRenderedLocation(RenderLocation);
        UpdateCamera();
        return true;
    }

    private void ClearStableStationaryModel()
    {
        StableStationaryModel = null;
        StableStationaryAppearance = null;
        StableStationaryAnimationId = NO_ANIMATION;
        StableStationaryAnimationHeightOffset = 0;
        bLastRenderedFrameSafeForStationaryHold = false;
        bHoldingStableStationaryFrame = false;
        bHoldContinuingRouteBoundaryThisFrame = false;
        bHoldingContinuingRouteBoundaryFrame = false;
        bCurrentSegmentCompletedEarly = false;
    }

    private Model CreateDetachedRenderableCopy(Model SourceModel)
    {
        if (SourceModel == null)
        {
            return null;
        }

        CachedModelPose Pose = CachedModelPose.From(SourceModel);
        if (Pose == null)
        {
            return null;
        }

        Model DetachedModel = client.mergeModels(SourceModel);
        if (!CachedAnimationFramesBuilder.IsDetachedCompatibleModel(
                DetachedModel,
                SourceModel,
                Pose))
        {
            return null;
        }

        DetachedModel.calculateBoundsCylinder();
        if (DetachedModel.getModelHeight() <= 0 && Pose.ModelHeight > 0)
        {
            DetachedModel.setModelHeight(Pose.ModelHeight);
        }
        return DetachedModel;
    }

    private void CacheStableStationaryModel(
            Model RenderedModel,
            PlayerAppearanceKey Appearance,
            int AnimationId,
            int AnimationHeightOffset)
    {
        if (RenderedModel == null || Appearance == null ||
                (StableStationaryModel != null &&
                        Appearance.equals(StableStationaryAppearance) &&
                        StableStationaryAnimationId == AnimationId))
        {
            return;
        }

        // Native clones carry renderer offsets belonging to the current scene
        // frame. Retain only an array-independent, reusable mesh.
        Model DetachedModel = CreateDetachedRenderableCopy(RenderedModel);
        if (DetachedModel == null)
        {
            return;
        }

        StableStationaryModel = DetachedModel;
        StableStationaryAppearance = Appearance;
        StableStationaryAnimationId = AnimationId;
        StableStationaryAnimationHeightOffset = AnimationHeightOffset;
    }

    private boolean AdvanceCustomAnimation(int ElapsedClientCycles)
    {
        if (CurrentAnimationRequest == null || AnimController == null)
        {
            return false;
        }

        if (CurrentAnimationIDPlaying != CurrentAnimationRequest.AnimationToPlay ||
                CurrentAnimationStartingFrame != CurrentAnimationRequest.StartingFrame ||
                CurrentAnimationEndingFrame != CurrentAnimationRequest.EndingFrame ||
                CurrentAnimationSpeed != CurrentAnimationRequest.AnimationSpeed)
        {
            Animation RequestedAnimation = client.loadAnimation(CurrentAnimationRequest.AnimationToPlay);
            if (RequestedAnimation == null)
            {
                // Keep the previous valid controller and retry next frame. A
                // transient cache miss must not expose an unanimated T-pose.
                int CurrentGameCycle = client.getGameCycle();
                if (AnimationLoadFailureStartGameCycle < 0)
                {
                    AnimationLoadFailureStartGameCycle = CurrentGameCycle;
                }
                return AnimController.getAnimation() != null &&
                        CurrentGameCycle - AnimationLoadFailureStartGameCycle <= LAST_GOOD_RENDER_HOLD_CYCLES;
            }

            AnimationLoadFailureStartGameCycle = -1;
            CurrentAnimationIDPlaying = CurrentAnimationRequest.AnimationToPlay;
            CurrentAnimationStartingFrame = CurrentAnimationRequest.StartingFrame;
            CurrentAnimationEndingFrame = CurrentAnimationRequest.EndingFrame;
            CurrentAnimationSpeed = CurrentAnimationRequest.AnimationSpeed;
            AnimController.setAnimation(RequestedAnimation);
            AnimController.setFrame(CurrentAnimationStartingFrame);
            CurrentAnimationElapsedTicks = 0;
            // Preserve the configured first frame for one render. Ticking a
            // newly selected locomotion sequence immediately can expose an
            // arbitrary mid-stride pose at an action-to-movement handoff.
            return true;
        }

        if (AnimController.getAnimation() == null || ElapsedClientCycles <= 0)
        {
            return AnimController.getAnimation() != null;
        }

        int CurrentFrame = AnimController.getFrame();
        if (CurrentFrame < CurrentAnimationStartingFrame)
        {
            Animation PlayingAnimation = AnimController.getAnimation();
            // setFrame() alone leaves AnimationController's private
            // interpolation clock untouched. Reset the controller so our
            // mirrored elapsed-tick key cannot drift after a non-zero start
            // frame loops.
            AnimController.setAnimation(PlayingAnimation);
            AnimController.setFrame(CurrentAnimationStartingFrame);
            CurrentFrame = CurrentAnimationStartingFrame;
            CurrentAnimationElapsedTicks = 0;
        }
        if (CurrentFrame >= CurrentAnimationEndingFrame)
        {
            AnimController.setFrame(CurrentAnimationEndingFrame);
            return true;
        }

        Animation PlayingAnimation = AnimController.getAnimation();
        int TicksToAdvance = ElapsedClientCycles * CurrentAnimationSpeed;
        int PreviousElapsedTicks = CurrentAnimationElapsedTicks;
        AnimController.tick(TicksToAdvance);
        if (AnimController.getAnimation() != null)
        {
            CurrentAnimationElapsedTicks = CalculateAnimationElapsedTicks(
                    PlayingAnimation,
                    CurrentFrame,
                    PreviousElapsedTicks,
                    TicksToAdvance,
                    AnimController.getFrame());
            if (AnimController.getFrame() < CurrentAnimationStartingFrame)
            {
                AnimController.setAnimation(PlayingAnimation);
                AnimController.setFrame(CurrentAnimationStartingFrame);
                CurrentAnimationElapsedTicks = 0;
            }
            else if (AnimController.getFrame() > CurrentAnimationEndingFrame)
            {
                AnimController.setFrame(CurrentAnimationEndingFrame);
                CurrentAnimationElapsedTicks = 0;
            }
        }
        else
        {
            CurrentAnimationElapsedTicks = 0;
        }
        return AnimController.getAnimation() != null;
    }

    static int CalculateAnimationElapsedTicks(
            Animation Animation,
            int StartingFrame,
            int StartingElapsedTicks,
            int TicksToAdvance,
            int ResultFrame)
    {
        if (Animation == null || Animation.isMayaAnim())
        {
            return 0;
        }

        int[] FrameLengths = Animation.getFrameLengths();
        if (FrameLengths == null || StartingFrame < 0 ||
                StartingFrame >= FrameLengths.length || ResultFrame < 0 ||
                ResultFrame >= FrameLengths.length)
        {
            return 0;
        }

        int Frame = StartingFrame;
        int ElapsedTicks = Math.max(0, StartingElapsedTicks) +
                Math.max(0, TicksToAdvance);
        int TransitionsWithoutProgress = 0;
        while (ElapsedTicks > FrameLengths[Frame])
        {
            int FrameLength = FrameLengths[Frame];
            if (FrameLength < 0)
            {
                return 0;
            }

            ElapsedTicks -= FrameLength;
            if (FrameLength == 0)
            {
                if (++TransitionsWithoutProgress > FrameLengths.length)
                {
                    // The controller would be trapped in an all-zero loop.
                    // Fail to the discrete first state instead of hanging the
                    // client thread while looking up a cached pose.
                    return 0;
                }
            }
            else
            {
                TransitionsWithoutProgress = 0;
            }

            ++Frame;
            if (Frame >= FrameLengths.length)
            {
                Frame -= Animation.getFrameStep();
                if (Frame < 0 || Frame >= FrameLengths.length)
                {
                    Frame = 0;
                }
            }
        }

        return Frame == ResultFrame ? ElapsedTicks : 0;
    }

    private void InvalidateCustomAnimation()
    {
        CurrentAnimationIDPlaying = NO_ANIMATION;
        CurrentAnimationStartingFrame = NO_ANIMATION;
        CurrentAnimationEndingFrame = NO_ANIMATION;
        CurrentAnimationSpeed = NO_ANIMATION;
        AnimationLoadFailureStartGameCycle = -1;
        CurrentAnimationElapsedTicks = 0;
    }

    static LocalPoint ApplyNativeMotionDelta(
            LocalPoint RenderAnchor,
            LocalPoint NativeAnchor,
            LocalPoint CurrentNativeLocation)
    {
        if (RenderAnchor == null || NativeAnchor == null || CurrentNativeLocation == null ||
                NativeAnchor.getWorldView() != CurrentNativeLocation.getWorldView() ||
                RenderAnchor.getWorldView() != CurrentNativeLocation.getWorldView())
        {
            return CurrentNativeLocation;
        }

        return new LocalPoint(
                RenderAnchor.getX() + CurrentNativeLocation.getX() - NativeAnchor.getX(),
                RenderAnchor.getY() + CurrentNativeLocation.getY() - NativeAnchor.getY(),
                RenderAnchor.getWorldView());
    }

    static LocalPoint SelectNativeMotionAnchor(
            LocalPoint PendingAnchor,
            LocalPoint CurrentNativeLocation)
    {
        if (PendingAnchor == null || CurrentNativeLocation == null ||
                PendingAnchor.getWorldView() != CurrentNativeLocation.getWorldView())
        {
            return CurrentNativeLocation;
        }
        return PendingAnchor;
    }

    static LocalPoint ApplySceneLocalOffset(LocalPoint TileLocation, int OffsetX, int OffsetY)
    {
        if (TileLocation == null)
        {
            return null;
        }
        return new LocalPoint(
                TileLocation.getX() + OffsetX,
                TileLocation.getY() + OffsetY,
                TileLocation.getWorldView());
    }

    private void RecordLastRenderedLocation(LocalPoint RenderLocation)
    {
        LastRenderedWorldPoint = WorldPoint.fromLocal(client, RenderLocation);
        LocalPoint TileLocation = LastRenderedWorldPoint == null
                ? null
                : LocalPoint.fromWorld(client, LastRenderedWorldPoint);
        if (TileLocation == null || TileLocation.getWorldView() != RenderLocation.getWorldView())
        {
            LastRenderedWorldOffsetX = 0;
            LastRenderedWorldOffsetY = 0;
            return;
        }
        LastRenderedWorldOffsetX = RenderLocation.getX() - TileLocation.getX();
        LastRenderedWorldOffsetY = RenderLocation.getY() - TileLocation.getY();
    }

    private void BeginNativeMotion(LocalPoint OwnerLocation)
    {
        if (bUsingNativeMotion)
        {
            return;
        }

        LocalPoint LastDisplayedLocation = Model != null && Model.isActive()
                ? Model.getLocation()
                : NewLocalPointToDraw;
        if (LastDisplayedLocation == null || OwnerLocation == null ||
                LastDisplayedLocation.getWorldView() != OwnerLocation.getWorldView())
        {
            LastDisplayedLocation = OwnerLocation;
        }

        NativeMotionAnchor = SelectNativeMotionAnchor(
                PendingNativeMotionAnchor,
                OwnerLocation);
        PendingNativeMotionAnchor = null;
        RenderMotionAnchor = LastDisplayedLocation;
        LastNativeMotionRenderLocation = LastDisplayedLocation;
        bUsingNativeMotion = true;
    }

    private void ApplyPendingNativeMotionSnap(LocalPoint OwnerLocation)
    {
        if (!bSnapNativeMotionToOwner || OwnerLocation == null)
        {
            return;
        }

        NativeMotionAnchor = OwnerLocation;
        RenderMotionAnchor = OwnerLocation;
        bSnapNativeMotionToOwner = false;
    }

    private void RebaseFromNativeMotion()
    {
        LocalPoint ResumeLocation = LastNativeMotionRenderLocation;
        LocalPoint OwnerLocation = Owner.getLocalLocation();
        if (ResumeLocation == null || OwnerLocation == null ||
                ResumeLocation.getWorldView() != OwnerLocation.getWorldView())
        {
            ResumeLocation = OwnerLocation;
        }

        if (ResumeLocation != null)
        {
            LocalPoint Destination = CurrentWorldPoint == null
                    ? null
                    : LocalPoint.fromWorld(client, CurrentWorldPoint);
            if (Destination == null || Destination.getWorldView() != ResumeLocation.getWorldView())
            {
                Destination = OwnerLocation;
            }

            LastLerpPosition = ResumeLocation;
            NewLocalPointToDraw = ResumeLocation;
            NextLerpPosition = Destination == null ? ResumeLocation : Destination;
            LastLerpPositionWorldPoint = WorldPoint.fromLocal(client, ResumeLocation);
            NextLerpPositionWorldPoint = CurrentWorldPoint;
            ResetTileMovementTimer();
        }

        ClearNativeMotion();
    }

    private void ClearNativeMotion()
    {
        bUsingNativeMotion = false;
        NativeMotionAnchor = null;
        PendingNativeMotionAnchor = null;
        RenderMotionAnchor = null;
        LastNativeMotionRenderLocation = null;
        bSnapNativeMotionToOwner = false;
    }

    private void PrepareNativeMotionState()
    {
        int OwnerAnimation = Owner.getAnimation();
        boolean UseNativeActionModel = ShouldUseNativeActionModel(OwnerAnimation);
        boolean UseAcceleratedMovement = IsAcceleratedMovementStillActive();
        boolean UseNativeSpotAnimationModel = HasActiveSpotAnimation();

        bShouldUseTrueLocationOrientation = OwnerAnimation != NO_ANIMATION &&
                currentTarget == null &&
                UniqueAnimationLocationAndOrientationExceptionList.contains(OwnerAnimation);
        bUseTrueLocationGraceThisFrame = bShouldUseTrueLocationOrientation ||
                (CurrentTime - LastTimeUniqueAnimationLocationOrientationWasUsed) < 600;
        bUseNativeMotionThisFrame = UseNativeActionModel ||
                UseAcceleratedMovement ||
                bUseTrueLocationGraceThisFrame;
        bUseNativeCompositeModelThisFrame = UseNativeActionModel ||
                UseAcceleratedMovement ||
                UseNativeSpotAnimationModel;
        if (bUseNativeCompositeModelThisFrame)
        {
            LastNativeCompositeModelGameCycle = client.getGameCycle();
        }

        LocalPoint OwnerLocation = Owner.getLocalLocation();
        if (bUseNativeMotionThisFrame)
        {
            BeginNativeMotion(OwnerLocation);
            ApplyPendingNativeMotionSnap(OwnerLocation);
        }
        else if (bUsingNativeMotion)
        {
            // Rebase before choosing this frame's locomotion request. This keeps
            // the request, tween, and rendered position on the same state epoch.
            RebaseFromNativeMotion();
        }
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
            CurrentOrientation = Owner.getCurrentOrientation();
            TargetOrientation = CurrentOrientation;
            ClearNativeMotion();
            bCustomLocomotionModelLastFrame = false;
            HideCustomModels();
            return true;
        }

        LocalPoint OwnerLocation = Owner.getLocalLocation();

        LocalPoint RenderLocation;
        int RenderOrientation;
        if (bUseTrueLocationGraceThisFrame)
        {
            RenderLocation = OwnerLocation;
            RenderOrientation = Owner.getCurrentOrientation();
            CurrentOrientation = RenderOrientation;
            TargetOrientation = RenderOrientation;

        }
        else if (bUseNativeMotionThisFrame)
        {
            RenderLocation = ApplyNativeMotionDelta(
                    RenderMotionAnchor,
                    NativeMotionAnchor,
                    OwnerLocation);
            RenderOrientation = Owner.getCurrentOrientation();
            CurrentOrientation = RenderOrientation;
            TargetOrientation = RenderOrientation;
        }
        else
        {
            RenderLocation = NewLocalPointToDraw;
            int OrientationStep = (int) Math.round(
                    CurrentAnimationRequest.OrientationSpeed *
                            (CurrentFrameDelta / 16.667));
            CurrentOrientation = MoveOrientationTowards(
                    CurrentOrientation,
                    TargetOrientation,
                    OrientationStep);
            RenderOrientation = CurrentOrientation;
        }

        if (RenderLocation == null || Model == null || AnimController == null)
        {
            return HoldLastRenderedFrame();
        }

        PlayerAppearanceKey CurrentAppearance = GetCurrentPlayerAppearance();
        if (bHoldContinuingRouteBoundaryThisFrame &&
                HoldContinuingRouteBoundaryFrame(
                        RenderLocation,
                        RenderOrientation,
                        CurrentAppearance))
        {
            return true;
        }

        Animation RequestedAnimation = CurrentAnimationRequest == null
                ? null
                : client.loadAnimation(CurrentAnimationRequest.AnimationToPlay);
        boolean RequestedAnimationInterpolated = RequestedAnimation != null &&
                IsAnimationInterpolated(RequestedAnimation);
        CachedAnimationFrames CachedFrames = RequestedAnimation == null ||
                CurrentAppearance == null ||
                !CurrentAppearance.equals(NeutralOwnerModelAppearance)
                ? null
                : NeutralAnimationFrameCache.get(RequestedAnimation.getId());
        boolean HasMatchingNeutralFrames = CachedFrames != null &&
                CachedFrames.Interpolated == RequestedAnimationInterpolated;
        if (!HasMatchingNeutralFrames)
        {
            RequestNeutralAnimationFrames(CurrentAppearance, RequestedAnimation);
        }

        boolean UseCustomLocomotionModel = ShouldUseCustomLocomotionModel(
                bUseNativeCompositeModelThisFrame,
                HasMatchingNeutralFrames,
                RequestedAnimation != null,
                HasHandItemOverride(RequestedAnimation));

        // Native actions and cache-refresh fallbacks do not advance locomotion
        // behind the model being displayed. Re-enter on the configured first
        // frame so casts, flinches, forced motion, and equipment changes cannot
        // reveal an arbitrary mid-stride pose.
        if (UseCustomLocomotionModel && !bCustomLocomotionModelLastFrame)
        {
            InvalidateCustomAnimation();
        }
        boolean CustomAnimationAvailable = UseCustomLocomotionModel &&
                AdvanceCustomAnimation(ElapsedClientCycles);
        if (!CustomAnimationAvailable)
        {
            UseCustomLocomotionModel = false;
        }

        Model OwnerModel = null;
        Model RenderedModel = null;
        if (CustomAnimationAvailable)
        {
            RenderedModel = CachedFrames.CreateModel(
                    client,
                    AnimController.getFrame(),
                    CurrentAnimationElapsedTicks);
            if (RenderedModel == null)
            {
                // A malformed range or an animation-definition change must
                // fail back to the game's correctly composed model.
                UseCustomLocomotionModel = false;
                CustomAnimationAvailable = false;
                RemoveNeutralAnimationFrames(RequestedAnimation.getId());
                RequestNeutralAnimationFrames(CurrentAppearance, RequestedAnimation);
            }
        }

        int RequestedAnimationId = CurrentAnimationRequest == null
                ? NO_ANIMATION
                : CurrentAnimationRequest.AnimationToPlay;
        boolean IdleRequest = RequestedAnimationId != NO_ANIMATION &&
                RequestedAnimationId == OldAnimationSet.IdlePoseAnimation;
        LocalPoint OwnerLocationForFallback = Owner.getLocalLocation();
        boolean NativeAtRenderedLocation = OwnerLocationForFallback != null &&
                OwnerLocationForFallback.getWorldView() == RenderLocation.getWorldView() &&
                OwnerLocationForFallback.equals(RenderLocation);
        int NativePoseAnimation = Owner.getPoseAnimation();
        if (ShouldHoldStableStationaryFrame(
                bStationaryThisFrame,
                IdleRequest,
                bUseNativeCompositeModelThisFrame,
                CustomAnimationAvailable,
                NativeAtRenderedLocation,
                RequestedAnimationId,
                NativePoseAnimation) &&
                HoldStableStationaryFrame(
                        RenderLocation,
                        RenderOrientation,
                        CurrentAppearance))
        {
            return true;
        }

        if (!UseCustomLocomotionModel)
        {
            OwnerModel = Owner.getModel();
            if (OwnerModel == null)
            {
                return HoldLastRenderedFrame();
            }

            // Native action/spot/forced frames are already composed by the
            // game. They receive no second skeletal transform here.
            RenderedModel = client.mergeModels(OwnerModel);
        }

        if (RenderedModel == null)
        {
            return HoldLastRenderedFrame();
        }

        if (!UseCustomLocomotionModel)
        {
            // Preserve the native model's renderer metadata only for the
            // native clone. Cached custom poses intentionally carry no scene
            // buffer offsets from the shared player-model scratch.
            RenderedModel.setModelHeight(OwnerModel.getModelHeight());
            RenderedModel.setUvBufferOffset(OwnerModel.getUvBufferOffset());
            RenderedModel.setBufferOffset(OwnerModel.getBufferOffset());
            RenderedModel.setSceneId(OwnerModel.getSceneId());
        }

        Model.setLocation(RenderLocation, Owner.getWorldView().getPlane());
        Model.setOrientation(RenderOrientation);
        Model.setModel(RenderedModel);

        int FootprintHeight = Perspective.getFootprintTileHeight(
                client,
                RenderLocation,
                Owner.getWorldView().getPlane(),
                Owner.getFootprintSize());
        int RenderedAnimationHeightOffset = UseCustomLocomotionModel
                ? OldAnimationHeight
                : Owner.getAnimationHeightOffset();
        FootprintHeight -= RenderedAnimationHeightOffset;
        Model.setZ(FootprintHeight);

        if (!Model.isActive())
        {
            Model.setActive(true);
        }

        bShouldRenderOwner = false;
        bCustomLocomotionModelLastFrame = UseCustomLocomotionModel;
        boolean OrdinaryLocomotionFrame = !bStationaryThisFrame || IdleRequest;
        bLastRenderedFrameSafeForStationaryHold =
                !bUseNativeCompositeModelThisFrame &&
                        OrdinaryLocomotionFrame &&
                        (UseCustomLocomotionModel ||
                                NativePoseAnimation != NO_ANIMATION);
        bHoldingStableStationaryFrame = false;
        bHoldingContinuingRouteBoundaryFrame = false;
        LastSuccessfulRenderGameCycle = client.getGameCycle();
        LastRenderedAppearance = CurrentAppearance;
        LastRenderedAnimationHeightOffset = RenderedAnimationHeightOffset;
        int RequestedAnimationIdForCache = CurrentAnimationRequest == null
                ? NO_ANIMATION
                : CurrentAnimationRequest.AnimationToPlay;
        boolean NativeIdlePose = NativePoseAnimation ==
                OldAnimationSet.IdlePoseAnimation;
        if (bStationaryThisFrame &&
                !bUseNativeCompositeModelThisFrame &&
                RequestedAnimationIdForCache != NO_ANIMATION &&
                RequestedAnimationIdForCache == OldAnimationSet.IdlePoseAnimation &&
                CurrentAppearance != null &&
                (UseCustomLocomotionModel ||
                        (NativeAtRenderedLocation && NativeIdlePose)))
        {
            CacheStableStationaryModel(
                    RenderedModel,
                    CurrentAppearance,
                    RequestedAnimationIdForCache,
                    RenderedAnimationHeightOffset);
        }
        RecordLastRenderedLocation(RenderLocation);
        if (bShouldUseTrueLocationOrientation)
        {
            LastTimeUniqueAnimationLocationOrientationWasUsed = CurrentTime;
        }
        if (bUseNativeMotionThisFrame)
        {
            LastNativeMotionRenderLocation = RenderLocation;
            NewLocalPointToDraw = RenderLocation;
        }
        UpdateCamera();
        return true;
    }

    public boolean Update()
    {
        if (Owner == null || Model == null)
        {
            return FailOpenToOwner();
        }

        // The live actor is briefly suppressed only while a neutral appearance
        // cache is crossing one native actor update. Keep the already composed
        // frame untouched during that interval (and for the remainder of an
        // appearance-race cycle): reading selectors or Owner.getModel() would
        // expose intentionally neutral or cross-appearance state.
        if (PendingNeutralCaptureState != null ||
                NeutralCaptureHoldGameCycle == client.getGameCycle())
        {
            return HoldLastRenderedFrame();
        }

        UpdateFrameTimer();
        UpdateOldIdleAnimations();
        UpdateTargetStatus();
        ObserveOwnerMovement();

        if (!UpdateTrueTileLocation())
        {
            return HoldLastRenderedFrame();
        }

        UpdateLerpDestinations();
        if (LastLerpPosition == null || NextLerpPosition == null)
        {
            return HoldLastRenderedFrame();
        }

        PrepareNativeMotionState();
        UpdateAnimationSelection();
        UpdateMovementType();
        ApplyTweening();
        LocalPoint RouteDestination = IsPlayerOwner()
                ? client.getLocalDestinationLocation()
                : null;
        bHoldContinuingRouteBoundaryThisFrame =
                !bStationaryThisFrame &&
                        !bUseNativeMotionThisFrame &&
                        ShouldHoldContinuingRouteBoundary(
                                MillisecondsSinceTileChange,
                                NewLocalPointToDraw,
                                NextLerpPosition,
                                RouteDestination,
                                bUseNativeCompositeModelThisFrame);
        if (!bStationaryThisFrame &&
                !bUseNativeMotionThisFrame &&
                ShouldStopAtRenderedDestination(
                        MillisecondsSinceTileChange,
                        NewLocalPointToDraw,
                        NextLerpPosition,
                        RouteDestination))
        {
            // Movement-speed multipliers can finish a tween before the server's
            // 600 ms segment clock. Switch pose state on the exact frame the
            // rendered model arrives instead of animating at zero velocity.
            if (MillisecondsSinceTileChange < 600)
            {
                bCurrentSegmentCompletedEarly = true;
            }
            bHoldContinuingRouteBoundaryThisFrame = false;
            SelectIdleAnimation();
        }
        return UpdateModelVisibleState();
    }
}
