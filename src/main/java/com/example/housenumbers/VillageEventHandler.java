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
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
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

        // --- 1. SCAN AND REGISTER NEARBY HOUSES (Ensures all village houses get tags) ---
        if (villager.tickCount % 60 == 0) {
            BlockPos villagerPos = villager.blockPosition();
            BlockPos.MutableBlockPos mutPos = new BlockPos.MutableBlockPos();

            for (int x = -10; x <= 10; x++) {
                for (int y = -3; y <= 3; y++) {
                    for (int z = -10; z <= 10; z++) {
                        mutPos.set(villagerPos.getX() + x, villagerPos.getY() + y, villagerPos.getZ() + z);
                        BlockState state = level.getBlockState(mutPos);

                        if (state.is(BlockTags.BEDS)) {
                            BlockPos bedPos = mutPos.immutable();
                            if (houseData.findExistingHouseAt(bedPos) == null) {
                                // Find associated door nearby
                                BlockPos doorPos = findDoorNear(level, bedPos);
                                houseData.registerHouse(level, bedPos, bedPos, doorPos != null ? doorPos : bedPos, 1);
                            }
                        }
                    }
                }
            }
        }

        // --- 2. DOOR SECURITY & ESCAPE LOGIC ---
        for (House house : houseData.getAllHouses()) {
            if (house.doorPos != null) {
                BlockState doorState = level.getBlockState(house.doorPos);
                if (doorState.getBlock() instanceof DoorBlock) {
                    // Normalize to lower half of door
                    BlockPos lowerDoorPos = doorState.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER 
                        ? house.doorPos.below() 
                        : house.doorPos;

                    double distSqrToDoor = villager.distanceToSqr(lowerDoorPos.getX() + 0.5, lowerDoorPos.getY(), lowerDoorPos.getZ() + 0.5);

                    if (distSqrToDoor <= 4.0) {
                        BlockPos houseCenter = house.bedPos != null ? house.bedPos : house.homePos;
                        double villagerDistToCenter = villager.blockPosition().distSqr(houseCenter);
                        double doorDistToCenter = lowerDoorPos.distSqr(houseCenter);

                        // Determine if villager is INSIDE or OUTSIDE
                        boolean isInside = villagerDistToCenter < doorDistToCenter;

                        if (isInside) {
                            // Anyone inside CAN open the door to exit calmly
                            setDoorOpen(level, lowerDoorPos, true);
                            if (!house.isOwner(villagerId) && level.isNight()) {
                                BlockPos outsidePos = lowerDoorPos.offset(
                                    lowerDoorPos.getX() - houseCenter.getX(),
                                    0,
                                    lowerDoorPos.getZ() - houseCenter.getZ()
                                );
                                villager.getNavigation().moveTo(outsidePos.getX(), outsidePos.getY(), outsidePos.getZ(), 0.5D);
                            }
                        } else {
                            // Outside: Only the owner can open the door
                            if (house.isOwner(villagerId) || isBabyOfOwner(villager, house)) {
                                setDoorOpen(level, lowerDoorPos, true);
                            } else {
                                setDoorOpen(level, lowerDoorPos, false);
                            }
                        }
                    }
                }
            }
        }

        // --- 3. BABY VILLAGER LOGIC ---
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
                        villager.getNavigation().moveTo(parent, 0.5D);
                    }
                }
            }
            return;
        }

        // --- 4. HOUSE CLAIMING & ASSIGNMENT ---
        House assignedHouse = houseData.getHouseForVillager(villagerId);

        if (assignedHouse == null) {
            // Find an existing unowned house
            for (House house : houseData.getAllHouses()) {
                if (!house.isFull() && villager.blockPosition().closerThan(house.homePos, 24.0)) {
                    houseData.assignVillagerToHouse(villagerId, house);
                    assignedHouse = house;
                    break;
                }
            }
        }

        // --- 5. BEHAVIOR & NIGHT MOVEMENT ---
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
                        villager.getBrain().setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(targetPos, 0.5F, 1));
                        villager.getNavigation().moveTo(targetPos.getX(), targetPos.getY(), targetPos.getZ(), 0.5D);
                    }
                } else {
                    if (distSqr > 4.0 && villager.tickCount % 40 == 0) {
                        villager.getBrain().setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(targetPos, 0.5F, 1));
                        villager.getNavigation().moveTo(targetPos.getX(), targetPos.getY(), targetPos.getZ(), 0.5D);
                    }
                }
            }
        } else {
            // Homeless villagers are cleared from taking non-owned home memories
            if (villager.getBrain().hasMemoryValue(MemoryModuleType.HOME)) {
                villager.getBrain().eraseMemory(MemoryModuleType.HOME);
            }
            if (villager.isSleeping()) {
                villager.stopSleeping();
            }
        }
    }

    private static BlockPos findDoorNear(ServerLevel level, BlockPos pos) {
        BlockPos.MutableBlockPos mutPos = new BlockPos.MutableBlockPos();
        for (int x = -5; x <= 5; x++) {
            for (int y = -2; y <= 2; y++) {
                for (int z = -5; z <= 5; z++) {
                    mutPos.set(pos.getX() + x, pos.getY() + y, pos.getZ() + z);
                    if (level.getBlockState(mutPos).is(BlockTags.DOORS)) {
                        return mutPos.immutable();
                    }
                }
            }
        }
        return null;
    }

    private static void setDoorOpen(ServerLevel level, BlockPos doorPos, boolean open) {
        BlockState state = level.getBlockState(doorPos);
        if (state.getBlock() instanceof DoorBlock) {
            if (state.getValue(DoorBlock.OPEN) != open) {
                level.setBlock(doorPos, state.setValue(DoorBlock.OPEN, open), 3);
            }
        }
    }

    private static boolean isBabyOfOwner(Villager villager, House house) {
        if (!villager.isBaby()) return false;
        UUID babyId = villager.getUUID();
        if (BABY_PARENT_MAP.containsKey(babyId)) {
            UUID parentId = BABY_PARENT_MAP.get(babyId);
            return house.isOwner(parentId);
        }
        return false;
    }
}
