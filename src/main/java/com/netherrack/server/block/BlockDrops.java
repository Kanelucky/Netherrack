package com.netherrack.server.block;

import com.netherrack.server.network.VanillaData;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What blocks drop when players break them in survival. Most drop their own item - the item
 * that places them, which usually shares their name. Some drop another, like grass dropping
 * dirt and stone dropping cobblestone, and some drop nothing: glass, leaves, ice and plain
 * grasses. A block that needs a tool of some tier to harvest, like stone or ores, drops
 * nothing broken without one. A double slab drops two slabs, and a door, bed or tall plant
 * drops once, from its lower half or foot. Beds and banners drop in the color they kept, and
 * banners with their patterns. Ores drop the least they can, since nothing gives
 * a tool Fortune yet, and chance drops, like saplings from leaves, are for later.
 */
public final class BlockDrops {

    /** Blocks that drop another item than their own, and how many. */
    private static final Map<String, Drop> DROPS_INSTEAD = new HashMap<>();

    private record Drop(String item, int count) {
    }

    static {
        for (String block : List.of("grass_block", "podzol", "mycelium", "farmland", "grass_path", "dirt_with_roots")) {
            instead(block, "dirt", 1);
        }
        instead("stone", "cobblestone", 1);
        instead("deepslate", "cobbled_deepslate", 1);
        Map<String, Drop> ores = Map.of(
                "coal", new Drop("coal", 1), "iron", new Drop("raw_iron", 1), "copper", new Drop("raw_copper", 2),
                "gold", new Drop("raw_gold", 1), "diamond", new Drop("diamond", 1), "emerald", new Drop("emerald", 1),
                "lapis", new Drop("lapis_lazuli", 4), "redstone", new Drop("redstone", 4));
        ores.forEach((ore, drop) -> {
            instead(ore + "_ore", drop.item(), drop.count());
            instead("deepslate_" + ore + "_ore", drop.item(), drop.count());
        });
        instead("lit_redstone_ore", "redstone", 4);
        instead("lit_deepslate_redstone_ore", "redstone", 4);
        instead("nether_gold_ore", "gold_nugget", 2);
        instead("quartz_ore", "quartz", 1);
        instead("ender_chest", "obsidian", 8);
        instead("lit_furnace", "furnace", 1);
        instead("lit_blast_furnace", "blast_furnace", 1);
        instead("lit_smoker", "smoker", 1);
        instead("lit_redstone_lamp", "redstone_lamp", 1);
        instead("glowstone", "glowstone_dust", 2);
        instead("sea_lantern", "prismarine_crystals", 2);
        instead("clay", "clay_ball", 4);
        instead("bookshelf", "book", 3);
        instead("melon_block", "melon_slice", 3);
        instead("snow", "snowball", 4);
        instead("web", "string", 1);
        instead("redstone_wire", "redstone", 1);
    }

    /** Blocks that drop nothing, not even themselves, without Silk Touch or shears, which nothing gives yet. */
    private static final Set<String> DROPS_NOTHING = Set.of(
            "minecraft:ice", "minecraft:packed_ice", "minecraft:blue_ice", "minecraft:frosted_ice",
            "minecraft:short_grass", "minecraft:tall_grass", "minecraft:fern", "minecraft:large_fern",
            "minecraft:seagrass", "minecraft:deadbush", "minecraft:vine", "minecraft:fire", "minecraft:soul_fire",
            "minecraft:cake", "minecraft:cave_vines", "minecraft:budding_amethyst", "minecraft:mob_spawner",
            "minecraft:turtle_egg", "minecraft:frog_spawn");

    private BlockDrops() {
    }

    private static void instead(String block, String item, int count) {
        DROPS_INSTEAD.put("minecraft:" + block, new Drop("minecraft:" + item, count));
    }

    /**
     * What a block drops, broken by a player holding this item (null for an empty hand).
     *
     * @param blockEntity what the block kept beyond its state, like a bed's color, which its
     *                    item keeps too; null if it kept nothing
     * @param harvested   false to skip the check that what's held harvests the block, for a
     *                    block that broke because what held it up went
     */
    public static List<ItemData> of(Block block, NbtMap blockEntity, String held, boolean harvested) {
        String name = block.getIdentifier();
        boolean upperHalf = BlockSupport.isTwoTall(block) && block.isSet("upper_block_bit");
        if (upperHalf || block.isSet("head_piece_bit") || dropsNothing(name)) {
            return List.of();
        }
        if (harvested && !block.getMining().harvests(held)) {
            return List.of();
        }
        String item;
        int count = 1;
        Drop instead = DROPS_INSTEAD.get(name);
        if (instead != null) {
            item = instead.item();
            count = instead.count();
        } else if (name.equals("minecraft:snow_layer")) {
            item = "minecraft:snowball";
            count = block.getProperty("height") instanceof Integer height ? height + 1 : 1;
        } else {
            String single = singleSlabOf(name);
            if (single != null) {
                count = 2;
            }
            item = VanillaData.get().itemPlacing(standingKindOf(single != null ? single : name));
        }
        ItemData stack = VanillaData.get().item(item, count);
        return stack.isNull() ? List.of() : List.of(BlockEntities.dropFor(stack, blockEntity));
    }

    private static boolean dropsNothing(String name) {
        return DROPS_NOTHING.contains(name) || name.endsWith("_leaves")
                || ((name.endsWith("glass") || name.endsWith("glass_pane")) && !name.equals("minecraft:tinted_glass"));
    }

    /** The slab two of make a double slab of this name, or null if it isn't one. */
    private static String singleSlabOf(String name) {
        if (name.endsWith("double_cut_copper_slab")) {
            return name.replace("double_cut_copper_slab", "cut_copper_slab");
        }
        if (name.endsWith("_double_slab")) {
            return name.substring(0, name.length() - "_double_slab".length()) + "_slab";
        }
        return null;
    }

    /** The kind of a sign, banner or coral fan on a wall that its item places: the standing one. */
    private static String standingKindOf(String name) {
        if (name.endsWith("wall_sign")) {
            return name.substring(0, name.length() - "wall_sign".length()) + "standing_sign";
        }
        if (name.equals("minecraft:wall_banner")) {
            return "minecraft:standing_banner";
        }
        if (name.endsWith("_coral_wall_fan")) {
            return name.substring(0, name.length() - "_coral_wall_fan".length()) + "_coral_fan";
        }
        return name;
    }
}
