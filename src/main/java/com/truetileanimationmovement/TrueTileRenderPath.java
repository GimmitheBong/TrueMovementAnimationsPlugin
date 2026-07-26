package com.truetileanimationmovement;

import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

/**
 * A bounded, collision-validated render path between successive server
 * true-tile updates.
 *
 * <p>RuneScape can publish two path steps in one server tick. Interpolating
 * directly between those endpoints draws a chord through corners. This class
 * expands that transition into one-tile legs and advances the visible model
 * along those legs without using the hidden player's trailing local position.</p>
 */
final class TrueTileRenderPath
{
    static final int SERVER_TICK_MILLISECONDS = 600;
    private static final int MAX_TRANSITION_STEPS = 2;
    private static final int MAX_QUEUED_LEGS = 8;

    @FunctionalInterface
    interface StepValidator
    {
        boolean canTravel(WorldPoint Start, int DirectionX, int DirectionY);
    }

    private static final class Leg
    {
        private final LocalPoint Start;
        private final LocalPoint End;
        private final double DurationMilliseconds;
        private final int MovementMagnitude;

        private Leg(
                LocalPoint Start,
                LocalPoint End,
                double DurationMilliseconds,
                int MovementMagnitude)
        {
            this.Start = Start;
            this.End = End;
            this.DurationMilliseconds = DurationMilliseconds;
            this.MovementMagnitude = MovementMagnitude;
        }
    }

    private final Deque<Leg> Legs = new ArrayDeque<>();
    private LocalPoint CurrentLocation;
    private double CurrentLegElapsedMilliseconds;
    private int LastMovementMagnitude = 1;

    static List<WorldPoint> PlanTransition(
            WorldPoint Start,
            WorldPoint End,
            int PreferredDirectionX,
            int PreferredDirectionY,
            StepValidator Validator)
    {
        if (Start == null || End == null)
        {
            return Collections.emptyList();
        }
        int InferredSteps = Math.max(
                Math.abs(End.getX() - Start.getX()),
                Math.abs(End.getY() - Start.getY()));
        return PlanTransition(
                Start,
                End,
                PreferredDirectionX,
                PreferredDirectionY,
                InferredSteps,
                Validator);
    }

    static List<WorldPoint> PlanTransition(
            WorldPoint Start,
            WorldPoint End,
            int PreferredDirectionX,
            int PreferredDirectionY,
            int ExpectedStepCount,
            StepValidator Validator)
    {
        if (Start == null || End == null || Validator == null ||
                Start.getPlane() != End.getPlane())
        {
            return Collections.emptyList();
        }

        int TotalX = End.getX() - Start.getX();
        int TotalY = End.getY() - Start.getY();
        int Distance = Math.max(Math.abs(TotalX), Math.abs(TotalY));
        if (Distance == 0)
        {
            return Collections.emptyList();
        }
        if (ExpectedStepCount < 1 ||
                ExpectedStepCount > MAX_TRANSITION_STEPS ||
                Distance > ExpectedStepCount)
        {
            return Collections.emptyList();
        }

        if (ExpectedStepCount == 1)
        {
            return Distance == 1 &&
                    Validator.canTravel(
                            Start,
                            TotalX,
                            TotalY)
                    ? Collections.singletonList(End)
                    : Collections.emptyList();
        }

        int PreferredX = Integer.signum(PreferredDirectionX);
        int PreferredY = Integer.signum(PreferredDirectionY);
        if (Distance == 1 &&
                PreferredX == TotalX &&
                PreferredY == TotalY &&
                Validator.canTravel(
                        Start,
                        TotalX,
                        TotalY))
        {
            // A continuing run can publish an adjacent endpoint. If the
            // observed route direction already points directly at it, this was
            // one genuine run step; forcing two non-zero legs would invent a
            // sideways weave. A differing preferred direction remains useful
            // evidence for the two-cardinal corner turns seen around farming
            // objects.
            return Collections.singletonList(End);
        }

        WorldPoint BestIntermediate = null;
        int BestScore = Integer.MIN_VALUE;

        // The intersection of the one-tile neighbourhoods around Start and
        // End contains every possible two-step route, including diagonal
        // detours for a net (2, 0) or (0, 2) transition.
        for (int FirstX = -1; FirstX <= 1; ++FirstX)
        {
            for (int FirstY = -1; FirstY <= 1; ++FirstY)
            {
                if (FirstX == 0 && FirstY == 0)
                {
                    continue;
                }

                int SecondX = TotalX - FirstX;
                int SecondY = TotalY - FirstY;
                if (SecondX < -1 || SecondX > 1 ||
                        SecondY < -1 || SecondY > 1 ||
                        (SecondX == 0 && SecondY == 0))
                {
                    continue;
                }

                WorldPoint Intermediate = new WorldPoint(
                        Start.getX() + FirstX,
                        Start.getY() + FirstY,
                        Start.getPlane());
                if (!Validator.canTravel(Start, FirstX, FirstY) ||
                        !Validator.canTravel(
                                Intermediate,
                                SecondX,
                                SecondY))
                {
                    continue;
                }

                int Score = FirstX * PreferredX +
                        FirstY * PreferredY;
                // Prefer the route with the least internal turn before using
                // facing as a tie-break. In open space this makes E+E win over
                // an unnecessary NE+SE weave for a two-tile east transition.
                Score += 100 *
                        (FirstX * SecondX +
                                FirstY * SecondY);
                if (FirstX == PreferredX && FirstY == PreferredY)
                {
                    Score += 10;
                }

                if (BestIntermediate == null || Score > BestScore)
                {
                    BestIntermediate = Intermediate;
                    BestScore = Score;
                }
            }
        }

        if (BestIntermediate == null)
        {
            return Collections.emptyList();
        }

        List<WorldPoint> Result = new ArrayList<>(2);
        Result.add(BestIntermediate);
        Result.add(End);
        return Result;
    }

    void reset(LocalPoint Location)
    {
        Legs.clear();
        CurrentLocation = Location;
        CurrentLegElapsedMilliseconds = 0;
        LastMovementMagnitude = 1;
    }

    boolean appendTransition(
            LocalPoint TransitionStart,
            List<LocalPoint> Waypoints)
    {
        return appendTransition(
                TransitionStart,
                Waypoints,
                Waypoints == null
                        ? 1
                        : Waypoints.size());
    }

    boolean appendTransition(
            LocalPoint TransitionStart,
            List<LocalPoint> Waypoints,
            int MovementMagnitude)
    {
        if (TransitionStart == null || Waypoints == null ||
                Waypoints.isEmpty() ||
                MovementMagnitude < 1 ||
                MovementMagnitude > 2 ||
                Waypoints.size() > MovementMagnitude ||
                Legs.size() + Waypoints.size() >
                        MAX_QUEUED_LEGS)
        {
            return false;
        }

        LocalPoint Tail = getTailLocation();
        if (Tail == null)
        {
            reset(TransitionStart);
            Tail = TransitionStart;
        }
        if (!Tail.equals(TransitionStart) ||
                Waypoints.size() > MAX_TRANSITION_STEPS)
        {
            return false;
        }

        // Running traverses one tile in half a tick. A two-step run therefore
        // has two 300 ms legs; a final/blocked one-step run has one 300 ms leg
        // and becomes idle at arrival instead of skating at walk speed.
        double LegDuration =
                SERVER_TICK_MILLISECONDS /
                        (double) MovementMagnitude;
        LocalPoint LegStart = TransitionStart;
        for (LocalPoint Waypoint : Waypoints)
        {
            if (Waypoint == null ||
                    Waypoint.getWorldView() != LegStart.getWorldView() ||
                    Waypoint.equals(LegStart))
            {
                return false;
            }
            LegStart = Waypoint;
        }

        LegStart = TransitionStart;
        for (LocalPoint Waypoint : Waypoints)
        {
            Legs.addLast(new Leg(
                    LegStart,
                    Waypoint,
                    LegDuration,
                    MovementMagnitude));
            LegStart = Waypoint;
        }
        LastMovementMagnitude = MovementMagnitude;
        return true;
    }

    LocalPoint advance(double ElapsedMilliseconds)
    {
        if (CurrentLocation == null || Legs.isEmpty() ||
                ElapsedMilliseconds <= 0)
        {
            return CurrentLocation;
        }

        double RemainingMilliseconds = ElapsedMilliseconds;
        while (RemainingMilliseconds > 0 && !Legs.isEmpty())
        {
            Leg CurrentLeg = Legs.peekFirst();
            double LegRemaining =
                    CurrentLeg.DurationMilliseconds -
                            CurrentLegElapsedMilliseconds;
            double Consumed = Math.min(
                    RemainingMilliseconds,
                    LegRemaining);
            CurrentLegElapsedMilliseconds += Consumed;
            RemainingMilliseconds -= Consumed;

            double Progress = Math.min(
                    1.0,
                    CurrentLegElapsedMilliseconds /
                            CurrentLeg.DurationMilliseconds);
            CurrentLocation = interpolate(
                    CurrentLeg.Start,
                    CurrentLeg.End,
                    Progress);

            if (CurrentLegElapsedMilliseconds >=
                    CurrentLeg.DurationMilliseconds)
            {
                CurrentLocation = CurrentLeg.End;
                Legs.removeFirst();
                CurrentLegElapsedMilliseconds = 0;
            }
        }

        return CurrentLocation;
    }

    boolean isMoving()
    {
        return !Legs.isEmpty();
    }

    LocalPoint getCurrentLocation()
    {
        return CurrentLocation;
    }

    LocalPoint getTailLocation()
    {
        Leg Tail = Legs.peekLast();
        return Tail == null ? CurrentLocation : Tail.End;
    }

    LocalPoint getActiveLegStart()
    {
        Leg Active = Legs.peekFirst();
        return Active == null ? CurrentLocation : Active.Start;
    }

    LocalPoint getActiveLegEnd()
    {
        Leg Active = Legs.peekFirst();
        return Active == null ? CurrentLocation : Active.End;
    }

    LocalPoint getTailLegStart()
    {
        Leg Tail = Legs.peekLast();
        return Tail == null ? CurrentLocation : Tail.Start;
    }

    LocalPoint getTailLegEnd()
    {
        Leg Tail = Legs.peekLast();
        return Tail == null ? CurrentLocation : Tail.End;
    }

    int getActiveMovementMagnitude()
    {
        Leg Active = Legs.peekFirst();
        return Active == null
                ? 1
                : Active.MovementMagnitude;
    }

    int getTailMovementMagnitude()
    {
        Leg Tail = Legs.peekLast();
        return Tail == null
                ? LastMovementMagnitude
                : Tail.MovementMagnitude;
    }

    private static LocalPoint interpolate(
            LocalPoint Start,
            LocalPoint End,
            double Progress)
    {
        return new LocalPoint(
                (int) Math.round(
                        Start.getX() +
                                (End.getX() - Start.getX()) * Progress),
                (int) Math.round(
                        Start.getY() +
                                (End.getY() - Start.getY()) * Progress),
                Start.getWorldView());
    }
}
