package com.example.housenumbers;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.npc.Villager;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@EventBusSubscriber(modid = HouseNumbersMod.MODID)
public class VillageEventHandler {

    private static final Map<UUID, UUID> BABY_PARENT_MAP = new HashMap<>();

    @SubscribeEvent
    public static void onEntityTick(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof Villager villager) || villager.level().isClientSide()) {
            return;
        }

        ServerLevel level = (ServerLevel) villager.level();
        HouseNumberData houseData = HouseNumberData.get(level);

        // --- 1. BABY VILLAGER LOGIC ---
        if (villager.isBaby()) {
            UUID babyId = villager.getUUID();

            if (!BABY_PARENT_MAP.containsKey(babyId)) {
                List<Villager> nearby = level.getEntitiesOfClass(
                    Villager.class,
                    villager.getBoundingBox().inflate(12.0)
                );

                for (Villager v : nearby) {
                    if (!v.isBaby()) {
                        BABY_PARENT_MAP.put(babyId, v.getUUID());
                        break;
                    }
                }
            }

            if (BABY_PARENT_MAP.containsKey(babyId)) {
                UUID parentId = BABY_PARENT_MAP.get(babyId);
                Villager parent = (Villager) level.getEntity(parentId);

                if (parent != null && parent.isAlive()) {
                    HouseNumberData.House parentHouse = houseData.getHouseForVillager(parent.getUUID());

                    if (parentHouse != null) {
                        villager.setCustomName(Component.literal("Baby (House #" + parentHouse.houseNumber + ")"));
                        villager.setCustomNameVisible(true);
                    }

                    if (villager.tickCount % 40 == 0 && villager.distanceToSqr(parent) > 25.0) {
                        villager.getNavigation().moveTo(parent, 0.6D); // Relaxed walking speed
                    }
                }
            }
            return;
        }

        // --- 2. HOUSE DETECTION & ASSIGNMENT ---
        HouseNumberData.House assignedHouse = houseData.getHouseForVillager(villager.getUUID());

        if (assignedHouse == null) {
            BlockPos villagerPos = villager.blockPosition();
            String villageId = "village_" + (villagerPos.getX() >> 7) + "_" + (villagerPos.getZ() >> 7);

            BlockPos foundBedPos = null;
            BlockPos foundDoorPos = null;
            BlockPos.MutableBlockPos mutPos = new BlockPos.MutableBlockPos();

            // Scan nearby area for beds or doors
            for (int x = -8; x <= 8; x++) {
                for (int y = -3; y <= 3; y++) {
                    for (int z = -8; z <= 8; z++) {
                        mutPos.set(villagerPos.getX() + x, villagerPos.getY() + y, villagerPos.getZ() + z);
                        if (foundBedPos == null && level.getBlockState(mutPos).is(BlockTags.BEDS)) {
                            foundBedPos = mutPos.immutable();
                        }
                        if (foundDoorPos == null && level.getBlockState(mutPos).is(BlockTags.DOORS)) {
                            foundDoorPos = mutPos.immutable();
                        }
                    }
                }
            }

            // Option A: House with Bed
            if (foundBedPos != null) {
                HouseNumberData.House existing = houseData.findExistingHouseAt(villageId, foundBedPos);
                if (existing != null && !existing.isFull()) {
                    houseData.assignVillagerToHouse(villager.getUUID(), existing);
                    assignedHouse = existing;
                } else if (existing == null) {
                    House newHouse = houseData.registerNewHouse(level, villageId, foundBedPos, foundBedPos, 1);
                    houseData.assignVillagerToHouse(villager.getUUID(), newHouse);
                    assignedHouse = newHouse;
                }
            }
            // Option B: House without Bed (Door detected)
            else if (foundDoorPos != null) {
                HouseNumberData.House existing = houseData.findExistingHouseAt(villageId, foundDoorPos);
                if (existing != null && !existing.isFull()) {
                    houseData.assignVillagerToHouse(villager.getUUID(), existing);
                    assignedHouse = existing;
                } else if (existing == null) {
                    House newHouse = houseData.registerNewHouse(level, villageId, foundDoorPos, null, 1);
                    houseData.assignVillagerToHouse(villager.getUUID(), newHouse);
                    assignedHouse = newHouse;
                }
            }
        }

        // --- 3. NIGHTTIME MOVEMENT & SLEEPING / STAYING IN HOUSE ---
        if (assignedHouse != null) {
            villager.setCustomName(Component.literal("House #" + assignedHouse.houseNumber));
            villager.setCustomNameVisible(true);

            // Bind home memory exclusively to assigned house position
            BlockPos targetPos = (assignedHouse.bedPos != null) ? assignedHouse.bedPos : assignedHouse.homePos;
            villager.getBrain().setMemory(
                MemoryModuleType.HOME,
                GlobalPos.of(level.dimension(), targetPos)
            );

            if (level.isNight()) {
                double distSqr = villager.blockPosition().distSqr(targetPos);

                // House WITH bed -> sleep in bed when close
                if (assignedHouse.bedPos != null && level.getBlockState(assignedHouse.bedPos).is(BlockTags.BEDS)) {
                    if (distSqr <= 3.0 && !villager.isSleeping()) {
                        villager.startSleeping(assignedHouse.bedPos);
                    } else if (!villager.isSleeping() && villager.tickCount % 40 == 0) {
                        villager.getBrain().setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(targetPos, 0.6F, 1));
                        villager.getNavigation().moveTo(targetPos.getX(), targetPos.getY(), targetPos.getZ(), 0.6D);
                    }
                } 
                // House WITHOUT bed -> stay inside house, don't sleep
                else {
                    if (distSqr > 4.0 && villager.tickCount % 40 == 0) {
                        villager.getBrain().setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(targetPos, 0.6F, 1));
                        villager.getNavigation().moveTo(targetPos.getX(), targetPos.getY(), targetPos.getZ(), 0.6D);
                    }
                }
            }
        } else {
            // Homeless villagers are prevented from taking homes/beds belonging to others
            if (villager.getBrain().hasMemoryValue(MemoryModuleType.HOME)) {
                villager.getBrain().eraseMemory(MemoryModuleType.HOME);
            }
        }
    }
}
