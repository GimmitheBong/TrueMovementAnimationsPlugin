package com.truetileanimationmovement;

import net.runelite.api.GameState;
import net.runelite.api.coords.LocalPoint;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
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
		assertFalse(CustomMovementHandler
				.IsMovementSegmentFromLatestWalkClick(2, 1));
		assertTrue(CustomMovementHandler
				.IsMovementSegmentFromLatestWalkClick(2, 2));

		assertTrue(CustomMovementHandler
				.IsVisibleMovementSegmentInProgress(
						true,
						599,
						600,
						new LocalPoint(1280, 2560, 0),
						new LocalPoint(1536, 2560, 0)));
		assertFalse(CustomMovementHandler
				.IsVisibleMovementSegmentInProgress(
						true,
						600,
						600,
						new LocalPoint(1280, 2560, 0),
						new LocalPoint(1536, 2560, 0)));
		assertFalse(CustomMovementHandler
				.IsVisibleMovementSegmentInProgress(
						true,
						599,
						600,
						SegmentDestination,
						SegmentDestination));
	}

	@Test
	public void activeMovementReclickPreservesOnlyThePublicationSeam()
	{
		LocalPoint SegmentStart =
				new LocalPoint(1280, 2560, 0);
		LocalPoint SegmentEnd =
				new LocalPoint(1536, 2560, 0);

		// The captured failure reached 609 ms on the old segment while the new
		// yellow click was still waiting for its first published segment.
		assertTrue(CustomMovementHandler
				.ShouldPreservePendingReclickMovementPose(
						609,
						600,
						true,
						true,
						true,
						true,
						4,
						3,
						SegmentStart,
						SegmentEnd));
		// A click made after visible movement stopped still uses stable idle,
		// which preserves the earlier running-on-the-spot correction.
		assertFalse(CustomMovementHandler
				.ShouldPreservePendingReclickMovementPose(
						609, 600, true, false, true, true,
						4, 3, SegmentStart, SegmentEnd));
		// The handoff is bounded and cannot sustain locomotion at an endpoint.
		assertFalse(CustomMovementHandler
				.ShouldPreservePendingReclickMovementPose(
						650, 600, true, true, true, true,
						4, 3, SegmentStart, SegmentEnd));
		// Once a segment for the newest click exists, ordinary movement owns it.
		assertFalse(CustomMovementHandler
				.ShouldPreservePendingReclickMovementPose(
						609, 600, true, true, true, true,
						4, 4, SegmentStart, SegmentEnd));
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
	public void poseFramePublicationCarriesOnlyCompatibleValidPhases()
	{
		assertEquals(5, CustomMovementHandler.SelectPoseFrameForPublication(
				1661, -1, 1661, 1661, 5, 8, 0, false, false));
		assertEquals(0, CustomMovementHandler.SelectPoseFrameForPublication(
				1661, -1, 1661, 1660, 5, 8, 0, false, false));
		assertEquals(5, CustomMovementHandler.SelectPoseFrameForPublication(
				1661, -2, 1661, 1661, 5, 8, 0, false, false));
		// An invalid frame cannot borrow a remembered phase from a different
		// animation; it uses the requested animation's authored entry frame.
		assertEquals(2, CustomMovementHandler.SelectPoseFrameForPublication(
				1661, -1, 808, 1661, 5, 12, 2, false, false));
		assertEquals(3, CustomMovementHandler.SelectPoseFrameForPublication(
				1661, 3, 1661, 1661, 5, 8, 0, false, false));
		// Directional locomotion variants deliberately keep phase when their
		// animation IDs change, preventing route-segment frame-zero resets.
		assertEquals(3, CustomMovementHandler.SelectPoseFrameForPublication(
				1661, 3, 1660, 1661, 3, 8, 0, false, false));
		// A stationary locomotion-to-idle mismatch deliberately starts from
		// idle's authored entry frame instead of racing the idle pose on the
		// hidden actor's still-active locomotion clock.
		assertEquals(0, CustomMovementHandler.SelectPoseFrameForPublication(
				1661, 6, 808, 1661, 6, 12, 0, false, true));
		assertEquals(2, CustomMovementHandler.SelectPoseFrameForPublication(
				1661, 3, 1661, 1661, 5, 8, 2, true, false));
		assertEquals(2, CustomMovementHandler.SelectPoseFrameForPublication(
				1661, 8, 1661, 1661, 5, 8, 2, false, false));
	}

	@Test
	public void stationaryIdleRestartIsLimitedToTheNativeLocomotionMismatch()
	{
		assertTrue(CustomMovementHandler.ShouldRestartStationaryIdlePose(
				false, -1, 1661, 808, 808));
		assertFalse(CustomMovementHandler.ShouldRestartStationaryIdlePose(
				true, -1, 1661, 808, 808));
		assertFalse(CustomMovementHandler.ShouldRestartStationaryIdlePose(
				false, 422, 1661, 808, 808));
		assertFalse(CustomMovementHandler.ShouldRestartStationaryIdlePose(
				false, -1, 1661, 1660, 808));
		assertFalse(CustomMovementHandler.ShouldRestartStationaryIdlePose(
				false, -1, 808, 808, 808));
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

	@Test
	public void movementSpeedLeadNeverCreatesAnEndpointPlateau()
	{
		double[] Multipliers = {1.0, 1.1, 1.2, 1.3, 2.0, 3.0};
		for (double Multiplier : Multipliers)
		{
			double PreviousProgress = -1.0;
			for (int Milliseconds = 0;
				 Milliseconds <= 600;
				 ++Milliseconds)
			{
				double BaseProgress = Milliseconds / 600.0;
				double Progress = CustomMovementHandler
						.ApplyContinuousMovementSpeedLead(
								BaseProgress,
								Multiplier);
				assertTrue(Progress >= PreviousProgress);
				if (Milliseconds > 0)
				{
					assertTrue(Progress > PreviousProgress);
				}
				assertTrue(Progress >= BaseProgress);
				if (Milliseconds < 600)
				{
					assertTrue(Progress < 1.0);
				}
				PreviousProgress = Progress;
			}
			assertEquals(1.0, PreviousProgress, 0.0);
		}
	}

	@Test
	public void movementSpeedLeadJoinsSegmentsAtNativeVelocity()
	{
		double SmallPhase = 0.000001;
		double Multiplier = 1.75;
		double StartSlope = CustomMovementHandler
				.ApplyContinuousMovementSpeedLead(
						SmallPhase,
						Multiplier) / SmallPhase;
		double EndSlope =
				(1.0 - CustomMovementHandler
						.ApplyContinuousMovementSpeedLead(
								1.0 - SmallPhase,
								Multiplier)) /
						SmallPhase;

		assertEquals(1.0, StartSlope, 0.00001);
		assertEquals(1.0, EndSlope, 0.00001);
		assertTrue(CustomMovementHandler
				.ApplyContinuousMovementSpeedLead(
						0.5,
						1.3) > 0.5);
	}

	@Test
	public void movementSpeedLeadIsBoundedWithoutChangingRequestChoreography()
	{
		assertEquals(1.3, CustomMovementHandler
				.GetConfiguredMovementLeadMultiplier(1.3), 0.0);
		assertEquals(1.75, CustomMovementHandler
				.GetConfiguredMovementLeadMultiplier(3.0), 0.0);
		assertEquals(1.0, CustomMovementHandler
				.GetConfiguredMovementLeadMultiplier(0.5), 0.0);
		assertEquals(1.0, CustomMovementHandler
				.GetConfiguredMovementLeadMultiplier(Double.NaN), 0.0);

		assertEquals(600, CustomMovementHandler
				.GetRequestMovementTweenDurationMilliseconds(1.0));
		assertEquals(400, CustomMovementHandler
				.GetRequestMovementTweenDurationMilliseconds(1.5));
		assertEquals(300, CustomMovementHandler
				.GetRequestMovementTweenDurationMilliseconds(2.0));
		assertEquals(200, CustomMovementHandler
				.GetRequestMovementTweenDurationMilliseconds(3.0));
		assertEquals(600, CustomMovementHandler
				.GetRequestMovementTweenDurationMilliseconds(Double.NaN));
		assertEquals(600, CustomMovementHandler
				.GetRequestMovementTweenDurationMilliseconds(
						Double.POSITIVE_INFINITY));

		double ProgressAt599 = CustomMovementHandler
				.ApplyContinuousMovementSpeedLead(
						599.0 / 600.0,
						3.0);
		int From = 1280;
		int To = 1536;
		int Draw = (int) (From + (To - From) * ProgressAt599);
		assertTrue(Draw >= From);
		assertTrue(Draw < To);
		assertEquals(0.0, CustomMovementHandler
				.ApplyContinuousMovementSpeedLead(Double.NaN, 1.3), 0.0);
	}

	@Test
	public void movementSpeedLeadBypassesSpecialSceneTiming()
	{
		assertTrue(CustomMovementHandler
				.ShouldApplyContinuousMovementSpeedLead(
						false, false, false, 0,
						false, false, false));
		assertFalse(CustomMovementHandler
				.ShouldApplyContinuousMovementSpeedLead(
						true, false, false, 0,
						false, false, false));
		assertFalse(CustomMovementHandler
				.ShouldApplyContinuousMovementSpeedLead(
						false, true, false, 0,
						false, false, false));
		assertFalse(CustomMovementHandler
				.ShouldApplyContinuousMovementSpeedLead(
						false, false, true, 0,
						false, false, false));
		assertFalse(CustomMovementHandler
				.ShouldApplyContinuousMovementSpeedLead(
						false, false, false, 450,
						false, false, false));
		assertFalse(CustomMovementHandler
				.ShouldApplyContinuousMovementSpeedLead(
						false, false, false, 0,
						true, false, false));
		assertFalse(CustomMovementHandler
				.ShouldApplyContinuousMovementSpeedLead(
						false, false, false, 0,
						false, true, false));
		assertFalse(CustomMovementHandler
				.ShouldApplyContinuousMovementSpeedLead(
						false, false, false, 0,
						false, false, true));
	}

}
