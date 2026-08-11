package com.example.housenumbers;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import java.util.Set;
import java.util.stream.Collectors;

@EventBusSubscriber(modid = HouseNumbersMod.MODID)
public class VillageEventHandler {

    private static int tickCounter = 0;
    private static final int SCAN_INTERVAL_TICKS = 100; // ~5 seconds
    private static final int SCAN_RADIUS = 32;           // blocks around each player

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (level.dimension() != Level.OVERWORLD) return;

        tickCounter++;
        if (tickCounter % SCAN_INTERVAL_TICKS != 0) return;

        PoiManager poiManager = level.getPoiManager();
        HouseNumberData data = HouseNumberData.get(level.getDataStorage());

        level.players().forEach(player -> {
            BlockPos center = player.blockPosition();

            Set<BlockPos> homeBeds = poiManager.findAll(
                    holder -> holder.is(PoiTypes.HOME),
                    pos -> true,
                    center,
                    SCAN_RADIUS,
                    PoiManager.Occupancy.ANY
            ).collect(Collectors.toSet());

            for (BlockPos bedPos : homeBeds) {
                if (!data.isKnown(bedPos)) {
                    int number = data.assign(bedPos);
                    spawnLabel(level, bedPos, number);
                }
            }
        });
    }

    private static void spawnLabel(ServerLevel level, BlockPos bedPos, int number) {
        BlockPos above = bedPos.above(2);
        ArmorStand stand = new ArmorStand(EntityType.ARMOR_STAND, level);
        stand.setPos(above.getX() + 0.5, above.getY(), above.getZ() + 0.5);
        stand.setInvisible(true);
        stand.setNoGravity(true);
        stand.setSmall(true);
        stand.setInvulnerable(true);
        stand.setNoBasePlate(true);
        stand.setSilent(true);
        stand.setCustomName(Component.literal("House #" + number));
        stand.setCustomNameVisible(true);
        level.addFreshEntity(stand);
    }
}
