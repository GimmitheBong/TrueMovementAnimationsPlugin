package com.truetileanimationmovement;

import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.GameTick;
import net.runelite.client.eventbus.Subscribe;

/**
 * Captures native owner models while the actor's animation selectors are
 * temporarily neutral. This lives outside the plugin because RuneLite permits
 * only one correctly named subscriber for each event type on a given object.
 */
final class NeutralModelCaptureSubscriber
{
	private final Client client;
	private final TrueMovementOverlay overlay;

	NeutralModelCaptureSubscriber(Client client, TrueMovementOverlay overlay)
	{
		this.client = client;
		this.overlay = overlay;
	}

	@Subscribe(priority = 1000.0f)
	public void onClientTick(ClientTick event)
	{
		Player player = GetRenderablePlayer();
		if (player == null)
		{
			overlay.InvalidateNeutralOwnerModels();
			return;
		}

		// The cache was armed at the preceding GameTick in this client cycle.
		// Native actor update has consumed the suppressed selectors; capture and
		// restore before normal ClientTick subscribers read the actor.
		overlay.CompleteNeutralOwnerModelCapture(player);
	}

	@Subscribe(priority = -1000.0f)
	public void onGameTick(GameTick event)
	{
		Player player = GetRenderablePlayer();
		if (player == null)
		{
			overlay.InvalidateNeutralOwnerModels();
			return;
		}

		// Run after normal GameTick subscribers. Native actor update later in this
		// cycle consumes the bounded suppression; high-priority ClientTick then
		// captures and restores it before any rendered frame.
		overlay.BeginNeutralOwnerModelCapture(player);
	}

	private Player GetRenderablePlayer()
	{
		if (!client.isGpu() || client.getGameState() != GameState.LOGGED_IN)
		{
			return null;
		}

		return client.getLocalPlayer();
	}
}
