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
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.AABB;

import java.util.*;

public class HouseNumberData extends SavedData {
    private static final String DATA_NAME = "house_number_data";

    public static class House {
        public final int houseNumber;
        public final BlockPos homePos;
        public final BlockPos bedPos; // Can be null if door only
        public final BlockPos doorPos; // Position of house entrance
        public final int maxCapacity;
        public final Set<UUID> assignedVillagers = new HashSet<>();

        public House(int houseNumber, BlockPos homePos, BlockPos bedPos, BlockPos doorPos, int maxCapacity) {
            this.houseNumber = houseNumber;
            this.homePos = homePos;
            this.bedPos = bedPos;
            this.doorPos = doorPos;
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

        int number = nextHouseNumber++;
        House newHouse = new House(number, homePos, bedPos, doorPos, Math.max(1, maxCapacity));
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

    private void spawnHouseTag(ServerLevel level, House house) {
        BlockPos basePos = house.homePos;
        BlockPos roofPos = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, basePos);
        int topY = Math.max(roofPos.getY(), basePos.getY() + 3);
        BlockPos tagPos = new BlockPos(basePos.getX(), topY, basePos.getZ());

        AABB searchBox = new AABB(tagPos).inflate(3.0);
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
            marker.setPos(tagPos.getX() + 0.5, tagPos.getY() + 0.8, tagPos.getZ() + 0.5);
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
            int cap = hTag.getInt("Capacity");

            House house = new House(num, homePos, bedPos, doorPos, cap);
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
