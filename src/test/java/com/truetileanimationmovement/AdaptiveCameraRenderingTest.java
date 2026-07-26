package com.truetileanimationmovement;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AdaptiveCameraRenderingTest
{
	@Test
	public void hiddenOwnerUsesAdaptiveCamera()
	{
		assertTrue(TrueTileMovementPlugin.ShouldRenderAdaptiveCamera(
				true,
				false));
	}

	@Test
	public void disabledAdaptiveCameraUsesNativeCamera()
	{
		assertFalse(TrueTileMovementPlugin.ShouldRenderAdaptiveCamera(
				false,
				false));
	}

	@Test
	public void visibleOwnerUsesNativeCamera()
	{
		assertFalse(TrueTileMovementPlugin.ShouldRenderAdaptiveCamera(
				true,
				true));
	}
}
