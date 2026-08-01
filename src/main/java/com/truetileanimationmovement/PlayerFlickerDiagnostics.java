package com.truetileanimationmovement;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * [TMA-PLAYER-FLICKER-DIAGNOSTICS]
 *
 * A bounded, read-only recorder for suspected presentation interruptions.
 * Live RuneLite state is deliberately absent from this class: the movement
 * handler creates an immutable {@link Sample} on the client thread and this
 * recorder only classifies and formats that snapshot.
 */
@Slf4j
final class PlayerFlickerDiagnostics
{
	static final int PRE_TRIGGER_SAMPLE_COUNT = 12;
	static final int POST_TRIGGER_SAMPLE_COUNT = 20;
	static final int PERSISTENT_SAMPLE_COUNT = 3;
	static final long INCIDENT_COOLDOWN_NANOS = 3_000_000_000L;

	@FunctionalInterface
	interface StateFormatter
	{
		String format();
	}

	enum Reason
	{
		IDLE_SELECTED_DURING_MOVEMENT("idle-selected-during-movement"),
		POSITION_STALLED_DURING_MOVEMENT("position-stalled-during-movement"),
		CUSTOM_PRESENTATION_MISSING("custom-presentation-missing");

		private final String label;

		Reason(String label)
		{
			this.label = label;
		}

		String getLabel()
		{
			return label;
		}
	}

	static final class Sample
	{
		final long monotonicNanos;
		final boolean stableScene;
		final boolean detectionEligible;
		final boolean idleDetectionEligible;
		final boolean movementExpected;
		final boolean movementSelected;
		final boolean idleSelected;
		final boolean positionStalled;
		final boolean customPresentationRequired;
		final boolean customPresentationReady;
		final StateFormatter stateFormatter;

		Sample(
				long monotonicNanos,
				boolean stableScene,
				boolean detectionEligible,
				boolean idleDetectionEligible,
				boolean movementExpected,
				boolean movementSelected,
				boolean idleSelected,
				boolean positionStalled,
				boolean customPresentationRequired,
				boolean customPresentationReady,
				StateFormatter stateFormatter)
		{
			this.monotonicNanos = monotonicNanos;
			this.stableScene = stableScene;
			this.detectionEligible = detectionEligible;
			this.idleDetectionEligible = idleDetectionEligible;
			this.movementExpected = movementExpected;
			this.movementSelected = movementSelected;
			this.idleSelected = idleSelected;
			this.positionStalled = positionStalled;
			this.customPresentationRequired = customPresentationRequired;
			this.customPresentationReady = customPresentationReady;
			this.stateFormatter = stateFormatter;
		}

		EnumSet<Reason> getReasons()
		{
			EnumSet<Reason> Reasons = EnumSet.noneOf(Reason.class);
			if (!stableScene ||
					!detectionEligible ||
					!movementExpected)
			{
				return Reasons;
			}

			if (idleDetectionEligible &&
					(!movementSelected || idleSelected))
			{
				Reasons.add(Reason.IDLE_SELECTED_DURING_MOVEMENT);
			}
			if (positionStalled)
			{
				Reasons.add(Reason.POSITION_STALLED_DURING_MOVEMENT);
			}
			if (customPresentationRequired &&
					!customPresentationReady)
			{
				Reasons.add(Reason.CUSTOM_PRESENTATION_MISSING);
			}
			return Reasons;
		}

		boolean isHealthyMovement()
		{
			return stableScene &&
					detectionEligible &&
					movementExpected &&
					(!idleDetectionEligible ||
							(movementSelected && !idleSelected)) &&
					!positionStalled &&
					(!customPresentationRequired ||
							customPresentationReady);
		}
	}

	static final class Detection
	{
		private static final Detection NONE =
				new Detection(0, "");

		final int incidentId;
		final String reason;

		private Detection(int incidentId, String reason)
		{
			this.incidentId = incidentId;
			this.reason = reason;
		}

		static Detection none()
		{
			return NONE;
		}

		boolean openedIncident()
		{
			return incidentId > 0;
		}
	}

	private final Deque<Sample> preTriggerSamples =
			new ArrayDeque<>(PRE_TRIGGER_SAMPLE_COUNT);
	private final List<Sample> candidateSamples = new ArrayList<>();
	private final List<Sample> candidatePreTriggerSamples =
			new ArrayList<>(PRE_TRIGGER_SAMPLE_COUNT);
	private final EnumSet<Reason> candidateReasons =
			EnumSet.noneOf(Reason.class);
	private final EnumSet<Reason> activeReasons =
			EnumSet.noneOf(Reason.class);

	private boolean previousHealthyMovement = false;
	private int incidentSequence = 0;
	private int activeIncidentId = 0;
	private int postTriggerSamplesRemaining = 0;
	private long lastIncidentMonotonicNanos = Long.MIN_VALUE;

	Detection accept(Sample Sample)
	{
		if (!Sample.stableScene)
		{
			resetContext("scene-or-client-state-changed");
			return Detection.none();
		}

		if (activeIncidentId != 0)
		{
			EnumSet<Reason> Reasons = Sample.getReasons();
			activeReasons.addAll(Reasons);
			logSample(activeIncidentId, "post", Sample, Reasons);
			--postTriggerSamplesRemaining;
			previousHealthyMovement = Sample.isHealthyMovement();
			if (postTriggerSamplesRemaining <= 0)
			{
				finishActiveIncident("post-window-complete");
			}
			return Detection.none();
		}

		EnumSet<Reason> Reasons = Sample.getReasons();
		if (!candidateSamples.isEmpty())
		{
			if (!Reasons.isEmpty())
			{
				candidateSamples.add(Sample);
				candidateReasons.addAll(Reasons);
				if (candidateSamples.size() >=
						PERSISTENT_SAMPLE_COUNT)
				{
					return confirmIncident(
							Sample,
							"persistent");
				}
				return Detection.none();
			}

			if (Sample.isHealthyMovement())
			{
				candidateSamples.add(Sample);
				return confirmIncident(Sample, "recovered");
			}

			// A real stop, action, or other excluded state followed the
			// candidate. It was not the one-frame run -> idle -> run seam.
			cancelCandidate();
		}

		boolean CooldownComplete =
				lastIncidentMonotonicNanos == Long.MIN_VALUE ||
						Sample.monotonicNanos -
								lastIncidentMonotonicNanos >=
										INCIDENT_COOLDOWN_NANOS;
		if (!Reasons.isEmpty() &&
				previousHealthyMovement &&
				CooldownComplete)
		{
			candidatePreTriggerSamples.addAll(preTriggerSamples);
			candidateSamples.add(Sample);
			candidateReasons.addAll(Reasons);
			previousHealthyMovement = false;
			return Detection.none();
		}

		addPreTriggerSample(Sample);
		previousHealthyMovement = Sample.isHealthyMovement();
		return Detection.none();
	}

	void resetContext(String Cause)
	{
		if (activeIncidentId != 0)
		{
			finishActiveIncident(Cause);
		}
		cancelCandidate();
		preTriggerSamples.clear();
		previousHealthyMovement = false;
	}

	private Detection confirmIncident(
			Sample ConfirmationSample,
			String Confirmation)
	{
		activeIncidentId = ++incidentSequence;
		lastIncidentMonotonicNanos =
				ConfirmationSample.monotonicNanos;
		activeReasons.clear();
		activeReasons.addAll(candidateReasons);

		String ReasonLabel = formatReasons(candidateReasons);
		log.debug(
				"[PlayerFlickerTrace] incident={} phase=begin confirmation={} reason={} preSamples={} candidateSamples={}",
				activeIncidentId,
				Confirmation,
				ReasonLabel,
				candidatePreTriggerSamples.size(),
				candidateSamples.size());
		for (Sample PreTriggerSample : candidatePreTriggerSamples)
		{
			logSample(
					activeIncidentId,
					"pre",
					PreTriggerSample,
					PreTriggerSample.getReasons());
		}
		for (int Index = 0;
			 Index < candidateSamples.size();
			 ++Index)
		{
			Sample CandidateSample = candidateSamples.get(Index);
			String Phase = Index == candidateSamples.size() - 1
					? "confirm"
					: "candidate";
			logSample(
					activeIncidentId,
					Phase,
					CandidateSample,
					CandidateSample.getReasons());
		}

		postTriggerSamplesRemaining =
				POST_TRIGGER_SAMPLE_COUNT;
		previousHealthyMovement =
				ConfirmationSample.isHealthyMovement();
		cancelCandidate();
		preTriggerSamples.clear();
		return new Detection(activeIncidentId, ReasonLabel);
	}

	private void finishActiveIncident(String Cause)
	{
		log.debug(
				"[PlayerFlickerTrace] incident={} phase=end reason={} cause={}",
				activeIncidentId,
				formatReasons(activeReasons),
				Cause);
		activeIncidentId = 0;
		postTriggerSamplesRemaining = 0;
		activeReasons.clear();
	}

	private void cancelCandidate()
	{
		candidateSamples.clear();
		candidatePreTriggerSamples.clear();
		candidateReasons.clear();
	}

	private void addPreTriggerSample(Sample Sample)
	{
		if (preTriggerSamples.size() ==
				PRE_TRIGGER_SAMPLE_COUNT)
		{
			preTriggerSamples.removeFirst();
		}
		preTriggerSamples.addLast(Sample);
	}

	private static String formatReasons(Set<Reason> Reasons)
	{
		if (Reasons.isEmpty())
		{
			return "none";
		}

		StringBuilder Result = new StringBuilder();
		for (Reason Reason : Reasons)
		{
			if (Result.length() > 0)
			{
				Result.append(',');
			}
			Result.append(Reason.getLabel());
		}
		return Result.toString();
	}

	private static void logSample(
			int IncidentId,
			String Phase,
			Sample Sample,
			Set<Reason> Reasons)
	{
		log.debug(
				"[PlayerFlickerTrace] incident={} phase={} reason={} {}",
				IncidentId,
				Phase,
				formatReasons(Reasons),
				Sample.stateFormatter.format());
	}
}
