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

        // --- PREVENT STARING & INTERACTION LOCKS ---
        if (villager.getBrain().hasMemoryValue(MemoryModuleType.INTERACTION_TARGET)) {
            villager.getBrain().eraseMemory(MemoryModuleType.INTERACTION_TARGET);
        }
        if (villager.getBrain().hasMemoryValue(MemoryModuleType.LOOK_TARGET)) {
            villager.getBrain().eraseMemory(MemoryModuleType.LOOK_TARGET);
        }

        // --- 1. SCAN HOUSES & INSTANT ASSIGNMENT ON LOAD ---
        if (villager.tickCount % 40 == 0) {
            BlockPos villagerPos = villager.blockPosition();
            BlockPos.MutableBlockPos mutPos = new BlockPos.MutableBlockPos();

            for (int x = -12; x <= 12; x++) {
                for (int y = -4; y <= 4; y++) {
                    for (int z = -12; z <= 12; z++) {
                        mutPos.set(villagerPos.getX() + x, villagerPos.getY() + y, villagerPos.getZ() + z);
                        BlockState state = level.getBlockState(mutPos);

                        if (state.is(BlockTags.BEDS)) {
                            BlockPos bedPos = mutPos.immutable();
                            if (houseData.findExistingHouseAt(bedPos) == null) {
                                BlockPos doorPos = findDoorNear(level, bedPos);
                                houseData.registerHouse(level, bedPos, bedPos, doorPos != null ? doorPos : bedPos, 1);
                            }
                        }
                    }
                }
            }
            houseData.autoAssignLoadedVillagers(level);
        }

        // --- 2. DOOR LOGIC & HOUSE BOUNDARIES ---
        for (House house : houseData.getAllHouses()) {
            if (house.doorPos != null) {
                BlockState doorState = level.getBlockState(house.doorPos);
                if (doorState.getBlock() instanceof DoorBlock) {
                    BlockPos lowerDoorPos = doorState.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER 
                        ? house.doorPos.below() 
                        : house.doorPos;

                    double distSqrToDoor = villager.distanceToSqr(lowerDoorPos.getX() + 0.5, lowerDoorPos.getY(), lowerDoorPos.getZ() + 0.5);

                    if (distSqrToDoor <= 4.0) {
                        BlockPos houseCenter = house.bedPos != null ? house.bedPos : house.homePos;
                        double villagerDistToCenter = villager.blockPosition().distSqr(houseCenter);
                        double doorDistToCenter = lowerDoorPos.distSqr(houseCenter);

                        boolean isInside = villagerDistToCenter < doorDistToCenter;
                        boolean isOwnerOrBaby = house.isOwner(villagerId) || isBabyOfOwner(villager, house);

                        if (isInside) {
                            if (level.isNight()) {
                                // AT NIGHT: Keep door closed so residents inside stay inside
                                closeDoorIfOpen(level, lowerDoorPos);

                                if (!isOwnerOrBaby) {
                                    // Evict non-owners inside at night
                                    BlockPos outsidePos = lowerDoorPos.offset(
                                        lowerDoorPos.getX() - houseCenter.getX(),
                                        0,
                                        lowerDoorPos.getZ() - houseCenter.getZ()
                                    );
                                    villager.getNavigation().moveTo(outsidePos.getX(), outsidePos.getY(), outsidePos.getZ(), 0.5D);
                                }
                            } else {
                                // DAYTIME: Owners and babies can open doors to head out
                                if (isOwnerOrBaby) {
                                    openDoorIfClosed(level, lowerDoorPos);
                                }
                            }
                        } else {
                            // OUTSIDE LOGIC
                            if (isOwnerOrBaby) {
                                // Owners and babies outside can open doors to enter
                                openDoorIfClosed(level, lowerDoorPos);
                            } else {
                                // Non-owners outside: CANNOT open door & stop targeting
                                if (villager.getNavigation().getPath() != null) {
                                    BlockPos targetPos = villager.getNavigation().getPath().getTarget();
                                    if (targetPos.distSqr(houseCenter) < 16.0) {
                                        villager.getNavigation().stop();
                                        villager.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // --- 3. BABY VILLAGER MANAGEMENT ---
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

            House parentHouse = null;
            Villager parent = null;

            if (BABY_PARENT_MAP.containsKey(villagerId)) {
                UUID parentId = BABY_PARENT_MAP.get(villagerId);
                parent = (Villager) level.getEntity(parentId);
                if (parent != null && parent.isAlive()) {
                    parentHouse = houseData.getHouseForVillager(parent.getUUID());
                }
            }

            if (parentHouse != null) {
                villager.setCustomName(Component.literal("Baby (House #" + parentHouse.houseNumber + ")"));
                villager.setCustomNameVisible(true);

                BlockPos targetPos = parentHouse.bedPos != null ? parentHouse.bedPos : parentHouse.homePos;

                if (level.isNight()) {
                    // AT NIGHT: Baby goes into parent's house and stays inside
                    double distSqrToHouse = villager.blockPosition().distSqr(targetPos);
                    if (distSqrToHouse > 3.0) {
                        if (villager.tickCount % 20 == 0) {
                            villager.getBrain().setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(targetPos, 0.5F, 1));
                            villager.getNavigation().moveTo(targetPos.getX(), targetPos.getY(), targetPos.getZ(), 0.5D);
                        }
                    } else {
                        // Already inside house, stop walking around
                        villager.getNavigation().stop();
                        villager.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
                    }
                } else {
                    // DAYTIME: Follow parent with a comfortable buffer distance so they don't face-lock
                    if (parent != null) {
                        double distSqrToParent = villager.distanceToSqr(parent);
                        if (distSqrToParent > 16.0 && villager.tickCount % 40 == 0) {
                            villager.getNavigation().moveTo(parent, 0.5D);
                        } else if (distSqrToParent <= 9.0) {
                            // Close enough, stop pushing directly into parent's face
                            villager.getNavigation().stop();
                            villager.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
                        }
                    }
                }
            } else if (level.isNight()) {
                // Baby without parent house stays put at night
                villager.getNavigation().stop();
                villager.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
            }

            return;
        }

        // --- 4. ADULT VILLAGER NAMING & NIGHT SLEEPING ---
        House assignedHouse = houseData.getHouseForVillager(villagerId);

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
                    // House without bed: Move inside and stay inside at night
                    if (distSqr > 3.0) {
                        if (villager.tickCount % 40 == 0) {
                            villager.getBrain().setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(targetPos, 0.5F, 1));
                            villager.getNavigation().moveTo(targetPos.getX(), targetPos.getY(), targetPos.getZ(), 0.5D);
                        }
                    } else {
                        villager.getNavigation().stop();
                        villager.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
                    }
                }
            }
        } else {
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

    private static void openDoorIfClosed(ServerLevel level, BlockPos doorPos) {
        BlockState state = level.getBlockState(doorPos);
        if (state.getBlock() instanceof DoorBlock) {
            if (!state.getValue(DoorBlock.OPEN)) {
                level.setBlock(doorPos, state.setValue(DoorBlock.OPEN, true), 3);
            }
        }
    }

    private static void closeDoorIfOpen(ServerLevel level, BlockPos doorPos) {
        BlockState state = level.getBlockState(doorPos);
        if (state.getBlock() instanceof DoorBlock) {
            if (state.getValue(DoorBlock.OPEN)) {
                level.setBlock(doorPos, state.setValue(DoorBlock.OPEN, false), 3);
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
