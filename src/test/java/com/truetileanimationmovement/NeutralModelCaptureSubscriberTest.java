package com.truetileanimationmovement;

import net.runelite.client.eventbus.EventBus;
import org.junit.Test;

public class NeutralModelCaptureSubscriberTest
{
	@Test
	public void registersWithRuneLiteEventBus()
	{
		EventBus eventBus = new EventBus();
		NeutralModelCaptureSubscriber subscriber =
				new NeutralModelCaptureSubscriber(null, null);

		eventBus.register(subscriber);
		eventBus.unregister(subscriber);
	}
}
