package com.example.freeaim;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.Camera;
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
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
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
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
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
 * Free Aim, VR-style (Vivecraft-like) controls with just a mouse.
 *
 * The hand is a real object in the world, hanging off your shoulder, with its own aim that the
 * mouse controls. The camera is a separate "head" that eases toward where the hand points.
 * Swinging the hand fast hits mobs / starts breaking blocks, and swinging into an incoming attack
 * or arrow (or holding guard) parries it. Settings: Mods -> Free Aim -> Config.
 */
@Mod.EventBusSubscriber(modid = FreeAimMod.MODID, value = Dist.CLIENT)
public class FreeAimClient {

    public static final KeyMapping TOGGLE = new KeyMapping(
            "key.freeaim.toggle", KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_G, "key.categories.freeaim");

    public static final KeyMapping LOOK = new KeyMapping(
            "key.freeaim.look", KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_LEFT_ALT, "key.categories.freeaim");

    /** Hold to guard. Middle mouse button also guards. */
    public static final KeyMapping GUARD = new KeyMapping(
            "key.freeaim.guard", KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_Z, "key.categories.freeaim");

    // ---- fixed tuning (everything else is in FreeAimConfig) ----
    private static final double HAND_MAX_YAW = 100.0;
    private static final double HAND_MAX_PITCH = 75.0;
    private static final double SHOULDER_SIDE = 0.30;
    private static final double SHOULDER_DROP = 0.18;
    private static final double HAND_FOV_TAN = Math.tan(Math.toRadians(35.0)); // vanilla draws hands with a 70° FOV
    private static final int SWING_MINE_HOLD_TICKS = 8;
    private static final long SWING_ATTACK_GAP_NANOS = 200_000_000L;

    // the hand's aim, in world space (degrees; pitch positive = down, like the player)
    private static double handYaw = 0.0, handPitch = 0.0;
    private static double lastHandYaw = 0.0, lastHandPitch = 0.0;
    private static double swingSpeed = 0.0;                 // smoothed hand speed, degrees/second
    private static Vec3 aimStart = Vec3.ZERO, aimDir = new Vec3(0, 0, 1);

    private static boolean enabled = false;
    private static boolean pendingAuto = false;
    private static boolean leftDown = false;
    private static boolean middleDown = false;
    private static boolean cursorHidden = false;
    private static boolean skipDelta = true;
    private static boolean lastSentActive = false;
    private static int swingMineTicks = 0;
    private static long lastSwingAttackNanos = 0L;
    private static long parryUntilNanos = 0L;
    private static long lastFrameNanos = System.nanoTime();

    @Mod.EventBusSubscriber(modid = FreeAimMod.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static class ModBus {
        @SubscribeEvent
        public static void registerKeys(RegisterKeyMappingsEvent event) {
            event.register(TOGGLE);
            event.register(LOOK);
            event.register(GUARD);
        }
    }

    // ---------------------------------------------------------------- geometry

    private static Vec3 handDirection() {
        float p = (float) Math.toRadians(handPitch);
        float y = (float) Math.toRadians(-handYaw);
        float cy = Mth.cos(y), sy = Mth.sin(y), cp = Mth.cos(p), sp = Mth.sin(p);
        return new Vec3(sy * cp, -sp, cy * cp);
    }

    /** The shoulder follows the body's facing (camera yaw only), so it doesn't swing around when you look up/down. */
    private static Vec3 shoulder(Minecraft mc, Camera cam) {
        double side = mc.player.getMainArm() == HumanoidArm.RIGHT ? 1.0 : -1.0;
        double yaw = Math.toRadians(cam.getYRot());
        Vec3 right = new Vec3(-Math.cos(yaw), 0.0, -Math.sin(yaw));
        return cam.getPosition().add(right.scale(SHOULDER_SIDE * side)).add(0.0, -SHOULDER_DROP, 0.0);
    }

    /** World point -> camera space (+x right, +y up, -z forward). */
    private static Vec3 toCameraSpace(Camera cam, Vec3 worldPoint) {
        Vec3 rel = worldPoint.subtract(cam.getPosition());
        var look = cam.getLookVector();
        var up = cam.getUpVector();
        var left = cam.getLeftVector();
        return new Vec3(
                -(rel.x * left.x() + rel.y * left.y() + rel.z * left.z()),
                rel.x * up.x() + rel.y * up.y() + rel.z * up.z(),
                -(rel.x * look.x() + rel.y * look.y() + rel.z * look.z()));
    }

    // ---------------------------------------------------------------- picking

    /** Ray from the hand along its aim; nearest block/entity in reach, or null. */
    private static HitResult pick(Minecraft mc) {
        LocalPlayer player = mc.player;
        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 dir = handDirection();
        Vec3 eye = player.getEyePosition();

        Vec3 grip = shoulder(mc, camera).add(dir.scale(FreeAimConfig.ARM_LENGTH.get()));
        Vec3 start = grip;
        // if the hand is poking through a wall, fall back to starting at the eyes
        if (mc.level.clip(new ClipContext(eye, grip, ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, player)).getType() == HitResult.Type.BLOCK) {
            start = eye;
        }
        aimStart = start;
        aimDir = dir;

        double blockReach = player.getBlockReach();
        double entityReach = player.getEntityReach();
        double reach = Math.max(blockReach, entityReach);
        Vec3 end = start.add(dir.scale(reach));

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

    /** Swing-attacks never hit players, and by default only hit hostile mobs. */
    private static boolean swingMayHit(Entity target) {
        if (!(target instanceof LivingEntity) || target instanceof Player) return false;
        return FreeAimConfig.SWING_HITS_PASSIVE.get() || target instanceof Enemy;
    }

    /** Called every frame while the hand is moving faster than the swing threshold. */
    private static void trySwingHit(Minecraft mc) {
        LocalPlayer player = mc.player;
        if (player.isUsingItem() || mc.gameMode == null) return;
        HitResult hit = pick(mc);
        if (hit instanceof EntityHitResult eh) {
            if (!FreeAimConfig.SWING_ATTACK.get() || !swingMayHit(eh.getEntity())) return;
            long now = System.nanoTime();
            if (now - lastSwingAttackNanos < SWING_ATTACK_GAP_NANOS) return;
            if (player.getAttackStrengthScale(0.5f) < FreeAimConfig.SWING_MIN_CHARGE.get()) return;
            lastSwingAttackNanos = now;
            mc.gameMode.attack(player, eh.getEntity());
            player.swing(InteractionHand.MAIN_HAND);
        } else if (hit instanceof BlockHitResult bh && FreeAimConfig.SWING_MINE.get()) {
            if (!mc.level.getBlockState(bh.getBlockPos()).isAir()) swingMineTicks = SWING_MINE_HOLD_TICKS;
        }
    }

    // ---------------------------------------------------------------- state helpers

    private static void setCursorHidden(Minecraft mc, boolean hide) {
        if (hide == cursorHidden) return;
        cursorHidden = hide;
        GLFW.glfwSetInputMode(mc.getWindow().getWindow(), GLFW.GLFW_CURSOR,
                hide ? GLFW.GLFW_CURSOR_HIDDEN : GLFW.GLFW_CURSOR_NORMAL);
    }

    private static void setEnabled(Minecraft mc, boolean on) {
        enabled = on;
        leftDown = false;
        middleDown = false;
        swingMineTicks = 0;
        swingSpeed = 0.0;
        if (on) {
            handYaw = mc.player.getYRot();
            handPitch = mc.player.getXRot() + 5.0;
            lastHandYaw = handYaw;
            lastHandPitch = handPitch;
            skipDelta = true;
        } else if (mc.screen == null) {
            setCursorHidden(mc, false);
            mc.mouseHandler.grabMouse();
        }
        mc.player.displayClientMessage(Component.literal("Free Aim: " + (on ? "ON" : "OFF")), true);
    }

    /** Tell the server where the hand is aiming (needed for parrying). Silently skipped if the server lacks the mod. */
    private static void sendHandState(Minecraft mc, boolean active) {
        if (!active && !lastSentActive) return;
        var conn = mc.getConnection();
        if (conn == null || !FreeAimNetwork.CHANNEL.isRemotePresent(conn.getConnection())) return;
        boolean guard = active && (middleDown || GUARD.isDown());
        boolean swinging = active && System.nanoTime() < parryUntilNanos;
        FreeAimNetwork.CHANNEL.sendToServer(
                new HandStatePacket(active, (float) handYaw, (float) handPitch, guard, swinging));
        lastSentActive = active;
    }

    // ---------------------------------------------------------------- events

    @SubscribeEvent
    public static void onLogin(ClientPlayerNetworkEvent.LoggingIn e) {
        pendingAuto = true;
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();

        while (TOGGLE.consumeClick()) {
            if (mc.player == null) continue;
            setEnabled(mc, !enabled);
        }

        if (mc.player == null || mc.level == null || mc.gameMode == null) {
            enabled = false;
            leftDown = false;
            middleDown = false;
            lastSentActive = false;
            return;
        }
        if (pendingAuto) {
            pendingAuto = false;
            if (FreeAimConfig.START_ENABLED.get() && !enabled) setEnabled(mc, true);
        }

        sendHandState(mc, enabled && mc.screen == null && FreeAimConfig.PARRY_ENABLED.get());
        if (!enabled) return;

        if (mc.screen != null) {          // inventory etc: give the normal cursor back
            setCursorHidden(mc, false);
            leftDown = false;
            middleDown = false;
            return;
        }

        // Free the mouse (closing a screen re-grabs it) and hide the OS cursor.
        if (mc.mouseHandler.isMouseGrabbed()) {
            mc.mouseHandler.releaseMouse();
            cursorHidden = false;
            skipDelta = true;
        }
        setCursorHidden(mc, true);

        boolean mining = leftDown || swingMineTicks > 0;
        if (swingMineTicks > 0) swingMineTicks--;
        if (mining) continueBreaking(mc);
        else mc.gameMode.stopDestroyBlock();
    }

    /** Swallow vanilla mouse-button handling and act from the hand instead of the screen centre. */
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
        } else if (e.getButton() == GLFW.GLFW_MOUSE_BUTTON_MIDDLE) {
            middleDown = press;                                           // hold to guard
        }
    }

    /** Same maths as vanilla's MouseHandler#turnPlayer (degrees = pixels * this * 0.15). */
    private static double mouseFactor(Minecraft mc) {
        double d = mc.options.sensitivity().get() * 0.6 + 0.2;
        return d * d * d * 8.0;
    }

    /**
     * Every frame: read raw mouse movement, snap the OS cursor back to the middle, then
     *  1. aim the hand with the movement (or, with LOOK held, turn the head instead),
     *  2. let the head ease toward the hand and be dragged if the hand leaves the view cone,
     *  3. measure how fast the hand is moving: fast = swing (hit / mine) and opens a parry window.
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
        LocalPlayer player = mc.player;

        Window w = mc.getWindow();
        double cx = w.getScreenWidth() / 2.0, cy = w.getScreenHeight() / 2.0;
        double[] px = new double[1], py = new double[1];
        GLFW.glfwGetCursorPos(w.getWindow(), px, py);
        double dx = px[0] - cx, dy = py[0] - cy;
        GLFW.glfwSetCursorPos(w.getWindow(), cx, cy);

        boolean look = LOOK.isDown();
        int invert = mc.options.invertYMouse().get() ? -1 : 1;
        double f = mouseFactor(mc);
        double sens = FreeAimConfig.HAND_SENSITIVITY.get();

        if (!skipDelta) {
            if (look) {
                player.turn(dx * f, dy * f * invert);                       // head only
            } else {
                handYaw += dx * f * 0.15 * sens;                            // hand only
                handPitch = Mth.clamp(handPitch + dy * f * 0.15 * invert * sens, -90.0, 90.0);
            }
        }
        skipDelta = false;

        if (!look) {
            double yawDiff = Mth.wrapDegrees(handYaw - player.getYRot());
            double pitchDiff = handPitch - player.getXRot();

            double soft = 1.0 - Math.exp(-FreeAimConfig.FOLLOW_RATE.get() * dt);   // gentle ease toward the hand
            double dYaw = yawDiff * soft;
            double dPitch = pitchDiff * soft;

            double coneYaw = FreeAimConfig.CONE_YAW.get();
            double conePitch = FreeAimConfig.CONE_PITCH.get();
            double remYaw = yawDiff - dYaw;                                  // hard limit: hand stays in the cone
            double remPitch = pitchDiff - dPitch;
            if (Math.abs(remYaw) > coneYaw) dYaw += remYaw - Math.signum(remYaw) * coneYaw;
            if (Math.abs(remPitch) > conePitch) dPitch += remPitch - Math.signum(remPitch) * conePitch;

            if (dYaw != 0.0 || dPitch != 0.0) player.turn(dYaw / 0.15, dPitch / 0.15);
        } else {
            // looking around with LOOK: the hand stays in the world, but can't be left completely behind
            double yawDiff = Mth.wrapDegrees(handYaw - player.getYRot());
            if (Math.abs(yawDiff) > HAND_MAX_YAW) handYaw = player.getYRot() + Math.signum(yawDiff) * HAND_MAX_YAW;
            double pitchDiff = handPitch - player.getXRot();
            if (Math.abs(pitchDiff) > HAND_MAX_PITCH) handPitch = player.getXRot() + Math.signum(pitchDiff) * HAND_MAX_PITCH;
        }

        // ---- hand speed (angular, world space) ----
        double dY = Mth.wrapDegrees(handYaw - lastHandYaw);
        double dP = handPitch - lastHandPitch;
        lastHandYaw = handYaw;
        lastHandPitch = handPitch;
        double yawPart = dY * Math.cos(Math.toRadians(handPitch));
        double angular = Math.sqrt(yawPart * yawPart + dP * dP);
        double inst = dt > 1.0e-4 ? angular / dt : 0.0;
        swingSpeed += (inst - swingSpeed) * (1.0 - Math.exp(-dt / 0.04));

        if (!look) {
            if (swingSpeed >= FreeAimConfig.SWING_SPEED.get()) trySwingHit(mc);
            if (swingSpeed >= FreeAimConfig.PARRY_SWING_SPEED.get()) {
                parryUntilNanos = now + (long) (FreeAimConfig.PARRY_WINDOW.get() * 1.0e9);
            }
        }
    }

    /**
     * Draw the hand where it really is in the world: at the shoulder, extended along its aim.
     * Position and direction are converted into the camera's space, so turning the camera slides
     * the hand across the screen while it keeps pointing at the same spot.
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
        float scale = FreeAimConfig.HAND_SCALE.get().floatValue();

        Camera cam = mc.gameRenderer.getMainCamera();
        // The hand layer uses its own 70° FOV; rescale so it lines up with the world at your FOV setting.
        double ratio = HAND_FOV_TAN / Math.tan(Math.toRadians(mc.options.fov().get()) / 2.0);

        Vec3 dirW = handDirection();
        double poke = Mth.sin(swing * Mth.PI) * 0.3;
        Vec3 gripW = shoulder(mc, cam).add(dirW.scale(FreeAimConfig.ARM_LENGTH.get() + poke));
        Vec3 gripC = toCameraSpace(cam, gripW);
        Vec3 aheadC = toCameraSpace(cam, gripW.add(dirW));
        gripC = new Vec3(gripC.x * ratio, gripC.y * ratio, gripC.z);
        aheadC = new Vec3(aheadC.x * ratio, aheadC.y * ratio, aheadC.z);
        Vec3 dirC = aheadC.subtract(gripC).normalize();

        ps.pushPose();
        ps.translate(gripC.x, gripC.y - 0.6 * equip, gripC.z);
        // A held tool's tip points ~45 degrees up from straight ahead in vanilla's neutral pose;
        // an empty arm points straight ahead. Rotate that "natural" direction onto the aim direction.
        float fy = stack.isEmpty() ? 0.0f : 0.7071f;
        float fz = stack.isEmpty() ? -1.0f : -0.7071f;
        ps.mulPose(new Quaternionf().rotationTo(0f, fy, fz, (float) dirC.x, (float) dirC.y, (float) dirC.z));
        ps.scale(scale, scale, scale);

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

    /** Replace the centre crosshair with a dot drawn where the hand's ray lands. */
    @SubscribeEvent
    public static void onOverlay(RenderGuiOverlayEvent.Pre e) {
        if (!enabled || e.getOverlay() != VanillaGuiOverlay.CROSSHAIR.type()) return;
        e.setCanceled(true);
        if (!FreeAimConfig.SHOW_RETICLE.get()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        HitResult hit = pick(mc);
        Vec3 target = hit != null ? hit.getLocation() : aimStart.add(aimDir.scale(20.0));
        Vec3 c = toCameraSpace(mc.gameRenderer.getMainCamera(), target);
        if (c.z > -0.05) return;                                           // behind the camera

        Window w = mc.getWindow();
        double tan = Math.tan(Math.toRadians(mc.options.fov().get()) / 2.0);
        double aspect = (double) w.getWidth() / (double) w.getHeight();
        double ndcX = c.x / (-c.z * tan * aspect);
        double ndcY = c.y / (-c.z * tan);
        if (Math.abs(ndcX) > 1.0 || Math.abs(ndcY) > 1.0) return;          // off-screen

        int x = (int) ((ndcX * 0.5 + 0.5) * e.getWindow().getGuiScaledWidth());
        int y = (int) ((0.5 - ndcY * 0.5) * e.getWindow().getGuiScaledHeight());
        e.getGuiGraphics().fill(x - 1, y - 1, x + 2, y + 2, 0xCCFFFFFF);
    }

    /** Outline the block the hand is pointing at. */
    @SubscribeEvent
    public static void onLevelRender(RenderLevelStageEvent e) {
        if (!enabled || e.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        if (!FreeAimConfig.SHOW_OUTLINE.get()) return;
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
