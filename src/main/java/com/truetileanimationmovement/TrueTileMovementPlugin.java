package com.truetileanimationmovement;

import com.google.inject.Provides;
import javax.inject.Inject;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.*;
import net.runelite.api.gameval.VarClientID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.callback.RenderCallback;
import net.runelite.client.callback.RenderCallbackManager;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.input.KeyListener;
import net.runelite.client.input.KeyManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.DrawManager;
import net.runelite.client.ui.overlay.OverlayManager;

import java.awt.image.BufferedImage;
import java.util.*;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.runelite.api.Perspective;
import net.runelite.client.util.ImageUtil;

import static net.runelite.api.HitsplatID.*;
import static net.runelite.api.MenuAction.*;

@Slf4j
@PluginDescriptor(
	name = "True Tile Movement"
)
public class TrueTileMovementPlugin extends Plugin
{
	@SuppressWarnings("deprecation")
	private static final Set<MenuAction> STATIONARY_INTERACTION_ACTIONS =
			EnumSet.of(
					MenuAction.ITEM_USE_ON_GAME_OBJECT,
					MenuAction.WIDGET_TARGET_ON_GAME_OBJECT,
					MenuAction.GAME_OBJECT_FIRST_OPTION,
					MenuAction.GAME_OBJECT_SECOND_OPTION,
					MenuAction.GAME_OBJECT_THIRD_OPTION,
					MenuAction.GAME_OBJECT_FOURTH_OPTION,
					MenuAction.GAME_OBJECT_FIFTH_OPTION,
					MenuAction.ITEM_USE_ON_NPC,
					MenuAction.WIDGET_TARGET_ON_NPC,
					MenuAction.NPC_FIRST_OPTION,
					MenuAction.NPC_SECOND_OPTION,
					MenuAction.NPC_THIRD_OPTION,
					MenuAction.NPC_FOURTH_OPTION,
					MenuAction.NPC_FIFTH_OPTION);

	@Inject
	private Client client;

	@Inject
	private TrueTileMovementConfig config;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private TrueMovementOverlay OverlayRenderer;

	@Inject
	private RenderCallbackManager renderCallbackManager;

	@Inject
	private ClientThread clientThread;

	@Inject
	private DrawManager drawManager;

	@Inject
	private EventBus eventBus;

	private NeutralModelCaptureSubscriber neutralModelCaptureSubscriber;

	private boolean neutralModelCaptureSubscriberRegistered = false;

	public List<Hitsplat> CurrentHitsplats = new ArrayList<>();
	public volatile boolean bIsPluginSupportedCurrently = true;
	private static final int TILE_OBJECT_TYPE_PLAYER = 0;
	private volatile Player hiddenLocalPlayer = null;
	private volatile int hiddenLocalPlayerId = -1;
	private volatile int hiddenLocalPlayerWorldViewId = -1;
	private volatile boolean hideLocalPlayerScene = false;
	private volatile boolean hideLocalPlayerUi = false;
	// [TMA-R01] Scene and UI suppression are published only after the complete
	// replacement frame is ready and identify this exact player/world view.
	private final RenderCallback renderCallback = new RenderCallback()
	{
		@Override
		public boolean addEntity(Renderable renderable, boolean ui)
		{
			if (bForceEarlyOut || !bIsPluginSupportedCurrently || !config.CustomOverheadRendering() || client.getLocalPlayer() == null)
			if (!hideLocalPlayerUi)
			{
				return true;
			}

			return !ui ||
					renderable != hiddenLocalPlayer;
		}

		@Override
		public boolean drawObject(Scene scene, TileObject object)
		{
			if (bForceEarlyOut)
			{
				return true;
			}

			// Only supported with GPU plugin
			TicksSincePluginWasSupport = 0;
			bIsPluginSupportedCurrently = true;

			// hide player
			CustomMovementHandler FoundHandler = OverlayRenderer.MovementHandlerCache.get(object.getId());
			if (FoundHandler != null && !FoundHandler.bShouldRenderOwner && !FoundHandler.bRenderOriginalOwnerDueToProximity)
			{
				return false;
			}

			return true;
			return ShouldDrawTileObject(
					hideLocalPlayerScene,
					hiddenLocalPlayerId,
					hiddenLocalPlayerWorldViewId,
					object.getHash(),
					object.getId());
        }
	};

	public volatile boolean bForceEarlyOut = false;

	public boolean bForceAdaptiveCameraOff = false;
	private float CurrentCameraPositionX = -1; // Offset in "sudo world space" (see adaptive camera function)
	private float CurrentCameraPositionY = Float.NaN;
	private float CurrentCameraPositionZ = -1;
	private static final float ADAPTIVE_CAMERA_REFERENCE_FRAME_MILLISECONDS = 16.667f;
	private static final float MAX_ADAPTIVE_CAMERA_FRAME_DELTA_MILLISECONDS = 100.0f;
	private long LastAdaptiveCameraUpdateNanos = 0;
	private static final float ADAPTIVE_CAMERA_VERTICAL_SMOOTHING_MILLISECONDS = 100.0f;
	private long LastAdaptiveCameraUpdateNanos = 0;
	// Keep free-camera mode confined to the adaptive frame: native input and menu
	// processing run after presentation, while the normal camera is never rendered.
	private volatile boolean bAdaptiveCameraRenderedThisFrame = false;
	private final Runnable PostDrawCameraModeHandoff = () ->
	{
		boolean AdaptiveCameraWasRendered = bAdaptiveCameraRenderedThisFrame;
		if (AdaptiveCameraWasRendered)
		{
			bAdaptiveCameraRenderedThisFrame = false;
			client.setCameraMode(0);
		}
	};

	private static final int CAMERA_VIEWPORT_BASE_HEIGHT = 334;
	private static final int CAMERA_VIEWPORT_ZOOM_BLEND_RANGE = 100;
	private static final int CAMERA_FOLLOW_HEIGHT_BASE = 25;
	private static final int CAMERA_FOLLOW_HEIGHT_SCALE = 25;
	private static final int CAMERA_FOLLOW_HEIGHT_DIVISOR = 256;

	private WorldView currentWorldView = null;
	private int LastPrintedAnimation = 0;

	private boolean IsAdaptiveCameraOn()
	{
		return !bForceAdaptiveCameraOff && config.AdaptiveCameraOn();
	}


	private static final int CAMERA_VIEWPORT_BASE_HEIGHT = 334;
	private static final int CAMERA_VIEWPORT_ZOOM_BLEND_RANGE = 100;
	private static final int CAMERA_FOLLOW_HEIGHT_BASE = 25;
	private static final int CAMERA_FOLLOW_HEIGHT_SCALE = 25;
	private static final int CAMERA_FOLLOW_HEIGHT_DIVISOR = 256;

	private WorldView currentWorldView = null;
	private boolean IsAdaptiveCameraOn()
	{
		return !bForceAdaptiveCameraOff && config.AdaptiveCameraOn();
	}

	static boolean ShouldDrawTileObject(
			boolean HideLocalPlayer,
			int HiddenLocalPlayerId,
			int HiddenLocalPlayerWorldViewId,
			long ObjectHash,
			int ObjectId)
	{
		int ObjectType = (int) ((ObjectHash >>> 16) & 7L);
		int ObjectWorldViewId = (int) ((ObjectHash >>> 52) & 4095L);
		return !HideLocalPlayer ||
				ObjectType != TILE_OBJECT_TYPE_PLAYER ||
				ObjectId != HiddenLocalPlayerId ||
				ObjectWorldViewId != HiddenLocalPlayerWorldViewId;
	}

	static boolean IsSameWorldView(WorldView First, WorldView Second)
	{
		return First != null && Second != null && First.getId() == Second.getId();
	}

	private void PublishLocalPlayerRenderState(Player player, boolean HideLocalPlayer)
	{
		// [TMA-R01, TMA-R12] Clearing these fields is the fail-open operation:
		// RuneScape immediately resumes ownership of the normal player render.
		if (!HideLocalPlayer || player == null)
		{
			hideLocalPlayerScene = false;
			hideLocalPlayerUi = false;
			hiddenLocalPlayer = null;
			hiddenLocalPlayerId = -1;
			hiddenLocalPlayerWorldViewId = -1;
			return;
		}

		hiddenLocalPlayer = player;
		hiddenLocalPlayerId = player.getId();
		hiddenLocalPlayerWorldViewId = player.getWorldView().getId();
		hideLocalPlayerUi = config.CustomOverheadRendering();
		hideLocalPlayerScene = true;
	}

	private float GetAdaptiveCameraFrameDeltaMilliseconds()
	{
		long CurrentUpdateNanos = System.nanoTime();
		float FrameDeltaMilliseconds = ADAPTIVE_CAMERA_REFERENCE_FRAME_MILLISECONDS;

		if (LastAdaptiveCameraUpdateNanos != 0 && CurrentUpdateNanos > LastAdaptiveCameraUpdateNanos)
		{
			FrameDeltaMilliseconds = Math.min(
					(CurrentUpdateNanos - LastAdaptiveCameraUpdateNanos) / 1_000_000.0f,
					MAX_ADAPTIVE_CAMERA_FRAME_DELTA_MILLISECONDS);
		}

		LastAdaptiveCameraUpdateNanos = CurrentUpdateNanos;
		return FrameDeltaMilliseconds;
	}

	@Subscribe
	public void onClientTick(ClientTick event)
	{
		bIsPluginSupportedCurrently = client.isGpu();
		Player player = client.getLocalPlayer();
		GameState gameState = client.getGameState();

		if (bForceEarlyOut ||
				!bIsPluginSupportedCurrently ||
				player == null ||
				gameState != GameState.LOGGED_IN)
		{
			PublishLocalPlayerRenderState(null, false);
			bForceAdaptiveCameraOff = true;
			if (bIsPluginSupportedCurrently && gameState == GameState.LOADING)
			{
				// [TMA-R09] Region rebuilds are temporary. Keep interpolation
				// history and the
				// last good model, but recreate scene-owned RuneLiteObjects when the
				// client becomes renderable again.
				OverlayRenderer.InvalidateRuneLiteObjects();
			}
			else
			{
				OverlayRenderer.Cleanup();
				CurrentHitsplats.clear();
				LastTimeHitSplatApplied = 0;
				currentWorldView = null;
			}
			CurrentCameraPositionX = -1;
			CurrentCameraPositionY = Float.NaN;
			CurrentCameraPositionZ = -1;
			LastAdaptiveCameraUpdateNanos = 0;
			bAdaptiveCameraRenderedThisFrame = false;
			client.setCameraMode(0);
			return;
		}

		bForceAdaptiveCameraOff = !IsSameWorldView(client.getWorldView(-1), player.getWorldView());

		// Input, menu sorting, and click detection always use the native camera.
		// BeforeRender installs the adaptive focal point only for presentation.
		if (IsAdaptiveCameraOn())
		{
			client.setCameraMode(0);
		}

		if (client.getLocalPlayer() == null || client.getWorldView(-1) != client.getLocalPlayer().getWorldView())
		{
			bForceAdaptiveCameraOff = true;
		}
		else
		{
			bForceAdaptiveCameraOff = false;
		}

		// Plugin no longer supported (Need GPU plugin)
		if (TicksSincePluginWasSupport > 5)
		{
			bIsPluginSupportedCurrently = false;
		}
		else
		{
			bIsPluginSupportedCurrently = true;
		}
		++TicksSincePluginWasSupport;
	}

	private void UpdateAdaptiveCamera(
			CustomMovementHandler PlayerMovementHandler,
			float FootprintHeight,
			int CameraFollowHeight)
	}

	static boolean ShouldRenderAdaptiveCamera(
			boolean AdaptiveCameraOn,
			boolean ShouldRenderOwner)
	{
		return AdaptiveCameraOn && !ShouldRenderOwner;
	}

	static boolean ShouldHidePreparedPlayer(
			boolean ShouldRenderOwner,
			boolean HasRenderableModel,
			int RenderedWorldViewId,
			int PlayerWorldViewId)
	{
		return !ShouldRenderOwner &&
				HasRenderableModel &&
				RenderedWorldViewId == PlayerWorldViewId;
	}

	private void UpdateAdaptiveCamera(
			CustomMovementHandler PlayerMovementHandler,
			float FootprintHeight,
			int CameraFollowHeight)
	{
		// [TMA-R10] Camera position and height come from the already prepared
		// replacement frame, never from a separately sampled hidden actor pose.
		Player player = client.getLocalPlayer();
		WorldPoint trueWorldTile = player.getWorldLocation();
		LocalPoint trueLocalTile = LocalPoint.fromWorld(client, trueWorldTile);
		if (trueLocalTile == null)
		{
			LastAdaptiveCameraUpdateNanos = 0;
			CurrentCameraPositionY = Float.NaN;
			return;
		}
		float CameraFrameDeltaMilliseconds = GetAdaptiveCameraFrameDeltaMilliseconds();

		// Store in sudo world space to prevent jumps when loading new chunks
		double CalculationOffsetVectorX = trueLocalTile.getX() - trueWorldTile.getX() * 128;
		double CalculationOffsetVectorY = trueLocalTile.getY() - trueWorldTile.getY() * 128;

		// Update our focal point Y (probably can calculate this somehow)
		if (CurrentCameraPositionX == -1 || CurrentCameraPositionZ == -1)
		{
			CurrentCameraPositionX = client.getCameraFocalPointX();
			CurrentCameraPositionZ = client.getCameraFocalPointZ();
		}
		else
		{
			CurrentCameraPositionX += (float) CalculationOffsetVectorX;
			CurrentCameraPositionZ += (float) CalculationOffsetVectorY;
		}

        LocalPoint CameraDestination = PlayerMovementHandler.Model.getLocation();
		LocalPoint CurrentCameraPositionLp = new LocalPoint((int) CurrentCameraPositionX, (int) CurrentCameraPositionZ, CameraDestination.getWorldView());
		float DistanceToTarget = CameraDestination.distanceTo(CurrentCameraPositionLp);
		float TileMaxDistanceAllowed = Math.max(
				1.0f,
				config.AdaptiveCameraMaxDistanceAllowed()); // Edge of circle

		// Slower the closer we are to the center
		float Velocity = Math.max(
				0.0f,
				(float) config.AdaptiveCameraReturnVelocity()) *
				(DistanceToTarget / TileMaxDistanceAllowed);

		boolean SnapToTarget = DistanceToTarget >
				Math.max(0.0, config.AdaptiveCameraSnapDistance()) * Perspective.LOCAL_TILE_SIZE;
		if (SnapToTarget)
		{
			CurrentCameraPositionX = CameraDestination.getX();
			CurrentCameraPositionZ = CameraDestination.getY();
		}

		float DirectionX = CameraDestination.getX() - CurrentCameraPositionX;
		float DirectionZ = CameraDestination.getY() - CurrentCameraPositionZ;

		float DistanceX = Math.abs(DirectionX);
		float DistanceZ = Math.abs(DirectionZ);

		// Scale with the interval for this rendered camera frame. The movement handler is
		// updated later in overlay rendering, so its CurrentFrameDelta belongs to the prior frame.
		// Scale with the interval for this rendered camera frame. Movement and camera
		// preparation share this BeforeRender snapshot, so neither depends on overlay FPS.
		Velocity *= CameraFrameDeltaMilliseconds / ADAPTIVE_CAMERA_REFERENCE_FRAME_MILLISECONDS;

		if (DistanceToTarget != 0)
		{
			DirectionX /= DistanceToTarget;
			DirectionZ /= DistanceToTarget;

			float DistanceToMoveX = DirectionX * Velocity;
			float DistanceToMoveZ = DirectionZ * Velocity;

			if (DistanceX < Math.abs(DistanceToMoveX))
			{
				CurrentCameraPositionX = CameraDestination.getX();
			}
			else
			{
				CurrentCameraPositionX += DistanceToMoveX;
			}

			if (DistanceZ < Math.abs(DistanceToMoveZ))
			{
				CurrentCameraPositionZ = CameraDestination.getY();
			}
			else
			{
				CurrentCameraPositionZ += DistanceToMoveZ;
			}
		}

		if (!Float.isFinite(CurrentCameraPositionY))
		{
			CurrentCameraPositionY = client.getCameraFocalPointY();
		}

		client.setCameraMode(1);
		client.setFreeCameraSpeed(0);

		CurrentCameraPositionY = SmoothCameraFocalPointY(
				CurrentCameraPositionY,
				GetAdaptiveCameraFocalPointY(FootprintHeight, CameraFollowHeight),
				CameraFrameDeltaMilliseconds,
				SnapToTarget);

		client.setCameraFocalPointX(CurrentCameraPositionX);
		client.setCameraFocalPointY(FootprintHeight - CameraFollowHeight);
		client.setCameraFocalPointY(CurrentCameraPositionY);
		client.setCameraFocalPointZ(CurrentCameraPositionZ);
		bAdaptiveCameraRenderedThisFrame = true;

		// Store in sudo-world space to prevent jumps
		CurrentCameraPositionX -= (float) CalculationOffsetVectorX;
		CurrentCameraPositionZ -= (float) CalculationOffsetVectorY;
	}

	static float GetAdaptiveCameraFocalPointY(float FootprintHeight, int CameraFollowHeight)
	{
		return FootprintHeight - CameraFollowHeight;
	}

	static float SmoothCameraFocalPointY(
			float CurrentY,
			float TargetY,
			float FrameDeltaMilliseconds,
			boolean SnapToTarget)
	{
		if (SnapToTarget || !Float.isFinite(CurrentY))
		{
			return TargetY;
		}

		float ClampedDelta = Math.max(
				0,
				Math.min(FrameDeltaMilliseconds, MAX_ADAPTIVE_CAMERA_FRAME_DELTA_MILLISECONDS));
		float Blend = 1.0f - (float) Math.exp(
				-ClampedDelta / ADAPTIVE_CAMERA_VERTICAL_SMOOTHING_MILLISECONDS);
		return CurrentY + (TargetY - CurrentY) * Blend;
	}

	private static int Clamp(int value, int minimum, int maximum)
	{
		return Math.max(minimum, Math.min(maximum, value));
	}

	/**
	 * Mirrors the normal camera settings script. In particular, its final
	 * multiply and divide use integer arithmetic; the follow height is not a
	 * floating-point zoom delta.
	 */
	private int GetCameraFollowHeight()
	{
		int SmallZoom = Clamp(
				client.getVarcIntValue(VarClientID.CAMERA_ZOOM_SMALL),
				client.getVarcIntValue(VarClientID.CAMERA_ZOOM_SMALL_MIN),
				client.getVarcIntValue(VarClientID.CAMERA_ZOOM_SMALL_MAX));
		int BigZoom = Clamp(
				client.getVarcIntValue(VarClientID.CAMERA_ZOOM_BIG),
				client.getVarcIntValue(VarClientID.CAMERA_ZOOM_BIG_MIN),
				client.getVarcIntValue(VarClientID.CAMERA_ZOOM_BIG_MAX));
		int ViewportBlend = Clamp(
				client.getViewportHeight() - CAMERA_VIEWPORT_BASE_HEIGHT,
				0,
				CAMERA_VIEWPORT_ZOOM_BLEND_RANGE);
		int EffectiveZoom = SmallZoom +
				(BigZoom - SmallZoom) * ViewportBlend / CAMERA_VIEWPORT_ZOOM_BLEND_RANGE;
		return CAMERA_FOLLOW_HEIGHT_BASE +
				CAMERA_FOLLOW_HEIGHT_SCALE * EffectiveZoom / CAMERA_FOLLOW_HEIGHT_DIVISOR;
	}

	/**
	 * Matches the native client's floating-point terrain interpolation used by
	 * its camera follow calculation. The public Perspective helper returns an
	 * integer and loses the fractional terrain component on sloped tiles.
	 */
	private static float GetCameraTileHeight(
			WorldView worldView,
			float localX,
			float localY,
			int plane)
	{
		int TileX = (int) (localX / Perspective.LOCAL_TILE_SIZE);
		int TileY = (int) (localY / Perspective.LOCAL_TILE_SIZE);
		if (TileX < 0 || TileY < 0 || TileX >= worldView.getSizeX() || TileY >= worldView.getSizeY())
		{
			return 0;
		}

		int EffectivePlane = plane;
		byte[][][] TileSettings = worldView.getTileSettings();
		if (plane < 3 && (TileSettings[1][TileX][TileY] & 2) == 2)
		{
			EffectivePlane++;
		}

		int[][] TileHeights = worldView.getTileHeights()[EffectivePlane];
		float TileOffsetX = localX % Perspective.LOCAL_TILE_SIZE;
		float TileOffsetY = localY % Perspective.LOCAL_TILE_SIZE;
		float SouthHeight =
				(Perspective.LOCAL_TILE_SIZE - TileOffsetX) * TileHeights[TileX][TileY] +
						TileOffsetX * TileHeights[TileX + 1][TileY];
		SouthHeight /= Perspective.LOCAL_TILE_SIZE;
		float NorthHeight =
				(Perspective.LOCAL_TILE_SIZE - TileOffsetX) * TileHeights[TileX][TileY + 1] +
						TileOffsetX * TileHeights[TileX + 1][TileY + 1];
				TileOffsetX * TileHeights[TileX + 1][TileY];
		SouthHeight /= Perspective.LOCAL_TILE_SIZE;
		float NorthHeight =
				(Perspective.LOCAL_TILE_SIZE - TileOffsetX) * TileHeights[TileX][TileY + 1] +
				TileOffsetX * TileHeights[TileX + 1][TileY + 1];
		NorthHeight /= Perspective.LOCAL_TILE_SIZE;

		return (TileOffsetY * NorthHeight +
				(Perspective.LOCAL_TILE_SIZE - TileOffsetY) * SouthHeight) /
				Perspective.LOCAL_TILE_SIZE;
	}

	private static float GetCameraFootprintTileHeight(
			WorldView worldView,
			LocalPoint localLocation,
			int plane,
			int footprintSize)
	{
		float LocalX = localLocation.getX();
		float LocalY = localLocation.getY();
		int LocalX = localLocation.getX();
		int LocalY = localLocation.getY();
		if (footprintSize == 0)
		{
			return GetCameraTileHeight(worldView, LocalX, LocalY, plane);
		}

		int HalfFootprint = footprintSize / 2;
		float Left = LocalX - HalfFootprint;
		float Bottom = LocalY - HalfFootprint;
		float Right = LocalX + HalfFootprint;
		float Top = LocalY + HalfFootprint;
		float MinimumHeight = Float.MAX_VALUE;

		for (float TileX = Left / Perspective.LOCAL_TILE_SIZE + 1;
		     TileX <= Right / Perspective.LOCAL_TILE_SIZE;
		     TileX++)
		{
			for (float TileY = Bottom / Perspective.LOCAL_TILE_SIZE + 1;
			     TileY <= Top / Perspective.LOCAL_TILE_SIZE;
			     TileY++)
		int Left = LocalX - HalfFootprint;
		int Bottom = LocalY - HalfFootprint;
		int Right = LocalX + HalfFootprint;
		int Top = LocalY + HalfFootprint;
		float MinimumHeight = Float.MAX_VALUE;

		int FirstTileX = (Left >> Perspective.LOCAL_COORD_BITS) + 1;
		int FirstTileY = (Bottom >> Perspective.LOCAL_COORD_BITS) + 1;
		int LastTileX = Right >> Perspective.LOCAL_COORD_BITS;
		int LastTileY = Top >> Perspective.LOCAL_COORD_BITS;
		for (int TileX = FirstTileX; TileX <= LastTileX; TileX++)
		{
			for (int TileY = FirstTileY; TileY <= LastTileY; TileY++)
			{
				MinimumHeight = Math.min(
						MinimumHeight,
						GetCameraTileHeight(
								worldView,
								TileX * Perspective.LOCAL_TILE_SIZE,
								TileY * Perspective.LOCAL_TILE_SIZE,
								plane));
			}
		}

		MinimumHeight = Math.min(MinimumHeight, GetCameraTileHeight(worldView, LocalX, LocalY, plane));
		MinimumHeight = Math.min(MinimumHeight, GetCameraTileHeight(worldView, Left, Bottom, plane));
		MinimumHeight = Math.min(MinimumHeight, GetCameraTileHeight(worldView, Left, Top, plane));
		MinimumHeight = Math.min(MinimumHeight, GetCameraTileHeight(worldView, Right, Bottom, plane));
		MinimumHeight = Math.min(MinimumHeight, GetCameraTileHeight(worldView, Right, Top, plane));
		return MinimumHeight;
	}

	static boolean ShouldRenderAdaptiveCamera(
			boolean AdaptiveCameraOn,
			boolean ShouldRenderOwner)
	{
		return AdaptiveCameraOn && !ShouldRenderOwner;
	}
	@Subscribe
	public void onBeforeRender(BeforeRender beforeRender)
	{
		bAdaptiveCameraRenderedThisFrame = false;
		if (bForceEarlyOut || !bIsPluginSupportedCurrently || client.getLocalPlayer() == null)
		{
	@Subscribe
	public void onBeforeRender(BeforeRender beforeRender)
	{
		// [TMA-R01, TMA-R10] Prepare model, hiding state, overlays, and camera
		// from one snapshot. No other callback is allowed to publish a frame.
		bAdaptiveCameraRenderedThisFrame = false;
		Player player = client.getLocalPlayer();
		if (bForceEarlyOut ||
				!bIsPluginSupportedCurrently ||
				player == null ||
				client.getGameState() != GameState.LOGGED_IN)
		{
			PublishLocalPlayerRenderState(null, false);
			LastAdaptiveCameraUpdateNanos = 0;
			return;
		}

		Player player = client.getLocalPlayer();
		CustomMovementHandler PlayerMovementHandler = OverlayRenderer.MovementHandlerCache.get(player.getId());
		if (PlayerMovementHandler == null)
		{
			LastAdaptiveCameraUpdateNanos = 0;
			return;
		}
		LocalPoint CameraHeightLocation = PlayerMovementHandler.Model == null
				? null
				: PlayerMovementHandler.Model.getLocation();
		if (CameraHeightLocation == null)
		{
			LastAdaptiveCameraUpdateNanos = 0;
			return;
		}

		float FootprintHeight = GetCameraFootprintTileHeight(
				player.getWorldView(),
				CameraHeightLocation,
				player.getWorldView().getPlane(),
				player.getFootprintSize());
		if (player.getAnimation() != -1)
		{
			FootprintHeight -= player.getAnimationHeightOffset();
		}
		else
		{
			FootprintHeight -= PlayerMovementHandler.OldAnimationHeight;
		}

		int CameraFollowHeight = GetCameraFollowHeight();

		if (ShouldRenderAdaptiveCamera(
				IsAdaptiveCameraOn(),
				PlayerMovementHandler.bShouldRenderOwner))
		{
			UpdateAdaptiveCamera(PlayerMovementHandler, FootprintHeight, CameraFollowHeight);
		}
		else
		{
			LastAdaptiveCameraUpdateNanos = 0;
			if (client.getCameraMode() == 0)
			{
				// Store in sudo world space
				WorldPoint trueWorldTile = client.getLocalPlayer().getWorldLocation();
				LocalPoint trueLocalTile = LocalPoint.fromWorld(client, trueWorldTile);
				if (trueLocalTile == null)
				{
					return;
				}
				double CalculationOffsetVectorX = trueLocalTile.getX() - trueWorldTile.getX() * 128;
				double CalculationOffsetVectorY = trueLocalTile.getY() - trueWorldTile.getY() * 128;

				CurrentCameraPositionX = (float) (client.getCameraFocalPointX() - CalculationOffsetVectorX);
				CurrentCameraPositionZ = (float) (client.getCameraFocalPointZ() - CalculationOffsetVectorY);
		WorldView PlayerWorldView = player.getWorldView();
		if (currentWorldView == null)
		{
			currentWorldView = PlayerWorldView;
		}
		else if (!IsSameWorldView(currentWorldView, PlayerWorldView))
		{
			OverlayRenderer.Cleanup();
			CurrentHitsplats.clear();
			LastTimeHitSplatApplied = 0;
			CurrentCameraPositionX = -1;
			CurrentCameraPositionY = Float.NaN;
			CurrentCameraPositionZ = -1;
			LastAdaptiveCameraUpdateNanos = 0;
			currentWorldView = PlayerWorldView;
		}
		else if (currentWorldView != PlayerWorldView)
		{
			// The scene wrapper can be replaced while retaining the same logical
			// world view. Re-register render objects without discarding movement.
			OverlayRenderer.InvalidateRuneLiteObjects();
			currentWorldView = PlayerWorldView;
		}

		try
		{
			CustomMovementHandler PlayerMovementHandler = OverlayRenderer.PrepareFrame(player);
			if (PlayerMovementHandler == null)
			{
				PublishLocalPlayerRenderState(player, false);
				LastAdaptiveCameraUpdateNanos = 0;
				return;
			}
			LocalPoint CameraHeightLocation = PlayerMovementHandler.Model == null
					? null
					: PlayerMovementHandler.Model.getLocation();
			if (CameraHeightLocation == null ||
					CameraHeightLocation.getWorldView() != PlayerWorldView.getId())
			{
				PublishLocalPlayerRenderState(player, false);
				OverlayRenderer.Cleanup();
				CurrentCameraPositionX = -1;
				CurrentCameraPositionY = Float.NaN;
				CurrentCameraPositionZ = -1;
				LastAdaptiveCameraUpdateNanos = 0;
				return;
			}

			boolean HideLocalPlayer = ShouldHidePreparedPlayer(
					PlayerMovementHandler.bShouldRenderOwner,
					PlayerMovementHandler.HasRenderableModel(),
					CameraHeightLocation.getWorldView(),
					PlayerWorldView.getId());
			PublishLocalPlayerRenderState(player, HideLocalPlayer);

			float FootprintHeight = GetCameraFootprintTileHeight(
					player.getWorldView(),
					CameraHeightLocation,
					player.getWorldView().getPlane(),
					player.getFootprintSize());
			int CameraFollowHeight = GetCameraFollowHeight();

			// Input processing uses the normal camera outside the draw interval. Never
			// expose that interaction camera during presentation: on uneven terrain its
			// focal height belongs to the hidden owner rather than the visible model.
			if (ShouldRenderAdaptiveCamera(
					IsAdaptiveCameraOn(),
					!HideLocalPlayer))
			{
				UpdateAdaptiveCamera(PlayerMovementHandler, FootprintHeight, CameraFollowHeight);
			}
			// Keep the normal camera position synchronized while adaptive rendering is paused.
			else
			{
				LastAdaptiveCameraUpdateNanos = 0;
				if (client.getCameraMode() == 0)
				{
					// Store in sudo world space
					WorldPoint trueWorldTile = player.getWorldLocation();
					LocalPoint trueLocalTile = LocalPoint.fromWorld(client, trueWorldTile);
					if (trueLocalTile == null)
					{
						CurrentCameraPositionY = Float.NaN;
						return;
					}
					double CalculationOffsetVectorX = trueLocalTile.getX() - trueWorldTile.getX() * 128;
					double CalculationOffsetVectorY = trueLocalTile.getY() - trueWorldTile.getY() * 128;

					CurrentCameraPositionX = (float) (client.getCameraFocalPointX() - CalculationOffsetVectorX);
					CurrentCameraPositionY = client.getCameraFocalPointY();
					CurrentCameraPositionZ = (float) (client.getCameraFocalPointZ() - CalculationOffsetVectorY);
				}
				client.setCameraMode(0);
			}
		}
		catch (RuntimeException ex)
		{
			CustomMovementHandler HeldMovementHandler = null;
			boolean HideLocalPlayer = false;
			try
			{
				HeldMovementHandler = OverlayRenderer.HoldLastFrame(player);
				HideLocalPlayer = HeldMovementHandler != null &&
						HeldMovementHandler.HasRenderableModel();
			}
			catch (RuntimeException HoldException)
			{
				ex.addSuppressed(HoldException);
			}
			PublishLocalPlayerRenderState(player, HideLocalPlayer);
			if (!HideLocalPlayer)
			{
				try
				{
					OverlayRenderer.Cleanup();
				}
				catch (RuntimeException CleanupException)
				{
					ex.addSuppressed(CleanupException);
				}
			}
			CurrentCameraPositionX = -1;
			CurrentCameraPositionY = Float.NaN;
			CurrentCameraPositionZ = -1;
			LastAdaptiveCameraUpdateNanos = 0;
			bAdaptiveCameraRenderedThisFrame = false;
			client.setCameraMode(0);
			log.debug("Unable to prepare True Tile render frame; holding the last stable frame when possible", ex);
		}
	}
	private long LastTimeHitSplatApplied = 0;
	@Subscribe
	public void onHitsplatApplied(HitsplatApplied event)
	{
		if (event.getActor() == client.getLocalPlayer())
		{
			LastTimeHitSplatApplied = System.nanoTime() / 1_000_000L;
			CurrentHitsplats.add(event.getHitsplat());
		}
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		if (bForceEarlyOut || !bIsPluginSupportedCurrently)
		{
			CurrentCameraPositionX = -1;
			CurrentCameraPositionY = Float.NaN;
			CurrentCameraPositionZ = -1;
			client.setCameraMode(0);
			return;
		}

		Player player = client.getLocalPlayer();
		if (player == null)
		{
			PublishLocalPlayerRenderState(null, false);
			return;
		}

		// Manage our current hitsplats
        CurrentHitsplats.removeIf(hitsplat -> hitsplat == null ||
				client.getGameCycle() >= hitsplat.getDisappearsOnGameCycle());

		// Recently been in combat
		if (LastTimeHitSplatApplied != 0 &&
				System.nanoTime() / 1_000_000L - LastTimeHitSplatApplied < 6000) // 6 seconds
		{
			// Show this one
			OverlayRenderer.bShowHPBar = true;
		}
		else
		{
			OverlayRenderer.bShowHPBar = false;
		}

		// Teleports
		if (client.getLocalPlayer().getAnimation() == 714 ||
				client.getLocalPlayer().getAnimation() == 878 ||
				client.getLocalPlayer().getAnimation() == 1816 ||
				client.getLocalPlayer().getAnimation() == 1979 ||
				client.getLocalPlayer().getAnimation() == 3872 ||
				client.getLocalPlayer().getAnimation() == 13811 ||
				client.getLocalPlayer().getAnimation() == 4069 ||
				client.getLocalPlayer().getAnimation() == 4071 ||
				client.getLocalPlayer().getAnimation() == 3869 ||
				client.getLocalPlayer().getAnimation() == 3865 ||
				client.getLocalPlayer().getAnimation() == 2881
		)
		{
			OverlayRenderer.LastTimeTeleport = System.currentTimeMillis();
			OverlayRenderer.bShouldPlayTeleportAnimation = true;
			OverlayRenderer.bTeleportInterrupted = false;
		}

		// Print recent animation for convenience
		if (config.PrintCurrentAnimationIDsToChat() && LastPrintedAnimation != client.getLocalPlayer().getAnimation())
		{
			LastPrintedAnimation = client.getLocalPlayer().getAnimation();
			client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "Current Animation ID " + client.getLocalPlayer().getAnimation(), null);
		}

		Player player = client.getLocalPlayer();
		if (player == null)
		{
			return;
		}

		WorldView newWorldView = player.getWorldView();
		if (currentWorldView == null)
		{
			currentWorldView = newWorldView;
		}
		else if (!IsSameWorldView(newWorldView, currentWorldView))
		{
			currentWorldView = newWorldView;
			OverlayRenderer.Cleanup();
			CurrentHitsplats.clear();
			LastTimeHitSplatApplied = 0;
		}
		else if (newWorldView != currentWorldView)
		{
			currentWorldView = newWorldView;
			OverlayRenderer.InvalidateRuneLiteObjects();
		}
	}

	public Map<HeadIcon, BufferedImage> prayerImages;
	public Map<Integer, BufferedImage> skullImages;
	public Map<Integer, BufferedImage> hitsplatImages;
	public void InitializePrayerImages()
	{
		prayerImages = Map.ofEntries(
				Map.entry(HeadIcon.MAGIC, ImageUtil.loadImageResource(getClass(), "/Magic.png")),
				Map.entry(HeadIcon.MELEE, ImageUtil.loadImageResource(getClass(), "/Melee.png")),
				Map.entry(HeadIcon.RANGED, ImageUtil.loadImageResource(getClass(), "/Ranged.png")),
				Map.entry(HeadIcon.SMITE, ImageUtil.loadImageResource(getClass(), "/Smite.png")),
				Map.entry(HeadIcon.RETRIBUTION, ImageUtil.loadImageResource(getClass(), "/Retribution.png")),
				Map.entry(HeadIcon.REDEMPTION, ImageUtil.loadImageResource(getClass(), "/Redemption.png"))
		);
	}

	public void InitializeSkullImages()
	{
		skullImages = Map.ofEntries(
				Map.entry(SkullIcon.SKULL, ImageUtil.loadImageResource(getClass(), "/skulls/Skull.png")),
				Map.entry(SkullIcon.SKULL_HIGH_RISK, ImageUtil.loadImageResource(getClass(), "/skulls/SkullHighRisk.png")),
				Map.entry(SkullIcon.SKULL_FIGHT_PIT, ImageUtil.loadImageResource(getClass(), "/skulls/SkullFightPits.png")),
				Map.entry(SkullIcon.SKULL_DEADMAN, ImageUtil.loadImageResource(getClass(), "/skulls/SkullDeadman.png")),
				Map.entry(SkullIcon.LOOT_KEYS_ONE, ImageUtil.loadImageResource(getClass(), "/skulls/SkullLootKey1.png")),
				Map.entry(SkullIcon.LOOT_KEYS_TWO, ImageUtil.loadImageResource(getClass(), "/skulls/SkullLootKey2.png")),
				Map.entry(SkullIcon.LOOT_KEYS_THREE, ImageUtil.loadImageResource(getClass(), "/skulls/SkullLootKey3.png")),
				Map.entry(SkullIcon.LOOT_KEYS_FOUR, ImageUtil.loadImageResource(getClass(), "/skulls/SkullLootKey4.png")),
				Map.entry(SkullIcon.LOOT_KEYS_FIVE, ImageUtil.loadImageResource(getClass(), "/skulls/SkullLootKey5.png")),
				Map.entry(SkullIcon.FORINTHRY_SURGE, ImageUtil.loadImageResource(getClass(), "/skulls/SkullForinthrySurge.png")),
				Map.entry(SkullIcon.FORINTHRY_SURGE_DEADMAN, ImageUtil.loadImageResource(getClass(), "/skulls/SkullForinthrySurgeDeadman.png")),
				Map.entry(SkullIcon.FORINTHRY_SURGE_KEYS_ONE, ImageUtil.loadImageResource(getClass(), "/skulls/SkullForinthrySurgeDeadmanKey1.png")),
				Map.entry(SkullIcon.FORINTHRY_SURGE_KEYS_TWO, ImageUtil.loadImageResource(getClass(), "/skulls/SkullForinthrySurgeDeadmanKey2.png")),
				Map.entry(SkullIcon.FORINTHRY_SURGE_KEYS_THREE, ImageUtil.loadImageResource(getClass(), "/skulls/SkullForinthrySurgeDeadmanKey3.png")),
				Map.entry(SkullIcon.FORINTHRY_SURGE_KEYS_FOUR, ImageUtil.loadImageResource(getClass(), "/skulls/SkullForinthrySurgeDeadmanKey4.png")),
				Map.entry(SkullIcon.FORINTHRY_SURGE_KEYS_FIVE, ImageUtil.loadImageResource(getClass(), "/skulls/SkullForinthrySurgeDeadmanKey5.png"))
		);
	}

	public void InitializeHitsplatImages()
	{
		// Use same for me/other because we only handle ourself anyway
		hitsplatImages = Map.ofEntries(
				Map.entry(DAMAGE_ME, ImageUtil.loadImageResource(getClass(), "/hitsplats/DamageMe.png")),
				Map.entry(DAMAGE_OTHER, ImageUtil.loadImageResource(getClass(), "/hitsplats/DamageMe.png")),
				Map.entry(BLOCK_ME, ImageUtil.loadImageResource(getClass(), "/hitsplats/BlockMe.png")),
				Map.entry(BLOCK_OTHER, ImageUtil.loadImageResource(getClass(), "/hitsplats/BlockMe.png")),
				Map.entry(POISON, ImageUtil.loadImageResource(getClass(), "/hitsplats/Poison.png")),
				Map.entry(VENOM, ImageUtil.loadImageResource(getClass(), "/hitsplats/Venom.png")),
				Map.entry(DISEASE, ImageUtil.loadImageResource(getClass(), "/hitsplats/Disease.png")),
				Map.entry(BLEED, ImageUtil.loadImageResource(getClass(), "/hitsplats/Bleed.png")),
				Map.entry(CORRUPTION, ImageUtil.loadImageResource(getClass(), "/hitsplats/Corruption.png")),
				Map.entry(BURN, ImageUtil.loadImageResource(getClass(), "/hitsplats/Burn.png")),
				Map.entry(SANITY_DRAIN, ImageUtil.loadImageResource(getClass(), "/hitsplats/SanityDrain.png")),
				Map.entry(HEAL, ImageUtil.loadImageResource(getClass(), "/hitsplats/Heal.png")),
				Map.entry(DOOM, ImageUtil.loadImageResource(getClass(), "/hitsplats/Doom.png")),
				Map.entry(SANITY_RESTORE, ImageUtil.loadImageResource(getClass(), "/hitsplats/SanityRestore.png")),
				Map.entry(DISEASE_BLOCKED, ImageUtil.loadImageResource(getClass(), "/hitsplats/Disease.png")),
				Map.entry(PRAYER_DRAIN, ImageUtil.loadImageResource(getClass(), "/hitsplats/PrayerDrain.png")),


				// TODO: The rest of these images, use default hitsplat for now
				Map.entry(DAMAGE_MAX_ME, ImageUtil.loadImageResource(getClass(), "/hitsplats/DamageMe.png")),
				Map.entry(DAMAGE_MAX_ME_CYAN, ImageUtil.loadImageResource(getClass(), "/hitsplats/DamageMe.png")),
				Map.entry(DAMAGE_MAX_ME_ORANGE, ImageUtil.loadImageResource(getClass(), "/hitsplats/DamageMe.png")),
				Map.entry(DAMAGE_MAX_ME_YELLOW, ImageUtil.loadImageResource(getClass(), "/hitsplats/DamageMe.png")),
				Map.entry(DAMAGE_MAX_ME_WHITE, ImageUtil.loadImageResource(getClass(), "/hitsplats/DamageMe.png")),
				Map.entry(CYAN_UP, ImageUtil.loadImageResource(getClass(), "/hitsplats/DamageMe.png")),
				Map.entry(CYAN_DOWN, ImageUtil.loadImageResource(getClass(), "/hitsplats/DamageMe.png")),
				Map.entry(DAMAGE_ME_CYAN, ImageUtil.loadImageResource(getClass(), "/hitsplats/DamageMe.png")),
				Map.entry(DAMAGE_OTHER_CYAN, ImageUtil.loadImageResource(getClass(), "/hitsplats/DamageMe.png")),
				Map.entry(DAMAGE_ME_ORANGE, ImageUtil.loadImageResource(getClass(), "/hitsplats/DamageMe.png")),
				Map.entry(DAMAGE_OTHER_ORANGE, ImageUtil.loadImageResource(getClass(), "/hitsplats/DamageMe.png")),
				Map.entry(DAMAGE_ME_YELLOW, ImageUtil.loadImageResource(getClass(), "/hitsplats/DamageMe.png")),
				Map.entry(DAMAGE_OTHER_YELLOW, ImageUtil.loadImageResource(getClass(), "/hitsplats/DamageMe.png")),
				Map.entry(DAMAGE_ME_WHITE, ImageUtil.loadImageResource(getClass(), "/hitsplats/DamageMe.png")),
				Map.entry(DAMAGE_OTHER_WHITE, ImageUtil.loadImageResource(getClass(), "/hitsplats/DamageMe.png")),
				Map.entry(DAMAGE_ME_POISE, ImageUtil.loadImageResource(getClass(), "/hitsplats/DamageMe.png")),
				Map.entry(DAMAGE_OTHER_POISE, ImageUtil.loadImageResource(getClass(), "/hitsplats/DamageMe.png")),
				Map.entry(DAMAGE_MAX_ME_POISE, ImageUtil.loadImageResource(getClass(), "/hitsplats/DamageMe.png"))
		);
	}



	@Override
	protected void startUp() throws Exception
	{
		InitializePrayerImages();
		InitializeSkullImages();
		InitializeHitsplatImages();

		renderCallbackManager.register(renderCallback);
		drawManager.registerEveryFrameListener(PostDrawCameraModeHandoff);
		overlayManager.add(OverlayRenderer);
		bForceEarlyOut = false;
		bIsPluginSupportedCurrently = client.isGpu();
		PublishLocalPlayerRenderState(null, false);
		OverlayRenderer.ResetTransientState();
		CurrentHitsplats.clear();
		LastTimeHitSplatApplied = 0;
		currentWorldView = null;
		CurrentCameraPositionX = -1;
		CurrentCameraPositionY = Float.NaN;
		CurrentCameraPositionZ = -1;
		LastAdaptiveCameraUpdateNanos = 0;
		bAdaptiveCameraRenderedThisFrame = false;
		// [TMA-R03] TrueMovementOverlay is deliberately unscoped. Construct the
		// auxiliary
		// subscriber with this plugin's exact overlay instance so its captures
		// update the same movement-handler cache that is rendered on screen.
		neutralModelCaptureSubscriber = new NeutralModelCaptureSubscriber(client, OverlayRenderer);
		eventBus.register(neutralModelCaptureSubscriber);
		neutralModelCaptureSubscriberRegistered = true;
	}

	public BufferedImage GetPrayerIcon(HeadIcon currentHeadIcon)
	{
		return prayerImages.get(currentHeadIcon);
	}

	public BufferedImage GetSkullIcon(int skullIcon)
	{
		return skullImages.get(skullIcon);
	}

	@Override
	protected void shutDown() throws Exception
	{
		// [TMA-R12] Stop capture first, expose the native player, then dispose
		// callbacks and scene objects on the client thread.
		bForceEarlyOut = true;
		if (neutralModelCaptureSubscriberRegistered)
		{
			eventBus.unregister(neutralModelCaptureSubscriber);
			neutralModelCaptureSubscriberRegistered = false;
		}
		neutralModelCaptureSubscriber = null;
		PublishLocalPlayerRenderState(null, false);
		CurrentCameraPositionX = -1;
		CurrentCameraPositionY = Float.NaN;
		CurrentCameraPositionZ = -1;
		LastAdaptiveCameraUpdateNanos = 0;
		bAdaptiveCameraRenderedThisFrame = false;

		clientThread.invoke(() ->
		{
			OverlayRenderer.Cleanup();
			renderCallbackManager.unregister(renderCallback);
			drawManager.unregisterEveryFrameListener(PostDrawCameraModeHandoff);
			overlayManager.remove(OverlayRenderer);
			bForceEarlyOut = true;
			try
			{
				renderCallbackManager.unregister(renderCallback);
			}
			catch (RuntimeException ex)
			{
				log.debug("Unable to unregister the True Tile render callback", ex);
			}
			try
			{
				drawManager.unregisterEveryFrameListener(PostDrawCameraModeHandoff);
			}
			catch (RuntimeException ex)
			{
				log.debug("Unable to unregister the True Tile camera handoff", ex);
			}
			try
			{
				overlayManager.remove(OverlayRenderer);
			}
			catch (RuntimeException ex)
			{
				log.debug("Unable to remove the True Tile overlay", ex);
			}
			try
			{
				OverlayRenderer.Cleanup();
			}
			catch (RuntimeException ex)
			{
				log.debug("Unable to clean up True Tile render objects", ex);
			}
			CurrentHitsplats.clear();
			LastTimeHitSplatApplied = 0;
			currentWorldView = null;
			client.setCameraMode(0);
		});
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		if (bForceEarlyOut || !bIsPluginSupportedCurrently)
		{
			return;
		}

		MenuEntry Entry = event.getMenuEntry();
		boolean StationaryInteraction = ShouldUseStationaryInteractionFacing(
				event.getMenuAction(),
				event.getMenuOption());

		// [TMA-R13] A new world action replaces the pending interaction target.
		// Widget clicks do not, so an interface opening cannot discard the
		// native facing direction captured from its NPC or object.
		if ((Entry != null && Entry.getWidget() == null) ||
				(STATIONARY_INTERACTION_ACTIONS.contains(
						event.getMenuAction()) &&
						!StationaryInteraction))
		{
			OverlayRenderer.ClearStationaryInteractionFacing();
		}
		if (StationaryInteraction)
		{
			Player LocalPlayer = client.getLocalPlayer();
			if (LocalPlayer != null)
			{
				OverlayRenderer.RequestStationaryInteractionFacing(
						LocalPlayer,
						Entry == null ? null : Entry.getNpc());
			}
		}

		// TODO make less manual
		if (event.getMenuOption().equals("Walk here") ||
				event.getMenuOption().equals("Attack") ||
				event.getMenuOption().equals("Jump") ||
				event.getMenuOption().equals("Talk to") ||
				event.getMenuOption().equals("Pickpocket"))
		{
			OverlayRenderer.bRecentlyClickedEvent = true;
		}
	}

	static boolean ShouldUseStationaryInteractionFacing(
			MenuAction Action,
			String Option)
	{
		return Action != null &&
				STATIONARY_INTERACTION_ACTIONS.contains(Action) &&
				!"Attack".equalsIgnoreCase(Option) &&
				!"Cast".equalsIgnoreCase(Option);
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged gameStateChanged)
	{
		if (bForceEarlyOut)
		{
			return;
		}

		// Runelite objects are stale
		if (gameStateChanged.getGameState() == GameState.LOADING ||
				gameStateChanged.getGameState() == GameState.CONNECTION_LOST ||
				gameStateChanged.getGameState() == GameState.HOPPING)
		GameState gameState = gameStateChanged.getGameState();
		if (gameState == GameState.LOADING)
		{
			// [TMA-R09] A rebuild is not a logout: preserve interpolation history
			// while invalidating objects and caches owned by the old scene.
			PublishLocalPlayerRenderState(null, false);
			OverlayRenderer.InvalidateRuneLiteObjects();
			CurrentCameraPositionX = -1;
			CurrentCameraPositionY = Float.NaN;
			CurrentCameraPositionZ = -1;
			LastAdaptiveCameraUpdateNanos = 0;
			bAdaptiveCameraRenderedThisFrame = false;
			client.setCameraMode(0);
		}
		else if (gameState != GameState.LOGGED_IN)
		{
			PublishLocalPlayerRenderState(null, false);
			OverlayRenderer.Cleanup();
			CurrentHitsplats.clear();
			LastTimeHitSplatApplied = 0;
			currentWorldView = null;
			CurrentCameraPositionX = -1;
			CurrentCameraPositionY = Float.NaN;
			CurrentCameraPositionZ = -1;
			LastAdaptiveCameraUpdateNanos = 0;
			bAdaptiveCameraRenderedThisFrame = false;
			client.setCameraMode(0);
		}
	}

	@Provides
	TrueTileMovementConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(TrueTileMovementConfig.class);
	}

}
