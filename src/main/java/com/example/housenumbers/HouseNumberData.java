package com.example.housenumbers;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.*;

public class HouseNumberData extends SavedData {
    private static final String DATA_NAME = "house_number_data";

    public static class House {
        public final int houseNumber;
        public final BlockPos homePos;
        public final BlockPos bedPos;
        public final BlockPos doorPos;
        public final Vec3 roofCenterPos;
        public final int maxCapacity;
        public final Set<UUID> assignedVillagers = new HashSet<>();

        public House(int houseNumber, BlockPos homePos, BlockPos bedPos, BlockPos doorPos, Vec3 roofCenterPos, int maxCapacity) {
            this.houseNumber = houseNumber;
            this.homePos = homePos;
            this.bedPos = bedPos;
            this.doorPos = doorPos;
            this.roofCenterPos = roofCenterPos;
            this.maxCapacity = maxCapacity;
        }

        public boolean isFull() {
            return assignedVillagers.size() >= maxCapacity;
        }

        public boolean isOwner(UUID villagerId) {
            return assignedVillagers.contains(villagerId);
        }
    }

    private int nextHouseNumber = 1;
    private final List<House> registeredHouses = new ArrayList<>();

    public static HouseNumberData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
            new SavedData.Factory<HouseNumberData>(
                HouseNumberData::new,
                HouseNumberData::load,
                (DataFixTypes) null
            ),
            DATA_NAME
        );
    }

    public List<House> getAllHouses() {
        return Collections.unmodifiableList(registeredHouses);
    }

    public House findExistingHouseAt(BlockPos pos) {
        for (House house : registeredHouses) {
            BlockPos target = house.bedPos != null ? house.bedPos : house.homePos;
            if (target.closerThan(pos, 6.0)) {
                return house;
            }
        }
        return null;
    }

    public House registerHouse(ServerLevel level, BlockPos homePos, BlockPos bedPos, BlockPos doorPos, int maxCapacity) {
        House existing = findExistingHouseAt(homePos);
        if (existing != null) {
            return existing;
        }

        Vec3 centerRoof = calculateRoofCenter(level, bedPos != null ? bedPos : homePos);
        int number = nextHouseNumber++;
        House newHouse = new House(number, homePos, bedPos, doorPos, centerRoof, Math.max(1, maxCapacity));
        registeredHouses.add(newHouse);

        spawnHouseTag(level, newHouse);

        setDirty();
        return newHouse;
    }

    public boolean assignVillagerToHouse(UUID villagerId, House house) {
        if (house.assignedVillagers.contains(villagerId)) {
            return true;
        }
        if (!house.isFull()) {
            house.assignedVillagers.add(villagerId);
            setDirty();
            return true;
        }
        return false;
    }

    public House getHouseForVillager(UUID villagerId) {
        for (House house : registeredHouses) {
            if (house.assignedVillagers.contains(villagerId)) {
                return house;
            }
        }
        return null;
    }

    public void autoAssignLoadedVillagers(ServerLevel level) {
        List<Villager> villagers = level.getEntities(EntityType.VILLAGER, v -> !v.isBaby() && getHouseForVillager(v.getUUID()) == null);
        for (Villager villager : villagers) {
            for (House house : registeredHouses) {
                if (!house.isFull()) {
                    assignVillagerToHouse(villager.getUUID(), house);
                    break;
                }
            }
        }
    }

    private Vec3 calculateRoofCenter(ServerLevel level, BlockPos origin) {
        int minX = origin.getX(), maxX = origin.getX();
        int minZ = origin.getZ(), maxZ = origin.getZ();
        int maxY = origin.getY();

        BlockPos.MutableBlockPos mut = new BlockPos.MutableBlockPos();
        for (int x = -5; x <= 5; x++) {
            for (int z = -5; z <= 5; z++) {
                for (int y = 0; y <= 8; y++) {
                    mut.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
                    BlockState state = level.getBlockState(mut);
                    if (!state.isAir()) {
                        minX = Math.min(minX, mut.getX());
                        maxX = Math.max(maxX, mut.getX());
                        minZ = Math.min(minZ, mut.getZ());
                        maxZ = Math.max(maxZ, mut.getZ());
                        maxY = Math.max(maxY, mut.getY());
                    }
                }
            }
        }

        double centerX = (minX + maxX) / 2.0 + 0.5;
        double centerZ = (minZ + maxZ) / 2.0 + 0.5;
        double topY = maxY + 1.2;

        return new Vec3(centerX, topY, centerZ);
    }

    private void spawnHouseTag(ServerLevel level, House house) {
        Vec3 tagPos = house.roofCenterPos;
        AABB searchBox = new AABB(new BlockPos((int) tagPos.x, (int) tagPos.y, (int) tagPos.z)).inflate(3.0);
        List<ArmorStand> existingStands = level.getEntitiesOfClass(ArmorStand.class, searchBox);

        boolean alreadyExists = false;
        for (ArmorStand stand : existingStands) {
            if (stand.getCustomName() != null && stand.getCustomName().getString().contains("House #")) {
                alreadyExists = true;
                break;
            }
        }

        if (!alreadyExists) {
            ArmorStand marker = new ArmorStand(EntityType.ARMOR_STAND, level);
            marker.setPos(tagPos.x, tagPos.y, tagPos.z);
            marker.setCustomName(Component.literal("House #" + house.houseNumber));
            marker.setCustomNameVisible(true);
            marker.setInvisible(true);
            marker.setNoGravity(true);
            level.addFreshEntity(marker);
        }
    }

    public static HouseNumberData load(CompoundTag tag, HolderLookup.Provider registries) {
        HouseNumberData data = new HouseNumberData();
        data.nextHouseNumber = tag.contains("NextHouseNumber") ? tag.getInt("NextHouseNumber") : 1;

        ListTag houseList = tag.getList("Houses", Tag.TAG_COMPOUND);
        for (int i = 0; i < houseList.size(); i++) {
            CompoundTag hTag = houseList.getCompound(i);
            int num = hTag.getInt("Number");
            BlockPos homePos = BlockPos.of(hTag.getLong("HomePos"));
            BlockPos bedPos = hTag.contains("BedPos") ? BlockPos.of(hTag.getLong("BedPos")) : null;
            BlockPos doorPos = hTag.contains("DoorPos") ? BlockPos.of(hTag.getLong("DoorPos")) : homePos;
            Vec3 roofCenter = new Vec3(hTag.getDouble("RoofX"), hTag.getDouble("RoofY"), hTag.getDouble("RoofZ"));
            int cap = hTag.getInt("Capacity");

            House house = new House(num, homePos, bedPos, doorPos, roofCenter, cap);
            int villagerCount = hTag.getInt("VillagerCount");
            for (int j = 0; j < villagerCount; j++) {
                house.assignedVillagers.add(hTag.getUUID("Villager_" + j));
            }
            data.registeredHouses.add(house);
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("NextHouseNumber", nextHouseNumber);

        ListTag houseList = new ListTag();
        for (House house : registeredHouses) {
            CompoundTag hTag = new CompoundTag();
            hTag.putInt("Number", house.houseNumber);
            hTag.putLong("HomePos", house.homePos.asLong());
            if (house.bedPos != null) {
                hTag.putLong("BedPos", house.bedPos.asLong());
            }
            if (house.doorPos != null) {
                hTag.putLong("DoorPos", house.doorPos.asLong());
            }
            hTag.putDouble("RoofX", house.roofCenterPos.x);
            hTag.putDouble("RoofY", house.roofCenterPos.y);
            hTag.putDouble("RoofZ", house.roofCenterPos.z);
            hTag.putInt("Capacity", house.maxCapacity);

            hTag.putInt("VillagerCount", house.assignedVillagers.size());
            int idx = 0;
            for (UUID uuid : house.assignedVillagers) {
                hTag.putUUID("Villager_" + idx++, uuid);
            }
            houseList.add(hTag);
        }
        tag.put("Houses", houseList);
        return tag;
    }
}
