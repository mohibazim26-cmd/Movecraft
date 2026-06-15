package net.countercraft.movecraft.async;

public record DirectControlInput(
        boolean forward,
        boolean backward,
        boolean left,
        boolean right,
        boolean jump,
        boolean sneak
) {
    public static final DirectControlInput EMPTY =
            new DirectControlInput(false, false, false, false, false, false);
}
