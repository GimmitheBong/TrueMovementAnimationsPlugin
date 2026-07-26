package com.truetileanimationmovement;

import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TrueTileRenderPathTest
{
    @Test
    public void directOneStepTransitionUsesOneLeg()
    {
        WorldPoint Start = new WorldPoint(3_000, 3_000, 0);
        WorldPoint End = new WorldPoint(3_001, 3_001, 0);

        List<WorldPoint> Plan =
                TrueTileRenderPath.PlanTransition(
                        Start,
                        End,
                        1,
                        1,
                        (From, X, Y) -> true);

        assertEquals(Arrays.asList(End), Plan);
    }

    @Test
    public void ambiguousRunPrefersTheObservedFirstLeg()
    {
        WorldPoint Start = new WorldPoint(3_000, 3_000, 0);
        WorldPoint End = new WorldPoint(3_002, 3_001, 0);

        List<WorldPoint> CardinalFirst =
                TrueTileRenderPath.PlanTransition(
                        Start,
                        End,
                        1,
                        0,
                        (From, X, Y) -> true);
        assertEquals(
                new WorldPoint(3_001, 3_000, 0),
                CardinalFirst.get(0));

        List<WorldPoint> DiagonalFirst =
                TrueTileRenderPath.PlanTransition(
                        Start,
                        End,
                        1,
                        1,
                        (From, X, Y) -> true);
        assertEquals(
                new WorldPoint(3_001, 3_001, 0),
                DiagonalFirst.get(0));
    }

    @Test
    public void blockedStraightRunCanUseAValidatedDiagonalDetour()
    {
        WorldPoint Start = new WorldPoint(3_000, 3_000, 0);
        WorldPoint End = new WorldPoint(3_002, 3_000, 0);
        WorldPoint Detour = new WorldPoint(3_001, 3_001, 0);

        List<WorldPoint> Plan =
                TrueTileRenderPath.PlanTransition(
                        Start,
                        End,
                        1,
                        0,
                        (From, X, Y) ->
                                (From.equals(Start) &&
                                        X == 1 && Y == 1) ||
                                        (From.equals(Detour) &&
                                                X == 1 && Y == -1));

        assertEquals(Arrays.asList(Detour, End), Plan);
    }

    @Test
    public void transitionWithoutAValidatedRouteFailsClosed()
    {
        WorldPoint Start = new WorldPoint(3_000, 3_000, 0);
        WorldPoint End = new WorldPoint(3_002, 3_001, 0);

        assertTrue(TrueTileRenderPath.PlanTransition(
                Start,
                End,
                1,
                0,
                (From, X, Y) -> false).isEmpty());
        assertTrue(TrueTileRenderPath.PlanTransition(
                Start,
                new WorldPoint(3_003, 3_000, 0),
                1,
                0,
                (From, X, Y) -> true).isEmpty());
    }

    @Test
    public void blockedAdjacentTransitionNeverInventsADetour()
    {
        WorldPoint Start = new WorldPoint(3_000, 3_000, 0);
        WorldPoint End = new WorldPoint(3_001, 3_000, 0);

        assertTrue(TrueTileRenderPath.PlanTransition(
                Start,
                End,
                0,
                1,
                (From, X, Y) ->
                        !(From.equals(Start) &&
                                X == 1 &&
                                Y == 0)).isEmpty());
    }

    @Test
    public void turningRunCanUseTwoLegsForANetAdjacentEndpoint()
    {
        WorldPoint Start = new WorldPoint(3_000, 3_000, 0);
        WorldPoint Corner = new WorldPoint(3_001, 3_000, 0);
        WorldPoint End = new WorldPoint(3_001, 3_001, 0);

        assertEquals(
                Arrays.asList(Corner, End),
                TrueTileRenderPath.PlanTransition(
                        Start,
                        End,
                        1,
                        0,
                        2,
                        (From, X, Y) ->
                                (From.equals(Start) &&
                                        X == 1 &&
                                        Y == 0) ||
                                        (From.equals(Corner) &&
                                                X == 0 &&
                                                Y == 1)));
    }

    @Test
    public void adjacentRunDoesNotInventAWeaveWhenObservedDirectionIsDirect()
    {
        WorldPoint Start = new WorldPoint(3_000, 3_000, 0);
        WorldPoint End = new WorldPoint(3_001, 3_000, 0);

        assertEquals(
                Arrays.asList(End),
                TrueTileRenderPath.PlanTransition(
                        Start,
                        End,
                        1,
                        0,
                        2,
                        (From, X, Y) -> true));
    }

    @Test
    public void openCardinalRunNeverWeavesToFollowFacing()
    {
        WorldPoint Start = new WorldPoint(3_000, 3_000, 0);
        WorldPoint Middle = new WorldPoint(3_001, 3_000, 0);
        WorldPoint End = new WorldPoint(3_002, 3_000, 0);

        assertEquals(
                Arrays.asList(Middle, End),
                TrueTileRenderPath.PlanTransition(
                        Start,
                        End,
                        1,
                        1,
                        (From, X, Y) -> true));
    }

    @Test
    public void twoStepTransitionVisitsItsCornerAndNeverOvershoots()
    {
        TrueTileRenderPath Path = new TrueTileRenderPath();
        LocalPoint Start = new LocalPoint(1_000, 2_000, -1);
        LocalPoint Corner = new LocalPoint(1_128, 2_000, -1);
        LocalPoint End = new LocalPoint(1_256, 2_128, -1);
        Path.reset(Start);

        assertTrue(Path.appendTransition(
                Start,
                Arrays.asList(Corner, End)));
        assertEquals(2, Path.getActiveMovementMagnitude());

        assertEquals(
                new LocalPoint(1_064, 2_000, -1),
                Path.advance(150));
        assertEquals(Corner, Path.advance(150));
        assertEquals(
                new LocalPoint(1_192, 2_064, -1),
                Path.advance(150));
        assertEquals(End, Path.advance(150));
        assertFalse(Path.isMoving());
        assertEquals(End, Path.advance(600));
    }

    @Test
    public void earlyNextTransitionRetainsTheQueuedCorner()
    {
        TrueTileRenderPath Path = new TrueTileRenderPath();
        LocalPoint Start = new LocalPoint(1_000, 2_000, -1);
        LocalPoint FirstCorner = new LocalPoint(1_128, 2_000, -1);
        LocalPoint FirstEnd = new LocalPoint(1_256, 2_128, -1);
        LocalPoint SecondEnd = new LocalPoint(1_384, 2_128, -1);
        Path.reset(Start);

        assertTrue(Path.appendTransition(
                Start,
                Arrays.asList(FirstCorner, FirstEnd)));
        assertEquals(
                new LocalPoint(1_064, 2_000, -1),
                Path.advance(150));

        assertTrue(Path.appendTransition(
                FirstEnd,
                Arrays.asList(SecondEnd)));
        assertEquals(FirstCorner, Path.getActiveLegEnd());

        assertEquals(FirstCorner, Path.advance(150));
        assertEquals(FirstEnd, Path.advance(300));
        assertEquals(SecondEnd, Path.advance(600));
        assertFalse(Path.isMoving());
    }

    @Test
    public void explicitRunPoseDoesNotAccelerateOneTileTranslation()
    {
        TrueTileRenderPath Path = new TrueTileRenderPath();
        LocalPoint Start = new LocalPoint(1_000, 2_000, -1);
        LocalPoint End = new LocalPoint(1_128, 2_000, -1);
        Path.reset(Start);

        assertTrue(Path.appendTransition(
                Start,
                Arrays.asList(End),
                2));
        assertEquals(2, Path.getActiveMovementMagnitude());
        assertEquals(
                new LocalPoint(1_064, 2_000, -1),
                Path.advance(150));
        assertTrue(Path.isMoving());
        assertEquals(End, Path.advance(150));
        assertFalse(Path.isMoving());
        assertEquals(2, Path.getTailMovementMagnitude());
    }

    @Test
    public void fractionalFrameTimeDoesNotAccumulatePathLag()
    {
        TrueTileRenderPath Path = new TrueTileRenderPath();
        LocalPoint Start = new LocalPoint(1_000, 2_000, -1);
        LocalPoint End = new LocalPoint(1_128, 2_000, -1);
        Path.reset(Start);
        assertTrue(Path.appendTransition(
                Start,
                Arrays.asList(End)));

        for (int Frame = 0; Frame < 36; ++Frame)
        {
            Path.advance(1000.0 / 60.0);
        }

        assertEquals(End, Path.getCurrentLocation());
        assertFalse(Path.isMoving());
    }

    @Test
    public void queuedRouteIsBounded()
    {
        TrueTileRenderPath Path = new TrueTileRenderPath();
        LocalPoint Tail = new LocalPoint(1_000, 2_000, -1);
        Path.reset(Tail);

        for (int Leg = 0; Leg < 8; ++Leg)
        {
            LocalPoint Next = new LocalPoint(
                    Tail.getX() + 128,
                    Tail.getY(),
                    -1);
            assertTrue(Path.appendTransition(
                    Tail,
                    Arrays.asList(Next)));
            Tail = Next;
        }

        assertFalse(Path.appendTransition(
                Tail,
                Arrays.asList(new LocalPoint(
                        Tail.getX() + 128,
                        Tail.getY(),
                        -1))));
    }
}
