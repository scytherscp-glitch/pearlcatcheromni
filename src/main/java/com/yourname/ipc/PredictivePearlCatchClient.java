package com.yourname.ipc;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.Entity;
import net.minecraft.entity.projectile.thrown.EnderPearlEntity;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

public class PredictivePearlCatchClient implements ClientModInitializer {

    private static KeyBinding ipcKey;

    // State machine for multi-tick execution
    private static boolean isCatching = false;
    private static int ticksSincePearlThrow = 0;
    private static int originalSlot = -1;
    private static int windChargeSlot = -1;
    private static float savedYaw = 0f;
    private static float savedPitch = 0f;

    @Override
    public void onInitializeClient() {
        ipcKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.predictiveipc.catch",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_V,
                "category.predictiveipc"
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null || client.world == null) return;

            // Step 1: Trigger on keypress
            while (ipcKey.wasPressed() && !isCatching) {
                startPearlCatchSequence(client);
            }

            // Step 2: Track live pearl & compensate for player momentum on subsequent ticks
            if (isCatching) {
                ticksSincePearlThrow++;
                executeInterceptionTick(client);
            }
        });
    }

    private void startPearlCatchSequence(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        int pearlSlot = findHotbarSlot(player, Items.ENDER_PEARL);
        windChargeSlot = findHotbarSlot(player, Items.WIND_CHARGE);

        if (pearlSlot == -1 || windChargeSlot == -1) return;

        // Save state so your aim and hotbar return seamlessly
        originalSlot = player.getInventory().selectedSlot;
        savedYaw = player.getYaw();
        savedPitch = player.getPitch();

        // Throw the Ender Pearl in your current look direction
        player.getInventory().selectedSlot = pearlSlot;
        client.interactionManager.interactItem(player, Hand.MAIN_HAND);
        player.swingHand(Hand.MAIN_HAND);

        isCatching = true;
        ticksSincePearlThrow = 0;
    }

    private void executeInterceptionTick(MinecraftClient client) {
        ClientPlayerEntity player = client.player;

        // Timeout safety after 6 ticks
        if (ticksSincePearlThrow > 6 || windChargeSlot == -1) {
            cleanupState(player);
            return;
        }

        // Locate our newly spawned Ender Pearl in the world for exact position + velocity
        EnderPearlEntity myPearl = findMyActivePearl(client);

        Vec3d pearlPos;
        Vec3d pearlVel;

        if (myPearl != null) {
            pearlPos = myPearl.getPos();
            pearlVel = myPearl.getVelocity();
        } else {
            // Fallback kinematic prediction if entity packet hasn't synced yet
            Vec3d lookVec = getRotationVector(savedPitch, savedYaw);
            Vec3d playerVel = player.getVelocity();
            // Account for vertical momentum inheritance
            Vec3d inheritedVel = new Vec3d(playerVel.x, player.isOnGround() ? 0.0 : playerVel.y, playerVel.z);
            pearlVel = lookVec.multiply(1.5).add(inheritedVel);
            pearlPos = player.getEyePos().add(pearlVel);
        }

        // Simulate 1-2 ticks ahead to find exact collision point with a Wind Charge
        Vec3d futurePearlPos = simulatePearlPosition(pearlPos, pearlVel, 1);

        // Compensate for the player's own momentum when spawning the Wind Charge
        Vec3d playerEyePos = player.getEyePos();
        Vec3d playerVel = player.getVelocity();
        Vec3d inheritedChargeVel = new Vec3d(playerVel.x, player.isOnGround() ? 0.0 : playerVel.y, playerVel.z);

        // Required velocity vector for the Wind Charge to reach futurePearlPos in 1 tick
        // Wind Charge speed is ~1.5 blocks/tick
        Vec3d requiredTotalVel = futurePearlPos.subtract(playerEyePos);
        Vec3d compensatedAimVec = requiredTotalVel.subtract(inheritedChargeVel).normalize();

        float targetYaw = (float) (MathHelper.atan2(compensatedAimVec.z, compensatedAimVec.x) * (180.0 / Math.PI)) - 90.0F;
        float targetPitch = (float) (-(MathHelper.atan2(compensatedAimVec.y, Math.sqrt(compensatedAimVec.x * compensatedAimVec.x + compensatedAimVec.z * compensatedAimVec.z)) * (180.0 / Math.PI)));

        // Snap rotation to compensated angle, fire Wind Charge, and restore original camera
        player.setYaw(targetYaw);
        player.setPitch(MathHelper.clamp(targetPitch, -90.0F, 90.0F));

        player.getInventory().selectedSlot = windChargeSlot;
        client.interactionManager.interactItem(player, Hand.MAIN_HAND);
        player.swingHand(Hand.MAIN_HAND);

        // Restore original look direction & hotbar slot immediately
        player.setYaw(savedYaw);
        player.setPitch(savedPitch);
        cleanupState(player);
    }

    /**
     * Simulates exact Minecraft Ender Pearl physics (0.99 drag, -0.03 gravity per tick)
     */
    private Vec3d simulatePearlPosition(Vec3d startPos, Vec3d startVel, int ticksAhead) {
        Vec3d pos = startPos;
        Vec3d vel = startVel;
        for (int i = 0; i < ticksAhead; i++) {
            pos = pos.add(vel);
            vel = vel.multiply(0.99).add(0.0, -0.03, 0.0);
        }
        return pos;
    }

    private EnderPearlEntity findMyActivePearl(MinecraftClient client) {
        EnderPearlEntity closest = null;
        double bestDistSq = 36.0; // Within 6 blocks of player

        for (Entity entity : client.world.getEntities()) {
            if (entity instanceof EnderPearlEntity pearl) {
                if (pearl.getOwner() != null && pearl.getOwner().equals(client.player)) {
                    double distSq = client.player.squaredDistanceTo(pearl);
                    if (distSq < bestDistSq) {
                        bestDistSq = distSq;
                        closest = pearl;
                    }
                }
            }
        }
        return closest;
    }

    private Vec3d getRotationVector(float pitch, float yaw) {
        float f = pitch * ((float) Math.PI / 180F);
        float g = -yaw * ((float) Math.PI / 180F);
        float h = MathHelper.cos(g);
        float i = MathHelper.sin(g);
        float j = MathHelper.cos(f);
        float k = MathHelper.sin(f);
        return new Vec3d(i * j, -k, h * j);
    }

    private int findHotbarSlot(ClientPlayerEntity player, Item item) {
        for (int i = 0; i < 9; i++) {
            if (player.getInventory().getStack(i).getItem() == item) {
                return i;
            }
        }
        return -1;
    }

    private void cleanupState(ClientPlayerEntity player) {
        if (originalSlot != -1) {
            player.getInventory().selectedSlot = originalSlot;
        }
        isCatching = false;
        ticksSincePearlThrow = 0;
        originalSlot = -1;
        windChargeSlot = -1;
    }
    }

