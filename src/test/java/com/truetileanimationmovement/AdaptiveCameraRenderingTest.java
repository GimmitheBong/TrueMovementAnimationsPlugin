package com.truetileanimationmovement;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AdaptiveCameraRenderingTest
{
	@Test
	public void openMenuUsesAdaptiveCameraAfterRecentInput()
	{
		assertTrue(TrueTileMovementPlugin.ShouldRenderAdaptiveCamera(
				true,
				true,
				true,
				false));
	}

	@Test
	public void pendingMenuCreationKeepsInteractionCamera()
	{
		assertFalse(TrueTileMovementPlugin.ShouldRenderAdaptiveCamera(
				true,
				true,
				false,
				false));
	}

	@Test
	public void regularAdaptiveRenderingIsUnchanged()
	{
		assertTrue(TrueTileMovementPlugin.ShouldRenderAdaptiveCamera(
				true,
				false,
				false,
				false));
	}

	@Test
	public void adaptivePrerequisitesStillTakePriority()
	{
		assertFalse(TrueTileMovementPlugin.ShouldRenderAdaptiveCamera(
				false,
				false,
				true,
				false));
		assertFalse(TrueTileMovementPlugin.ShouldRenderAdaptiveCamera(
				true,
				false,
				true,
				true));
	}
}
