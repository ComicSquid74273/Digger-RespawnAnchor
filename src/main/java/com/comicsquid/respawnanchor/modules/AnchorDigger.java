package com.comicsquid.respawnanchor.modules;

import com.comicsquid.respawnanchor.DiggerRespawnAnchor;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.entity.DamageUtils;
import meteordevelopment.meteorclient.utils.misc.input.Input;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.player.SlotUtils;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

public final class AnchorDigger extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgInventory = settings.createGroup("Inventory");
    private final SettingGroup sgSafety = settings.createGroup("Safety");

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
        .name("rotate")
        .description("Rotates server-side toward the anchor before each action.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> placeDelay = sgGeneral.add(new IntSetting.Builder()
        .name("place-delay")
        .description("Ticks between placing anchors.")
        .defaultValue(1)
        .range(0, 20)
        .sliderRange(0, 10)
        .build()
    );

    private final Setting<Integer> chargeDelay = sgGeneral.add(new IntSetting.Builder()
        .name("charge-delay")
        .description("Ticks between charging anchors.")
        .defaultValue(1)
        .range(0, 20)
        .sliderRange(0, 10)
        .build()
    );

    private final Setting<Integer> detonateDelay = sgGeneral.add(new IntSetting.Builder()
        .name("detonate-delay")
        .description("Ticks between detonating anchors.")
        .defaultValue(1)
        .range(0, 20)
        .sliderRange(0, 10)
        .build()
    );

    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
        .name("range")
        .description("Maximum distance from the anchor position.")
        .defaultValue(5)
        .range(1, 6)
        .sliderRange(1, 6)
        .build()
    );

    private final Setting<Boolean> searchInventory = sgInventory.add(new BoolSetting.Builder()
        .name("search-inventory")
        .description("Moves required items from the main inventory into the hotbar.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> swing = sgGeneral.add(new BoolSetting.Builder()
        .name("swing")
        .description("Swings your hand client-side.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> maxSelfDamage = sgSafety.add(new DoubleSetting.Builder()
        .name("max-self-damage")
        .description("Maximum predicted damage allowed before detonation.")
        .defaultValue(4)
        .range(0, 36)
        .sliderRange(0, 20)
        .build()
    );

    private final Setting<Double> minHealth = sgSafety.add(new DoubleSetting.Builder()
        .name("min-health")
        .description("Minimum health plus absorption required after predicted damage.")
        .defaultValue(10)
        .range(0, 36)
        .sliderRange(0, 36)
        .build()
    );

    private final Setting<Boolean> pauseOnDamage = sgSafety.add(new BoolSetting.Builder()
        .name("pause-on-damage")
        .description("Pauses detonation if the predicted self-damage is too high.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> blastShield = sgSafety.add(new BoolSetting.Builder()
        .name("blast-shield")
        .description("Places a glowstone block on the player side of the anchor before detonating to block self-damage.")
        .defaultValue(true)
        .build()
    );

    private BlockPos activeAnchorPos;
    private int placeDelayTimer;
    private int chargeDelayTimer;
    private int detonateDelayTimer;
    private boolean rotating;
    private boolean warnedDimension;
    private boolean warnedAnchor;
    private boolean warnedGlowstone;
    private boolean warnedUnsafe;

    private enum ActionType {
        Place,
        Charge,
        Detonate,
        Shield
    }

    public AnchorDigger() {
        super(DiggerRespawnAnchor.CATEGORY, "anchor-digger", "Hold use while aiming at terrain to place, charge, and detonate respawn anchors.");
    }

    @Override
    public void onActivate() {
        resetHold();
    }

    @Override
    public void onDeactivate() {
        if (mc.options != null) mc.options.keyUse.setDown(Input.isPressed(mc.options.keyUse));
        resetHold();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null || mc.gameMode == null) return;

        if (mc.gui.screen() != null) {
            resetHold();
            return;
        }

        boolean keyPressed = Input.isPressed(mc.options.keyUse);

        // Suppress client right click block placement/use when module is actively working on an anchor
        if (keyPressed && activeAnchorPos != null) {
            mc.options.keyUse.setDown(false);
        }

        if (activeAnchorPos == null && !keyPressed) {
            return;
        }

        if (mc.level.dimension() == Level.NETHER) {
            if (!warnedDimension) {
                error("Respawn anchors do not explode in the Nether.");
                warnedDimension = true;
            }
            resetCycle();
            return;
        }
        warnedDimension = false;

        if (rotating) return;

        // Increment timers
        placeDelayTimer++;
        chargeDelayTimer++;
        detonateDelayTimer++;

        // Get targeted block
        BlockPos target = getTargetPos();

        // Prioritize finishing the active anchor first if it still exists
        if (activeAnchorPos != null) {
            BlockState activeState = mc.level.getBlockState(activeAnchorPos);
            if (activeState.is(Blocks.RESPAWN_ANCHOR)) {
                if (!isWithinRange(activeAnchorPos)) {
                    resetCycle();
                    return;
                }

                int charges = activeState.getValue(BlockStateProperties.RESPAWN_ANCHOR_CHARGES);
                if (charges == 0) {
                    chargeAnchor(activeAnchorPos);
                } else {
                    detonateAnchor(activeAnchorPos);
                }
                return;
            } else {
                activeAnchorPos = null;
            }
        }

        // If no active anchor and key is pressed, place a new one or handle existing target
        if (keyPressed && target != null && isWithinRange(target)) {
            BlockState targetState = mc.level.getBlockState(target);
            if (targetState.is(Blocks.RESPAWN_ANCHOR)) {
                activeAnchorPos = target.immutable();
                int charges = targetState.getValue(BlockStateProperties.RESPAWN_ANCHOR_CHARGES);
                if (charges == 0) {
                    chargeAnchor(activeAnchorPos);
                } else {
                    detonateAnchor(activeAnchorPos);
                }
            } else if (targetState.canBeReplaced()) {
                placeAnchor(target);
            }
        }
    }

    private void placeAnchor(BlockPos pos) {
        if (placeDelayTimer < placeDelay.get()) return;

        FindItemResult anchor = findAnchor();
        if (!anchor.found()) {
            if (!warnedAnchor) {
                warning("No respawn anchors found.");
                warnedAnchor = true;
            }
            return;
        }
        warnedAnchor = false;

        Direction side = BlockUtils.getPlaceSide(pos);
        BlockPos neighbour = side == null ? pos : pos.relative(side);
        Vec3 hitPos = Vec3.atCenterOf(pos);
        if (side != null) {
            hitPos = hitPos.add(side.getStepX() * 0.5, side.getStepY() * 0.5, side.getStepZ() * 0.5);
        }
        BlockHitResult bhr = new BlockHitResult(hitPos, side == null ? Direction.UP : side.getOpposite(), neighbour, false);

        interact(anchor, bhr, ActionType.Place, pos);
    }

    private void chargeAnchor(BlockPos pos) {
        if (chargeDelayTimer < chargeDelay.get()) return;

        FindItemResult glowstone = findGlowstone();
        if (!glowstone.found()) {
            if (!warnedGlowstone) {
                warning("No glowstone found.");
                warnedGlowstone = true;
            }
            return;
        }
        warnedGlowstone = false;

        Vec3 center = Vec3.atCenterOf(pos);
        BlockHitResult bhr = new BlockHitResult(center, Direction.UP, pos, true);

        interact(glowstone, bhr, ActionType.Charge, pos);
    }

    private void detonateAnchor(BlockPos pos) {
        if (detonateDelayTimer < detonateDelay.get()) return;
        if (!isSafe(pos)) return;

        // If blast shield is enabled, place a glowstone block on our side of the anchor before detonating
        if (blastShield.get()) {
            BlockPos shieldPos = getShieldPos(pos);
            if (shieldPos != null) {
                FindItemResult glowstone = findGlowstone();
                if (glowstone.found()) {
                    Direction side = BlockUtils.getPlaceSide(shieldPos);
                    BlockPos neighbour = side == null ? shieldPos : shieldPos.relative(side);
                    Vec3 hitPos = Vec3.atCenterOf(shieldPos);
                    if (side != null) {
                        hitPos = hitPos.add(side.getStepX() * 0.5, side.getStepY() * 0.5, side.getStepZ() * 0.5);
                    }
                    BlockHitResult bhr = new BlockHitResult(hitPos, side == null ? Direction.UP : side.getOpposite(), neighbour, false);
                    interact(glowstone, bhr, ActionType.Shield, shieldPos);
                }
            }
        }

        FindItemResult detonator = findDetonator();
        if (!detonator.found()) return;

        Vec3 center = Vec3.atCenterOf(pos);
        BlockHitResult bhr = new BlockHitResult(center, Direction.UP, pos, true);

        interact(detonator, bhr, ActionType.Detonate, pos);
    }

    private BlockPos getShieldPos(BlockPos anchor) {
        if (mc.player == null) return null;

        // Find the relative vector direction from anchor to player eyes
        Vec3 dir = mc.player.getEyePosition().subtract(Vec3.atCenterOf(anchor));
        Direction closestDir = Direction.getNearest((int) dir.x, (int) dir.y, (int) dir.z, Direction.UP);

        // Calculate block position between player and anchor
        BlockPos targetPos = anchor.relative(closestDir);

        // Verify if we can place a block there
        if (mc.level.getBlockState(targetPos).canBeReplaced()) {
            return targetPos;
        }

        return null;
    }

    private void interact(FindItemResult item, BlockHitResult hitResult, ActionType type, BlockPos targetPos) {
        Vec3 hitPos = hitResult.getLocation();
        Runnable action = () -> {
            rotating = false;
            if (!isActive() || mc.player == null || mc.level == null) return;

            packetInteract(item, hitResult);

            if (type == ActionType.Place) {
                activeAnchorPos = targetPos.immutable();
                placeDelayTimer = 0;
            } else if (type == ActionType.Charge) {
                chargeDelayTimer = 0;
            } else if (type == ActionType.Detonate) {
                detonateDelayTimer = 0;
                // Instantly remove block client-side for immediate visual response and fast digging
                mc.level.setBlock(targetPos, Blocks.AIR.defaultBlockState(), 3);
                activeAnchorPos = null;
            }
        };

        if (rotate.get()) {
            rotating = true;
            Rotations.rotate(Rotations.getYaw(hitPos), Rotations.getPitch(hitPos), 50, action);
        } else {
            action.run();
        }
    }

    private void packetInteract(FindItemResult item, BlockHitResult hitResult) {
        if (mc.player == null || mc.getConnection() == null) return;

        InteractionHand hand = item.isOffhand() ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
        int slot = item.slot();
        int originalSlot = mc.player.getInventory().getSelectedSlot();

        boolean needSwap = !item.isOffhand() && originalSlot != slot;

        if (needSwap) {
            mc.player.connection.send(new ServerboundSetCarriedItemPacket(slot));
        }

        // Send direct use packet to bypass client-side hand flickering/desyncs
        mc.player.connection.send(new ServerboundUseItemOnPacket(hand, hitResult, 0));

        if (swing.get()) {
            if (hand == InteractionHand.MAIN_HAND) {
                mc.player.swing(InteractionHand.MAIN_HAND);
            } else {
                mc.player.connection.send(new ServerboundSwingPacket(hand));
            }
        }

        if (needSwap) {
            mc.player.connection.send(new ServerboundSetCarriedItemPacket(originalSlot));
        }
    }

    private FindItemResult findGlowstone() {
        if (mc.player == null) return new FindItemResult(-1, 0);

        // Slot 9 is index 8
        ItemStack slot9Stack = mc.player.getInventory().getItem(8);
        if (slot9Stack.is(Items.GLOWSTONE)) {
            return new FindItemResult(8, slot9Stack.getCount());
        }

        FindItemResult hotbarResult = InvUtils.findInHotbar(Items.GLOWSTONE);
        if (hotbarResult.found()) {
            InvUtils.move().from(hotbarResult.slot()).toHotbar(8);
            return new FindItemResult(8, mc.player.getInventory().getItem(8).getCount());
        }

        if (searchInventory.get()) {
            FindItemResult invResult = InvUtils.find(s -> s.is(Items.GLOWSTONE), SlotUtils.MAIN_START, SlotUtils.MAIN_END);
            if (invResult.found()) {
                InvUtils.move().from(invResult.slot()).toHotbar(8);
                return new FindItemResult(8, mc.player.getInventory().getItem(8).getCount());
            }
        }
        return new FindItemResult(-1, 0);
    }

    private FindItemResult findAnchor() {
        FindItemResult hotbarResult = InvUtils.findInHotbar(Items.RESPAWN_ANCHOR);
        if (hotbarResult.found()) return hotbarResult;

        if (searchInventory.get()) {
            FindItemResult invResult = InvUtils.find(stack -> stack.is(Items.RESPAWN_ANCHOR), SlotUtils.MAIN_START, SlotUtils.MAIN_END);
            if (invResult.found()) {
                int targetSlot = getBestAnchorSlot();
                InvUtils.move().from(invResult.slot()).toHotbar(targetSlot);
                return InvUtils.findInHotbar(Items.RESPAWN_ANCHOR);
            }
        }
        return new FindItemResult(-1, 0);
    }

    private int getBestAnchorSlot() {
        if (mc.player == null) return 0;
        // Avoid slot 9 (index 8) because that is specifically reserved for Glowstone
        for (int i = 0; i < 8; i++) {
            if (mc.player.getInventory().getItem(i).isEmpty()) {
                return i;
            }
        }
        return 0;
    }

    private FindItemResult findDetonator() {
        FindItemResult anchor = findAnchor();
        if (anchor.found() && anchor.isHotbar()) return anchor;

        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (!stack.isEmpty() && !stack.is(Items.GLOWSTONE)) {
                return new FindItemResult(i, stack.getCount());
            }
        }
        return new FindItemResult(-1, 0);
    }

    private BlockPos getTargetPos() {
        if (!(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) return null;

        BlockPos hitPos = hit.getBlockPos();
        if (mc.level.getBlockState(hitPos).canBeReplaced()) return hitPos.immutable();
        return hitPos.relative(hit.getDirection()).immutable();
    }

    private boolean isWithinRange(BlockPos pos) {
        return PlayerUtils.isWithin(Vec3.atCenterOf(pos), range.get());
    }

    private boolean isSafe(BlockPos pos) {
        if (!pauseOnDamage.get()) return true;

        float damage = DamageUtils.anchorDamage(mc.player, Vec3.atCenterOf(pos));
        boolean safe = SafetyPolicy.allows(damage, PlayerUtils.getTotalHealth(), maxSelfDamage.get(), minHealth.get());

        if (!safe && !warnedUnsafe) {
            warning("Anchor paused: predicted self-damage is %.1f.", damage);
            warnedUnsafe = true;
        } else if (safe) {
            warnedUnsafe = false;
        }

        return safe;
    }

    private void resetCycle() {
        activeAnchorPos = null;
        rotating = false;
    }

    private void resetHold() {
        resetCycle();
        warnedDimension = false;
        warnedAnchor = false;
        warnedGlowstone = false;
        warnedUnsafe = false;
        placeDelayTimer = 0;
        chargeDelayTimer = 0;
        detonateDelayTimer = 0;
    }
}