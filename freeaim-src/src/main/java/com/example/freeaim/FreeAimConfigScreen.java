package com.example.freeaim;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraftforge.common.ForgeConfigSpec;

/** Opened from the "Config" button in the Mods list. */
public class FreeAimConfigScreen extends Screen {
    private final Screen parent;

    public FreeAimConfigScreen(Screen parent) {
        super(Component.literal("Free Aim Settings"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int colW = 150, gap = 10, row = 22;
        int left = this.width / 2 - colW - gap / 2;
        int right = this.width / 2 + gap / 2;

        int y = 32;
        toggle(left, y, colW, "Start enabled", FreeAimConfig.START_ENABLED); y += row;
        slider(left, y, colW, "Hand sensitivity", FreeAimConfig.HAND_SENSITIVITY, 0.2, 3.0, "%.2f"); y += row;
        slider(left, y, colW, "Camera follow", FreeAimConfig.FOLLOW_RATE, 0.0, 4.0, "%.1f"); y += row;
        slider(left, y, colW, "Cone sideways", FreeAimConfig.CONE_YAW, 5.0, 60.0, "%.0f"); y += row;
        slider(left, y, colW, "Cone up/down", FreeAimConfig.CONE_PITCH, 5.0, 45.0, "%.0f"); y += row;
        slider(left, y, colW, "Hand size", FreeAimConfig.HAND_SCALE, 0.4, 1.5, "%.2f"); y += row;
        slider(left, y, colW, "Arm length", FreeAimConfig.ARM_LENGTH, 0.3, 1.0, "%.2f"); y += row;
        toggle(left, y, colW, "Show dot", FreeAimConfig.SHOW_RETICLE); y += row;
        toggle(left, y, colW, "Block outline", FreeAimConfig.SHOW_OUTLINE);

        y = 32;
        toggle(right, y, colW, "Swing attacks", FreeAimConfig.SWING_ATTACK); y += row;
        toggle(right, y, colW, "Swing mines", FreeAimConfig.SWING_MINE); y += row;
        toggle(right, y, colW, "Swing hits passive", FreeAimConfig.SWING_HITS_PASSIVE); y += row;
        slider(right, y, colW, "Swing speed", FreeAimConfig.SWING_SPEED, 60.0, 1000.0, "%.0f"); y += row;
        slider(right, y, colW, "Min. charge", FreeAimConfig.SWING_MIN_CHARGE, 0.0, 1.0, "%.2f"); y += row;
        toggle(right, y, colW, "Parry", FreeAimConfig.PARRY_ENABLED); y += row;
        slider(right, y, colW, "Parry swing speed", FreeAimConfig.PARRY_SWING_SPEED, 60.0, 800.0, "%.0f"); y += row;
        slider(right, y, colW, "Parry window (s)", FreeAimConfig.PARRY_WINDOW, 0.1, 0.6, "%.2f");

        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
                .bounds(this.width / 2 - 100, this.height - 28, 200, 20).build());
    }

    private void slider(int x, int y, int w, String label, ForgeConfigSpec.DoubleValue cfg,
                        double min, double max, String fmt) {
        addRenderableWidget(new DoubleSlider(x, y, w, 20, label, cfg, min, max, fmt));
    }

    private void toggle(int x, int y, int w, String label, ForgeConfigSpec.BooleanValue cfg) {
        addRenderableWidget(CycleButton.onOffBuilder(cfg.get())
                .create(x, y, w, 20, Component.literal(label), (btn, value) -> cfg.set(value)));
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(g);
        g.drawCenteredString(this.font, this.title, this.width / 2, 15, 0xFFFFFF);
        super.render(g, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        FreeAimConfig.SPEC.save();
        this.minecraft.setScreen(parent);
    }

    private static class DoubleSlider extends AbstractSliderButton {
        private final String label;
        private final ForgeConfigSpec.DoubleValue cfg;
        private final double min, max;
        private final String fmt;

        DoubleSlider(int x, int y, int w, int h, String label, ForgeConfigSpec.DoubleValue cfg,
                     double min, double max, String fmt) {
            super(x, y, w, h, Component.empty(), Mth.clamp((cfg.get() - min) / (max - min), 0.0, 1.0));
            this.label = label;
            this.cfg = cfg;
            this.min = min;
            this.max = max;
            this.fmt = fmt;
            updateMessage();
        }

        private double current() {
            return min + this.value * (max - min);
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.literal(label + ": " + String.format(fmt, current())));
        }

        @Override
        protected void applyValue() {
            cfg.set(Math.round(current() * 100.0) / 100.0);
        }
    }
}
