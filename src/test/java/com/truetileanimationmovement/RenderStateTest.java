package com.truetileanimationmovement;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RenderStateTest
{
    @Test
    public void onlyMatchingPlayerTileObjectIsSuppressed()
    {
        int localPlayerId = 42;
        int localWorldViewId = 7;
        long playerHash = (long) localWorldViewId << 52;
        long otherWorldViewPlayerHash = (long) (localWorldViewId + 1) << 52;
        long gameObjectHash = playerHash | 2L << 16;

        assertFalse(TrueTileMovementPlugin.ShouldDrawTileObject(
                true, localPlayerId, localWorldViewId, playerHash, localPlayerId));
        assertTrue(TrueTileMovementPlugin.ShouldDrawTileObject(
                true, localPlayerId, localWorldViewId, playerHash, localPlayerId + 1));
        assertTrue(TrueTileMovementPlugin.ShouldDrawTileObject(
                true, localPlayerId, localWorldViewId, gameObjectHash, localPlayerId));
        assertTrue(TrueTileMovementPlugin.ShouldDrawTileObject(
                true, localPlayerId, localWorldViewId, otherWorldViewPlayerHash, localPlayerId));
        assertTrue(TrueTileMovementPlugin.ShouldDrawTileObject(
                false, localPlayerId, localWorldViewId, playerHash, localPlayerId));
    }

    @Test
    public void cameraFocalHeightUsesStableRenderedGroundHeight()
    {
        assertEquals(935.0f, TrueTileMovementPlugin.GetAdaptiveCameraFocalPointY(960.0f, 25), 0.0f);
    }

    @Test
    public void cameraFocalHeightSmoothsWithoutJumping()
    {
        float next = TrueTileMovementPlugin.SmoothCameraFocalPointY(1000.0f, 900.0f, 16.667f, false);

        assertTrue(next < 1000.0f);
        assertTrue(next > 900.0f);
        assertEquals(900.0f,
                TrueTileMovementPlugin.SmoothCameraFocalPointY(next, 900.0f, 16.667f, true),
                0.0f);
    }

    @Test
    public void cameraFocalHeightRecoversFromUninitializedState()
    {
        assertEquals(900.0f,
                TrueTileMovementPlugin.SmoothCameraFocalPointY(Float.NaN, 900.0f, 16.667f, false),
                0.0f);
    }

    @Test
    public void staleWorldViewReplacementNeverHidesPlayer()
    {
        assertTrue(TrueTileMovementPlugin.ShouldHidePreparedPlayer(false, true, 7, 7));
        assertFalse(TrueTileMovementPlugin.ShouldHidePreparedPlayer(false, true, 6, 7));
        assertFalse(TrueTileMovementPlugin.ShouldHidePreparedPlayer(true, true, 7, 7));
        assertFalse(TrueTileMovementPlugin.ShouldHidePreparedPlayer(false, false, 7, 7));
    }
}
