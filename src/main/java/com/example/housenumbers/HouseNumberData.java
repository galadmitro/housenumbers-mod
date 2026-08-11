package com.example.housenumbers;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.DimensionDataStorage;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Saved to disk with the world. Remembers which bed (= "house") already
 * got a number, and what the next free number is, so numbers:
 *  - are assigned once, in discovery order
 *  - never repeat
 *  - survive server restarts
 */
public class HouseNumberData extends SavedData {
    private static final String ID = "housenumbers_data";

    private final Map<BlockPos, Integer> posToNumber = new LinkedHashMap<>();
    private int nextNumber = 1;

    public static HouseNumberData get(DimensionDataStorage storage) {
        return storage.computeIfAbsent(
                new SavedData.Factory<>(HouseNumberData::new, HouseNumberData::load),
                ID
        );
    }

    public boolean isKnown(BlockPos pos) {
        return posToNumber.containsKey(pos.immutable());
    }

    /** Returns the existing number for this bed, or assigns and returns the next free one. */
    public int assign(BlockPos pos) {
        BlockPos key = pos.immutable();
        Integer existing = posToNumber.get(key);
        if (existing != null) {
            return existing;
        }
        int assigned = nextNumber;
        nextNumber++;
        posToNumber.put(key, assigned);
        setDirty();
        return assigned;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Map.Entry<BlockPos, Integer> e : posToNumber.entrySet()) {
            CompoundTag entry = new CompoundTag();
            entry.putInt("x", e.getKey().getX());
            entry.putInt("y", e.getKey().getY());
            entry.putInt("z", e.getKey().getZ());
            entry.putInt("num", e.getValue());
            list.add(entry);
        }
        tag.put("houses", list);
        tag.putInt("next", nextNumber);
        return tag;
    }

    public static HouseNumberData load(CompoundTag tag, HolderLookup.Provider registries) {
        HouseNumberData data = new HouseNumberData();
        ListTag list = tag.getList("houses", CompoundTag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            BlockPos pos = new BlockPos(entry.getInt("x"), entry.getInt("y"), entry.getInt("z"));
            data.posToNumber.put(pos, entry.getInt("num"));
        }
        data.nextNumber = Math.max(1, tag.getInt("next"));
        return data;
    }
}
