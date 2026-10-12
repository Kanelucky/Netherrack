package com.netherrack.server.block;

/**
 * The cobblestone block ("minecraft:cobblestone" in Bedrock).
 */
public class CobblestoneBlock extends Block {

    public CobblestoneBlock() {
        super("minecraft:cobblestone");
    }

    @Override
    public float getHardness() {
        return 2f;
    }

    @Override
    public boolean requiresTool() {
        return true;
    }
}
