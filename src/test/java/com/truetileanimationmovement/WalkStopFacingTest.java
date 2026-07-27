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
}
