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
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.AABB;

import java.util.*;

public class HouseNumberData extends SavedData {
    private static final String DATA_NAME = "house_number_data";

    public static class House {
        public final String villageId;
        public final int houseNumber;
        public final BlockPos bedPos;
        public final int maxCapacity;
        public final Set<UUID> assignedVillagers = new HashSet<>();

        public House(String villageId, int houseNumber, BlockPos bedPos, int maxCapacity) {
            this.villageId = villageId;
            this.houseNumber = houseNumber;
            this.bedPos = bedPos;
            this.maxCapacity = maxCapacity;
        }
    }

    private final Map<String, Integer> villageHouseCounters = new HashMap<>();
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

    public House findOrRegisterHouse(ServerLevel level, String villageId, BlockPos bedPos, int bedCount) {
        // If a house is already registered around this exact bed (within 5 blocks), return existing house
        for (House house : registeredHouses) {
            if (house.villageId.equals(villageId) && house.bedPos.closerThan(bedPos, 5)) {
                return house;
            }
        }

        // Assign a strict sequential house number per village region
        int nextNumber = villageHouseCounters.getOrDefault(villageId, 0) + 1;
        villageHouseCounters.put(villageId, nextNumber);

        House newHouse = new House(villageId, nextNumber, bedPos, Math.max(1, bedCount));
        registeredHouses.add(newHouse);
        
        // Spawn floating house tag directly above the house roof
        spawnHouseTag(level, newHouse);

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

    private void spawnHouseTag(ServerLevel level, House house) {
        // Find roof height directly above the bed position
        BlockPos tagPos = house.bedPos.above(4);
        while (level.getBlockState(tagPos).isSolid() && tagPos.getY() < level.getMaxBuildHeight()) {
            tagPos = tagPos.above();
        }

        AABB searchBox = new AABB(tagPos).inflate(2.0);
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
            marker.setPos(tagPos.getX() + 0.5, tagPos.getY() + 0.2, tagPos.getZ() + 0.5);
            marker.setCustomName(Component.literal("House #" + house.houseNumber));
            marker.setCustomNameVisible(true);
            marker.setInvisible(true);
            marker.setNoGravity(true);
            level.addFreshEntity(marker);
        }
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
            hTag.putLong("Pos", house.bedPos.asLong());
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
