package com.truetileanimationmovement;

import java.util.function.Consumer;
import net.runelite.api.Actor;
import net.runelite.api.Menu;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.widgets.Widget;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MenuEntryHandlingTest
{
	@Test
	public void walkAndCancelDoNotRequireInteractionCamera()
	{
		MenuEntry[] entries = {
				new FakeMenuEntry(MenuAction.CANCEL),
				new FakeMenuEntry(MenuAction.WALK)
		};

		assertFalse(TrueTileMovementPlugin.HasWalkHereWithOtherOptions(entries));
	}

	@Test
	public void walkAndTargetedOptionRequireInteractionCamera()
	{
		MenuEntry[] entries = {
				new FakeMenuEntry(MenuAction.CANCEL),
				new FakeMenuEntry(MenuAction.WALK),
				new FakeMenuEntry(MenuAction.GROUND_ITEM_FIRST_OPTION)
		};

		assertTrue(TrueTileMovementPlugin.HasWalkHereWithOtherOptions(entries));
	}

	@Test
	public void menuWithoutWalkDoesNotRequireInteractionCamera()
	{
		MenuEntry[] entries = {
				new FakeMenuEntry(MenuAction.CANCEL),
				new FakeMenuEntry(MenuAction.NPC_FIRST_OPTION)
		};

		assertFalse(TrueTileMovementPlugin.HasWalkHereWithOtherOptions(entries));
	}

	private static final class FakeMenuEntry implements MenuEntry
	{
		private String option = "";
		private String target = "";
		private MenuAction type;
		private Consumer<MenuEntry> callback;

		private FakeMenuEntry(MenuAction type)
		{
			this.type = type;
		}

		@Override
		public String getOption()
		{
			return option;
		}

		@Override
		public MenuEntry setOption(String option)
		{
			this.option = option;
			return this;
		}

		@Override
		public String getTarget()
		{
			return target;
		}

		@Override
		public MenuEntry setTarget(String target)
		{
			this.target = target;
			return this;
		}

		@Override
		public int getIdentifier()
		{
			return 0;
		}

		@Override
		public MenuEntry setIdentifier(int identifier)
		{
			return this;
		}

		@Override
		public MenuAction getType()
		{
			return type;
		}

		@Override
		public MenuEntry setType(MenuAction type)
		{
			this.type = type;
			return this;
		}

		@Override
		public int getParam0()
		{
			return 0;
		}

		@Override
		public MenuEntry setParam0(int param0)
		{
			return this;
		}

		@Override
		public int getParam1()
		{
			return 0;
		}

		@Override
		public MenuEntry setParam1(int param1)
		{
			return this;
		}

		@Override
		public boolean isForceLeftClick()
		{
			return false;
		}

		@Override
		public MenuEntry setForceLeftClick(boolean forceLeftClick)
		{
			return this;
		}

		@Override
		public int getWorldViewId()
		{
			return 0;
		}

		@Override
		public MenuEntry setWorldViewId(int worldViewId)
		{
			return this;
		}

		@Override
		public boolean isDeprioritized()
		{
			return false;
		}

		@Override
		public MenuEntry setDeprioritized(boolean deprioritized)
		{
			return this;
		}

		@Override
		public MenuEntry onClick(Consumer<MenuEntry> callback)
		{
			this.callback = callback;
			return this;
		}

		@Override
		public Consumer<MenuEntry> onClick()
		{
			return callback;
		}

		@Override
		public boolean isItemOp()
		{
			return false;
		}

		@Override
		public int getItemOp()
		{
			return 0;
		}

		@Override
		public int getItemId()
		{
			return 0;
		}

		@Override
		public MenuEntry setItemId(int itemId)
		{
			return this;
		}

		@Override
		public Widget getWidget()
		{
			return null;
		}

		@Override
		public NPC getNpc()
		{
			return null;
		}

		@Override
		public Player getPlayer()
		{
			return null;
		}

		@Override
		public Actor getActor()
		{
			return null;
		}

		@Override
		public Menu getSubMenu()
		{
			return null;
		}

		@Override
		public Menu createSubMenu()
		{
			throw new UnsupportedOperationException();
		}

		@Override
		public void deleteSubMenu()
		{
		}
	}
}
