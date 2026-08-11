package com.example.housenumbers;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@EventBusSubscriber(modid = HouseNumbersMod.MODID)
public class VillageEventHandler {

    private static final String NBT_TAG_ENTITY_UUID = "hn_tag_uuid";
    private static final String NBT_PARENT_UUID = "hn_parent_uuid";

    private static int tickCounter = 0;
    private static final int DISCOVER_INTERVAL_TICKS = 100; // ~5s: find new beds, lock homes
    private static final int FOLLOW_INTERVAL_TICKS = 5;     // ~0.25s: keep babies near parent
    private static final int SCAN_RADIUS = 32;

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (level.dimension() != Level.OVERWORLD) return;

        tickCounter++;
        PoiManager poiManager = level.getPoiManager();
        HouseNumberData data = HouseNumberData.get(level.getDataStorage());

        boolean doDiscover = tickCounter % DISCOVER_INTERVAL_TICKS == 0;
        boolean doFollow = tickCounter % FOLLOW_INTERVAL_TICKS == 0;
        if (!doDiscover && !doFollow) return;

        level.players().forEach(player -> {
            BlockPos center = player.blockPosition();

            if (doDiscover) {
                discoverBeds(level, poiManager, data, center);
            }

            List<Villager> villagers = level.getEntitiesOfClass(
                    Villager.class,
                    new AABB(center).inflate(SCAN_RADIUS)
            );

            for (Villager villager : villagers) {
                if (villager.isBaby()) {
                    handleBaby(level, poiManager, villager, villagers, data, doFollow);
                } else if (doDiscover) {
                    lockHome(level, poiManager, villager);
                    tagVillagerWithOwnHouse(level, villager, data);
                }
            }
        });
    }

    private static void discoverBeds(ServerLevel level, PoiManager poiManager, HouseNumberData data, BlockPos center) {
        Set<BlockPos> homeBeds = poiManager.findAll(
                holder -> holder.is(PoiTypes.HOME),
                pos -> true,
                center,
                SCAN_RADIUS,
                PoiManager.Occupancy.ANY
        ).collect(Collectors.toSet());

        for (BlockPos bedPos : homeBeds) {
            if (!data.isKnown(bedPos)) {
                int number = data.assign(bedPos);
                spawnHouseLabel(level, bedPos, number);
            }
        }
    }

    private static void lockHome(ServerLevel level, PoiManager poiManager, Villager villager) {
        Brain<Villager> brain = villager.getBrain();
        if (brain.hasMemoryValue(MemoryModuleType.HOME)) {
            return;
        }

        BlockPos villagerPos = villager.blockPosition();
        poiManager.getInRange(h -> h.is(PoiTypes.HOME), villagerPos, SCAN_RADIUS, PoiManager.Occupancy.HAS_SPACE)
                .min(Comparator.comparingDouble(p -> p.pos().distSqr(villagerPos)))
                .ifPresent(poiRecord -> {
                    BlockPos bedPos = poiRecord.pos();
                    boolean claimed = poiManager.take(
                            h -> h.is(PoiTypes.HOME),
                            (h, p) -> p.equals(bedPos),
                            bedPos,
                            1
                    ).isPresent();
                    if (claimed) {
                        brain.setMemory(MemoryModuleType.HOME, GlobalPos.of(level.dimension(), bedPos));
                    }
                });
    }

    private static void tagVillagerWithOwnHouse(ServerLevel level, Villager villager, HouseNumberData data) {
        villager.getBrain().getMemory(MemoryModuleType.HOME).ifPresent(home -> {
            Integer number = data.numberFor(home.pos());
            if (number != null) {
                updateTag(level, villager, number);
            }
        });
    }

    private static void handleBaby(ServerLevel level, PoiManager poiManager, Villager baby,
                                    List<Villager> nearbyVillagers, HouseNumberData data, boolean doFollow) {
        CompoundTag persist = baby.getPersistentData();

        baby.getBrain().getMemory(MemoryModuleType.HOME).ifPresent(home -> {
            poiManager.release(home.pos());
            baby.getBrain().eraseMemory(MemoryModuleType.HOME);
        });

        Villager parent = null;
        if (persist.hasUUID(NBT_PARENT_UUID)) {
            UUID parentId = persist.getUUID(NBT_PARENT_UUID);
            var entity = level.getEntity(parentId);
            if (entity instanceof Villager v && v.isAlive()) parent = v;
        }
        if (parent == null) {
            parent = nearbyVillagers.stream()
                    .filter(v -> !v.isBaby())
                    .min(Comparator.comparingDouble(v -> v.distanceToSqr(baby)))
                    .orElse(null);
            if (parent != null) {
                persist.putUUID(NBT_PARENT_UUID, parent.getUUID());
            }
        }

        if (parent == null) return;

        if (doFollow) {
            double distSqr = baby.distanceToSqr(parent);
            if (distSqr > 9.0 && !baby.getNavigation().isInProgress()) {
                baby.getNavigation().moveTo(parent, 0.6);
            }
        }

        parent.getBrain().getMemory(MemoryModuleType.HOME).ifPresent(home -> {
            Integer number = data.numberFor(home.pos());
            if (number != null) {
                updateTag(level, baby, number);
            }
        });
    }

    private static void updateTag(ServerLevel level, Villager owner, int number) {
        CompoundTag persist = owner.getPersistentData();
        Component text = Component.literal("House #" + number);

        if (persist.hasUUID(NBT_TAG_ENTITY_UUID)) {
            var existing = level.getEntity(persist.getUUID(NBT_TAG_ENTITY_UUID));
            if (existing instanceof ArmorStand stand && stand.isAlive()) {
                if (!text.getString().equals(stand.getName().getString())) {
                    stand.setCustomName(text);
                }
                return;
            }
        }

        ArmorStand tag = new ArmorStand(EntityType.ARMOR_STAND, level);
        tag.setPos(owner.getX(), owner.getY(), owner.getZ());
        tag.setInvisible(true);
        tag.setNoGravity(true);
        tag.setInvulnerable(true);
        tag.setNoBasePlate(true);
        tag.setSilent(true);
        
        // Modify NBT directly to avoid private access on setSmall / setMarker
        CompoundTag tagNbt = new CompoundTag();
        tag.saveWithoutId(tagNbt);
        tagNbt.putBoolean("Small", true);
        tagNbt.putBoolean("Marker", false);
        tag.load(tagNbt);

        tag.setCustomName(text);
        tag.setCustomNameVisible(true);
        level.addFreshEntity(tag);
        tag.startRiding(owner, true);

        persist.putUUID(NBT_TAG_ENTITY_UUID, tag.getUUID());
    }

    private static void spawnHouseLabel(ServerLevel level, BlockPos bedPos, int number) {
        Vec3 center = bedCenter(level, bedPos);
        ArmorStand stand = new ArmorStand(EntityType.ARMOR_STAND, level);
        stand.setPos(center.x, bedPos.getY() + 2, center.z);
        stand.setInvisible(true);
        stand.setNoGravity(true);
        stand.setInvulnerable(true);
        stand.setNoBasePlate(true);
        stand.setSilent(true);
        stand.setCustomName(Component.literal("House #" + number));
        stand.setCustomNameVisible(true);
        level.addFreshEntity(stand);
    }

    private static Vec3 bedCenter(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof BedBlock && state.hasProperty(BedBlock.PART)) {
            Direction facing = state.getValue(BedBlock.FACING);
            BedPart part = state.getValue(BedBlock.PART);
            BlockPos otherHalf = part == BedPart.HEAD ? pos.relative(facing.getOpposite()) : pos.relative(facing);
            double x = (pos.getX() + otherHalf.getX()) / 2.0 + 0.5;
            double z = (pos.getZ() + otherHalf.getZ()) / 2.0 + 0.5;
            return new Vec3(x, pos.getY(), z);
        }
        return new Vec3(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
    }
}
