package com.truetileanimationmovement;

import net.runelite.api.Animation;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.gameval.AnimationID;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class CustomMovementHandlerTest
{
    private static final class TestAnimation implements Animation
    {
        private final boolean maya;
        private final int duration;
        private final int frameStep;
        private final int[] frameLengths;

        private TestAnimation(
                boolean maya,
                int duration,
                int frameStep,
                int[] frameLengths)
        {
            this.maya = maya;
            this.duration = duration;
            this.frameStep = frameStep;
            this.frameLengths = frameLengths;
        }

        @Override
        public int getId()
        {
            return 1;
        }

        @Override
        public boolean isMayaAnim()
        {
            return maya;
        }

        @Override
        public int getNumFrames()
        {
            return frameLengths == null ? duration : frameLengths.length;
        }

        @Override
        public int getRestartMode()
        {
            return 0;
        }

        @Override
        public void setRestartMode(int restartMode)
        {
        }

        @Override
        public int getDuration()
        {
            return duration;
        }

        @Override
        public int getFrameStep()
        {
            return frameStep;
        }

        @Override
        public int[] getFrameLengths()
        {
            return frameLengths;
        }

        @Override
        public int getLeftHandItem()
        {
            return -1;
        }

        @Override
        public int getRightHandItem()
        {
            return -1;
        }
    }

    @Test
    public void frameDeltaIsMonotonicAndBounded()
    {
        assertEquals(0, CustomMovementHandler.CalculateFrameDeltaMilliseconds(0, 5_000_000L));
        assertEquals(0, CustomMovementHandler.CalculateFrameDeltaMilliseconds(8_000_000L, 5_000_000L));
        assertEquals(16, CustomMovementHandler.CalculateFrameDeltaMilliseconds(1_000_000L, 17_000_000L));
        assertEquals(100, CustomMovementHandler.CalculateFrameDeltaMilliseconds(1_000_000L, 501_000_000L));
    }

    @Test
    public void animationProgressUsesClientCyclesAndBoundsCatchUp()
    {
        assertEquals(0, CustomMovementHandler.CalculateElapsedClientCycles(-1, 100));
        assertEquals(0, CustomMovementHandler.CalculateElapsedClientCycles(100, 100));
        assertEquals(3, CustomMovementHandler.CalculateElapsedClientCycles(100, 103));
        assertEquals(100, CustomMovementHandler.CalculateElapsedClientCycles(100, 500));
    }

    @Test
    public void orientationTakesShortestPathAcrossWrapBoundary()
    {
        assertEquals(16, CustomMovementHandler.ShortestAngleDifference(2040, 8));
        assertEquals(-16, CustomMovementHandler.ShortestAngleDifference(8, 2040));
        assertEquals(4, CustomMovementHandler.MoveOrientationTowards(2040, 8, 12));
        assertEquals(2044, CustomMovementHandler.MoveOrientationTowards(8, 2040, 12));
    }

    @Test
    public void pointOrientationAlwaysNormalizesAfterOffset()
    {
        assertEquals(1536,
                CustomMovementHandler.getOrientationBetweenPoints(0, 0, -1, 0, 270));
        assertEquals(1024,
                CustomMovementHandler.getOrientationBetweenPoints(0, 0, 0, -1, 270));
    }

    @Test
    public void coincidentPointsPreserveExistingOrientation()
    {
        assertEquals(777,
                CustomMovementHandler.getOrientationBetweenPoints(10, 20, 10, 20, 90, 777));
    }

    @Test
    public void combatTargetFacingHonoursTheToggleAndConfiguredRadius()
    {
        assertTrue(CustomMovementHandler.ShouldUseCombatTargetFacing(
                true, 4, 4));
        assertFalse(CustomMovementHandler.ShouldUseCombatTargetFacing(
                true, 5, 4));
        assertFalse(CustomMovementHandler.ShouldUseCombatTargetFacing(
                false, 1, 4));
        assertTrue(CustomMovementHandler.ShouldUseCombatTargetFacing(
                true, 1, -5));
        assertFalse(CustomMovementHandler.ShouldUseCombatTargetFacing(
                true, 2, -5));
        assertTrue(CustomMovementHandler.ShouldUseCombatTargetFacing(
                true, 10, 50));

        TrueTileMovementConfig config = new TrueTileMovementConfig() { };
        assertTrue(config.CombatTargetFacingEnabled());
        assertEquals(4, config.CombatTargetFacingDistance());
    }

    @Test
    public void retargetingStartsAtLastRenderedPositionWhenSafe()
    {
        LocalPoint rendered = new LocalPoint(1_000, 2_000, -1);
        LocalPoint destination = new LocalPoint(1_128, 2_000, -1);
        assertSame(rendered, CustomMovementHandler.SelectTweenStart(rendered, destination));

        LocalPoint farAway = new LocalPoint(4_000, 5_000, -1);
        assertSame(destination, CustomMovementHandler.SelectTweenStart(farAway, destination));
    }

    @Test
    public void everyNativeActionWinsWhileItIsActive()
    {
        assertFalse(CustomMovementHandler.ShouldUseNativeActionModel(-1));
        assertTrue(CustomMovementHandler.ShouldUseNativeActionModel(AnimationID.HUMAN_EAT));
        assertTrue(CustomMovementHandler.ShouldUseNativeActionModel(AnimationID.HUMAN_UNARMEDBLOCK));
        assertTrue(CustomMovementHandler.ShouldUseNativeActionModel(AnimationID.HUMAN_CASTTELEPORT));
    }

    @Test
    public void customLocomotionRequiresANeutralModelWithoutCompositionOverrides()
    {
        assertTrue(CustomMovementHandler.ShouldUseCustomLocomotionModel(
                false, true, true, false));
        assertFalse(CustomMovementHandler.ShouldUseCustomLocomotionModel(
                true, true, true, false));
        assertFalse(CustomMovementHandler.ShouldUseCustomLocomotionModel(
                false, false, true, false));
        assertFalse(CustomMovementHandler.ShouldUseCustomLocomotionModel(
                false, true, false, false));
        assertFalse(CustomMovementHandler.ShouldUseCustomLocomotionModel(
                false, true, true, true));

        assertFalse(CustomMovementHandler.HasHandItemOverride(-1, -1));
        assertTrue(CustomMovementHandler.HasHandItemOverride(0, -1));
        assertTrue(CustomMovementHandler.HasHandItemOverride(-1, 0));
    }

    @Test
    public void neutralCaptureCompletesOnlyOnTheExactFollowingClientCycle()
    {
        assertFalse(CustomMovementHandler.IsNeutralCaptureCompletionCycle(100, 100));
        assertTrue(CustomMovementHandler.IsNeutralCaptureCompletionCycle(100, 101));
        assertFalse(CustomMovementHandler.IsNeutralCaptureCompletionCycle(100, 102));
        assertTrue(CustomMovementHandler.IsNeutralCaptureCompletionCycle(
                Integer.MAX_VALUE,
                Integer.MIN_VALUE));
    }

    @Test
    public void animationFrameKeysMatchAnimationControllerPacking()
    {
        assertEquals(7,
                CustomMovementHandler.BuildAnimationFrameKey(false, 7, 12));
        assertEquals(
                Integer.MIN_VALUE | (12 << 16) | 7,
                CustomMovementHandler.BuildAnimationFrameKey(true, 7, 12));
    }

    @Test
    public void animationStateCountIncludesEveryInterpolatedElapsedTick()
    {
        assertEquals(3,
                CustomMovementHandler.CountAnimationStates(
                        false, 0, new int[]{2, 3, 4}, false));
        assertEquals(12,
                CustomMovementHandler.CountAnimationStates(
                        false, 0, new int[]{2, 3, 4}, true));
        assertEquals(17,
                CustomMovementHandler.CountAnimationStates(
                        true, 17, null, true));
        assertEquals(0,
                CustomMovementHandler.CountAnimationStates(
                        false, 0, null, true));

        assertArrayEquals(
                new int[]{
                        Integer.MIN_VALUE,
                        Integer.MIN_VALUE | (1 << 16),
                        Integer.MIN_VALUE | (2 << 16),
                        Integer.MIN_VALUE | 1,
                        Integer.MIN_VALUE | (1 << 16) | 1},
                CustomMovementHandler.BuildAnimationFrameKeys(
                        false, 0, new int[]{2, 1}, true));
    }

    @Test
    public void mirroredClassicElapsedTicksFollowControllerLooping()
    {
        Animation animation = new TestAnimation(
                false,
                3,
                2,
                new int[]{2, 3, 4});

        assertEquals(1,
                CustomMovementHandler.CalculateAnimationElapsedTicks(
                        animation, 0, 0, 1, 0));
        assertEquals(1,
                CustomMovementHandler.CalculateAnimationElapsedTicks(
                        animation, 0, 0, 3, 1));
        assertEquals(1,
                CustomMovementHandler.CalculateAnimationElapsedTicks(
                        animation, 1, 3, 1, 2));
        assertEquals(1,
                CustomMovementHandler.CalculateAnimationElapsedTicks(
                        animation, 2, 4, 1, 1));
        assertEquals(1,
                CustomMovementHandler.CalculateAnimationElapsedTicks(
                        new TestAnimation(
                                false,
                                2,
                                2,
                                new int[]{1, 0}),
                        0, 0, 6, 0));
        assertEquals(0,
                CustomMovementHandler.CalculateAnimationElapsedTicks(
                        new TestAnimation(true, 10, 10, null),
                        0, 0, 4, 4));
    }

    @Test
    public void nativeMotionStartsAtRenderedAnchorAndPreservesDisplacement()
    {
        LocalPoint renderedAnchor = new LocalPoint(1_000, 2_000, -1);
        LocalPoint nativeAnchor = new LocalPoint(1_200, 2_300, -1);
        LocalPoint currentNative = new LocalPoint(1_584, 2_172, -1);

        assertEquals(
                new LocalPoint(1_384, 1_872, -1),
                CustomMovementHandler.ApplyNativeMotionDelta(
                        renderedAnchor,
                        nativeAnchor,
                        currentNative));

        assertSame(nativeAnchor,
                CustomMovementHandler.SelectNativeMotionAnchor(nativeAnchor, currentNative));

        assertEquals(
                new LocalPoint(1_013, 1_993, -1),
                CustomMovementHandler.ApplySceneLocalOffset(
                        new LocalPoint(1_000, 2_000, -1),
                        13,
                        -7));
    }

    @Test
    public void acceleratedMovementDetectionIgnoresNormalRunning()
    {
        LocalPoint start = new LocalPoint(1_000, 2_000, -1);
        assertFalse(CustomMovementHandler.IsAcceleratedDestinationChange(
                start,
                new LocalPoint(1_256, 2_256, -1)));
        assertTrue(CustomMovementHandler.IsAcceleratedDestinationChange(
                start,
                new LocalPoint(1_257, 2_000, -1)));

        assertFalse(CustomMovementHandler.IsAcceleratedLocalMovement(
                start,
                new LocalPoint(1_032, 2_000, -1),
                2));
        assertTrue(CustomMovementHandler.IsAcceleratedLocalMovement(
                start,
                new LocalPoint(1_033, 2_000, -1),
                2));

        assertFalse(CustomMovementHandler.IsSpatialDiscontinuity(
                start,
                new LocalPoint(2_024, 2_000, -1)));
        assertTrue(CustomMovementHandler.IsSpatialDiscontinuity(
                start,
                new LocalPoint(2_025, 2_000, -1)));
    }

    @Test
    public void movementSelectionBridgesOnlyAContinuingRoute()
    {
        LocalPoint start = new LocalPoint(1_000, 2_000, -1);
        LocalPoint destination = new LocalPoint(1_128, 2_000, -1);
        LocalPoint laterRouteDestination = new LocalPoint(1_512, 2_000, -1);

        assertTrue(CustomMovementHandler.ShouldSelectMovingAnimation(
                599, start, destination, destination, start, false));
        assertFalse(CustomMovementHandler.ShouldSelectMovingAnimation(
                600, start, destination, destination, start, false));
        assertTrue(CustomMovementHandler.ShouldSelectMovingAnimation(
                600, start, destination, laterRouteDestination, start, false));
        assertTrue(CustomMovementHandler.ShouldSelectMovingAnimation(
                699, start, destination, laterRouteDestination, start, false));
        assertFalse(CustomMovementHandler.ShouldSelectMovingAnimation(
                700, start, destination, laterRouteDestination, start, false));
        assertFalse(CustomMovementHandler.ShouldSelectMovingAnimation(
                900, start, destination, laterRouteDestination, start, false));
        assertFalse(CustomMovementHandler.ShouldSelectMovingAnimation(
                600, start, destination, null, start, false));
        assertFalse(CustomMovementHandler.ShouldSelectMovingAnimation(
                0, destination, destination, laterRouteDestination, destination, false));
        assertFalse(CustomMovementHandler.ShouldSelectMovingAnimation(
                0, null, destination, start, start, false));
        assertFalse(CustomMovementHandler.ShouldSelectMovingAnimation(
                0, start, null, laterRouteDestination, start, false));
        assertFalse(CustomMovementHandler.ShouldSelectMovingAnimation(
                600,
                start,
                destination,
                new LocalPoint(1_512, 2_000, 123),
                start,
                false));

        // A faster tween can reach the displayed endpoint before the nominal
        // server-tick clock. The rendered arrival is authoritative.
        assertFalse(CustomMovementHandler.ShouldSelectMovingAnimation(
                300,
                start,
                destination,
                laterRouteDestination,
                destination,
                false));
        assertTrue(CustomMovementHandler.ShouldSelectMovingAnimation(
                650,
                start,
                destination,
                laterRouteDestination,
                destination,
                false));
        assertFalse(CustomMovementHandler.ShouldSelectMovingAnimation(
                650,
                start,
                destination,
                laterRouteDestination,
                destination,
                true));

        assertTrue(CustomMovementHandler.ShouldStopAtRenderedDestination(
                300, destination, destination, laterRouteDestination));
        assertTrue(CustomMovementHandler.ShouldStopAtRenderedDestination(
                600, destination, destination, destination));
        assertFalse(CustomMovementHandler.ShouldStopAtRenderedDestination(
                600, destination, destination, laterRouteDestination));
        assertFalse(CustomMovementHandler.ShouldStopAtRenderedDestination(
                650, destination, destination, laterRouteDestination));
        assertTrue(CustomMovementHandler.ShouldHoldContinuingRouteBoundary(
                600, destination, destination, laterRouteDestination, false));
        assertTrue(CustomMovementHandler.ShouldHoldContinuingRouteBoundary(
                699, destination, destination, laterRouteDestination, false));
        assertFalse(CustomMovementHandler.ShouldHoldContinuingRouteBoundary(
                700, destination, destination, laterRouteDestination, false));
        assertFalse(CustomMovementHandler.ShouldHoldContinuingRouteBoundary(
                600, start, destination, laterRouteDestination, false));
        assertFalse(CustomMovementHandler.ShouldHoldContinuingRouteBoundary(
                600, destination, destination, laterRouteDestination, true));
    }

    @Test
    public void stationaryCacheMissNeverUsesHiddenWalkingPose()
    {
        int idleAnimation = 808;
        int nativeWalkAnimation = 819;

        assertTrue(CustomMovementHandler.ShouldHoldStableStationaryFrame(
                true, true, false, false, false, idleAnimation, nativeWalkAnimation));
        assertTrue(CustomMovementHandler.ShouldHoldStableStationaryFrame(
                true, true, false, false, false, idleAnimation, idleAnimation));
        assertTrue(CustomMovementHandler.ShouldHoldStableStationaryFrame(
                true, true, false, false, true, idleAnimation, nativeWalkAnimation));
        assertFalse(CustomMovementHandler.ShouldHoldStableStationaryFrame(
                false, true, false, false, false, idleAnimation, nativeWalkAnimation));
        assertFalse(CustomMovementHandler.ShouldHoldStableStationaryFrame(
                true, false, false, false, false, idleAnimation, nativeWalkAnimation));
        assertFalse(CustomMovementHandler.ShouldHoldStableStationaryFrame(
                true, true, true, false, false, idleAnimation, nativeWalkAnimation));
        assertFalse(CustomMovementHandler.ShouldHoldStableStationaryFrame(
                true, true, false, true, false, idleAnimation, nativeWalkAnimation));
        assertFalse(CustomMovementHandler.ShouldHoldStableStationaryFrame(
                true, true, false, false, true, idleAnimation, idleAnimation));
        assertTrue(CustomMovementHandler.ShouldHoldStableStationaryFrame(
                true, true, false, false, true, idleAnimation, -1));
    }

    @Test
    public void disablingLeapsOverridesEveryPluginLeapRequest()
    {
        assertTrue(CustomMovementHandler.ShouldUsePluginLeap(false, true, true));
        assertFalse(CustomMovementHandler.ShouldUsePluginLeap(true, true, true));
        assertFalse(CustomMovementHandler.ShouldUsePluginLeap(false, false, true));
        assertFalse(CustomMovementHandler.ShouldUsePluginLeap(false, true, false));

        TrueTileMovementConfig config = new TrueTileMovementConfig() { };
        assertFalse(config.DisableLeapingAnimations());
    }
}
