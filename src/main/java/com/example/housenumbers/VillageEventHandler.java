package com.example.housenumbers;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.StructureTags;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingEvent;

import java.util.List;

@EventBusSubscriber(modid = HouseNumbersMod.MODID)
public class VillageEventHandler {

    @SubscribeEvent
    public static void onVillagerTick(LivingEvent.LivingTickEvent event) {
        if (!(event.getEntity() instanceof Villager villager) || villager.level().isClientSide()) {
            return;
        }

        ServerLevel level = (ServerLevel) villager.level();
        HouseNumberData houseData = HouseNumberData.get(level);

        // --- 1. BABY VILLAGER LOGIC ---
        if (villager.isBaby()) {
            // Babies do not claim homes. They stay close to adult villagers and inherit house tags.
            List<Villager> adults = level.getEntitiesOfClass(
                Villager.class,
                villager.getBoundingBox().inflate(12.0),
                v -> !v.isBaby()
            );

            if (!adults.isEmpty()) {
                Villager parent = adults.get(0);
                HouseNumberData.House parentHouse = houseData.getHouseForVillager(parent.getUUID());
                
                if (parentHouse != null) {
                    villager.setCustomName(Component.literal("Baby (House #" + parentHouse.houseNumber + ")"));
                    villager.setCustomNameVisible(true);
                }

                // Smooth pathfinding: keep baby following parent closely
                if (villager.distanceToSqr(parent) > 9.0) {
                    villager.getNavigation().moveTo(parent, 1.15D);
                }
            }
            return;
        }

        // --- 2. ADULT HOUSE ASSIGNMENT & STRUCTURE SEARCH ---
        HouseNumberData.House assignedHouse = houseData.getHouseForVillager(villager.getUUID());

        if (assignedHouse == null) {
            BlockPos villagerPos = villager.blockPosition();
            StructureStart structure = level.structureManager().getStructureWithPieceAt(villagerPos, StructureTags.VILLAGE);

            if (structure.isValid()) {
                // Regional village ID so each distinct village starts at House #1
                BlockPos center = structure.getBoundingBox().getCenter();
                String villageId = "village_" + (center.getX() >> 8) + "_" + (center.getZ() >> 8);

                // Count beds in the structure bounding box to set maximum capacity
                int capacity = 0;
                BlockPos.MutableBlockPos mutPos = new BlockPos.MutableBlockPos();
                for (int x = structure.getBoundingBox().minX(); x <= structure.getBoundingBox().maxX(); x++) {
                    for (int y = structure.getBoundingBox().minY(); y <= structure.getBoundingBox().maxY(); y++) {
                        for (int z = structure.getBoundingBox().minZ(); z <= structure.getBoundingBox().maxZ(); z++) {
                            mutPos.set(x, y, z);
                            if (level.getBlockState(mutPos).is(BlockTags.BEDS)) {
                                capacity++;
                            }
                        }
                    }
                }

                HouseNumberData.House house = houseData.findOrRegisterHouse(villageId, center, capacity);
                if (houseData.assignVillagerToHouse(villager.getUUID(), house)) {
                    assignedHouse = house;
                }
            }
        }

        // --- 3. NIGHTTIME BEHAVIOR & DIRECT NAVIGATION ---
        if (assignedHouse != null) {
            // Apply name tag
            villager.setCustomName(Component.literal("House #" + assignedHouse.houseNumber));
            villager.setCustomNameVisible(true);

            // Bind home memory directly to structure location
            villager.getBrain().setMemory(
                MemoryModuleType.HOME,
                GlobalPos.of(level.dimension(), assignedHouse.centerPos)
            );

            // Daytime: Free roaming.
            // Nighttime: Strictly route to assigned house center and ignore other structures.
            if (level.isNight()) {
                double distSqr = villager.blockPosition().distSqr(assignedHouse.centerPos);

                if (distSqr > 4.0) {
                    villager.getBrain().setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(assignedHouse.centerPos, 1.25F, 1));
                    
                    // Periodic navigation force refresh for better pathfinding around village obstacles
                    if (villager.getNavigation().isDone() || villager.tickCount % 40 == 0) {
                        villager.getNavigation().moveTo(
                            assignedHouse.centerPos.getX(),
                            assignedHouse.centerPos.getY(),
                            assignedHouse.centerPos.getZ(),
                            1.25D
                        );
                    }
                }
            }
        }
    }
}
