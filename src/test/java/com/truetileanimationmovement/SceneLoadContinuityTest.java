package com.truetileanimationmovement;

import net.runelite.api.coords.LocalPoint;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class SceneLoadContinuityTest
{
	@Test
	public void rebaseCarriesOnlyMissedFrameTime()
	{
		assertEquals(
				0,
				CustomMovementHandler
						.GetSceneRebaseElapsedMilliseconds(-1));
		assertEquals(
				228,
				CustomMovementHandler
						.GetSceneRebaseElapsedMilliseconds(228));
		assertEquals(
				600,
				CustomMovementHandler
						.GetSceneRebaseElapsedMilliseconds(900));
		assertEquals(
				600,
				CustomMovementHandler
						.GetSceneRebaseElapsedMilliseconds(
								228,
								false));
		assertEquals(
				0,
				CustomMovementHandler
						.GetSceneRebaseElapsedMilliseconds(
								228,
								true,
								true));
		assertEquals(
				600,
				CustomMovementHandler
						.GetSceneRebaseElapsedMilliseconds(
								228,
								false,
								true));
	}

	@Test
	public void unfinishedRecoveryReanchorsAtDisplayedPoint()
	{
		assertTrue(CustomMovementHandler.ShouldReanchorSceneRecovery(
				true,
				228,
				600,
				true));
	}

	@Test
	public void ordinaryOrCompletedMovementDoesNotUseRecoveryReanchor()
	{
		assertFalse(CustomMovementHandler.ShouldReanchorSceneRecovery(
				false,
				228,
				600,
				true));
		assertFalse(CustomMovementHandler.ShouldReanchorSceneRecovery(
				true,
				600,
				600,
				true));
		assertFalse(CustomMovementHandler.ShouldReanchorSceneRecovery(
				true,
				228,
				600,
				false));
	}

	@Test
	public void nativeOwnerRemainsVisibleUntilReplacementIsReady()
	{
		assertFalse(TrueTileMovementPlugin.ShouldSuppressNativeOwner(
				true,
				true,
				false));
		assertFalse(TrueTileMovementPlugin.ShouldSuppressNativeOwner(
				false,
				true,
				true));
		assertFalse(TrueTileMovementPlugin.ShouldSuppressNativeOwner(
				false,
				false,
				false));
		assertTrue(TrueTileMovementPlugin.ShouldSuppressNativeOwner(
				false,
				false,
				true));
	}

	@Test
	public void presentedNativeHandoffBecomesTheRebaseAnchor()
	{
		LocalPoint NativeOwner = new LocalPoint(100, 200, 0);
		LocalPoint RetainedCustom = new LocalPoint(300, 400, 0);

		assertSame(
				NativeOwner,
				CustomMovementHandler.SelectSceneRebaseAnchor(
						true,
						NativeOwner,
						RetainedCustom));
		assertSame(
				RetainedCustom,
				CustomMovementHandler.SelectSceneRebaseAnchor(
						false,
						NativeOwner,
						RetainedCustom));
	}

	@Test
	public void onlyOutwardMovementAtSceneMarginUsesBoundaryBridge()
	{
		int lowBoundary = 16 * 128 + 64;
		int highBoundary = 87 * 128 + 64;

		assertTrue(CustomMovementHandler.IsSceneBoundaryExitSegment(
				new LocalPoint(5000, lowBoundary + 128, 0),
				new LocalPoint(5000, lowBoundary, 0),
				new LocalPoint(5000, lowBoundary - 1024, 0)));
		assertTrue(CustomMovementHandler.IsSceneBoundaryExitSegment(
				new LocalPoint(highBoundary - 128, 5000, 0),
				new LocalPoint(highBoundary, 5000, 0),
				new LocalPoint(highBoundary + 1024, 5000, 0)));
		assertFalse(CustomMovementHandler.IsSceneBoundaryExitSegment(
				new LocalPoint(5000, lowBoundary + 128, 0),
				new LocalPoint(5000, lowBoundary, 0),
				new LocalPoint(5000, lowBoundary + 1024, 0)));
		assertFalse(CustomMovementHandler.IsSceneBoundaryExitSegment(
				new LocalPoint(5000, 5000, 0),
				new LocalPoint(5000, 5000 - 128, 0),
				new LocalPoint(5000, 1000, 0)));
	}

	@Test
	public void boundaryBridgeIsTimeAndDistanceBounded()
	{
		assertEquals(
				1.0,
				CustomMovementHandler.GetSceneBoundaryBridgeTweenValue(
						600,
						600,
						128),
				0.0001);
		assertEquals(
				1.2,
				CustomMovementHandler.GetSceneBoundaryBridgeTweenValue(
						720,
						600,
						128),
				0.0001);
		assertEquals(
				1.25,
				CustomMovementHandler.GetSceneBoundaryBridgeTweenValue(
						900,
						600,
						256),
				0.0001);
	}

	@Test
	public void scenePresentationClockDefersAndGraduallyRepaysLongFrames()
	{
		assertEquals(
				34,
				CustomMovementHandler
						.GetScenePresentationImmediateFrameDelta(212));
		assertEquals(
				22,
				CustomMovementHandler
						.GetScenePresentationImmediateFrameDelta(22));
		assertEquals(
				5,
				CustomMovementHandler
						.GetScenePresentationDebtPayback(22, 190));
		assertEquals(
				2,
				CustomMovementHandler
						.GetScenePresentationDebtPayback(34, 2));
		assertEquals(
				0,
				CustomMovementHandler
						.GetScenePresentationDebtPayback(0, 190));
	}

	@Test
	public void recoveryRetargetPreservesEstablishedVelocity()
	{
		double runVelocity = 256.0 / 600.0;

		assertEquals(
				736,
				CustomMovementHandler.GetSceneRecoveryTweenDuration(
						314,
						runVelocity,
						600));
		assertEquals(
				600,
				CustomMovementHandler.GetSceneRecoveryTweenDuration(
						0,
						runVelocity,
						600));
	}
}
