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

    private static void discoverAndGroupStructures(ServerLevel level, PoiManager poiManager, HouseNumberData data, BlockPos playerPos) {
        List<BlockPos> unassignedBeds = poiManager.findAll(
                holder -> holder.is(PoiTypes.HOME),
                pos -> true,
                playerPos,
                SCAN_RADIUS,
                PoiManager.Occupancy.ANY
        ).filter(bedPos -> !data.isBedKnown(bedPos)).collect(Collectors.toList());

        if (unassignedBeds.isEmpty()) return;

        // Group beds in the same building structure together
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

            // Find or associate with the nearest village center within VILLAGE_RADIUS
            BlockPos villageCenter = data.findOrCreateVillageCenter(primaryBed, VILLAGE_RADIUS);
            int houseNumber = data.getNextHouseNumberForVillage(villageCenter);

            for (BlockPos bed : house) {
                data.registerBed(bed, houseNumber, villageCenter);
            }
            data.incrementVillageHouseNumber(villageCenter);

            // Locate physical structure roof top and spawn house label high above building
            BlockPos roofPos = findPhysicalRoofPeak(level, house);
            spawnRoofLabel(level, roofPos, houseNumber);
        }
    }

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
                            return; // Block taking beds in another village
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

    private static void enforceBoundariesAndPrivacy(ServerLevel level, Villager villager, HouseNumberData data) {
        CompoundTag nbt = villager.getPersistentData();
        BlockPos currentPos = villager.blockPosition();

        if (nbt.contains(NBT_VILLAGE_X)) {
            BlockPos homeVillage = new BlockPos(nbt.getInt(NBT_VILLAGE_X), nbt.getInt(NBT_VILLAGE_Y), nbt.getInt(NBT_VILLAGE_Z));
            if (!currentPos.closerThan(homeVillage, VILLAGE_RADIUS)) {
                if (!villager.getNavigation().isInProgress()) {
                    villager.getNavigation().moveTo(homeVillage.getX(), homeVillage.getY(), homeVillage.getZ(), 0.5);
                }
                return;
            }
        }

        if (level.isDay()) {
            villager.getBrain().getMemory(MemoryModuleType.HOME).ifPresent(home -> {
                Integer myHouseNum = data.getHouseNumber(home.pos());
                if (myHouseNum == null) return;

                for (BlockPos nearbyPos : BlockPos.betweenClosed(currentPos.offset(-2, -1, -2), currentPos.offset(2, 1, 2))) {
                    if (data.isBedKnown(nearbyPos)) {
                        Integer currentHouseNum = data.getHouseNumber(nearbyPos);
                        if (currentHouseNum != null && !currentHouseNum.equals(myHouseNum)) {
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

    /** Finds the building center and raycasts upward to find the roof top. */
    private static BlockPos findPhysicalRoofPeak(ServerLevel level, List<BlockPos> houseBeds) {
        int sumX = 0, sumZ = 0, startY = houseBeds.get(0).getY();

        for (BlockPos b : houseBeds) {
            sumX += b.getX();
            sumZ += b.getZ();
            startY = Math.max(startY, b.getY());
        }

        int centerX = sumX / houseBeds.size();
        int centerZ = sumZ / houseBeds.size();

        BlockPos.MutableBlockPos checkPos = new BlockPos.MutableBlockPos(centerX, startY, centerZ);
        while (checkPos.getY() < level.getMaxBuildHeight() - 1) {
            if (level.isEmptyBlock(checkPos) && level.isEmptyBlock(checkPos.above())) {
                if (!level.isEmptyBlock(checkPos.below())) {
                    return checkPos.immutable();
                }
            }
            checkPos.move(0, 1, 0);
        }

        return new BlockPos(centerX, startY + 4, centerZ);
    }

    private static void spawnRoofLabel(ServerLevel level, BlockPos roofPos, int number) {
        ArmorStand stand = new ArmorStand(EntityType.ARMOR_STAND, level);
        stand.setPos(roofPos.getX() + 0.5, roofPos.getY() + 1.2, roofPos.getZ() + 0.5);
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
