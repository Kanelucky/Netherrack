package com.netherrack.server.block;

import org.jose4j.json.JsonUtil;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * How a block takes to mining, from minecraft-data (see data/LICENSE-minecraft-data.txt):
 * its hardness, which sets how long it takes to break, the kind of tool that digs it
 * quicker, the least tier of that tool it drops anything for (null if anything does), and
 * whether it's solid - a whole box to bump into - or something you walk through, like
 * flowers and torches. A negative hardness means only creative players break it.
 */
public record Mining(double hardness, Tool tool, Integer harvestTier, boolean solid) {

    public enum Tool { PICKAXE, AXE, SHOVEL, HOE, SWORD, SHEARS }

    /** Tool tiers, as their items' names start, with how fast each digs and the tier of block it harvests. */
    private enum Tier {
        WOODEN(2, 0), GOLDEN(12, 0), STONE(4, 1), COPPER(5, 1), IRON(6, 2), DIAMOND(8, 3), NETHERITE(9, 4);

        final double speed;
        final int level;

        Tier(double speed, int level) {
            this.speed = speed;
            this.level = level;
        }
    }

    /** What a block nobody has data for takes: a typical block's mining, with no tool needed. */
    private static final Mining UNKNOWN = new Mining(1, null, null, true);

    private static final Map<String, Mining> TABLE = load();

    /**
     * The mining of the block with this identifier. Blocks the data is a version behind on,
     * like the wool and concrete slabs and stairs defined in data, mine like the block they're
     * made of.
     */
    public static Mining of(String identifier) {
        Mining mining = TABLE.get(identifier);
        if (mining != null) {
            return mining;
        }
        for (String suffix : new String[]{"_double_slab", "_slab", "_stairs"}) {
            if (identifier.endsWith(suffix)) {
                Mining base = TABLE.get(identifier.substring(0, identifier.length() - suffix.length()));
                if (base != null) {
                    return base;
                }
            }
        }
        return UNKNOWN;
    }

    public boolean isUnbreakable() {
        return hardness < 0;
    }

    /**
     * How many ticks a player holding this item (null for an empty hand) takes to break
     * the block, by the game's reckoning: a tool of the kind that suits the block digs at its
     * tier's speed, anything else at 1, and each tick does that speed over the hardness - a
     * thirtieth of it when what's held harvests the block, a hundredth when it doesn't. 0 for
     * a block that breaks at once. Nothing that speeds or slows mining is counted yet.
     */
    public int breakTicks(String held) {
        Tool kind = toolOf(held);
        Tier tier = tierOf(held);
        double speed = 1;
        if (kind != null && kind == tool) {
            speed = switch (kind) {
                case SWORD -> 15;
                case SHEARS -> 5;
                default -> tier != null ? tier.speed : 1;
            };
        }
        double ticks = hardness * (harvests(held) ? 30 : 100) / speed;
        // Hardness comes as a float a hair over its true value, which mustn't round up to another tick.
        return ticks < 1 ? 0 : (int) Math.ceil(ticks - 1e-4);
    }

    /**
     * Whether a player holding this item (null for an empty hand) gets the block's drops when
     * they break it: anyone does, unless it needs a tool of some kind and tier.
     */
    public boolean harvests(String held) {
        if (harvestTier == null) {
            return true;
        }
        Tool kind = toolOf(held);
        // Cobwebs need a sword, or shears.
        if (tool == Tool.SWORD && kind == Tool.SHEARS) {
            return true;
        }
        Tier tier = tierOf(held);
        return tool != null && kind == tool && (tier != null ? tier.level : 0) >= harvestTier;
    }

    /** The kind of tool an item is, by its name ("minecraft:stone_pickaxe"), or null if it's none. */
    private static Tool toolOf(String item) {
        if (item == null) {
            return null;
        }
        String name = item.substring(item.indexOf(':') + 1);
        if (name.equals("shears")) {
            return Tool.SHEARS;
        }
        for (Tool kind : Tool.values()) {
            if (name.endsWith("_" + kind.name().toLowerCase(Locale.ROOT))) {
                return kind;
            }
        }
        return null;
    }

    private static Tier tierOf(String item) {
        if (item == null) {
            return null;
        }
        String name = item.substring(item.indexOf(':') + 1);
        for (Tier tier : Tier.values()) {
            if (name.startsWith(tier.name().toLowerCase(Locale.ROOT) + "_")) {
                return tier;
            }
        }
        return null;
    }

    private static Map<String, Mining> load() {
        String resource = "/data/block_mining.json";
        try (InputStream in = Mining.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Missing bundled resource: " + resource);
            }
            Map<String, Object> json = JsonUtil.parseJson(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            Map<String, Mining> table = new HashMap<>();
            ((Map<?, ?>) json.get("blocks")).forEach((name, value) -> {
                Map<?, ?> entry = (Map<?, ?>) value;
                Tool tool = entry.get("tool") instanceof String kind ? Tool.valueOf(kind.toUpperCase(Locale.ROOT)) : null;
                Integer tier = entry.get("tier") instanceof Number number ? number.intValue() : null;
                table.put((String) name, new Mining(((Number) entry.get("hardness")).doubleValue(), tool, tier,
                        !Boolean.FALSE.equals(entry.get("solid"))));
            });
            return table;
        } catch (IOException | org.jose4j.lang.JoseException e) {
            throw new IllegalStateException("Failed to read " + resource, e);
        }
    }
}
