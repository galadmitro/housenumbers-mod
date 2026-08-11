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
import net.minecraft.world.level.chunk.LevelChunk;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import java.util.*;
import java.util.stream.Collectors;

@EventBusSubscriber(modid = HouseNumbersMod.MODID)
public class VillageEventHandler {

    private static final String NBT_VILLAGE_X = "hn_v_x";
    private static final String NBT_VILLAGE_Y = "hn_v_y";
    private static final String NBT_VILLAGE_Z = "hn_v_z";
    private static final String NBT_PARENT_UUID = "hn_parent_uuid";

    private static int tickCounter = 0;
    private static final int CHECK_INTERVAL_TICKS = 10;     // Run strict checks twice a second
    private static final int VILLAGE_RADIUS = 80;
    private static final int HOUSE_STRUCTURE_RADIUS = 8;    // Beds within 8 blocks = same house structure

    /** Triggers IMMEDIATELY when a village chunk generates or loads—no player proximity required! */
    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (level.dimension() != Level.OVERWORLD) return;
        if (!(event.getChunk() instanceof LevelChunk chunk)) return;

        PoiManager poiManager = level.getPoiManager();
        HouseNumberData data = HouseNumberData.get(level.getDataStorage());

        BlockPos chunkCenter = chunk.getPos().getMiddleBlockPosition(64);
        discoverAndTagHouseStructures(level, poiManager, data, chunkCenter, 32);
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (level.dimension() != Level.OVERWORLD) return;

        tickCounter++;
        if (tickCounter % CHECK_INTERVAL_TICKS != 0) return;

        PoiManager poiManager = level.getPoiManager();
        HouseNumberData data = HouseNumberData.get(level.getDataStorage());

        level.getAllEntities().forEach(entity -> {
            if (entity instanceof Villager villager && villager.isAlive()) {
                if (villager.isBaby()) {
                    handleBabyVillager(level, villager);
                } else {
                    lockHomeInVillage(level, poiManager, villager, data);
                    enforceStrictHousePrivacy(level, villager, data);
                    enforceVillageBoundary(level, villager);
                }
            }
        });
    }

    private static void discoverAndTagHouseStructures(ServerLevel level, PoiManager poiManager, HouseNumberData data, BlockPos center, int radius) {
        List<BlockPos> unassignedBeds = poiManager.findAll(
                holder -> holder.is(PoiTypes.HOME),
                pos -> true,
                center,
                radius,
                PoiManager.Occupancy.ANY
        ).filter(bedPos -> !data.isBedKnown(bedPos)).collect(Collectors.toList());

        if (unassignedBeds.isEmpty()) return;

        List<List<BlockPos>> structureClusters = new ArrayList<>();
        for (BlockPos bed : unassignedBeds) {
            boolean addedToExisting = false;
            for (List<BlockPos> cluster : structureClusters) {
                for (BlockPos member : cluster) {
                    if (member.closerThan(bed, HOUSE_STRUCTURE_RADIUS)) {
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
                structureClusters.add(newCluster);
            }
        }

        for (List<BlockPos> houseBeds : structureClusters) {
            BlockPos primaryBed = houseBeds.get(0);
            BlockPos villageCenter = data.findOrCreateVillageCenter(primaryBed, VILLAGE_RADIUS);

            BlockPos structureCenter = calculateStructureCenter(houseBeds);
            if (data.isStructureTagged(structureCenter)) continue;

            int houseNumber = data.getNextHouseNumberForVillage(villageCenter);

            for (BlockPos bed : houseBeds) {
                data.registerBed(bed, houseNumber, villageCenter);
            }
            data.incrementVillageHouseNumber(villageCenter);
            data.markStructureTagged(structureCenter);

            BlockPos roofPos = findRoofTop(level, structureCenter);
            spawnRoofLabel(level, roofPos, houseNumber);
        }
    }

    /** Strictly prevents villagers from entering or hiding in unowned houses (even when attacked or during a bell ring). */
    private static void enforceStrictHousePrivacy(ServerLevel level, Villager villager, HouseNumberData data) {
        Brain<Villager> brain = villager.getBrain();
        BlockPos currentPos = villager.blockPosition();

        // Wipe bell panic and hiding place memories so they never hide in someone else's house
        if (brain.hasMemoryValue(MemoryModuleType.HEARD_BELL_TIME)) {
            brain.eraseMemory(MemoryModuleType.HEARD_BELL_TIME);
        }
        if (brain.hasMemoryValue(MemoryModuleType.HIDING_PLACE)) {
            brain.eraseMemory(MemoryModuleType.HIDING_PLACE);
        }

        Optional<GlobalPos> myHomeOpt = brain.getMemory(MemoryModuleType.HOME);
        BlockPos myHomeBed = myHomeOpt.map(GlobalPos::pos).orElse(null);
        Integer myHouseNumber = myHomeBed != null ? data.getHouseNumber(myHomeBed) : null;

        // Check if villager is standing inside ANY house structure
        for (BlockPos nearby : BlockPos.betweenClosed(currentPos.offset(-2, -1, -2), currentPos.offset(2, 1, 2))) {
            if (data.isBedKnown(nearby)) {
                Integer currentHouseNumber = data.getHouseNumber(nearby);

                // If standing in a house that isn't theirs (or if they don't own a house), EVICT THEM!
                if (currentHouseNumber != null && !currentHouseNumber.equals(myHouseNumber)) {
                    brain.eraseMemory(MemoryModuleType.WALK_TARGET);
                    brain.eraseMemory(MemoryModuleType.PATH);

                    BlockPos exitPos = findOutdoorExit(level, currentPos);
                    villager.getNavigation().moveTo(exitPos.getX() + 0.5, exitPos.getY(), exitPos.getZ() + 0.5, 0.65);
                    return;
                }
            }
        }
    }

    private static void handleBabyVillager(ServerLevel level, Villager baby) {
        CompoundTag nbt = baby.getPersistentData();

        baby.getBrain().getMemory(MemoryModuleType.HOME).ifPresent(home -> {
            level.getPoiManager().release(home.pos());
            baby.getBrain().eraseMemory(MemoryModuleType.HOME);
        });

        Villager parent = null;
        if (nbt.hasUUID(NBT_PARENT_UUID)) {
            var entity = level.getEntity(nbt.getUUID(NBT_PARENT_UUID));
            if (entity instanceof Villager v && v.isAlive()) {
                parent = v;
            }
        }

        if (parent == null) {
            parent = level.getEntitiesOfClass(Villager.class, baby.getBoundingBox().inflate(32))
                    .stream()
                    .filter(v -> !v.isBaby())
                    .min(Comparator.comparingDouble(v -> v.distanceToSqr(baby)))
                    .orElse(null);

            if (parent != null) {
                nbt.putUUID(NBT_PARENT_UUID, parent.getUUID());
            }
        }

        if (parent != null && baby.distanceToSqr(parent) > 16.0) {
            if (!baby.getNavigation().isInProgress()) {
                baby.getNavigation().moveTo(parent, 0.55);
            }
        }
    }

    private static void lockHomeInVillage(ServerLevel level, PoiManager poiManager, Villager villager, HouseNumberData data) {
        Brain<Villager> brain = villager.getBrain();
        if (brain.hasMemoryValue(MemoryModuleType.HOME)) return;

        BlockPos villagerPos = villager.blockPosition();

        poiManager.getInRange(h -> h.is(PoiTypes.HOME), villagerPos, 48, PoiManager.Occupancy.HAS_SPACE)
                .min(Comparator.comparingDouble(p -> p.getPos().distSqr(villagerPos)))
                .ifPresent(poiRecord -> {
                    BlockPos bedPos = poiRecord.getPos();
                    BlockPos bedVillage = data.getVillageCenter(bedPos);

                    CompoundTag nbt = villager.getPersistentData();
                    if (nbt.contains(NBT_VILLAGE_X)) {
                        BlockPos myVillage = new BlockPos(nbt.getInt(NBT_VILLAGE_X), nbt.getInt(NBT_VILLAGE_Y), nbt.getInt(NBT_VILLAGE_Z));
                        if (bedVillage != null && !bedVillage.equals(myVillage)) {
                            return;
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

    private static void enforceVillageBoundary(ServerLevel level, Villager villager) {
        CompoundTag nbt = villager.getPersistentData();
        if (!nbt.contains(NBT_VILLAGE_X)) return;

        BlockPos villagePos = new BlockPos(nbt.getInt(NBT_VILLAGE_X), nbt.getInt(NBT_VILLAGE_Y), nbt.getInt(NBT_VILLAGE_Z));
        if (!villager.blockPosition().closerThan(villagePos, VILLAGE_RADIUS)) {
            if (!villager.getNavigation().isInProgress()) {
                villager.getNavigation().moveTo(villagePos.getX(), villagePos.getY(), villagePos.getZ(), 0.5);
            }
        }
    }

    private static BlockPos findOutdoorExit(ServerLevel level, BlockPos startPos) {
        for (int r = 1; r <= 8; r++) {
            for (BlockPos p : BlockPos.betweenClosed(startPos.offset(-r, -1, -r), startPos.offset(r, 2, r))) {
                if (level.canSeeSky(p) && level.isEmptyBlock(p) && level.isEmptyBlock(p.above())) {
                    return p.immutable();
                }
            }
        }
        return startPos.offset(3, 0, 3);
    }

    private static BlockPos calculateStructureCenter(List<BlockPos> beds) {
        int sumX = 0, sumY = 0, sumZ = 0;
        for (BlockPos b : beds) {
            sumX += b.getX();
            sumY += b.getY();
            sumZ += b.getZ();
        }
        return new BlockPos(sumX / beds.size(), sumY / beds.size(), sumZ / beds.size());
    }

    private static BlockPos findRoofTop(ServerLevel level, BlockPos center) {
        BlockPos.MutableBlockPos checkPos = new BlockPos.MutableBlockPos(center.getX(), center.getY(), center.getZ());
        while (checkPos.getY() < level.getMaxBuildHeight() - 1) {
            if (level.isEmptyBlock(checkPos) && level.isEmptyBlock(checkPos.above())) {
                if (!level.isEmptyBlock(checkPos.below())) {
                    return checkPos.immutable();
                }
            }
            checkPos.move(0, 1, 0);
        }
        return new BlockPos(center.getX(), center.getY() + 4, center.getZ());
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
