package com.example.housenumbers;

import net.neoforged.fml.common.Mod;

/**
 * Entry point. All the real work happens in VillageEventHandler,
 * which is auto-subscribed to the game bus via @EventBusSubscriber.
 */
@Mod(HouseNumbersMod.MODID)
public class HouseNumbersMod {
    public static final String MODID = "housenumbers";

    public HouseNumbersMod() {
        // Nothing to register up front — VillageEventHandler self-subscribes.
    }
}
