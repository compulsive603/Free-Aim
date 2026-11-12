package com.example.freeaim;

import net.minecraftforge.common.ForgeConfigSpec;

/** Saved to config/freeaim-client.toml and editable from Mods -> Free Aim -> Config. */
public final class FreeAimConfig {
    public static final ForgeConfigSpec SPEC;

    public static final ForgeConfigSpec.BooleanValue START_ENABLED;
    public static final ForgeConfigSpec.DoubleValue HAND_SENSITIVITY;
    public static final ForgeConfigSpec.DoubleValue FOLLOW_RATE;
    public static final ForgeConfigSpec.DoubleValue CONE_YAW;
    public static final ForgeConfigSpec.DoubleValue CONE_PITCH;
    public static final ForgeConfigSpec.DoubleValue HAND_SCALE;
    public static final ForgeConfigSpec.DoubleValue ARM_LENGTH;
    public static final ForgeConfigSpec.BooleanValue SWING_ATTACK;
    public static final ForgeConfigSpec.BooleanValue SWING_MINE;
    public static final ForgeConfigSpec.BooleanValue SWING_HITS_PASSIVE;
    public static final ForgeConfigSpec.DoubleValue SWING_SPEED;
    public static final ForgeConfigSpec.DoubleValue SWING_MIN_CHARGE;
    public static final ForgeConfigSpec.BooleanValue PARRY_ENABLED;
    public static final ForgeConfigSpec.DoubleValue PARRY_SWING_SPEED;
    public static final ForgeConfigSpec.DoubleValue PARRY_WINDOW;
    public static final ForgeConfigSpec.BooleanValue SHOW_RETICLE;
    public static final ForgeConfigSpec.BooleanValue SHOW_OUTLINE;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();

        b.push("general");
        START_ENABLED = b.comment("Turn Free Aim on automatically when you join a world.")
                .define("startEnabled", false);
        b.pop();

        b.push("hand");
        HAND_SENSITIVITY = b.comment("How fast the hand turns, relative to your normal mouse sensitivity.")
                .defineInRange("handSensitivity", 1.0, 0.2, 3.0);
        FOLLOW_RATE = b.comment("How quickly the camera eases toward where the hand points (0 = only when dragged).")
                .defineInRange("cameraFollowRate", 1.2, 0.0, 4.0);
        CONE_YAW = b.comment("How far sideways (degrees) the hand can stray from the view centre before it drags the camera.")
                .defineInRange("coneYaw", 28.0, 5.0, 60.0);
        CONE_PITCH = b.comment("Same, but up/down (degrees).")
                .defineInRange("conePitch", 20.0, 5.0, 45.0);
        HAND_SCALE = b.comment("Size of the held item / arm.")
                .defineInRange("handScale", 0.85, 0.4, 1.5);
        ARM_LENGTH = b.comment("Distance from the shoulder to the grip, in blocks.")
                .defineInRange("armLength", 0.6, 0.3, 1.0);
        b.pop();

        b.push("swing");
        SWING_ATTACK = b.comment("Swinging the hand fast through a mob attacks it (no click needed).")
                .define("swingAttack", true);
        SWING_MINE = b.comment("Swinging the hand fast at a block starts breaking it (no click needed).")
                .define("swingMine", true);
        SWING_HITS_PASSIVE = b.comment("If false, swing-attacks only hit hostile mobs. If true they hit any living thing except players.")
                .define("swingHitsPassive", false);
        SWING_SPEED = b.comment("How fast (degrees per second) the hand must move to count as a swing.")
                .defineInRange("swingSpeed", 250.0, 60.0, 1000.0);
        SWING_MIN_CHARGE = b.comment("Swing-attacks wait until your attack cooldown is at least this charged (1.0 = full damage).")
                .defineInRange("swingMinCharge", 0.9, 0.0, 1.0);
        b.pop();

        b.push("parry");
        PARRY_ENABLED = b.comment("Enable parrying. Needs this mod on the server too (always true in singleplayer).")
                .define("parryEnabled", true);
        PARRY_SWING_SPEED = b.comment("Hand speed (degrees per second) that opens a parry window. Hold the guard key / middle mouse to guard without swinging.")
                .defineInRange("parrySwingSpeed", 200.0, 60.0, 800.0);
        PARRY_WINDOW = b.comment("How long (seconds) a parry stays active after a fast swing.")
                .defineInRange("parryWindow", 0.3, 0.1, 0.6);
        b.pop();

        b.push("visuals");
        SHOW_RETICLE = b.comment("Show the dot where the hand's ray lands.")
                .define("showReticle", true);
        SHOW_OUTLINE = b.comment("Outline the block the hand points at.")
                .define("showBlockOutline", true);
        b.pop();

        SPEC = b.build();
    }

    private FreeAimConfig() {}
}
