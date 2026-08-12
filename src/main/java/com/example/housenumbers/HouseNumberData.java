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
import net.minecraft.world.entity.Entity;
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
        public BlockPos homePos;
        public BlockPos doorPos;
        public Vec3 roofCenterPos;
        public final List<BlockPos> beds = new ArrayList<>();
        public final Set<UUID> assignedVillagers = new HashSet<>();
        public final Map<UUID, BlockPos> villagerBedMap = new HashMap<>();

        public House(int villageId, int houseNumber, BlockPos homePos, BlockPos doorPos, Vec3 roofCenterPos) {
            this.villageId = villageId;
            this.houseNumber = houseNumber;
            this.homePos = homePos;
            this.doorPos = doorPos;
            this.roofCenterPos = roofCenterPos;
        }

        public void addBed(BlockPos bedPos) {
            if (!beds.contains(bedPos)) {
                beds.add(bedPos);
            }
        }

        public int getMaxCapacity() {
            return Math.max(1, beds.size());
        }

        public boolean isFull() {
            return assignedVillagers.size() >= getMaxCapacity();
        }

        public boolean isOwner(UUID villagerId) {
            return assignedVillagers.contains(villagerId);
        }

        public BlockPos getBedForVillager(UUID villagerId) {
            if (villagerBedMap.containsKey(villagerId)) {
                return villagerBedMap.get(villagerId);
            }
            for (BlockPos bed : beds) {
                if (!villagerBedMap.containsValue(bed)) {
                    villagerBedMap.put(villagerId, bed);
                    return bed;
                }
            }
            return beds.isEmpty() ? homePos : beds.get(0);
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
                BlockPos target = !h.beds.isEmpty() ? h.beds.get(0) : h.homePos;
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
    private final Map<UUID, Integer> villagerVillageMap = new HashMap<>();

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

    public House findHouseNear(BlockPos pos, double maxDist) {
        double maxDistSqr = maxDist * maxDist;
        for (House house : registeredHouses) {
            if (house.homePos.distSqr(pos) <= maxDistSqr) {
                return house;
            }
            for (BlockPos b : house.beds) {
                if (b.distSqr(pos) <= maxDistSqr) {
                    return house;
                }
            }
        }
        return null;
    }

    public VillageCluster findClusterNear(BlockPos pos) {
        for (VillageCluster cluster : clusters) {
            if (cluster.isNear(pos, VILLAGE_RADIUS)) {
                return cluster;
            }
        }
        return null;
    }

    public Integer getVillageForVillager(UUID villagerId) {
        House house = getHouseForVillager(villagerId);
        if (house != null) {
            villagerVillageMap.put(villagerId, house.villageId);
            return house.villageId;
        }
        return villagerVillageMap.get(villagerId);
    }

    public void setVillagerVillage(UUID villagerId, int villageId) {
        villagerVillageMap.put(villagerId, villageId);
        setDirty();
    }

    public House registerOrUpdateHouse(ServerLevel level, BlockPos bedPos, BlockPos doorPos) {
        House existing = findHouseNear(bedPos, 8.0);
        if (existing != null) {
            existing.addBed(bedPos);
            if (doorPos != null) existing.doorPos = doorPos;
            existing.roofCenterPos = calculateRoofCenter(level, existing.homePos);
            spawnOrUpdateHouseTag(level, existing);
            setDirty();
            return existing;
        }

        VillageCluster cluster = findClusterNear(bedPos);
        if (cluster == null) {
            cluster = new VillageCluster(nextVillageId++);
            clusters.add(cluster);
        }

        int houseNumber = cluster.nextHouseNumber++;
        Vec3 centerRoof = calculateRoofCenter(level, bedPos);

        House newHouse = new House(cluster.villageId, houseNumber, bedPos, doorPos != null ? doorPos : bedPos, centerRoof);
        newHouse.addBed(bedPos);

        cluster.houses.add(newHouse);
        registeredHouses.add(newHouse);

        spawnOrUpdateHouseTag(level, newHouse);
        setDirty();
        return newHouse;
    }

    public boolean assignVillagerToHouse(UUID villagerId, House house) {
        if (house.assignedVillagers.contains(villagerId)) {
            villagerVillageMap.put(villagerId, house.villageId);
            house.getBedForVillager(villagerId);
            return true;
        }
        if (!house.isFull()) {
            house.assignedVillagers.add(villagerId);
            villagerVillageMap.put(villagerId, house.villageId);
            house.getBedForVillager(villagerId);
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

    public void cleanupDeadVillagers(ServerLevel level) {
        boolean changed = false;
        for (House house : registeredHouses) {
            Iterator<UUID> iterator = house.assignedVillagers.iterator();
            while (iterator.hasNext()) {
                UUID uuid = iterator.next();
                Entity entity = level.getEntity(uuid);
                if (entity != null && (!entity.isAlive() || !(entity instanceof Villager))) {
                    iterator.remove();
                    house.villagerBedMap.remove(uuid);
                    villagerVillageMap.remove(uuid);
                    changed = true;
                }
            }
        }
        if (changed) {
            setDirty();
        }
    }

    public void autoAssignLoadedVillagers(ServerLevel level) {
        cleanupDeadVillagers(level);

        List<? extends Villager> villagers = level.getEntities(EntityType.VILLAGER, v -> !v.isBaby() && getHouseForVillager(v.getUUID()) == null);

        for (Villager villager : villagers) {
            BlockPos villagerPos = villager.blockPosition();
            UUID vId = villager.getUUID();

            Integer assignedVillageId = villagerVillageMap.get(vId);
            if (assignedVillageId == null) {
                VillageCluster nearCluster = findClusterNear(villagerPos);
                if (nearCluster != null) {
                    assignedVillageId = nearCluster.villageId;
                    villagerVillageMap.put(vId, assignedVillageId);
                }
            }

            House bestHouse = null;
            double bestDistSqr = Double.MAX_VALUE;

            for (House house : registeredHouses) {
                if (assignedVillageId != null && house.villageId != assignedVillageId) {
                    continue;
                }

                if (!house.isFull()) {
                    BlockPos housePos = !house.beds.isEmpty() ? house.beds.get(0) : house.homePos;
                    double distSqr = housePos.distSqr(villagerPos);
                    if (distSqr < bestDistSqr) {
                        bestDistSqr = distSqr;
                        bestHouse = house;
                    }
                }
            }

            if (bestHouse != null) {
                assignVillagerToHouse(vId, bestHouse);
            }
        }
    }

    private Vec3 calculateRoofCenter(ServerLevel level, BlockPos origin) {
        int highestY = origin.getY();
        BlockPos.MutableBlockPos mut = new BlockPos.MutableBlockPos();

        for (int x = -7; x <= 7; x++) {
            for (int z = -7; z <= 7; z++) {
                for (int y = 0; y <= 12; y++) {
                    mut.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
                    BlockState state = level.getBlockState(mut);
                    if (isHouseStructureBlock(state)) {
                        highestY = Math.max(highestY, mut.getY());
                    }
                }
            }
        }

        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        boolean foundPeak = false;

        for (int x = -7; x <= 7; x++) {
            for (int z = -7; z <= 7; z++) {
                for (int y = Math.max(origin.getY(), highestY - 2); y <= highestY; y++) {
                    mut.set(origin.getX() + x, y, origin.getZ() + z);
                    BlockState state = level.getBlockState(mut);
                    if (isHouseStructureBlock(state)) {
                        minX = Math.min(minX, mut.getX());
                        maxX = Math.max(maxX, mut.getX());
                        minZ = Math.min(minZ, mut.getZ());
                        maxZ = Math.max(maxZ, mut.getZ());
                        foundPeak = true;
                    }
                }
            }
        }

        if (!foundPeak) {
            return new Vec3(origin.getX() + 0.5, origin.getY() + 3.5, origin.getZ() + 0.5);
        }

        double centerX = (minX + maxX) / 2.0 + 0.5;
        double centerZ = (minZ + maxZ) / 2.0 + 0.5;
        double topY = highestY + 1.2;

        return new Vec3(centerX, topY, centerZ);
    }

    private boolean isHouseStructureBlock(BlockState state) {
        if (state.isAir() ||
            state.is(net.minecraft.world.level.block.Blocks.DIRT) ||
            state.is(net.minecraft.world.level.block.Blocks.GRASS_BLOCK) ||
            state.is(net.minecraft.world.level.block.Blocks.DIRT_PATH) ||
            state.is(net.minecraft.world.level.block.Blocks.FARMLAND) ||
            state.is(net.minecraft.world.level.block.Blocks.STONE) ||
            state.is(net.minecraft.world.level.block.Blocks.COBBLESTONE) ||
            state.is(net.minecraft.world.level.block.Blocks.GRAVEL) ||
            state.is(net.minecraft.world.level.block.Blocks.SAND) ||
            state.is(net.minecraft.world.level.block.Blocks.SHORT_GRASS) ||
            state.is(net.minecraft.world.level.block.Blocks.TALL_GRASS) ||
            state.is(BlockTags.LEAVES) ||
            state.is(BlockTags.FLOWERS) ||
            state.is(BlockTags.BEDS)) {
            return false;
        }

        return state.getBlock() instanceof StairBlock ||
               state.getBlock() instanceof SlabBlock ||
               state.is(BlockTags.PLANKS) ||
               state.is(BlockTags.LOGS) ||
               state.is(BlockTags.TERRACOTTA) ||
               state.is(BlockTags.WOOL);
    }

    private void spawnOrUpdateHouseTag(ServerLevel level, House house) {
        Vec3 tagPos = house.roofCenterPos;
        AABB searchBox = new AABB(new BlockPos((int) tagPos.x, (int) tagPos.y, (int) tagPos.z)).inflate(12.0);
        List<ArmorStand> existingStands = level.getEntitiesOfClass(ArmorStand.class, searchBox);

        String tagText = "House #" + house.houseNumber;
        ArmorStand targetStand = null;

        for (ArmorStand stand : existingStands) {
            if (stand.getCustomName() != null && stand.getCustomName().getString().equals(tagText)) {
                targetStand = stand;
                break;
            }
        }

        if (targetStand != null) {
            targetStand.setPos(tagPos.x, tagPos.y, tagPos.z);
            targetStand.setInvisible(true);
            targetStand.setNoGravity(true);
            targetStand.setCustomNameVisible(true);
        } else {
            ArmorStand marker = new ArmorStand(EntityType.ARMOR_STAND, level);
            marker.setPos(tagPos.x, tagPos.y, tagPos.z);
            marker.setCustomName(Component.literal(tagText));
            marker.setCustomNameVisible(true);
            marker.setInvisible(true);
            marker.setNoGravity(true);
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
            BlockPos doorPos = hTag.contains("DoorPos") ? BlockPos.of(hTag.getLong("DoorPos")) : homePos;
            Vec3 roofCenter = new Vec3(hTag.getDouble("RoofX"), hTag.getDouble("RoofY"), hTag.getDouble("RoofZ"));

            House house = new House(vId, num, homePos, doorPos, roofCenter);

            if (hTag.contains("Beds", Tag.TAG_LIST)) {
                ListTag bedList = hTag.getList("Beds", Tag.TAG_COMPOUND);
                for (int k = 0; k < bedList.size(); k++) {
                    house.addBed(BlockPos.of(bedList.getCompound(k).getLong("Pos")));
                }
            } else if (hTag.contains("BedPos")) {
                house.addBed(BlockPos.of(hTag.getLong("BedPos")));
            }

            int villagerCount = hTag.getInt("VillagerCount");
            for (int j = 0; j < villagerCount; j++) {
                UUID vUuid = hTag.getUUID("Villager_" + j);
                house.assignedVillagers.add(vUuid);
                data.villagerVillageMap.put(vUuid, vId);
                if (hTag.contains("VillagerBed_" + j)) {
                    house.villagerBedMap.put(vUuid, BlockPos.of(hTag.getLong("VillagerBed_" + j)));
                }
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
            if (house.doorPos != null) {
                hTag.putLong("DoorPos", house.doorPos.asLong());
            }
            hTag.putDouble("RoofX", house.roofCenterPos.x);
            hTag.putDouble("RoofY", house.roofCenterPos.y);
            hTag.putDouble("RoofZ", house.roofCenterPos.z);

            ListTag bedList = new ListTag();
            for (BlockPos bPos : house.beds) {
                CompoundTag bTag = new CompoundTag();
                bTag.putLong("Pos", bPos.asLong());
                bedList.add(bTag);
            }
            hTag.put("Beds", bedList);

            hTag.putInt("VillagerCount", house.assignedVillagers.size());
            int idx = 0;
            for (UUID uuid : house.assignedVillagers) {
                hTag.putUUID("Villager_" + idx, uuid);
                BlockPos bed = house.villagerBedMap.get(uuid);
                if (bed != null) {
                    hTag.putLong("VillagerBed_" + idx, bed.asLong());
                }
                idx++;
            }
            houseList.add(hTag);
        }
        tag.put("Houses", houseList);
        return tag;
    }
}
