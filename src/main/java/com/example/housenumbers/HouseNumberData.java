package com.example.housenumbers;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.DimensionDataStorage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class HouseNumberData extends SavedData {
    private static final String ID = "housenumbers_data";

    private final Map<BlockPos, Integer> bedToHouseNumber = new HashMap<>();
    private final Map<BlockPos, BlockPos> bedToVillageCenter = new HashMap<>();
    private final Map<BlockPos, Integer> villageNextNumber = new HashMap<>();
    private final List<BlockPos> villageCenters = new ArrayList<>();

    public static HouseNumberData get(DimensionDataStorage storage) {
        return storage.computeIfAbsent(
                new SavedData.Factory<>(HouseNumberData::new, HouseNumberData::load),
                ID
        );
    }

    public boolean isBedKnown(BlockPos pos) {
        return bedToHouseNumber.containsKey(pos.immutable());
    }

    public Integer getHouseNumber(BlockPos pos) {
        return bedToHouseNumber.get(pos.immutable());
    }

    public BlockPos getVillageCenter(BlockPos pos) {
        return bedToVillageCenter.get(pos.immutable());
    }

    /** Finds an existing village center within radius, or registers a new village center. */
    public BlockPos findOrCreateVillageCenter(BlockPos pos, double radius) {
        BlockPos key = pos.immutable();
        for (BlockPos center : villageCenters) {
            if (center.closerThan(key, radius)) {
                return center;
            }
        }
        villageCenters.add(key);
        setDirty();
        return key;
    }

    public void registerBed(BlockPos bedPos, int houseNumber, BlockPos villageCenter) {
        BlockPos key = bedPos.immutable();
        bedToHouseNumber.put(key, houseNumber);
        bedToVillageCenter.put(key, villageCenter.immutable());
        setDirty();
    }

    public int getNextHouseNumberForVillage(BlockPos villageCenter) {
        return villageNextNumber.getOrDefault(villageCenter.immutable(), 1);
    }

    public void incrementVillageHouseNumber(BlockPos villageCenter) {
        BlockPos key = villageCenter.immutable();
        villageNextNumber.put(key, getNextHouseNumberForVillage(key) + 1);
        setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag bedsList = new ListTag();
        for (Map.Entry<BlockPos, Integer> entry : bedToHouseNumber.entrySet()) {
            BlockPos bed = entry.getKey();
            BlockPos center = bedToVillageCenter.get(bed);
            if (center == null) continue;

            CompoundTag bTag = new CompoundTag();
            bTag.putInt("bx", bed.getX());
            bTag.putInt("by", bed.getY());
            bTag.putInt("bz", bed.getZ());
            bTag.putInt("num", entry.getValue());
            bTag.putInt("cx", center.getX());
            bTag.putInt("cy", center.getY());
            bTag.putInt("cz", center.getZ());
            bedsList.add(bTag);
        }
        tag.put("beds", bedsList);

        ListTag villagesList = new ListTag();
        for (Map.Entry<BlockPos, Integer> entry : villageNextNumber.entrySet()) {
            CompoundTag vTag = new CompoundTag();
            vTag.putInt("cx", entry.getKey().getX());
            vTag.putInt("cy", entry.getKey().getY());
            vTag.putInt("cz", entry.getKey().getZ());
            vTag.putInt("next", entry.getValue());
            villagesList.add(vTag);
        }
        tag.put("villages", villagesList);

        ListTag centersList = new ListTag();
        for (BlockPos c : villageCenters) {
            CompoundTag cTag = new CompoundTag();
            cTag.putInt("x", c.getX());
            cTag.putInt("y", c.getY());
            cTag.putInt("z", c.getZ());
            centersList.add(cTag);
        }
        tag.put("centers", centersList);

        return tag;
    }

    public static HouseNumberData load(CompoundTag tag, HolderLookup.Provider registries) {
        HouseNumberData data = new HouseNumberData();
        ListTag bedsList = tag.getList("beds", CompoundTag.TAG_COMPOUND);
        for (int i = 0; i < bedsList.size(); i++) {
            CompoundTag bTag = bedsList.getCompound(i);
            BlockPos bed = new BlockPos(bTag.getInt("bx"), bTag.getInt("by"), bTag.getInt("bz"));
            BlockPos center = new BlockPos(bTag.getInt("cx"), bTag.getInt("cy"), bTag.getInt("cz"));
            data.bedToHouseNumber.put(bed, bTag.getInt("num"));
            data.bedToVillageCenter.put(bed, center);
        }

        ListTag villagesList = tag.getList("villages", CompoundTag.TAG_COMPOUND);
        for (int i = 0; i < villagesList.size(); i++) {
            CompoundTag vTag = villagesList.getCompound(i);
            BlockPos center = new BlockPos(vTag.getInt("cx"), vTag.getInt("cy"), vTag.getInt("cz"));
            data.villageNextNumber.put(center, vTag.getInt("next"));
        }

        ListTag centersList = tag.getList("centers", CompoundTag.TAG_COMPOUND);
        for (int i = 0; i < centersList.size(); i++) {
            CompoundTag cTag = centersList.getCompound(i);
            data.villageCenters.add(new BlockPos(cTag.getInt("x"), cTag.getInt("y"), cTag.getInt("z")));
        }

        return data;
    }
}
