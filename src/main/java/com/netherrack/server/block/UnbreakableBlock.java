package com.netherrack.server.block;

import org.cloudburstmc.nbt.NbtMap;

import java.util.Set;

/**
 * A block nobody can break outside creative mode: bedrock, barriers, command blocks,
 * portals and the like. Vanilla gives these a hardness of -1. The mining data gives most of
 * them that too, but not all - allow, deny and border blocks, and invisible bedrock, come
 * as 0 there - so they're listed here as well.
 */
public class UnbreakableBlock extends Block {

    private static final Set<String> IDENTIFIERS = Set.of(
            "minecraft:bedrock", "minecraft:invisible_bedrock", "minecraft:barrier",
            "minecraft:command_block", "minecraft:chain_command_block", "minecraft:repeating_command_block",
            "minecraft:structure_block", "minecraft:jigsaw", "minecraft:allow", "minecraft:deny",
            "minecraft:border_block", "minecraft:end_portal_frame", "minecraft:end_portal",
            "minecraft:end_gateway", "minecraft:portal", "minecraft:moving_block");

    public UnbreakableBlock(NbtMap state) {
        super(state);
    }

    /** Whether blocks with this identifier are unbreakable. */
    public static boolean isUnbreakable(String identifier) {
        return IDENTIFIERS.contains(identifier) || identifier.startsWith("minecraft:light_block_");
    }

    @Override
    public double getHardness() {
        return -1;
    }
}
