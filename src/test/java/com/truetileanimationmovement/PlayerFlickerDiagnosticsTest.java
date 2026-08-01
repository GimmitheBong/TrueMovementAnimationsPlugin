package com.truetileanimationmovement;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PlayerFlickerDiagnosticsTest
{
	private static final long CLIENT_TICK_NANOS = 20_000_000L;

	@Test
	public void oneIdleSampleBetweenMovementSamplesOpensOneIncident()
	{
		PlayerFlickerDiagnostics Recorder =
				new PlayerFlickerDiagnostics();

		assertFalse(Recorder.accept(healthy(0)).openedIncident());
		assertFalse(Recorder.accept(idle(20)).openedIncident());
		PlayerFlickerDiagnostics.Detection Detection =
				Recorder.accept(healthy(40));

		assertTrue(Detection.openedIncident());
		assertTrue(Detection.reason.contains(
				"idle-selected-during-movement"));
		// The post-window owns nearby samples, so one visual interruption
		// cannot emit another chat marker immediately.
		assertFalse(Recorder.accept(idle(60)).openedIncident());
	}

	@Test
	public void genuineRouteEndDoesNotOpenAnIncident()
	{
		PlayerFlickerDiagnostics Recorder =
				new PlayerFlickerDiagnostics();

		assertFalse(Recorder.accept(healthy(0)).openedIncident());
		assertFalse(Recorder.accept(stopped(20)).openedIncident());
		assertFalse(Recorder.accept(stopped(40)).openedIncident());
	}

	@Test
	public void sceneChangeCancelsAnUnconfirmedCandidate()
	{
		PlayerFlickerDiagnostics Recorder =
				new PlayerFlickerDiagnostics();

		assertFalse(Recorder.accept(healthy(0)).openedIncident());
		assertFalse(Recorder.accept(idle(20)).openedIncident());
		assertFalse(Recorder.accept(unstable(40)).openedIncident());
		assertFalse(Recorder.accept(healthy(60)).openedIncident());
	}

	@Test
	public void persistentMidRouteIdleConfirmsAfterThreeSamples()
	{
		PlayerFlickerDiagnostics Recorder =
				new PlayerFlickerDiagnostics();

		assertFalse(Recorder.accept(healthy(0)).openedIncident());
		assertFalse(Recorder.accept(idle(20)).openedIncident());
		assertFalse(Recorder.accept(idle(40)).openedIncident());
		assertTrue(Recorder.accept(idle(60)).openedIncident());
	}

	@Test
	public void missingCustomPresentationIsRecordedAfterRecovery()
	{
		PlayerFlickerDiagnostics Recorder =
				new PlayerFlickerDiagnostics();

		assertFalse(Recorder.accept(healthy(0)).openedIncident());
		assertFalse(Recorder.accept(missingModel(20)).openedIncident());
		PlayerFlickerDiagnostics.Detection Detection =
				Recorder.accept(healthy(40));

		assertTrue(Detection.openedIncident());
		assertTrue(Detection.reason.contains(
				"custom-presentation-missing"));
	}

	@Test
	public void excludedActionCannotBeMisclassifiedAsIdleFlicker()
	{
		PlayerFlickerDiagnostics Recorder =
				new PlayerFlickerDiagnostics();

		assertFalse(Recorder.accept(healthy(0)).openedIncident());
		assertFalse(Recorder.accept(excludedAction(20)).openedIncident());
		assertFalse(Recorder.accept(healthy(40)).openedIncident());
	}

	@Test
	public void actionStillAllowsMissingPresentationDetection()
	{
		PlayerFlickerDiagnostics Recorder =
				new PlayerFlickerDiagnostics();

		assertFalse(Recorder.accept(healthy(0)).openedIncident());
		assertFalse(Recorder.accept(actionWithMissingModel(20)).openedIncident());
		PlayerFlickerDiagnostics.Detection Detection =
				Recorder.accept(healthy(40));

		assertTrue(Detection.openedIncident());
		assertTrue(Detection.reason.contains(
				"custom-presentation-missing"));
	}

	@Test
	public void positionStallDuringLocomotionIsRecordedAfterRecovery()
	{
		PlayerFlickerDiagnostics Recorder =
				new PlayerFlickerDiagnostics();

		assertFalse(Recorder.accept(healthy(0)).openedIncident());
		assertFalse(Recorder.accept(positionStalled(20)).openedIncident());
		PlayerFlickerDiagnostics.Detection Detection =
				Recorder.accept(healthy(40));

		assertTrue(Detection.openedIncident());
		assertTrue(Detection.reason.contains(
				"position-stalled-during-movement"));
	}

	@Test
	public void nativePresentationDoesNotRequireCustomModel()
	{
		PlayerFlickerDiagnostics Recorder =
				new PlayerFlickerDiagnostics();

		assertFalse(Recorder.accept(healthy(0)).openedIncident());
		assertFalse(Recorder.accept(
				sample(20, true, true, true, true, true, false,
						false, false)).openedIncident());
		assertFalse(Recorder.accept(healthy(40)).openedIncident());
	}

	@Test
	public void formattingIsLazyAndWindowsAreBounded()
	{
		PlayerFlickerDiagnostics Recorder =
				new PlayerFlickerDiagnostics();
		AtomicInteger FormatCount = new AtomicInteger();
		long Time = 0;

		for (int Index = 0;
			 Index < PlayerFlickerDiagnostics.PRE_TRIGGER_SAMPLE_COUNT + 5;
			 ++Index)
		{
			assertFalse(Recorder.accept(
					healthy(Time, FormatCount)).openedIncident());
			Time += CLIENT_TICK_NANOS;
		}
		assertTrue(FormatCount.get() == 0);

		assertFalse(Recorder.accept(
				idle(Time, FormatCount)).openedIncident());
		Time += CLIENT_TICK_NANOS;
		assertTrue(Recorder.accept(
				healthy(Time, FormatCount)).openedIncident());
		Time += CLIENT_TICK_NANOS;
		assertTrue(FormatCount.get() ==
				PlayerFlickerDiagnostics.PRE_TRIGGER_SAMPLE_COUNT + 2);

		for (int Index = 0;
			 Index < PlayerFlickerDiagnostics.POST_TRIGGER_SAMPLE_COUNT;
			 ++Index)
		{
			assertFalse(Recorder.accept(
					healthy(Time, FormatCount)).openedIncident());
			Time += CLIENT_TICK_NANOS;
		}
		assertTrue(FormatCount.get() ==
				PlayerFlickerDiagnostics.PRE_TRIGGER_SAMPLE_COUNT + 2 +
						PlayerFlickerDiagnostics.POST_TRIGGER_SAMPLE_COUNT);
		assertFalse(Recorder.accept(
				healthy(Time, FormatCount)).openedIncident());
		assertTrue(FormatCount.get() ==
				PlayerFlickerDiagnostics.PRE_TRIGGER_SAMPLE_COUNT + 2 +
						PlayerFlickerDiagnostics.POST_TRIGGER_SAMPLE_COUNT);
	}

	@Test
	public void incidentCooldownUsesMonotonicTime()
	{
		PlayerFlickerDiagnostics Recorder =
				new PlayerFlickerDiagnostics();
		long Time = 0;

		assertFalse(Recorder.accept(healthy(Time)).openedIncident());
		Time += CLIENT_TICK_NANOS;
		assertFalse(Recorder.accept(idle(Time)).openedIncident());
		Time += CLIENT_TICK_NANOS;
		assertTrue(Recorder.accept(healthy(Time)).openedIncident());
		long FirstIncidentTime = Time;

		for (int Index = 0;
			 Index < PlayerFlickerDiagnostics.POST_TRIGGER_SAMPLE_COUNT;
			 ++Index)
		{
			Time += CLIENT_TICK_NANOS;
			assertFalse(Recorder.accept(healthy(Time)).openedIncident());
		}

		Time += CLIENT_TICK_NANOS;
		assertFalse(Recorder.accept(idle(Time)).openedIncident());
		Time += CLIENT_TICK_NANOS;
		assertFalse(Recorder.accept(healthy(Time)).openedIncident());

		Time = FirstIncidentTime +
				PlayerFlickerDiagnostics.INCIDENT_COOLDOWN_NANOS;
		assertFalse(Recorder.accept(idle(Time)).openedIncident());
		Time += CLIENT_TICK_NANOS;
		assertTrue(Recorder.accept(healthy(Time)).openedIncident());
	}

	private static PlayerFlickerDiagnostics.Sample healthy(long Time)
	{
		return sample(Time, true, true, true, true, true, false,
				true, true);
	}

	private static PlayerFlickerDiagnostics.Sample idle(long Time)
	{
		return sample(Time, true, true, true, true, false, true,
				true, true);
	}

	private static PlayerFlickerDiagnostics.Sample stopped(long Time)
	{
		return sample(Time, true, true, true, false, false, true,
				true, true);
	}

	private static PlayerFlickerDiagnostics.Sample unstable(long Time)
	{
		return sample(Time, false, false, false, true, false, true,
				true, false);
	}

	private static PlayerFlickerDiagnostics.Sample missingModel(long Time)
	{
		return sample(Time, true, true, true, true, true, false,
				true, false);
	}

	private static PlayerFlickerDiagnostics.Sample excludedAction(long Time)
	{
		return sample(Time, true, true, false, true, false, true,
				true, true);
	}

	private static PlayerFlickerDiagnostics.Sample actionWithMissingModel(
			long Time)
	{
		return sample(Time, true, true, false, true, false, true,
				true, false);
	}

	private static PlayerFlickerDiagnostics.Sample positionStalled(long Time)
	{
		return new PlayerFlickerDiagnostics.Sample(
				Time,
				true,
				true,
				true,
				true,
				true,
				false,
				true,
				true,
				true,
				() -> "time=" + Time);
	}

	private static PlayerFlickerDiagnostics.Sample healthy(
			long Time,
			AtomicInteger FormatCount)
	{
		return sample(Time, true, true, true, true, true, false,
				true, true, FormatCount);
	}

	private static PlayerFlickerDiagnostics.Sample idle(
			long Time,
			AtomicInteger FormatCount)
	{
		return sample(Time, true, true, true, true, false, true,
				true, true, FormatCount);
	}

	private static PlayerFlickerDiagnostics.Sample sample(
			long Time,
			boolean StableScene,
			boolean DetectionEligible,
			boolean IdleDetectionEligible,
			boolean MovementExpected,
			boolean MovementSelected,
			boolean IdleSelected,
			boolean CustomPresentationRequired,
			boolean CustomPresentationReady)
	{
		return sample(
				Time,
				StableScene,
				DetectionEligible,
				IdleDetectionEligible,
				MovementExpected,
				MovementSelected,
				IdleSelected,
				CustomPresentationRequired,
				CustomPresentationReady,
				new AtomicInteger());
	}

	private static PlayerFlickerDiagnostics.Sample sample(
			long Time,
			boolean StableScene,
			boolean DetectionEligible,
			boolean IdleDetectionEligible,
			boolean MovementExpected,
			boolean MovementSelected,
			boolean IdleSelected,
			boolean CustomPresentationRequired,
			boolean CustomPresentationReady,
			AtomicInteger FormatCount)
	{
		return new PlayerFlickerDiagnostics.Sample(
				Time,
				StableScene,
				DetectionEligible,
				IdleDetectionEligible,
				MovementExpected,
				MovementSelected,
				IdleSelected,
				false,
				CustomPresentationRequired,
				CustomPresentationReady,
				() ->
				{
					FormatCount.incrementAndGet();
					return "time=" + Time;
				});
	}
}
