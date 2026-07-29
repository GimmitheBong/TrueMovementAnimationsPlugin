package com.truetileanimationmovement;

import net.runelite.api.MenuAction;
import net.runelite.api.coords.LocalPoint;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class WalkStopFacingTest
{
    @Test
    public void worldInteractionsCancelTheWalkFacingHold()
    {
        assertTrue(TrueTileMovementPlugin.IsRedWorldInteraction(
                MenuAction.GAME_OBJECT_FIRST_OPTION));
        assertTrue(TrueTileMovementPlugin.IsRedWorldInteraction(
                MenuAction.NPC_FIRST_OPTION));
        assertTrue(TrueTileMovementPlugin.IsRedWorldInteraction(
                MenuAction.PLAYER_FIRST_OPTION));
    }

    @Test
    public void walkAndWidgetClicksAreNotRedWorldInteractions()
    {
        assertFalse(TrueTileMovementPlugin.IsRedWorldInteraction(
                MenuAction.WALK));
        assertFalse(TrueTileMovementPlugin.IsRedWorldInteraction(
                MenuAction.CC_OP));
    }

    @Test
    public void hiddenOwnerMustReachTheExactRenderedDestination()
    {
        LocalPoint Destination = new LocalPoint(1280, 2560, 0);

        assertTrue(CustomMovementHandler.HasHiddenOwnerCaughtUp(
                new LocalPoint(1280, 2560, 0),
                Destination));
        assertFalse(CustomMovementHandler.HasHiddenOwnerCaughtUp(
                new LocalPoint(1279, 2560, 0),
                Destination));
        assertFalse(CustomMovementHandler.HasHiddenOwnerCaughtUp(
                null,
                Destination));
    }

    @Test
    public void routeEndOrientationChangesAreAbsorbedUntilNativeFacingSettles()
    {
        assertTrue(CustomMovementHandler.ShouldPreserveReleasedWalkFacing(
                false,
                512,
                1024));
        assertTrue(CustomMovementHandler.ShouldPreserveReleasedWalkFacing(
                true,
                1024,
                1024));
        assertFalse(CustomMovementHandler.ShouldPreserveReleasedWalkFacing(
                true,
                1024,
                1536));
    }

    @Test
    public void nativeFacingRequiresOneStableGameTick()
    {
        assertFalse(CustomMovementHandler.HasNativeWalkFacingSettled(
                1599,
                1000));
        assertTrue(CustomMovementHandler.HasNativeWalkFacingSettled(
                1600,
                1000));
    }

    @Test
    public void aSecondWalkClickDoesNotDropAnActiveCatchUpHold()
    {
        assertTrue(CustomMovementHandler
                .ShouldContinueActiveWalkStopCatchUp(
                        true,
                        true,
                        true));
        assertFalse(CustomMovementHandler
                .ShouldContinueActiveWalkStopCatchUp(
                        true,
                        true,
                        false));
        assertFalse(CustomMovementHandler
                .ShouldContinueActiveWalkStopCatchUp(
                        false,
                        false,
                        true));
    }

    @Test
    public void aPublishedRouteCannotReleaseCatchUpBeforeMovementStarts()
    {
        assertTrue(CustomMovementHandler.ShouldHoldPendingWalkStart(
                true,
                true,
                true,
                false));
        assertFalse(CustomMovementHandler.ShouldHoldPendingWalkStart(
                true,
                true,
                true,
                true));
        assertFalse(CustomMovementHandler.ShouldHoldPendingWalkStart(
                false,
                true,
                true,
                false));
    }

    @Test
    public void catchUpIdleControllerContinuesAcrossTheSettlingHandoff()
    {
        // Start only after a yellow route visibly moved and stopped.
        assertTrue(CustomMovementHandler.ShouldUseWalkStopIdleController(
                false,
                true,
                true,
                true,
                -1,
                808));

        // Once active, keep the same controller through positional catch-up
        // and the native-facing settle period.
        assertTrue(CustomMovementHandler.ShouldUseWalkStopIdleController(
                true,
                true,
                false,
                false,
                -1,
                808));

        // A click waiting to move must not revive an already released clock.
        assertFalse(CustomMovementHandler.ShouldUseWalkStopIdleController(
                false,
                true,
                false,
                false,
                -1,
                808));
        assertFalse(CustomMovementHandler.ShouldUseWalkStopIdleController(
                false,
                true,
                true,
                false,
                -1,
                808));
        assertFalse(CustomMovementHandler.ShouldUseWalkStopIdleController(
                true,
                true,
                true,
                true,
                422,
                808));
        assertFalse(CustomMovementHandler.ShouldUseWalkStopIdleController(
                true,
                false,
                true,
                true,
                -1,
                808));
    }
}
