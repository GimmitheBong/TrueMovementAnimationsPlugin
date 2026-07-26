package com.truetileanimationmovement;

public class IdleAnimationSet
{
    public int IdleRotateLeft = 0;
    public int IdleRotateRight = 0;
    public int WalkAnimation = 0;
    public int WalkRotateLeft = 0;
    public int WalkRotateRight = 0;
    public int WalkRotate180 = 0;
    public int IdlePoseAnimation = 0;
    public int RunAnimation = 0;
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
        UniqueLabel = String.valueOf(IdleRotateLeft) +
                String.valueOf(IdleRotateRight) +
                String.valueOf(WalkAnimation) +
                String.valueOf(WalkRotateLeft) +
                String.valueOf(WalkRotateRight) +
                String.valueOf(WalkRotate180) +
                String.valueOf(IdlePoseAnimation) +
                String.valueOf(RunAnimation);
        UniqueLabel = IdleRotateLeft + ":" +
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
