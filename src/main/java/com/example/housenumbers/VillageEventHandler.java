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

    // Permanent baby -> parent tracking map
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

            // Bind permanently to a single parent if not already bound
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

            // Follow bound parent naturally
            if (BABY_PARENT_MAP.containsKey(babyId)) {
                UUID parentId = BABY_PARENT_MAP.get(babyId);
                Villager parent = (Villager) level.getEntity(parentId);

                if (parent != null && parent.isAlive()) {
                    HouseNumberData.House parentHouse = houseData.getHouseForVillager(parent.getUUID());

                    if (parentHouse != null) {
                        villager.setCustomName(Component.literal("Baby (House #" + parentHouse.houseNumber + ")"));
                        villager.setCustomNameVisible(true);
                    }

                    // Only adjust navigation if baby gets too far, otherwise let vanilla baby AI play
                    if (villager.tickCount % 40 == 0 && villager.distanceToSqr(parent) > 25.0) {
                        villager.getNavigation().moveTo(parent, 1.0D);
                    }
                }
            }
            return;
        }

        // --- 2. PERMANENT HOUSE ASSIGNMENT & INDIVIDUAL BED SEARCH ---
        HouseNumberData.House assignedHouse = houseData.getHouseForVillager(villager.getUUID());

        if (assignedHouse == null) {
            BlockPos villagerPos = villager.blockPosition();

            // Strict region ID based on chunk coordinates
            String villageId = "village_" + (villagerPos.getX() >> 7) + "_" + (villagerPos.getZ() >> 7);

            // Find the single closest bed block within 8 blocks
            BlockPos foundBedPos = null;
            BlockPos.MutableBlockPos mutPos = new BlockPos.MutableBlockPos();

            for (int x = -8; x <= 8; x++) {
                for (int y = -3; y <= 3; y++) {
                    for (int z = -8; z <= 8; z++) {
                        mutPos.set(villagerPos.getX() + x, villagerPos.getY() + y, villagerPos.getZ() + z);
                        if (level.getBlockState(mutPos).is(BlockTags.BEDS)) {
                            foundBedPos = mutPos.immutable();
                            break;
                        }
                    }
                    if (foundBedPos != null) break;
                }
                if (foundBedPos != null) break;
            }

            if (foundBedPos != null) {
                // Register house specifically centered on THIS bed, capacity defaults to 2 beds max per house
                HouseNumberData.House house = houseData.findOrRegisterHouse(level, villageId, foundBedPos, 2);
                if (houseData.assignVillagerToHouse(villager.getUUID(), house)) {
                    assignedHouse = house;
                }
            }
        }

        // --- 3. NIGHTTIME SLEEPING & HOUSE PERSISTENCE ---
        if (assignedHouse != null) {
            villager.setCustomName(Component.literal("House #" + assignedHouse.houseNumber));
            villager.setCustomNameVisible(true);

            // Override home memory so villager never unbinds from house
            villager.getBrain().setMemory(
                MemoryModuleType.HOME,
                GlobalPos.of(level.dimension(), assignedHouse.bedPos)
            );

            if (level.isNight()) {
                double distToBedSqr = villager.blockPosition().distSqr(assignedHouse.bedPos);

                // If villager is close to the bed block, sleep in the bed!
                if (distToBedSqr <= 3.0 && !villager.isSleeping()) {
                    villager.startSleeping(assignedHouse.bedPos);
                } else if (!villager.isSleeping() && villager.tickCount % 40 == 0) {
                    // Navigate directly to the bed inside the house
                    villager.getBrain().setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(assignedHouse.bedPos, 1.0F, 1));
                    villager.getNavigation().moveTo(
                        assignedHouse.bedPos.getX(),
                        assignedHouse.bedPos.getY(),
                        assignedHouse.bedPos.getZ(),
                        1.0D
                    );
                }
            }
        }
    }
}
