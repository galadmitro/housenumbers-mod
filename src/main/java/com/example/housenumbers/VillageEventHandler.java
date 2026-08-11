package com.example.housenumbers;

import net.minecraft.core.BlockPos;
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
import net.minecraft.world.phys.AABB;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import java.util.*;
import java.util.stream.Collectors;

@EventBusSubscriber(modid = HouseNumbersMod.MODID)
public class VillageEventHandler {

    private static final String NBT_VILLAGE_X = "hn_v_x";
    private static final String NBT_VILLAGE_Y = "hn_v_y";
    private static final String NBT_VILLAGE_Z = "hn_v_z";

    private static int tickCounter = 0;
    private static final int DISCOVER_INTERVAL_TICKS = 100; // ~5s
    private static final int NAV_INTERVAL_TICKS = 30;       // Smooth pathfinding checks
    private static final int SCAN_RADIUS = 48;
    private static final int VILLAGE_RADIUS = 75;
    private static final int HOUSE_CLUSTERING_RADIUS = 6;

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (level.dimension() != Level.OVERWORLD) return;

        tickCounter++;
        PoiManager poiManager = level.getPoiManager();
        HouseNumberData data = HouseNumberData.get(level.getDataStorage());

        boolean doDiscover = tickCounter % DISCOVER_INTERVAL_TICKS == 0;
        boolean doNav = tickCounter % NAV_INTERVAL_TICKS == 0;
        if (!doDiscover && !doNav) return;

        level.players().forEach(player -> {
            BlockPos playerPos = player.blockPosition();

            if (doDiscover) {
                discoverAndGroupStructures(level, poiManager, data, playerPos);
            }

            List<Villager> villagers = level.getEntitiesOfClass(
                    Villager.class,
                    new AABB(playerPos).inflate(SCAN_RADIUS)
            );

            for (Villager villager : villagers) {
                if (doDiscover) {
                    lockHomeInVillage(level, poiManager, villager, data);
                }
                if (doNav) {
                    enforceBoundariesAndPrivacy(level, villager, data);
                }
            }
        });
    }

    /** Discovers beds, groups nearby beds into single Houses, and places tags above the roof. */
    private static void discoverAndGroupStructures(ServerLevel level, PoiManager poiManager, HouseNumberData data, BlockPos playerPos) {
        List<BlockPos> unassignedBeds = poiManager.findAll(
                holder -> holder.is(PoiTypes.HOME),
                pos -> true,
                playerPos,
                SCAN_RADIUS,
                PoiManager.Occupancy.ANY
        ).filter(bedPos -> !data.isBedKnown(bedPos)).collect(Collectors.toList());

        if (unassignedBeds.isEmpty()) return;

        // Group beds into structures (beds within 6 blocks belong to the same House)
        List<List<BlockPos>> houseClusters = new ArrayList<>();
        for (BlockPos bed : unassignedBeds) {
            boolean addedToExisting = false;
            for (List<BlockPos> cluster : houseClusters) {
                for (BlockPos member : cluster) {
                    if (member.closerThan(bed, HOUSE_CLUSTERING_RADIUS)) {
                        cluster.add(bed);
                        addedToExisting = true;
                        break;
                    }
                }
                if (addedToExisting) break;
            }
            if (!addedToExisting) {
                List<BlockPos> newCluster = new ArrayList<>();
                newCluster.add(bed);
                houseClusters.add(newCluster);
            }
        }

        for (List<BlockPos> house : houseClusters) {
            BlockPos primaryBed = house.get(0);
            BlockPos villageCenter = findOrCreateVillageCenter(data, primaryBed);
            int houseNumber = data.getNextHouseNumberForVillage(villageCenter);

            for (BlockPos bed : house) {
                data.registerBed(bed, houseNumber, villageCenter);
            }
            data.incrementVillageHouseNumber(villageCenter);

            // Calculate roof location and spawn the tag above the house structure
            BlockPos roofPos = findRoofTop(level, house);
            spawnRoofLabel(level, roofPos, houseNumber);
        }
    }

    /** Locks villager homes strictly within their own village boundary. */
    private static void lockHomeInVillage(ServerLevel level, PoiManager poiManager, Villager villager, HouseNumberData data) {
        Brain<Villager> brain = villager.getBrain();
        if (brain.hasMemoryValue(MemoryModuleType.HOME)) return;

        BlockPos villagerPos = villager.blockPosition();

        poiManager.getInRange(h -> h.is(PoiTypes.HOME), villagerPos, SCAN_RADIUS, PoiManager.Occupancy.HAS_SPACE)
                .min(Comparator.comparingDouble(p -> p.getPos().distSqr(villagerPos)))
                .ifPresent(poiRecord -> {
                    BlockPos bedPos = poiRecord.getPos();
                    BlockPos bedVillage = data.getVillageCenter(bedPos);

                    CompoundTag nbt = villager.getPersistentData();
                    if (nbt.contains(NBT_VILLAGE_X)) {
                        BlockPos myVillage = new BlockPos(nbt.getInt(NBT_VILLAGE_X), nbt.getInt(NBT_VILLAGE_Y), nbt.getInt(NBT_VILLAGE_Z));
                        if (bedVillage != null && !bedVillage.equals(myVillage)) {
                            return; // Don't allow taking a bed from another village!
                        }
                    }

                    boolean claimed = poiManager.take(
                            h -> h.is(PoiTypes.HOME),
                            (h, p) -> p.equals(bedPos),
                            bedPos,
                            1
                    ).isPresent();

                    if (claimed) {
                        brain.setMemory(MemoryModuleType.HOME, GlobalPos.of(level.dimension(), bedPos));
                        if (bedVillage != null) {
                            nbt.putInt(NBT_VILLAGE_X, bedVillage.getX());
                            nbt.putInt(NBT_VILLAGE_Y, bedVillage.getY());
                            nbt.putInt(NBT_VILLAGE_Z, bedVillage.getZ());
                        }
                    }
                });
    }

    /** Prevents villagers from straying into other villages or trespassing during the day. */
    private static void enforceBoundariesAndPrivacy(ServerLevel level, Villager villager, HouseNumberData data) {
        CompoundTag nbt = villager.getPersistentData();
        BlockPos currentPos = villager.blockPosition();

        // 1. Cross-village boundary check
        if (nbt.contains(NBT_VILLAGE_X)) {
            BlockPos homeVillage = new BlockPos(nbt.getInt(NBT_VILLAGE_X), nbt.getInt(NBT_VILLAGE_Y), nbt.getInt(NBT_VILLAGE_Z));
            if (!currentPos.closerThan(homeVillage, VILLAGE_RADIUS)) {
                if (!villager.getNavigation().isInProgress()) {
                    villager.getNavigation().moveTo(homeVillage.getX(), homeVillage.getY(), homeVillage.getZ(), 0.5);
                }
                return;
            }
        }

        // 2. Daytime trespassing prevention (stay out of other houses during day)
        if (level.isDay()) {
            villager.getBrain().getMemory(MemoryModuleType.HOME).ifPresent(home -> {
                Integer myHouseNum = data.getHouseNumber(home.pos());
                if (myHouseNum == null) return;

                // Check if villager is standing inside a house that isn't theirs
                for (BlockPos nearbyPos : BlockPos.betweenClosed(currentPos.offset(-2, -1, -2), currentPos.offset(2, 1, 2))) {
                    if (data.isBedKnown(nearbyPos)) {
                        Integer currentHouseNum = data.getHouseNumber(nearbyPos);
                        if (currentHouseNum != null && !currentHouseNum.equals(myHouseNum)) {
                            // Step out of the house toward open space
                            villager.getNavigation().moveTo(villager.getX() + (villager.getRandom().nextDouble() - 0.5) * 6,
                                    villager.getY(),
                                    villager.getZ() + (villager.getRandom().nextDouble() - 0.5) * 6, 0.55);
                            break;
                        }
                    }
                }
            });
        }
    }

    private static BlockPos findOrCreateVillageCenter(HouseNumberData data, BlockPos bedPos) {
        BlockPos knownCenter = data.getVillageCenter(bedPos);
        if (knownCenter != null) return knownCenter;
        return bedPos;
    }

    /** Raycasts upward from the center of a house bed cluster to find the roof top. */
    private static BlockPos findRoofTop(ServerLevel level, List<BlockPos> houseBeds) {
        int sumX = 0, sumY = 0, sumZ = 0;
        for (BlockPos pos : houseBeds) {
            sumX += pos.getX();
            sumY += pos.getY();
            sumZ += pos.getZ();
        }
        BlockPos avgCenter = new BlockPos(sumX / houseBeds.size(), sumY / houseBeds.size(), sumZ / houseBeds.size());

        BlockPos roof = avgCenter.above(2);
        while (roof.getY() < level.getMaxBuildHeight() - 1 && !level.isEmptyBlock(roof)) {
            roof = roof.above();
        }
        return roof;
    }

    private static void spawnRoofLabel(ServerLevel level, BlockPos roofPos, int number) {
        ArmorStand stand = new ArmorStand(EntityType.ARMOR_STAND, level);
        stand.setPos(roofPos.getX() + 0.5, roofPos.getY() + 0.5, roofPos.getZ() + 0.5);
        stand.setInvisible(true);
        stand.setNoGravity(true);
        stand.setInvulnerable(true);
        stand.setNoBasePlate(true);
        stand.setSilent(true);

        CompoundTag tagNbt = new CompoundTag();
        stand.saveWithoutId(tagNbt);
        tagNbt.putBoolean("Small", true);
        tagNbt.putBoolean("Marker", true);
        stand.load(tagNbt);

        stand.setCustomName(Component.literal("House #" + number));
        stand.setCustomNameVisible(true);
        level.addFreshEntity(stand);
    }
}
