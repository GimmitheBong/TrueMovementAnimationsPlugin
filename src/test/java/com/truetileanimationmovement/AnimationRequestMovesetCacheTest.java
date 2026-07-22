package com.truetileanimationmovement;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

public class AnimationRequestMovesetCacheTest
{
    @Test
    public void uncapturedAnimationSetNeverRestoresAnimationZero()
    {
        IdleAnimationSet animationSet = new IdleAnimationSet();

        assertEquals(-1, animationSet.IdlePoseAnimation);
        assertEquals("-1:-1:-1:-1:-1:-1:-1:-1", animationSet.GetUniqueLabel());
    }

    @Test
    public void animationSetKeyCannotCollideAtDigitBoundaries()
    {
        IdleAnimationSet first = animationSet(1, 23);
        IdleAnimationSet second = animationSet(12, 3);
        TrueTileMovementConfig config = configWithOrientationSpeed(30);

        assertNotEquals(
                AnimationRequestMovesetCache.BuildCacheKey(first, "Movement", config),
                AnimationRequestMovesetCache.BuildCacheKey(second, "Movement", config));
    }

    @Test
    public void cacheKeyIncludesRelevantConfiguration()
    {
        IdleAnimationSet animationSet = animationSet(1, 23);

        assertNotEquals(
                AnimationRequestMovesetCache.BuildCacheKey(
                        animationSet, "Movement", configWithOrientationSpeed(30)),
                AnimationRequestMovesetCache.BuildCacheKey(
                        animationSet, "Movement", configWithOrientationSpeed(60)));
    }

    private static IdleAnimationSet animationSet(int idleRotateLeft, int idleRotateRight)
    {
        IdleAnimationSet animationSet = new IdleAnimationSet();
        animationSet.IdleRotateLeft = idleRotateLeft;
        animationSet.IdleRotateRight = idleRotateRight;
        animationSet.WalkAnimation = 4;
        animationSet.WalkRotateLeft = 5;
        animationSet.WalkRotateRight = 6;
        animationSet.WalkRotate180 = 7;
        animationSet.IdlePoseAnimation = 8;
        animationSet.RunAnimation = 10;
        animationSet.CacheUniqueLabel();
        return animationSet;
    }

    private static TrueTileMovementConfig configWithOrientationSpeed(final int speed)
    {
        return new TrueTileMovementConfig()
        {
            @Override
            public int OrientationRotationSpeed()
            {
                return speed;
            }
        };
    }
}
