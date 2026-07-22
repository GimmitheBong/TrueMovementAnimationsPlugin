package com.truetileanimationmovement;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

public class PlayerAppearanceKeyTest
{
    @Test
    public void equalAppearanceDataProducesEqualKeys()
    {
        PlayerAppearanceKey first = PlayerAppearanceKey.Of(
                0,
                -1,
                new int[] { 1, 2, 3 },
                new int[] { 4, 5 },
                new short[][] { { 6, 7 }, null },
                new short[][] { null, { 8 } });
        PlayerAppearanceKey second = PlayerAppearanceKey.Of(
                0,
                -1,
                new int[] { 1, 2, 3 },
                new int[] { 4, 5 },
                new short[][] { { 6, 7 }, null },
                new short[][] { null, { 8 } });

        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
    }

    @Test
    public void keyOwnsDeepCopiesOfMutableCompositionArrays()
    {
        int[] equipment = { 1, 2, 3 };
        int[] colors = { 4, 5 };
        short[][] colorOverrides = { { 6, 7 }, null };
        short[][] textureOverrides = { null, { 8 } };

        PlayerAppearanceKey key = PlayerAppearanceKey.Of(
                0,
                -1,
                equipment,
                colors,
                colorOverrides,
                textureOverrides);

        equipment[0] = 99;
        colors[0] = 99;
        colorOverrides[0][0] = 99;
        textureOverrides[1][0] = 99;

        assertEquals(
                PlayerAppearanceKey.Of(
                        0,
                        -1,
                        new int[] { 1, 2, 3 },
                        new int[] { 4, 5 },
                        new short[][] { { 6, 7 }, null },
                        new short[][] { null, { 8 } }),
                key);
    }

    @Test
    public void everyGeometryInputInvalidatesTheKey()
    {
        PlayerAppearanceKey baseline = PlayerAppearanceKey.Of(
                0,
                -1,
                new int[] { 1, 2 },
                new int[] { 3 },
                new short[][] { { 4 } },
                new short[][] { { 5 } });

        assertNotEquals(baseline, PlayerAppearanceKey.Of(
                1, -1, new int[] { 1, 2 }, new int[] { 3 },
                new short[][] { { 4 } }, new short[][] { { 5 } }));
        assertNotEquals(baseline, PlayerAppearanceKey.Of(
                0, 42, new int[] { 1, 2 }, new int[] { 3 },
                new short[][] { { 4 } }, new short[][] { { 5 } }));
        assertNotEquals(baseline, PlayerAppearanceKey.Of(
                0, -1, new int[] { 1, 9 }, new int[] { 3 },
                new short[][] { { 4 } }, new short[][] { { 5 } }));
        assertNotEquals(baseline, PlayerAppearanceKey.Of(
                0, -1, new int[] { 1, 2 }, new int[] { 9 },
                new short[][] { { 4 } }, new short[][] { { 5 } }));
        assertNotEquals(baseline, PlayerAppearanceKey.Of(
                0, -1, new int[] { 1, 2 }, new int[] { 3 },
                new short[][] { { 9 } }, new short[][] { { 5 } }));
        assertNotEquals(baseline, PlayerAppearanceKey.Of(
                0, -1, new int[] { 1, 2 }, new int[] { 3 },
                new short[][] { { 4 } }, new short[][] { { 9 } }));
    }

    @Test
    public void nullCompositionArraysAreStable()
    {
        PlayerAppearanceKey first = PlayerAppearanceKey.Of(0, -1, null, null, null, null);
        PlayerAppearanceKey second = PlayerAppearanceKey.Of(0, -1, null, null, null, null);

        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
    }
}
