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
    public void catchUpIdleControllerOnlyRunsDuringAnIdleFacingHold()
    {
        // [TMA-IDLE-CATCH-UP] A controller is allowed only after a yellow
        // route visibly moved and then stopped. The negative case below is
        // the regression where a second click revived stale idle state before
        // movement had begun.
        assertTrue(CustomMovementHandler.ShouldUseWalkStopIdleController(
                true,
                true,
                true,
                -1,
                808));

        assertFalse(CustomMovementHandler.ShouldUseWalkStopIdleController(
                true,
                false,
                true,
                -1,
                808));
        assertFalse(CustomMovementHandler.ShouldUseWalkStopIdleController(
                true,
                true,
                false,
                -1,
                808));
        assertFalse(CustomMovementHandler.ShouldUseWalkStopIdleController(
                true,
                true,
                true,
                422,
                808));
        assertFalse(CustomMovementHandler.ShouldUseWalkStopIdleController(
                false,
                true,
                true,
                -1,
                808));
    }
}
