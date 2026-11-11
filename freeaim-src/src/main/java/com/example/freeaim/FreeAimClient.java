package com.example.freeaim;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.event.RenderHandEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Quaternionf;
import org.lwjgl.glfw.GLFW;

/**
 * Free Aim ("VR hands without the headset").
 *
 * The mouse no longer turns the camera. It moves your HAND: the first-person arm / held item
 * follows the mouse in real time and points at whatever it is over. Left click swings at that
 * spot (hit entities, hold to mine blocks), right click uses at that spot.
 * Push the hand to the edge of the screen to turn your body/camera.
 */
@Mod.EventBusSubscriber(modid = FreeAimMod.MODID, value = Dist.CLIENT)
public class FreeAimClient {

    public static final KeyMapping TOGGLE = new KeyMapping(
            "key.freeaim.toggle", KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_G, "key.categories.freeaim");

    // ---- tweakables ----
    private static final double HAND_SENSITIVITY = 1.0;  // 1.0 = hand moves pixel-for-pixel with the mouse
    private static final double EDGE = 0.15;             // outer 15% of the screen turns the camera
    private static final double TURN_SPEED = 200.0;      // degrees/second at the very edge
    private static final double HAND_DEPTH = 0.8;        // how far in front of the camera the hand floats
    private static final double ITEM_LENGTH = 0.4;       // so the item TIP (not the grip) lands on the target
    private static final double HAND_FOV_TAN = Math.tan(Math.toRadians(35.0)); // first-person hand uses a 70° FOV

    // hand position on screen, normalised -1..1 (x right, y up)
    private static double handX = 0.35, handY = -0.25;

    private static boolean enabled = false;
    private static boolean leftDown = false;
    private static boolean cursorHidden = false;
    private static boolean skipDelta = true;
    private static long lastFrameNanos = System.nanoTime();

    @Mod.EventBusSubscriber(modid = FreeAimMod.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static class ModBus {
        @SubscribeEvent
        public static void registerKeys(RegisterKeyMappingsEvent event) {
            event.register(TOGGLE);
        }
    }

    // ---------------------------------------------------------------- picking

    /** Ray from the camera through the hand's screen position; nearest block/entity in reach, or null. */
    private static HitResult pick(Minecraft mc) {
        LocalPlayer player = mc.player;
        var camera = mc.gameRenderer.getMainCamera();
        Window w = mc.getWindow();

        double tanHalfFov = Math.tan(Math.toRadians(mc.options.fov().get()) / 2.0);
        double aspect = (double) w.getWidth() / (double) w.getHeight();

        var look = camera.getLookVector();
        var up = camera.getUpVector();
        var left = camera.getLeftVector();
        double rx = -handX * tanHalfFov * aspect;   // "right" = -left
        double uy = handY * tanHalfFov;
        Vec3 dir = new Vec3(
                look.x() + left.x() * rx + up.x() * uy,
                look.y() + left.y() * rx + up.y() * uy,
                look.z() + left.z() * rx + up.z() * uy).normalize();

        double blockReach = player.getBlockReach();
        double entityReach = player.getEntityReach();
        double reach = Math.max(blockReach, entityReach);

        Vec3 start = camera.getPosition();
        Vec3 end = start.add(dir.scale(reach));
        Vec3 eye = player.getEyePosition();

        BlockHitResult blockHit = mc.level.clip(new ClipContext(
                start, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        double blockDist = Double.MAX_VALUE;
        if (blockHit.getType() == HitResult.Type.BLOCK
                && eye.distanceToSqr(blockHit.getLocation()) <= blockReach * blockReach) {
            blockDist = start.distanceToSqr(blockHit.getLocation());
        } else {
            blockHit = null;
        }

        EntityHitResult entityHit = ProjectileUtil.getEntityHitResult(
                player, start, end, new AABB(start, end).inflate(1.0),
                e -> !e.isSpectator() && e.isPickable(), reach * reach);
        if (entityHit != null && eye.distanceToSqr(entityHit.getLocation()) > entityReach * entityReach) {
            entityHit = null;
        }

        if (entityHit != null && start.distanceToSqr(entityHit.getLocation()) < blockDist) return entityHit;
        return blockHit;
    }

    // ---------------------------------------------------------------- actions

    private static void attackAtHand(Minecraft mc) {
        if (mc.player.isUsingItem()) return;
        if (pick(mc) instanceof EntityHitResult eh) {
            mc.gameMode.attack(mc.player, eh.getEntity());
        }
        mc.player.swing(InteractionHand.MAIN_HAND);
    }

    private static void continueBreaking(Minecraft mc) {
        if (mc.player.isUsingItem()) return;
        if (pick(mc) instanceof BlockHitResult bh && !mc.level.getBlockState(bh.getBlockPos()).isAir()) {
            BlockPos pos = bh.getBlockPos();
            Direction dir = bh.getDirection();
            if (mc.gameMode.continueDestroyBlock(pos, dir)) {
                mc.particleEngine.crack(pos, dir);
                mc.player.swing(InteractionHand.MAIN_HAND);
            }
        } else {
            mc.gameMode.stopDestroyBlock();
        }
    }

    private static void useAtHand(Minecraft mc) {
        LocalPlayer player = mc.player;
        HitResult hit = pick(mc);
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack stack = player.getItemInHand(hand);
            if (hit instanceof EntityHitResult eh) {
                Entity ent = eh.getEntity();
                InteractionResult r = mc.gameMode.interactAt(player, ent, eh, hand);
                if (!r.consumesAction()) r = mc.gameMode.interact(player, ent, hand);
                if (r.consumesAction()) {
                    if (r.shouldSwing()) player.swing(hand);
                    return;
                }
            } else if (hit instanceof BlockHitResult bh) {
                InteractionResult r = mc.gameMode.useItemOn(player, hand, bh);
                if (r.consumesAction()) {
                    if (r.shouldSwing()) player.swing(hand);
                    return;
                }
            }
            if (!stack.isEmpty()) {
                InteractionResult r = mc.gameMode.useItem(player, hand);
                if (r.consumesAction()) {
                    if (r.shouldSwing()) player.swing(hand);
                    return;
                }
            }
        }
    }

    // ---------------------------------------------------------------- cursor handling

    private static void setCursorHidden(Minecraft mc, boolean hide) {
        if (hide == cursorHidden) return;
        cursorHidden = hide;
        GLFW.glfwSetInputMode(mc.getWindow().getWindow(), GLFW.GLFW_CURSOR,
                hide ? GLFW.GLFW_CURSOR_HIDDEN : GLFW.GLFW_CURSOR_NORMAL);
    }

    // ---------------------------------------------------------------- events

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();

        while (TOGGLE.consumeClick()) {
            if (mc.player == null) continue;
            enabled = !enabled;
            leftDown = false;
            if (enabled) {
                skipDelta = true;
            } else if (mc.screen == null) {
                setCursorHidden(mc, false);
                mc.mouseHandler.grabMouse();
            }
            mc.player.displayClientMessage(Component.literal("Free Aim: " + (enabled ? "ON" : "OFF")), true);
        }

        if (mc.player == null || mc.level == null || mc.gameMode == null) {
            enabled = false;
            leftDown = false;
            return;
        }
        if (!enabled) return;

        if (mc.screen != null) {          // inventory etc: give the normal cursor back
            setCursorHidden(mc, false);
            leftDown = false;
            return;
        }

        // Free the mouse (closing a screen re-grabs it) and hide the OS cursor: the HAND is the cursor now.
        if (mc.mouseHandler.isMouseGrabbed()) {
            mc.mouseHandler.releaseMouse();
            cursorHidden = false;
            skipDelta = true;
        }
        setCursorHidden(mc, true);

        if (leftDown) continueBreaking(mc);
        else mc.gameMode.stopDestroyBlock();
    }

    /** Swallow vanilla mouse-button handling and act at the hand instead of the screen centre. */
    @SubscribeEvent
    public static void onMouseButton(InputEvent.MouseButton.Pre e) {
        Minecraft mc = Minecraft.getInstance();
        if (!enabled || mc.screen != null || mc.player == null || mc.level == null || mc.gameMode == null) return;
        e.setCanceled(true);

        boolean press = e.getAction() == GLFW.GLFW_PRESS;
        if (e.getButton() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            leftDown = press;
            if (press) attackAtHand(mc);
        } else if (e.getButton() == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            if (press) useAtHand(mc);
            else if (mc.player.isUsingItem()) mc.gameMode.releaseUsingItem(mc.player);
        }
    }

    /**
     * Every frame: read raw mouse movement, move the hand, snap the OS cursor back to the middle
     * (so the hand can never run out of room), and turn the camera if the hand is at an edge.
     */
    @SubscribeEvent
    public static void onRenderTick(TickEvent.RenderTickEvent e) {
        if (e.phase != TickEvent.Phase.START) return;
        long now = System.nanoTime();
        double dt = Math.min((now - lastFrameNanos) / 1.0e9, 0.1);
        lastFrameNanos = now;

        Minecraft mc = Minecraft.getInstance();
        boolean active = enabled && mc.player != null && mc.screen == null
                && mc.isWindowActive() && !mc.mouseHandler.isMouseGrabbed();
        if (!active) { skipDelta = true; return; }

        Window w = mc.getWindow();
        double cx = w.getScreenWidth() / 2.0, cy = w.getScreenHeight() / 2.0;
        double[] px = new double[1], py = new double[1];
        GLFW.glfwGetCursorPos(w.getWindow(), px, py);
        if (!skipDelta) {
            double k = 2.0 / w.getScreenHeight() * HAND_SENSITIVITY;
            handX = Mth.clamp(handX + (px[0] - cx) * k, -1.0, 1.0);
            handY = Mth.clamp(handY - (py[0] - cy) * k, -1.0, 1.0);
        }
        skipDelta = false;
        GLFW.glfwSetCursorPos(w.getWindow(), cx, cy);

        double yaw = edge(handX);
        double pitch = -edge(handY);            // hand up => look up
        if (yaw != 0 || pitch != 0) {
            // Entity#turn multiplies its arguments by 0.15 degrees
            mc.player.turn(yaw * TURN_SPEED * dt / 0.15, pitch * TURN_SPEED * dt / 0.15);
        }
    }

    private static double edge(double v) {
        double a = Math.abs(v);
        double inner = 1.0 - EDGE;
        if (a <= inner) return 0.0;
        return Math.signum(v) * Math.min(1.0, (a - inner) / EDGE);
    }

    /**
     * Replace the vanilla first-person main hand: place the arm / item where the mouse is and
     * aim it along the line from the shoulder to that spot. Swing progress becomes a poke.
     * (While eating, drawing a bow, blocking, or holding a map, vanilla rendering takes over.)
     */
    @SubscribeEvent
    public static void onRenderHand(RenderHandEvent e) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (!enabled || player == null || mc.level == null || mc.screen != null
                || e.getHand() != InteractionHand.MAIN_HAND) return;
        ItemStack stack = e.getItemStack();
        if (player.isUsingItem() || stack.is(Items.FILLED_MAP)) return;
        e.setCanceled(true);

        PoseStack ps = e.getPoseStack();
        boolean right = player.getMainArm() == HumanoidArm.RIGHT;
        float side = right ? 1.0f : -1.0f;
        float swing = e.getSwingProgress();
        float equip = e.getEquipProgress();

        Window w = mc.getWindow();
        double aspect = (double) w.getWidth() / (double) w.getHeight();

        // where the tip should appear (camera space: +x right, +y up, -z forward)
        Vec3 tip = new Vec3(handX * HAND_FOV_TAN * aspect * HAND_DEPTH, handY * HAND_FOV_TAN * HAND_DEPTH, -HAND_DEPTH);
        Vec3 shoulder = new Vec3(side * 0.55, -0.7, -0.2);
        Vec3 dir = tip.subtract(shoulder).normalize();

        double length = stack.isEmpty() ? 0.0 : ITEM_LENGTH;
        double poke = Mth.sin(swing * (float) Math.PI) * 0.35;
        Vec3 grip = tip.subtract(dir.scale(length)).add(dir.scale(poke));

        ps.pushPose();
        ps.translate(grip.x, grip.y - 0.6 * equip, grip.z);
        ps.mulPose(new Quaternionf().rotationTo(0f, 0f, -1f, (float) dir.x, (float) dir.y, (float) dir.z));

        if (stack.isEmpty()) {
            // Same arm placement chain vanilla uses, minus its fixed screen-corner offset.
            ps.mulPose(Axis.YP.rotationDegrees(side * 45.0f));
            RenderSystem.setShaderTexture(0, player.getSkinTextureLocation());
            ps.translate(side * -1.0f, 3.6f, 3.5f);
            ps.mulPose(Axis.ZP.rotationDegrees(side * 120.0f));
            ps.mulPose(Axis.XP.rotationDegrees(200.0f));
            ps.mulPose(Axis.YP.rotationDegrees(side * -135.0f));
            ps.translate(side * 5.6f, 0.0f, 0.0f);
            PlayerRenderer renderer = (PlayerRenderer) mc.getEntityRenderDispatcher().getRenderer(player);
            if (right) renderer.renderRightHand(ps, e.getMultiBufferSource(), e.getPackedLight(), player);
            else renderer.renderLeftHand(ps, e.getMultiBufferSource(), e.getPackedLight(), player);
        } else {
            ItemDisplayContext ctx = right
                    ? ItemDisplayContext.FIRST_PERSON_RIGHT_HAND : ItemDisplayContext.FIRST_PERSON_LEFT_HAND;
            mc.getItemRenderer().renderStatic(player, stack, ctx, !right, ps, e.getMultiBufferSource(),
                    mc.level, e.getPackedLight(), OverlayTexture.NO_OVERLAY, player.getId() + ctx.ordinal());
        }
        ps.popPose();
    }

    /** Replace the centre crosshair with a small dot where the hand is pointing. */
    @SubscribeEvent
    public static void onOverlay(RenderGuiOverlayEvent.Pre e) {
        if (!enabled || e.getOverlay() != VanillaGuiOverlay.CROSSHAIR.type()) return;
        e.setCanceled(true);
        int x = (int) ((handX * 0.5 + 0.5) * e.getWindow().getGuiScaledWidth());
        int y = (int) ((0.5 - handY * 0.5) * e.getWindow().getGuiScaledHeight());
        e.getGuiGraphics().fill(x - 1, y - 1, x + 2, y + 2, 0xCCFFFFFF);
    }

    /** Outline the block the hand is pointing at. */
    @SubscribeEvent
    public static void onLevelRender(RenderLevelStageEvent e) {
        if (!enabled || e.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.screen != null) return;
        if (!(pick(mc) instanceof BlockHitResult bh)) return;

        BlockPos pos = bh.getBlockPos();
        BlockState state = mc.level.getBlockState(pos);
        if (state.isAir()) return;

        Vec3 cam = e.getCamera().getPosition();
        VoxelShape shape = state.getShape(mc.level, pos, CollisionContext.of(mc.player));
        PoseStack.Pose pose = e.getPoseStack().last();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        VertexConsumer vc = buffers.getBuffer(RenderType.lines());
        double ox = pos.getX() - cam.x, oy = pos.getY() - cam.y, oz = pos.getZ() - cam.z;

        shape.forAllEdges((x1, y1, z1, x2, y2, z2) -> {
            float dx = (float) (x2 - x1), dy = (float) (y2 - y1), dz = (float) (z2 - z1);
            float len = Mth.sqrt(dx * dx + dy * dy + dz * dz);
            dx /= len; dy /= len; dz /= len;
            vc.vertex(pose.pose(), (float) (x1 + ox), (float) (y1 + oy), (float) (z1 + oz))
                    .color(0f, 0f, 0f, 0.5f).normal(pose.normal(), dx, dy, dz).endVertex();
            vc.vertex(pose.pose(), (float) (x2 + ox), (float) (y2 + oy), (float) (z2 + oz))
                    .color(0f, 0f, 0f, 0.5f).normal(pose.normal(), dx, dy, dz).endVertex();
        });
        buffers.endBatch(RenderType.lines());
    }
}
