package com.example.freeaim;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.AbstractHurtingProjectile;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server side of the parry. The client only reports where the hand aims and whether the player
 * is guarding or mid-swing; the server does the actual deflecting / blocking.
 *
 *  - Projectiles: the "blade" is a segment from the grip along the hand's aim. Any projectile whose
 *    path passes through it while a parry is active is batted away along the hand's aim and becomes
 *    yours. (Attacking arrows directly isn't possible: vanilla servers kick you for it.)
 *  - Melee: an attack from a mob the hand is pointing at is cancelled. A swing-parry has a wider
 *    angle and staggers the attacker; plain guarding is narrower and doesn't.
 *
 * Needs the mod on the server (automatic in singleplayer). Tunables are constants here because a
 * dedicated server doesn't load client config.
 */
@Mod.EventBusSubscriber(modid = FreeAimMod.MODID)
public class ServerParry {

    private static final double ARM = 0.6;
    private static final double SHOULDER_SIDE = 0.30;
    private static final double SHOULDER_DROP = 0.18;

    private static final double BLADE_LENGTH = 1.1;          // with an item in hand
    private static final double FIST_LENGTH = 0.3;           // empty hand
    private static final double BLADE_RADIUS = 0.6;          // how close a projectile must pass to the blade
    private static final double MELEE_RANGE = 4.5;
    private static final double MELEE_COS_SWING = Math.cos(Math.toRadians(70.0));
    private static final double MELEE_COS_GUARD = Math.cos(Math.toRadians(40.0));
    private static final int STALE_TICKS = 6;                // ignore reports older than this

    private static final class State {
        boolean active, guard, swinging;
        float yaw, pitch;
        long tick;
    }

    private static final Map<UUID, State> STATES = new ConcurrentHashMap<>();

    static void update(ServerPlayer player, HandStatePacket p) {
        State st = STATES.computeIfAbsent(player.getUUID(), id -> new State());
        st.active = p.active();
        st.guard = p.guard();
        st.swinging = p.swinging();
        st.yaw = p.yaw();
        st.pitch = p.pitch();
        st.tick = player.level().getGameTime();
    }

    private static State activeState(ServerPlayer sp) {
        State st = STATES.get(sp.getUUID());
        if (st == null || !st.active || !(st.guard || st.swinging)) return null;
        if (sp.level().getGameTime() - st.tick > STALE_TICKS) return null;
        return st;
    }

    private static Vec3 direction(float yawDeg, float pitchDeg) {
        float p = pitchDeg * ((float) Math.PI / 180F);
        float y = -yawDeg * ((float) Math.PI / 180F);
        float cy = Mth.cos(y), sy = Mth.sin(y), cp = Mth.cos(p), sp = Mth.sin(p);
        return new Vec3(sy * cp, -sp, cy * cp);
    }

    private static double distToSegment(Vec3 p, Vec3 a, Vec3 b) {
        Vec3 ab = b.subtract(a);
        double len2 = ab.lengthSqr();
        double t = len2 < 1.0e-9 ? 0.0 : Mth.clamp(p.subtract(a).dot(ab) / len2, 0.0, 1.0);
        return p.distanceTo(a.add(ab.scale(t)));
    }

    private static void wearWeapon(ServerPlayer sp) {
        ItemStack stack = sp.getMainHandItem();
        if (stack.isDamageableItem()) {
            stack.hurtAndBreak(1, sp, p -> p.broadcastBreakEvent(InteractionHand.MAIN_HAND));
        }
    }

    private static void clash(ServerLevel level, Vec3 at, float pitch) {
        level.playSound(null, at.x, at.y, at.z, SoundEvents.SHIELD_BLOCK, SoundSource.PLAYERS, 0.9f, pitch);
        level.sendParticles(ParticleTypes.CRIT, at.x, at.y, at.z, 10, 0.2, 0.2, 0.2, 0.3);
    }

    // ------------------------------------------------------------ projectiles

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent e) {
        if (e.phase != TickEvent.Phase.END || !(e.player instanceof ServerPlayer sp)) return;
        State st = activeState(sp);
        if (st == null) return;

        ServerLevel level = sp.serverLevel();
        Vec3 dir = direction(st.yaw, st.pitch);

        double side = sp.getMainArm() == HumanoidArm.RIGHT ? 1.0 : -1.0;
        double yaw = Math.toRadians(sp.getYRot());
        Vec3 right = new Vec3(-Math.cos(yaw), 0.0, -Math.sin(yaw));
        Vec3 shoulder = sp.getEyePosition().add(right.scale(SHOULDER_SIDE * side)).add(0.0, -SHOULDER_DROP, 0.0);
        Vec3 grip = shoulder.add(dir.scale(ARM));
        Vec3 tip = grip.add(dir.scale(sp.getMainHandItem().isEmpty() ? FIST_LENGTH : BLADE_LENGTH));

        AABB box = new AABB(grip, tip).inflate(BLADE_RADIUS + 2.5);
        List<Projectile> projectiles = level.getEntitiesOfClass(Projectile.class, box,
                pr -> pr.getOwner() != sp && pr.isAlive());

        for (Projectile pr : projectiles) {
            Vec3 vel = pr.getDeltaMovement();
            if (vel.lengthSqr() < 0.04) continue;                       // stuck in a block / barely moving

            // sample the projectile's path over the next tick against the blade
            boolean hit = false;
            for (int i = 0; i <= 4 && !hit; i++) {
                Vec3 point = pr.position().add(vel.scale(i / 4.0));
                hit = distToSegment(point, grip, tip) <= BLADE_RADIUS;
            }
            if (!hit) continue;

            double speed = Math.max(vel.length(), 1.0) * 1.1;
            Vec3 newVel = dir.scale(speed);
            pr.setDeltaMovement(newVel);
            pr.setOwner(sp);
            if (pr instanceof AbstractHurtingProjectile hp) {           // fireballs steer by "power", not velocity
                Vec3 power = dir.scale(0.1);
                hp.xPower = power.x;
                hp.yPower = power.y;
                hp.zPower = power.z;
            }
            if (pr instanceof AbstractArrow arrow) arrow.setCritArrow(true);
            pr.hasImpulse = true;
            pr.hurtMarked = true;                                       // sync the new velocity to clients

            clash(level, pr.position(), 1.4f);
            wearWeapon(sp);
        }
    }

    // ------------------------------------------------------------ melee

    @SubscribeEvent
    public static void onAttacked(LivingAttackEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer sp)) return;
        State st = activeState(sp);
        if (st == null) return;

        DamageSource src = e.getSource();
        Entity direct = src.getDirectEntity();
        if (!(direct instanceof LivingEntity attacker) || src.getEntity() != direct) return;   // melee only

        Vec3 toAttacker = attacker.getBoundingBox().getCenter().subtract(sp.getEyePosition());
        double dist = toAttacker.length();
        if (dist < 1.0e-4 || dist > MELEE_RANGE) return;

        double cos = direction(st.yaw, st.pitch).dot(toAttacker.scale(1.0 / dist));
        if (cos < (st.swinging ? MELEE_COS_SWING : MELEE_COS_GUARD)) return;   // weapon wasn't in the way

        e.setCanceled(true);
        double dx = sp.getX() - attacker.getX(), dz = sp.getZ() - attacker.getZ();
        if (st.swinging) {                                                      // a real parry: stagger them
            attacker.knockback(0.9, dx, dz);
            attacker.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 30, 3));
        } else {
            attacker.knockback(0.4, dx, dz);
        }
        clash(sp.serverLevel(), attacker.getBoundingBox().getCenter(), st.swinging ? 1.2f : 0.9f);
        wearWeapon(sp);
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent e) {
        STATES.remove(e.getEntity().getUUID());
    }
}
