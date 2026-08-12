package com.example.housenumbers;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.*;

public class HouseNumberData extends SavedData {
    private static final String DATA_NAME = "house_number_data";

    public static class House {
        public final String villageId;
        public final int houseNumber;
        public final BlockPos centerPos;
        public final int maxCapacity;
        public final Set<UUID> assignedVillagers = new HashSet<>();

        public House(String villageId, int houseNumber, BlockPos centerPos, int maxCapacity) {
            this.villageId = villageId;
            this.houseNumber = houseNumber;
            this.centerPos = centerPos;
            this.maxCapacity = maxCapacity;
        }
    }

    private final Map<String, Integer> villageHouseCounters = new HashMap<>();
    private final List<House> registeredHouses = new ArrayList<>();

    public static HouseNumberData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
            new SavedData.Factory<>(
                HouseNumberData::new,
                HouseNumberData::load,
                null
            ),
            DATA_NAME
        );
    }

    public House findOrRegisterHouse(String villageId, BlockPos structurePos, int bedCount) {
        for (House house : registeredHouses) {
            if (house.villageId.equals(villageId) && house.centerPos.closerThan(structurePos, 16)) {
                return house;
            }
        }

        int nextNumber = villageHouseCounters.getOrDefault(villageId, 0) + 1;
        villageHouseCounters.put(villageId, nextNumber);

        House newHouse = new House(villageId, nextNumber, structurePos, Math.max(1, bedCount));
        registeredHouses.add(newHouse);
        setDirty();
        return newHouse;
    }

    public boolean assignVillagerToHouse(UUID villagerId, House house) {
        if (house.assignedVillagers.contains(villagerId)) {
            return true;
        }
        if (house.assignedVillagers.size() < house.maxCapacity) {
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

    public static HouseNumberData load(CompoundTag tag, HolderLookup.Provider registries) {
        HouseNumberData data = new HouseNumberData();

        ListTag houseList = tag.getList("Houses", Tag.TAG_COMPOUND);
        for (int i = 0; i < houseList.size(); i++) {
            CompoundTag hTag = houseList.getCompound(i);
            String vId = hTag.getString("VillageId");
            int num = hTag.getInt("Number");
            BlockPos pos = BlockPos.of(hTag.getLong("Pos"));
            int cap = hTag.getInt("Capacity");

            House house = new House(vId, num, pos, cap);
            int villagerCount = hTag.getInt("VillagerCount");
            for (int j = 0; j < villagerCount; j++) {
                house.assignedVillagers.add(hTag.getUUID("Villager_" + j));
            }
            data.registeredHouses.add(house);
            data.villageHouseCounters.put(vId, Math.max(data.villageHouseCounters.getOrDefault(vId, 0), num));
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag houseList = new ListTag();
        for (House house : registeredHouses) {
            CompoundTag hTag = new CompoundTag();
            hTag.putString("VillageId", house.villageId);
            hTag.putInt("Number", house.houseNumber);
            hTag.putLong("Pos", house.centerPos.asLong());
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
