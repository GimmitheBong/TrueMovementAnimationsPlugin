package com.truetileanimationmovement;

import net.runelite.api.coords.LocalPoint;
import net.runelite.api.gameval.AnimationID;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class CustomMovementHandlerTest
{
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
}
