package com.example.housenumbers;

import com.example.housenumbers.HouseNumberData.House;
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
        UUID villagerId = villager.getUUID();

        // --- 1. TRESPASS PREVENTION (Keep villagers out of houses they don't own) ---
        for (House house : houseData.getAllHouses()) {
            if (!house.assignedVillagers.contains(villagerId)) {
                BlockPos centerPos = house.bedPos != null ? house.bedPos : house.homePos;
                if (villager.blockPosition().closerThan(centerPos, 4.5)) {
                    if (villager.isSleeping()) {
                        villager.stopSleeping();
                    }
                    villager.getBrain().eraseMemory(MemoryModuleType.HOME);
                    villager.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);

                    // Push non-owner villager away from the house interior
                    int offsetX = villager.getX() >= centerPos.getX() ? 6 : -6;
                    int offsetZ = villager.getZ() >= centerPos.getZ() ? 6 : -6;
                    BlockPos expelPos = centerPos.offset(offsetX, 0, offsetZ);

                    villager.getNavigation().moveTo(expelPos.getX(), expelPos.getY(), expelPos.getZ(), 0.75D);
                }
            }
        }

        // --- 2. BABY VILLAGER LOGIC ---
        if (villager.isBaby()) {
            if (!BABY_PARENT_MAP.containsKey(villagerId)) {
                List<Villager> nearby = level.getEntitiesOfClass(
                    Villager.class,
                    villager.getBoundingBox().inflate(12.0)
                );

                for (Villager v : nearby) {
                    if (!v.isBaby()) {
                        BABY_PARENT_MAP.put(villagerId, v.getUUID());
                        break;
                    }
                }
            }

            if (BABY_PARENT_MAP.containsKey(villagerId)) {
                UUID parentId = BABY_PARENT_MAP.get(villagerId);
                Villager parent = (Villager) level.getEntity(parentId);

                if (parent != null && parent.isAlive()) {
                    House parentHouse = houseData.getHouseForVillager(parent.getUUID());

                    if (parentHouse != null) {
                        villager.setCustomName(Component.literal("Baby (House #" + parentHouse.houseNumber + ")"));
                        villager.setCustomNameVisible(true);
                    }

                    if (villager.tickCount % 40 == 0 && villager.distanceToSqr(parent) > 25.0) {
                        villager.getNavigation().moveTo(parent, 0.6D);
                    }
                }
            }
            return;
        }

        // --- 3. HOUSE DETECTION & ASSIGNMENT ---
        House assignedHouse = houseData.getHouseForVillager(villagerId);

        if (assignedHouse == null) {
            BlockPos villagerPos = villager.blockPosition();
            String villageId = "village_" + (villagerPos.getX() >> 7) + "_" + (villagerPos.getZ() >> 7);

            BlockPos foundBedPos = null;
            BlockPos foundDoorPos = null;
            BlockPos.MutableBlockPos mutPos = new BlockPos.MutableBlockPos();

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
                House existing = houseData.findExistingHouseAt(villageId, foundBedPos);
                if (existing != null && !existing.isFull()) {
                    houseData.assignVillagerToHouse(villagerId, existing);
                    assignedHouse = existing;
                } else if (existing == null) {
                    // Register house AND assign this natural villager in one atomic step
                    assignedHouse = houseData.registerAndAssignHouse(level, villageId, villagerId, foundBedPos, foundBedPos, 1);
                }
            }
            // Option B: House without Bed (Door detected)
            else if (foundDoorPos != null) {
                House existing = houseData.findExistingHouseAt(villageId, foundDoorPos);
                if (existing != null && !existing.isFull()) {
                    houseData.assignVillagerToHouse(villagerId, existing);
                    assignedHouse = existing;
                } else if (existing == null) {
                    assignedHouse = houseData.registerAndAssignHouse(level, villageId, villagerId, foundDoorPos, null, 1);
                }
            }
        }

        // --- 4. NIGHTTIME MOVEMENT & SLEEPING LOGIC ---
        if (assignedHouse != null) {
            villager.setCustomName(Component.literal("House #" + assignedHouse.houseNumber));
            villager.setCustomNameVisible(true);

            BlockPos targetPos = (assignedHouse.bedPos != null) ? assignedHouse.bedPos : assignedHouse.homePos;
            villager.getBrain().setMemory(
                MemoryModuleType.HOME,
                GlobalPos.of(level.dimension(), targetPos)
            );

            if (level.isNight()) {
                double distSqr = villager.blockPosition().distSqr(targetPos);

                if (assignedHouse.bedPos != null && level.getBlockState(assignedHouse.bedPos).is(BlockTags.BEDS)) {
                    if (distSqr <= 3.0 && !villager.isSleeping()) {
                        villager.startSleeping(assignedHouse.bedPos);
                    } else if (!villager.isSleeping() && villager.tickCount % 40 == 0) {
                        villager.getBrain().setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(targetPos, 0.6F, 1));
                        villager.getNavigation().moveTo(targetPos.getX(), targetPos.getY(), targetPos.getZ(), 0.6D);
                    }
                } else {
                    if (distSqr > 4.0 && villager.tickCount % 40 == 0) {
                        villager.getBrain().setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(targetPos, 0.6F, 1));
                        villager.getNavigation().moveTo(targetPos.getX(), targetPos.getY(), targetPos.getZ(), 0.6D);
                    }
                }
            }
        } else {
            // Homeless villagers are explicitly cleared from taking homes/beds
            if (villager.getBrain().hasMemoryValue(MemoryModuleType.HOME)) {
                villager.getBrain().eraseMemory(MemoryModuleType.HOME);
            }
            if (villager.isSleeping()) {
                villager.stopSleeping();
            }
        }
    }
}
