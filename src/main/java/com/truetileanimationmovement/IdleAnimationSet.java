package com.truetileanimationmovement;

public class IdleAnimationSet
{
    private static final int NO_ANIMATION = -1;

    public int IdleRotateLeft = NO_ANIMATION;
    public int IdleRotateRight = NO_ANIMATION;
    public int WalkAnimation = NO_ANIMATION;
    public int WalkRotateLeft = NO_ANIMATION;
    public int WalkRotateRight = NO_ANIMATION;
    public int WalkRotate180 = NO_ANIMATION;
    public int IdlePoseAnimation = NO_ANIMATION;
    public int RunAnimation = NO_ANIMATION;

    private String UniqueLabel;

    public IdleAnimationSet()
    {
        CacheUniqueLabel();
    }

    public void CacheUniqueLabel()
    {
        UniqueLabel =
                IdleRotateLeft + ":" +
                IdleRotateRight + ":" +
                WalkAnimation + ":" +
                WalkRotateLeft + ":" +
                WalkRotateRight + ":" +
                WalkRotate180 + ":" +
                IdlePoseAnimation + ":" +
                RunAnimation;
    }

    public String GetUniqueLabel()
    {
        return UniqueLabel;
    }
}
