package com.truetileanimationmovement;

import net.runelite.api.GameState;
import net.runelite.api.coords.LocalPoint;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MovementContinuityTest
{
	@Test
	public void unfinishedRouteBridgesOnlyTheAnimationTickGap()
	{
		LocalPoint SegmentDestination =
				new LocalPoint(1280, 2560, 0);
		LocalPoint RouteDestination =
				new LocalPoint(1536, 2560, 0);

		assertTrue(CustomMovementHandler
				.ShouldKeepMovementAnimationDuringRouteGap(
						620,
						true,
						true,
						false,
						SegmentDestination,
						RouteDestination));
		// The latest area-transition captures reached 852-858 ms before the
		// next authoritative route segment was published.
		assertTrue(CustomMovementHandler
				.ShouldKeepMovementAnimationDuringRouteGap(
						858,
						true,
						true,
						false,
						SegmentDestination,
						RouteDestination));
		// Red-click interactions cancel the yellow-walk continuity arm.
		assertFalse(CustomMovementHandler
				.ShouldKeepMovementAnimationDuringRouteGap(
						620,
						true,
						false,
						false,
						SegmentDestination,
						RouteDestination));
		assertFalse(CustomMovementHandler
				.ShouldKeepMovementAnimationDuringRouteGap(
						620,
						true,
						true,
						false,
						SegmentDestination,
						SegmentDestination));
		assertFalse(CustomMovementHandler
				.ShouldKeepMovementAnimationDuringRouteGap(
						620,
						false,
						true,
						false,
						SegmentDestination,
						RouteDestination));
		assertFalse(CustomMovementHandler
				.ShouldKeepMovementAnimationDuringRouteGap(
						599,
						true,
						true,
						false,
						SegmentDestination,
						RouteDestination));
		assertFalse(CustomMovementHandler
				.ShouldKeepMovementAnimationDuringRouteGap(
						900,
						true,
						true,
						false,
						SegmentDestination,
						RouteDestination));
		assertFalse(CustomMovementHandler
				.ShouldKeepMovementAnimationDuringRouteGap(
						620,
						true,
						true,
						false,
						SegmentDestination,
						new LocalPoint(1536, 2560, 1)));
		assertFalse(CustomMovementHandler
				.ShouldKeepMovementAnimationDuringRouteGap(
						620,
						true,
						true,
						false,
						SegmentDestination,
						null));
		// A stop-facing handoff owns the endpoint pose. Route-gap grace must
		// never keep locomotion alive at the same time.
		assertTrue(CustomMovementHandler
				.ShouldKeepMovementAnimationDuringRouteGap(
						620,
						true,
						true,
						false,
						false,
						SegmentDestination,
						RouteDestination));
		assertFalse(CustomMovementHandler
				.ShouldKeepMovementAnimationDuringRouteGap(
						620,
						true,
						true,
						false,
						true,
						SegmentDestination,
						RouteDestination));
	}

	@Test
	public void freshWalkClickCannotRunInPlaceBeforeItsFirstSegment()
	{
		LocalPoint SegmentDestination =
				new LocalPoint(1280, 2560, 0);
		LocalPoint RouteDestination =
				new LocalPoint(1536, 2560, 0);

		assertFalse(CustomMovementHandler
				.ShouldKeepMovementAnimationDuringRouteGap(
						620,
						true,
						true,
						true,
						SegmentDestination,
						RouteDestination));
		assertTrue(CustomMovementHandler
				.ShouldAwaitFreshWalkMovementSegment(
						600,
						600,
						false,
						false));
		assertFalse(CustomMovementHandler
				.ShouldAwaitFreshWalkMovementSegment(
						599,
						600,
						false,
						false));
	}

	@Test
	public void proximityHandoffOccursOnlyAtStableIdle()
	{
		assertTrue(CustomMovementHandler
				.ShouldUseOriginalOwnerPresentation(
						true,
						false,
						false,
						false,
						-1,
						0,
						0,
						5,
						1,
						10));

		assertFalse(CustomMovementHandler
				.ShouldUseOriginalOwnerPresentation(
						true,
						true,
						false,
						false,
						-1,
						0,
						0,
						5,
						1,
						10));
		assertFalse(CustomMovementHandler
				.ShouldUseOriginalOwnerPresentation(
						true,
						false,
						false,
						false,
						422,
						0,
						0,
						5,
						1,
						10));
		// The old one-sided orientation comparison accepted every sufficiently
		// negative difference, even when the models faced far apart.
		assertFalse(CustomMovementHandler
				.ShouldUseOriginalOwnerPresentation(
						true,
						false,
						false,
						false,
						-1,
						0,
						0,
						-200,
						1,
						10));
	}

	@Test
	public void equivalentAnimationIdsDoNotResetTheController()
	{
		assertFalse(CustomMovementHandler
				.ShouldReplaceAnimationController(
						808,
						808,
						false));
		assertTrue(CustomMovementHandler
				.ShouldReplaceAnimationController(
						808,
						819,
						false));
		assertTrue(CustomMovementHandler
				.ShouldReplaceAnimationController(
						808,
						808,
						true));
	}

	@Test
	public void shortRenderCallbackGapDoesNotDisableThePlugin()
	{
		assertTrue(TrueTileMovementPlugin
				.IsGpuCallbackStillSupported(
						GameState.LOGGED_IN,
						50));
		assertFalse(TrueTileMovementPlugin
				.IsGpuCallbackStillSupported(
						GameState.LOGGED_IN,
						51));
		assertTrue(TrueTileMovementPlugin
				.IsGpuCallbackStillSupported(
						GameState.LOADING,
						500));
	}

}
