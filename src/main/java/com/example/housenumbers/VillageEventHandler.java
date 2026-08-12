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

import java.util.ArrayList;
import java.util.List;

@EventBusSubscriber(modid = HouseNumbersMod.MODID)
public class VillageEventHandler {

    @SubscribeEvent
    public static void onEntityTick(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof Villager villager) || villager.level().isClientSide()) {
            return;
        }

        ServerLevel level = (ServerLevel) villager.level();
        HouseNumberData houseData = HouseNumberData.get(level);

        // --- 1. BABY VILLAGER LOGIC ---
        if (villager.isBaby()) {
            // Only update baby pathing once every 30 ticks to prevent jittering/teleporting
            if (villager.tickCount % 30 == 0) {
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

                    // Smooth walking follow without teleporting
                    if (villager.distanceToSqr(parent) > 16.0 && villager.getNavigation().isDone()) {
                        villager.getNavigation().moveTo(parent, 1.0D);
                    }
                }
            }
            return;
        }

        // --- 2. INDIVIDUAL HOUSE SCANNING & ASSIGNMENT ---
        HouseNumberData.House assignedHouse = houseData.getHouseForVillager(villager.getUUID());

        if (assignedHouse == null) {
            BlockPos villagerPos = villager.blockPosition();

            // Unique village identifier based on region coordinates (resets to #1 in distant villages)
            String villageId = "village_" + (villagerPos.getX() >> 8) + "_" + (villagerPos.getZ() >> 8);

            // Scan 12 blocks around villager for beds to define a unique house structure
            List<BlockPos> bedsInHouse = new ArrayList<>();
            BlockPos.MutableBlockPos mutPos = new BlockPos.MutableBlockPos();

            for (int x = -12; x <= 12; x++) {
                for (int y = -4; y <= 4; y++) {
                    for (int z = -12; z <= 12; z++) {
                        mutPos.set(villagerPos.getX() + x, villagerPos.getY() + y, villagerPos.getZ() + z);
                        if (level.getBlockState(mutPos).is(BlockTags.BEDS)) {
                            bedsInHouse.add(mutPos.immutable());
                        }
                    }
                }
            }

            if (!bedsInHouse.isEmpty()) {
                // Calculate actual house center from the bed positions inside the building
                long sumX = 0, sumY = 0, sumZ = 0;
                for (BlockPos b : bedsInHouse) {
                    sumX += b.getX();
                    sumY += b.getY();
                    sumZ += b.getZ();
                }
                BlockPos houseCenter = new BlockPos((int)(sumX / bedsInHouse.size()), (int)(sumY / bedsInHouse.size()), (int)(sumZ / bedsInHouse.size()));

                HouseNumberData.House house = houseData.findOrRegisterHouse(level, villageId, houseCenter, bedsInHouse.size());
                if (houseData.assignVillagerToHouse(villager.getUUID(), house)) {
                    assignedHouse = house;
                }
            }
        }

        // --- 3. NIGHTTIME MOVEMENT & DISPLAY ---
        if (assignedHouse != null) {
            villager.setCustomName(Component.literal("House #" + assignedHouse.houseNumber));
            villager.setCustomNameVisible(true);

            // Override Vanilla memory so beds aren't needed to maintain home persistence
            villager.getBrain().setMemory(
                MemoryModuleType.HOME,
                GlobalPos.of(level.dimension(), assignedHouse.centerPos)
            );

            // Only issue navigation orders every 40 ticks (2 seconds) to keep walking smooth
            if (level.isNight() && villager.tickCount % 40 == 0) {
                double distSqr = villager.blockPosition().distSqr(assignedHouse.centerPos);

                if (distSqr > 9.0 && villager.getNavigation().isDone()) {
                    villager.getBrain().setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(assignedHouse.centerPos, 1.0F, 1));
                    villager.getNavigation().moveTo(
                        assignedHouse.centerPos.getX(),
                        assignedHouse.centerPos.getY(),
                        assignedHouse.centerPos.getZ(),
                        1.0D
                    );
                }
            }
        }
    }
}
