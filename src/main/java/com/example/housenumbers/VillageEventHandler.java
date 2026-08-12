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
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
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

        // --- PREVENT SLIDING / WALKING WHILE SLEEPING ---
        if (villager.isSleeping()) {
            villager.getNavigation().stop();
            villager.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
            return;
        }

        ServerLevel level = (ServerLevel) villager.level();
        HouseNumberData houseData = HouseNumberData.get(level);
        UUID villagerId = villager.getUUID();

        // --- EXPANDED HOUSE & BED SCANNING ---
        if (houseData.getHouseForVillager(villagerId) == null || villager.tickCount % 20 == 0) {
            BlockPos villagerPos = villager.blockPosition();
            BlockPos.MutableBlockPos mutPos = new BlockPos.MutableBlockPos();

            for (int x = -32; x <= 32; x += 3) {
                for (int y = -10; y <= 10; y += 2) {
                    for (int z = -32; z <= 32; z += 3) {
                        mutPos.set(villagerPos.getX() + x, villagerPos.getY() + y, villagerPos.getZ() + z);
                        BlockState state = level.getBlockState(mutPos);

                        if (state.is(BlockTags.BEDS)) {
                            boolean isHead = true;
                            if (state.hasProperty(BedBlock.PART)) {
                                isHead = state.getValue(BedBlock.PART) == BedPart.HEAD;
                            }
                            if (isHead) {
                                BlockPos bedPos = mutPos.immutable();
                                int capacity = countBedsInStructure(level, bedPos);
                                BlockPos doorPos = findDoorNear(level, bedPos);
                                houseData.registerHouse(level, bedPos, bedPos, doorPos != null ? doorPos : bedPos, capacity);
                            }
                        }
                    }
                }
            }
            houseData.autoAssignLoadedVillagers(level);
        }

        Integer villagerVillageId = houseData.getVillageForVillager(villagerId);

        // --- STRICT VILLAGE BOUNDARY: CANCEL NAVIGATION TO OTHER VILLAGES ---
        if (villager.getNavigation().getPath() != null) {
            BlockPos targetPos = villager.getNavigation().getPath().getTarget();
            House targetHouse = houseData.findExistingHouseAt(targetPos);
            
            if (targetHouse != null) {
                // Do not allow walking towards a house in another village cluster
                if (villagerVillageId != null && targetHouse.villageId != villagerVillageId) {
                    villager.getNavigation().stop();
                    villager.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
                } else {
                    boolean isOwnerOrBaby = targetHouse.isOwner(villagerId) || isBabyOfOwner(villager, targetHouse);
                    if (!isOwnerOrBaby) {
                        villager.getNavigation().stop();
                        villager.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
                    }
                }
            }
        }

        // --- DOOR & NIGHT BOUNDARY LOGIC ---
        for (House house : houseData.getAllHouses()) {
            if (house.doorPos != null) {
                BlockState doorState = level.getBlockState(house.doorPos);
                if (doorState.getBlock() instanceof DoorBlock) {
                    BlockPos lowerDoorPos = doorState.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER 
                        ? house.doorPos.below() 
                        : house.doorPos;

                    double distSqrToDoor = villager.distanceToSqr(lowerDoorPos.getX() + 0.5, lowerDoorPos.getY(), lowerDoorPos.getZ() + 0.5);

                    if (distSqrToDoor <= 6.0) {
                        BlockPos houseCenter = house.bedPos != null ? house.bedPos : house.homePos;
                        double villagerDistToCenter = villager.blockPosition().distSqr(houseCenter);
                        double doorDistToCenter = lowerDoorPos.distSqr(houseCenter);

                        boolean isInside = villagerDistToCenter < doorDistToCenter;
                        boolean isOwnerOrBaby = house.isOwner(villagerId) || isBabyOfOwner(villager, house);

                        if (isInside) {
                            if (level.isNight()) {
                                if (isOwnerOrBaby) {
                                    if (villager.getNavigation().getPath() != null) {
                                        BlockPos targetPos = villager.getNavigation().getPath().getTarget();
                                        if (targetPos.distSqr(houseCenter) >= doorDistToCenter) {
                                            villager.getNavigation().stop();
                                            villager.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
                                        }
                                    }
                                } else {
                                    BlockPos outsidePos = lowerDoorPos.offset(
                                        lowerDoorPos.getX() - houseCenter.getX(),
                                        0,
                                        lowerDoorPos.getZ() - houseCenter.getZ()
                                    );
                                    villager.getNavigation().moveTo(outsidePos.getX(), outsidePos.getY(), outsidePos.getZ(), 0.5D);
                                }
                            } else {
                                if (isOwnerOrBaby) {
                                    openDoorIfClosed(level, lowerDoorPos);
                                }
                            }
                        } else {
                            if (isOwnerOrBaby) {
                                openDoorIfClosed(level, lowerDoorPos);
                            } else {
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

        // --- BABY VILLAGER MANAGEMENT & NAMETAGS (SAME VILLAGE ONLY) ---
        if (villager.isBaby()) {
            if (!BABY_PARENT_MAP.containsKey(villagerId)) {
                List<Villager> nearby = level.getEntitiesOfClass(
                    Villager.class,
                    villager.getBoundingBox().inflate(12.0)
                );

                for (Villager v : nearby) {
                    if (!v.isBaby()) {
                        Integer parentVillage = houseData.getVillageForVillager(v.getUUID());
                        if (villagerVillageId == null || parentVillage == null || villagerVillageId.equals(parentVillage)) {
                            BABY_PARENT_MAP.put(villagerId, v.getUUID());
                            if (parentVillage != null) {
                                houseData.setVillagerVillage(villagerId, parentVillage);
                            }
                            break;
                        }
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
                    double distSqrToHouse = villager.blockPosition().distSqr(targetPos);
                    if (distSqrToHouse > 4.0) {
                        if (villager.tickCount % 20 == 0) {
                            villager.getBrain().setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(targetPos, 0.5F, 1));
                            villager.getNavigation().moveTo(targetPos.getX(), targetPos.getY(), targetPos.getZ(), 0.5D);
                        }
                    }
                } else {
                    if (parent != null) {
                        double distSqrToParent = villager.distanceToSqr(parent);
                        if (distSqrToParent > 16.0 && villager.tickCount % 40 == 0) {
                            villager.getNavigation().moveTo(parent, 0.5D);
                        } else if (distSqrToParent <= 9.0) {
                            villager.getNavigation().stop();
                            villager.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
                        }
                    }
                }
            }
            return;
        }

        // --- ADULT VILLAGER NAMETAGS & NIGHT SLEEPING ---
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
                        villager.getNavigation().stop();
                        villager.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
                        villager.startSleeping(assignedHouse.bedPos);
                    } else if (!villager.isSleeping() && villager.tickCount % 20 == 0) {
                        villager.getBrain().setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(targetPos, 0.6F, 1));
                        villager.getNavigation().moveTo(targetPos.getX(), targetPos.getY(), targetPos.getZ(), 0.6D);
                    }
                } else {
                    if (distSqr > 4.0 && villager.tickCount % 20 == 0) {
                        villager.getBrain().setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(targetPos, 0.6F, 1));
                        villager.getNavigation().moveTo(targetPos.getX(), targetPos.getY(), targetPos.getZ(), 0.6D);
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

    private static int countBedsInStructure(ServerLevel level, BlockPos origin) {
        int count = 0;
        BlockPos.MutableBlockPos mut = new BlockPos.MutableBlockPos();
        for (int x = -6; x <= 6; x++) {
            for (int y = -3; y <= 3; y++) {
                for (int z = -6; z <= 6; z++) {
                    mut.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
                    BlockState state = level.getBlockState(mut);
                    if (state.is(BlockTags.BEDS)) {
                        boolean isHead = !state.hasProperty(BedBlock.PART) || state.getValue(BedBlock.PART) == BedPart.HEAD;
                        if (isHead) {
                            count++;
                        }
                    }
                }
            }
        }
        return Math.max(1, count);
    }

    private static BlockPos findDoorNear(ServerLevel level, BlockPos pos) {
        BlockPos.MutableBlockPos mutPos = new BlockPos.MutableBlockPos();
        for (int x = -6; x <= 6; x++) {
            for (int y = -3; y <= 3; y++) {
                for (int z = -6; z <= 6; z++) {
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
