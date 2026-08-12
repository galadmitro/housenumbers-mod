package com.example.housenumbers;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.*;

public class HouseNumberData extends SavedData {
    private static final String DATA_NAME = "house_number_data";
    public static final double VILLAGE_RADIUS = 64.0;

    public static class House {
        public final int villageId;
        public final int houseNumber;
        public final BlockPos homePos;
        public final BlockPos bedPos;
        public final BlockPos doorPos;
        public final Vec3 roofCenterPos;
        public final int maxCapacity;
        public final Set<UUID> assignedVillagers = new HashSet<>();

        public House(int villageId, int houseNumber, BlockPos homePos, BlockPos bedPos, BlockPos doorPos, Vec3 roofCenterPos, int maxCapacity) {
            this.villageId = villageId;
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

    public static class VillageCluster {
        public final int villageId;
        public int nextHouseNumber = 1;
        public final List<House> houses = new ArrayList<>();

        public VillageCluster(int villageId) {
            this.villageId = villageId;
        }

        public boolean isNear(BlockPos pos, double maxDistance) {
            for (House h : houses) {
                BlockPos target = h.bedPos != null ? h.bedPos : h.homePos;
                if (target.closerThan(pos, maxDistance)) {
                    return true;
                }
            }
            return false;
        }
    }

    private int nextVillageId = 1;
    private final List<VillageCluster> clusters = new ArrayList<>();
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

    private VillageCluster findClusterNear(BlockPos pos) {
        for (VillageCluster cluster : clusters) {
            if (cluster.isNear(pos, VILLAGE_RADIUS)) {
                return cluster;
            }
        }
        return null;
    }

    public House registerHouse(ServerLevel level, BlockPos homePos, BlockPos bedPos, BlockPos doorPos, int maxCapacity) {
        House existing = findExistingHouseAt(homePos);
        if (existing != null) {
            return existing;
        }

        BlockPos targetPos = bedPos != null ? bedPos : homePos;
        VillageCluster cluster = findClusterNear(targetPos);

        if (cluster == null) {
            cluster = new VillageCluster(nextVillageId++);
            clusters.add(cluster);
        }

        Vec3 centerRoof = calculateRoofCenter(level, targetPos);
        int houseNumber = cluster.nextHouseNumber++;

        House newHouse = new House(cluster.villageId, houseNumber, homePos, bedPos, doorPos, centerRoof, Math.max(1, maxCapacity));
        cluster.houses.add(newHouse);
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

    // Guarantees all homeless villagers in the world get assigned to available houses
    public void autoAssignLoadedVillagers(ServerLevel level) {
        List<? extends Villager> villagers = level.getEntities(EntityType.VILLAGER, v -> !v.isBaby() && getHouseForVillager(v.getUUID()) == null);
        
        for (Villager villager : villagers) {
            BlockPos villagerPos = villager.blockPosition();
            House bestHouse = null;
            double bestDistSqr = Double.MAX_VALUE;

            for (House house : registeredHouses) {
                if (!house.isFull()) {
                    BlockPos housePos = house.bedPos != null ? house.bedPos : house.homePos;
                    double distSqr = housePos.distSqr(villagerPos);
                    if (distSqr < bestDistSqr) {
                        bestDistSqr = distSqr;
                        bestHouse = house;
                    }
                }
            }

            if (bestHouse != null) {
                assignVillagerToHouse(villager.getUUID(), bestHouse);
            }
        }
    }

    // Precise roof detection: Filters out dirt, grass, plants, and terrain
    private Vec3 calculateRoofCenter(ServerLevel level, BlockPos origin) {
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        int maxY = origin.getY();
        boolean foundRoofBlock = false;

        BlockPos.MutableBlockPos mut = new BlockPos.MutableBlockPos();
        for (int x = -6; x <= 6; x++) {
            for (int z = -6; z <= 6; z++) {
                for (int y = 0; y <= 8; y++) {
                    mut.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
                    BlockState state = level.getBlockState(mut);

                    if (!state.isAir() && isHouseStructureBlock(state)) {
                        minX = Math.min(minX, mut.getX());
                        maxX = Math.max(maxX, mut.getX());
                        minZ = Math.min(minZ, mut.getZ());
                        maxZ = Math.max(maxZ, mut.getZ());
                        maxY = Math.max(maxY, mut.getY());
                        foundRoofBlock = true;
                    }
                }
            }
        }

        if (!foundRoofBlock) {
            return new Vec3(origin.getX() + 0.5, origin.getY() + 3.0, origin.getZ() + 0.5);
        }

        double centerX = (minX + maxX) / 2.0 + 0.5;
        double centerZ = (minZ + maxZ) / 2.0 + 0.5;
        double topY = maxY + 1.2;

        return new Vec3(centerX, topY, centerZ);
    }

    private boolean isHouseStructureBlock(BlockState state) {
        if (state.is(net.minecraft.world.level.block.Blocks.DIRT) ||
            state.is(net.minecraft.world.level.block.Blocks.GRASS_BLOCK) ||
            state.is(net.minecraft.world.level.block.Blocks.DIRT_PATH) ||
            state.is(net.minecraft.world.level.block.Blocks.FARMLAND) ||
            state.is(net.minecraft.world.level.block.Blocks.STONE) ||
            state.is(net.minecraft.world.level.block.Blocks.SHORT_GRASS) ||
            state.is(net.minecraft.world.level.block.Blocks.TALL_GRASS) ||
            state.is(BlockTags.LEAVES) ||
            state.is(BlockTags.FLOWERS) ||
            state.is(BlockTags.BEDS)) {
            return false;
        }
        return state.isSolid() || state.getBlock() instanceof StairBlock || state.getBlock() instanceof SlabBlock;
    }

    private void spawnHouseTag(ServerLevel level, House house) {
        Vec3 tagPos = house.roofCenterPos;
        AABB searchBox = new AABB(new BlockPos((int) tagPos.x, (int) tagPos.y, (int) tagPos.z)).inflate(8.0);
        List<ArmorStand> existingStands = level.getEntitiesOfClass(ArmorStand.class, searchBox);

        ArmorStand existing = null;
        for (ArmorStand stand : existingStands) {
            if (stand.getCustomName() != null && stand.getCustomName().getString().equals("House #" + house.houseNumber)) {
                existing = stand;
                break;
            }
        }

        if (existing != null) {
            existing.setPos(tagPos.x, tagPos.y, tagPos.z);
        } else {
            ArmorStand marker = new ArmorStand(EntityType.ARMOR_STAND, level);
            marker.setPos(tagPos.x, tagPos.y, tagPos.z);
            marker.setCustomName(Component.literal("House #" + house.houseNumber));
            marker.setCustomNameVisible(true);
            marker.setInvisible(true);
            marker.setNoGravity(true);
            marker.setMarker(true);
            level.addFreshEntity(marker);
        }
    }

    public static HouseNumberData load(CompoundTag tag, HolderLookup.Provider registries) {
        HouseNumberData data = new HouseNumberData();
        data.nextVillageId = tag.contains("NextVillageId") ? tag.getInt("NextVillageId") : 1;

        Map<Integer, VillageCluster> clusterMap = new HashMap<>();

        ListTag houseList = tag.getList("Houses", Tag.TAG_COMPOUND);
        for (int i = 0; i < houseList.size(); i++) {
            CompoundTag hTag = houseList.getCompound(i);
            int vId = hTag.contains("VillageId") ? hTag.getInt("VillageId") : 1;
            int num = hTag.getInt("Number");
            BlockPos homePos = BlockPos.of(hTag.getLong("HomePos"));
            BlockPos bedPos = hTag.contains("BedPos") ? BlockPos.of(hTag.getLong("BedPos")) : null;
            BlockPos doorPos = hTag.contains("DoorPos") ? BlockPos.of(hTag.getLong("DoorPos")) : homePos;
            Vec3 roofCenter = new Vec3(hTag.getDouble("RoofX"), hTag.getDouble("RoofY"), hTag.getDouble("RoofZ"));
            int cap = hTag.getInt("Capacity");

            House house = new House(vId, num, homePos, bedPos, doorPos, roofCenter, cap);
            int villagerCount = hTag.getInt("VillagerCount");
            for (int j = 0; j < villagerCount; j++) {
                house.assignedVillagers.add(hTag.getUUID("Villager_" + j));
            }
            data.registeredHouses.add(house);

            VillageCluster cluster = clusterMap.computeIfAbsent(vId, id -> {
                VillageCluster c = new VillageCluster(id);
                data.clusters.add(c);
                return c;
            });
            cluster.houses.add(house);
            cluster.nextHouseNumber = Math.max(cluster.nextHouseNumber, num + 1);
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("NextVillageId", nextVillageId);

        ListTag houseList = new ListTag();
        for (House house : registeredHouses) {
            CompoundTag hTag = new CompoundTag();
            hTag.putInt("VillageId", house.villageId);
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
