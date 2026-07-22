package com.truetileanimationmovement;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.Point;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.*;
import net.runelite.api.coords.LocalPoint;
import net.runelite.client.ui.overlay.components.LineComponent;

import javax.inject.Inject;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.List;
import java.util.function.ToIntFunction;
import java.util.stream.Collectors;

@Slf4j
public class TrueMovementOverlay extends OverlayPanel
{
    // [TMA-R01] This overlay owns the handler that prepares the exact frame
    // consumed by BeforeRender. It is intentionally not a second model source.
    // General
    private final Client client;
    private final TrueTileMovementPlugin plugin;
    private final TrueTileMovementConfig config;

    public boolean bRuneliteObjectsStale = false;
    public boolean bRecentlyClickedEvent = false;

    // HP Bar
    public boolean bShowHPBar = true;
    private static final Color BAR_FILL_COLOR = Color.green;
    private static final Color BAR_BG_COLOR = Color.red;
    private static final Dimension HP_BAR_SIZE = new Dimension(30, 5);

    public void Cleanup()
    {
        // [TMA-R12] Cleanup is ownership transfer back to the native player,
        // not merely removal of entries from this map.
        for (Map.Entry<Integer, CustomMovementHandler> entry : MovementHandlerCache.entrySet())
        {
            var value = entry.getValue();
            try
            {
                value.Cleanup();
            }
            catch (RuntimeException ex)
            {
                log.debug("Unable to clean up a True Tile movement handler", ex);
            }
        }

        MovementHandlerCache.clear();
        bRuneliteObjectsStale = false;
        ResetTransientState();
    }

    public void InvalidateRuneLiteObjects()
    {
        // [TMA-R09] Region loading invalidates scene objects, while handlers
        // retain enough world-space history to rebase the visible position.
        InvalidateNeutralOwnerModels();
        bRuneliteObjectsStale = true;
    }

    public void ResetTransientState()
    {
        bRecentlyClickedEvent = false;
        bShowHPBar = false;
    }

    void BeginNeutralOwnerModelCapture(Player player)
    {
        // [TMA-R03] Both capture callbacks must reach this overlay's exact
        // handler instance; a separately injected unscoped overlay is invalid.
        if (player == null)
        {
            return;
        }

        CustomMovementHandler Handler = MovementHandlerCache.get(player.getId());
        if (Handler != null && Handler.IsOwner(player))
        {
            Handler.BeginNeutralOwnerModelCaptureOnGameTick();
        }
    }

    void CompleteNeutralOwnerModelCapture(Player player)
    {
        if (player == null)
        {
            return;
        }

        RemoveHandlersForOtherPlayerIds(player.getId());

        CustomMovementHandler Handler = MovementHandlerCache.get(player.getId());
        if (Handler == null)
        {
            return;
        }
        if (Handler.IsOwner(player))
        {
            Handler.CompleteNeutralOwnerModelCaptureOnClientTick();
        }
        else
        {
            // A scene rebuild can replace the Player wrapper between the two
            // callbacks. Restore the old actor immediately; PrepareFrame will
            // rebind or replace the handler before it can render again.
            Handler.InvalidateNeutralOwnerModel();
        }
    }

    void InvalidateNeutralOwnerModels()
    {
        for (CustomMovementHandler Handler : MovementHandlerCache.values())
        {
            try
            {
                Handler.InvalidateNeutralOwnerModel();
            }
            catch (RuntimeException ex)
            {
                log.debug("Unable to invalidate a neutral True Tile player model", ex);
            }
        }
    }

    CustomMovementHandler PrepareFrame(Player player)
    {
        // [TMA-R01, TMA-R09] Rebind or rebuild first, then return only a fully
        // updated handler. Callers must not hide the owner on a null result.
        if (player == null)
        {
            return null;
        }

        RemoveHandlersForOtherPlayerIds(player.getId());

        CustomMovementHandler playerEntry = MovementHandlerCache.get(player.getId());
        if (playerEntry != null && !playerEntry.IsOwner(player))
        {
            if (!bRuneliteObjectsStale || !playerEntry.RebindOwnerAfterSceneLoad(player))
            {
                playerEntry.Cleanup();
                MovementHandlerCache.remove(player.getId());
                playerEntry = null;
            }
        }

        if (playerEntry == null)
        {
            playerEntry = new CustomMovementHandler(client, plugin, config, this, player);
            MovementHandlerCache.put(player.getId(), playerEntry);
        }

        playerEntry.Initialize(bRuneliteObjectsStale);
        bRuneliteObjectsStale = false;

        if (!playerEntry.Update())
        {
            return null;
        }

        return playerEntry;
    }

    private void RemoveHandlersForOtherPlayerIds(int CurrentPlayerId)
    {
        Iterator<Map.Entry<Integer, CustomMovementHandler>> Iterator =
                MovementHandlerCache.entrySet().iterator();
        while (Iterator.hasNext())
        {
            Map.Entry<Integer, CustomMovementHandler> Entry = Iterator.next();
            if (Entry.getKey() == CurrentPlayerId)
            {
                continue;
            }

            try
            {
                Entry.getValue().Cleanup();
            }
            catch (RuntimeException ex)
            {
                log.debug("Unable to remove a stale True Tile player handler", ex);
            }
            Iterator.remove();
        }
    }

    CustomMovementHandler HoldLastFrame(Player player)
    {
        if (player == null)
        {
            return null;
        }

        CustomMovementHandler playerEntry = MovementHandlerCache.get(player.getId());
        if (playerEntry == null || !playerEntry.IsOwner(player) || !playerEntry.HoldLastRenderedFrame())
        {
            return null;
        }
        return playerEntry;
    }

    // Tracking data for all characters we are handling the rendering (including player)
    private final Map<Integer /* character ID */, CustomMovementHandler> MovementHandlerCache = new HashMap<>();

    @Inject
    private TrueMovementOverlay(Client client, TrueTileMovementPlugin plugin, TrueTileMovementConfig config)
    {
        this.client = client;
        this.plugin = plugin;
        this.config = config;

        setPosition(OverlayPosition.DYNAMIC);
        setPriority(PRIORITY_HIGH);
        setLayer(OverlayLayer.ABOVE_SCENE);
    }

    public void RenderHPBar(Graphics2D graphics, Point HPBarPoint)
    {
        final float ratio = (float) client.getBoostedSkillLevel(Skill.HITPOINTS) / client.getRealSkillLevel(Skill.HITPOINTS);

        // Draw bar
        final int barX = HPBarPoint.getX() - 15;
        final int barY = HPBarPoint.getY();
        final int barWidth = HP_BAR_SIZE.width;
        final int barHeight = HP_BAR_SIZE.height;

        // Restricted by the width to prevent the bar from being too long while you are boosted above your real HP level.
        final int progressFill = (int) Math.ceil(Math.min((barWidth * ratio), barWidth));

        graphics.setColor(BAR_BG_COLOR);
        graphics.fillRect(barX, barY, barWidth, barHeight);
        graphics.setColor(BAR_FILL_COLOR);
        graphics.fillRect(barX, barY, progressFill, barHeight);

    }
    @Inject
    private FontManager fontManager;

    public List<Hitsplat> getTop4Hitsplats(
            List<Hitsplat> hitsplats,
            ToIntFunction<Hitsplat> priorityFunction)
    {
        return hitsplats.stream()
                .sorted(Comparator.comparingInt(priorityFunction).reversed())
                .limit(4)
                .collect(Collectors.toList());
    }

    public void RenderHitsplats(Graphics2D graphics)
    {
        if (!config.CustomOverheadRendering())
        {
            return;
        }

        Player player = client.getLocalPlayer();
        if (player == null)
        {
            return;
        }
        var playerEntry = MovementHandlerCache.get(player.getId());
        if (playerEntry == null || !playerEntry.HasRenderableModel())
        {
            return;
        }

        plugin.CurrentHitsplats.removeIf(
                hitsplat -> hitsplat == null ||
                        client.getGameCycle() >= hitsplat.getDisappearsOnGameCycle());

        Point[] HitsplatPointOffsets = {
                new Point(0, 0),
                new Point(0, -config.MultipleHitsplatOffset() + 5),
                new Point(-config.MultipleHitsplatOffset() / 2 - 3, -config.MultipleHitsplatOffset() / 2),
                new Point(config.MultipleHitsplatOffset() / 2 + 3, -config.MultipleHitsplatOffset() / 2)
        };

        // Render up to 4 hitsplats
        List<Hitsplat> Top4Hitsplats = getTop4Hitsplats(
                plugin.CurrentHitsplats,
                hs ->
                {
                    int priority = 0;
                    priority += 100 * (hs.getDisappearsOnGameCycle() - client.getGameCycle()); // Main priority is based on decay
                    priority += hs.getAmount(); // Secondary priority based on amount

                    return priority;
                }
        );


        // Reverse the array to draw most important last
        int i = 0;
        for (int j = Top4Hitsplats.size() - 1; j >= 0; --j)
        {
            Hitsplat hitsplat = Top4Hitsplats.get(j);
            if (hitsplat == null)
            {
                continue;
            }

            String text = String.valueOf(hitsplat.getAmount());

            FontMetrics metrics = graphics.getFontMetrics();
            Point point = Perspective.localToCanvas(
                    client,
                    playerEntry.Model.getLocation().getWorldView(),
                    playerEntry.Model.getLocation().getX(),
                    playerEntry.Model.getLocation().getY(),
                    playerEntry.Model.getZ() - player.getLogicalHeight() / 2
            );

            if (point != null)
            {
                point = new Point(point.getX() - metrics.stringWidth(text) / 2, point.getY());
                BufferedImage HitsplatImage = plugin.hitsplatImages.get(hitsplat.getHitsplatType());
                int x = point.getX() + HitsplatPointOffsets[i].getX();
                int y = point.getY() + 8 + HitsplatPointOffsets[i].getY();

                int textWidth = metrics.stringWidth(text);
                int textHeight = metrics.getHeight();

                int textX = x;
                int textY = y;

                if (HitsplatImage != null)
                {
                    int imageX = textX + textWidth / 2 - HitsplatImage.getWidth() / 2;
                    int imageY = textY - textHeight + (textHeight - HitsplatImage.getHeight()) / 2;

                    graphics.drawImage(
                            HitsplatImage,
                            imageX,
                            imageY,
                            null
                    );

                }


                // Shadow
                graphics.setColor(Color.BLACK);
                graphics.drawString(text,
                        textX + 1,
                        textY + 1);

                graphics.setColor(Color.WHITE);
                graphics.drawString(
                        text,
                        textX,
                        textY
                );
            }

            ++i;
        }
    }

    public void RenderOverheadObjects(Graphics2D graphics)
    {
        if (!config.CustomOverheadRendering())
        {
            return;
        }

        Player player = client.getLocalPlayer();
        if (player == null)
        {
            return;
        }
        var playerEntry = MovementHandlerCache.get(player.getId());

        HeadIcon headIcon = player.getOverheadIcon();
        int skullIcon = client.getLocalPlayer().getSkullIcon();
        String OverheadText = player.getOverheadText();
        boolean bIsOverheadTextActive = OverheadText != null;

        if ((!bShowHPBar && headIcon == null && skullIcon == -1 && !bIsOverheadTextActive) ||
                playerEntry == null ||
                !playerEntry.HasRenderableModel())
        {
            return;
        }

        final LocalPoint localLocation = playerEntry.Model.getLocation();

        // Adjust height in 3D space
        int zOffset = player.getLogicalHeight() + config.OverheadObjectOffset();

        Point point = Perspective.localToCanvas(
                client,
                localLocation.getWorldView(),
                localLocation.getX(),
                localLocation.getY(),
                playerEntry.Model.getZ() - zOffset
        );

        if (point == null)
        {
            return;
        }
        int yOffset = 0;

        // Chat text changes the overhead offset.
        if (bIsOverheadTextActive)
        {
            graphics.setFont(FontManager.getRunescapeBoldFont());

            FontMetrics metrics = graphics.getFontMetrics();
            int drawX = point.getX() - metrics.stringWidth(OverheadText) / 2;
            int drawY = point.getY() + yOffset + config.OverheadTextOffset();

            // Shadow
            graphics.setColor(Color.BLACK);
            graphics.drawString(OverheadText, drawX + 1, drawY + 1);

            // Foreground
            graphics.setColor(Color.YELLOW); // Just support yellow for now
            graphics.drawString(OverheadText, drawX, drawY);

            yOffset = yOffset - 5;
        }

        if (bShowHPBar)
        {
            // Render HP bar
            Point HPBarPoint = new Point(point.getX(), point.getY() - config.OverheadHPBarOffset() + yOffset);
            RenderHPBar(graphics, HPBarPoint);

            yOffset = yOffset - 4;
        }

        if (skullIcon != -1)
        {
            BufferedImage SkullImage = plugin.GetSkullIcon(skullIcon);
            if (SkullImage != null)
            {
                graphics.drawImage(
                        SkullImage,
                        point.getX() - SkullImage.getWidth() / 2,
                        point.getY() - 30 - 2 + yOffset,
                        null
                );

                yOffset = yOffset - 28;
            }
        }

        if (headIcon != null)
        {

            BufferedImage PrayerImage = plugin.GetPrayerIcon(headIcon);
            if (PrayerImage != null)
            {
                graphics.drawImage(
                        PrayerImage,
                        point.getX() - PrayerImage.getWidth() / 2,
                        point.getY() - 30 - 2 + yOffset,
                        null
                );
            }

        }
    }

    @Override
    public Dimension render(Graphics2D graphics)
    {
        if (plugin.bForceEarlyOut || !plugin.bIsPluginSupportedCurrently)
        {
            // On screen message for requiring GPU plugin
            if (!plugin.bIsPluginSupportedCurrently)
            {
                panelComponent.getChildren().clear();
                panelComponent.getChildren().add(
                        LineComponent.builder()
                                .left("True Tile Animation Movement Plugin: DISABLED (GPU Plugin is required)")
                                .build()
                );

                return super.render(graphics);
            }
            return null;
        }

        // Overheads
        RenderOverheadObjects(graphics);

        // Hitsplats
        RenderHitsplats(graphics);

        return null;
    }
}
