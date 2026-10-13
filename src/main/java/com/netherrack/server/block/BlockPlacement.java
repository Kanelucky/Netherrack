package com.netherrack.server.block;

import com.netherrack.server.world.Chunk;
import com.netherrack.server.world.World;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtMapBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Where a placed block goes and the state it takes, by the game's rules for the common cases:
 * <ul>
 *   <li>A block goes in the space beyond the clicked face, if that holds nothing a block
 *   can't replace (air, water, short grass...). A clicked block that's replaceable itself
 *   is replaced instead, as if placed on top of it.</li>
 *   <li>A slab onto the open half of the same slab, or into a space holding one, makes a
 *   double slab. Candles, sea pickles, snow, petals and turtle eggs pile onto their own kind,
 *   and glow lichen and vines spread over another face of the space they're in.</li>
 *   <li>Doors, tall plants and beds need the space beyond as well. A door hinges on its right
 *   when there's a door to its left to pair with, or a wall to its right and not its left.</li>
 *   <li>Torches, buttons, levers, ladders, plants and the like need a block to hold them:
 *   see BlockSupport. A torch that can't lean on the block it was placed against leans on a
 *   neighbour that can hold it, or stands on the floor.</li>
 *   <li>Signs, banners and coral fans placed against a side take their kind on a wall.</li>
 *   <li>Blocks that turn take their state from the face and the player: see {@link #placedState}.</li>
 *   <li>Fences, panes, walls, fence gates and stairs take their shape from the blocks around
 *   them (BlockConnections); the blocks around are brought in line once it's placed.</li>
 * </ul>
 * Nothing here changes the world: {@link #plan} says what would go where, for the caller to
 * check (no player in the way) and then set.
 */
public final class BlockPlacement {

    private static final String VERTICAL_HALF = "minecraft:vertical_half";
    private static final String CARDINAL = "minecraft:cardinal_direction";
    private static final String UPPER_BLOCK = "upper_block_bit";
    private static final String HEAD_PIECE = "head_piece_bit";

    /** Where a torch that can't lean on the block it was placed against looks for one that can hold it. */
    private static final List<Face> TORCH_SUPPORTS = List.of(Face.SOUTH, Face.WEST, Face.NORTH, Face.EAST, Face.DOWN);

    /** Blocks that turn their front to the player, from above or below as well as around. */
    private static final Set<String> FACES_PLAYER = Set.of("minecraft:dispenser", "minecraft:dropper", "minecraft:barrel");

    /** Plants of which up to four lie in one space, counted by their "growth". */
    private static final Set<String> PILES_BY_GROWTH = Set.of("minecraft:pink_petals", "minecraft:leaf_litter",
            "minecraft:wildflowers");

    /** The sides coral fans on a wall face, in the order their "coral_direction" numbers them. */
    static final List<Face> CORAL_WALL_ORDER = List.of(Face.WEST, Face.EAST, Face.NORTH, Face.SOUTH);

    /** Blocks that only go on the side of a block, never on its top or underside. */
    private static final Set<String> ON_SIDES_ONLY = Set.of("minecraft:ladder", "minecraft:tripwire_hook",
            "minecraft:cocoa");

    /** The bit for each face in glow lichen's, sculk veins' and resin clumps' "multi_face_direction_bits". */
    private static final Map<Face, Integer> MULTI_FACE_BITS = Map.of(Face.DOWN, 1, Face.UP, 2, Face.SOUTH, 4,
            Face.WEST, 8, Face.NORTH, 16, Face.EAST, 32);

    /** The bit for each side in vines' "vine_direction_bits". */
    private static final Map<Face, Integer> VINE_BITS = Map.of(Face.SOUTH, 1, Face.WEST, 2, Face.NORTH, 4, Face.EAST, 8);

    /** A block to put at a position. */
    public record Placed(Vector3i position, Block block) {
    }

    private BlockPlacement() {
    }

    /**
     * Where a block placed against a face of the clicked block would go, and in what state:
     * one or two blocks, or nothing if there's no room or nothing to hold it.
     *
     * @param clickY how far up the clicked face the player clicked, 0 to 1
     */
    public static List<Placed> plan(World world, Block block, Vector3i clicked, Face face, float clickY, Placer placer) {
        Block clickedBlock = at(world, clicked);
        if (clickedBlock == null || clickedBlock == Blocks.AIR) {
            return List.of(); // the client clicked a block the world doesn't have
        }
        String name = block.getIdentifier();
        if (Scaffolding.is(block) && Scaffolding.buildsOn(world, clicked, face)) {
            return scaffoldingPlan(world, block, Scaffolding.placement(world, clicked, face));
        }

        // Onto the open half of the same slab, or onto a pile of the same thing.
        if (clickedBlock.getIdentifier().equals(name)) {
            Face openFace = "top".equals(clickedBlock.getProperty(VERTICAL_HALF)) ? Face.DOWN : Face.UP;
            Block doubled = face == openFace ? doubleSlabOf(clickedBlock) : null;
            if (doubled != null) {
                return List.of(new Placed(clicked, doubled));
            }
            Block piled = piledOnto(clickedBlock, face);
            if (piled != null) {
                return List.of(new Placed(clicked, piled));
            }
        }

        boolean replacing = BlockShapes.isReplaceable(clickedBlock)
                && !(clickedBlock.getIdentifier().equals(name) && name.equals("minecraft:snow_layer"));
        Vector3i target = replacing ? clicked : face.from(clicked);
        if (!inWorld(target)) {
            return List.of();
        }
        Block targetBlock = at(world, target);
        if (targetBlock != null && targetBlock.getIdentifier().equals(name)) {
            Block combined = doubleSlabOf(targetBlock);
            if (combined == null) {
                combined = piledOnto(targetBlock, face);
            }
            if (combined == null) {
                combined = spreadOver(targetBlock, face);
            }
            if (combined != null) {
                return List.of(new Placed(target, combined));
            }
        }
        if (!BlockShapes.isReplaceable(targetBlock)) {
            return List.of();
        }

        Face placedOn = replacing ? Face.UP : face;
        if (ON_SIDES_ONLY.contains(name) && !placedOn.isHorizontal()) {
            return List.of();
        }
        Block state = block;
        if (placedOn.isHorizontal()) {
            Block onWall = wallVariantOf(block);
            if (onWall != null) {
                state = onWall;
            }
        }
        if ((state.getIdentifier().endsWith("standing_sign") || state.getIdentifier().endsWith("standing_banner"))
                && placedOn != Face.UP) {
            return List.of();
        }
        state = placedState(state, target, placedOn, clickY, placer);
        if (state.hasProperty("torch_facing_direction")) {
            state = torchAt(world, target, placedOn, state);
        } else if (state.getIdentifier().endsWith("lantern") && state.hasProperty("hanging")) {
            state = lanternAt(world, target, placedOn, state);
        } else if (state.getIdentifier().equals("minecraft:bell")) {
            state = bellAt(world, target, placedOn, state);
        }
        if (state == null || !BlockSupport.standsAt(world, target, state)) {
            return List.of();
        }
        if (state.hasProperty("door_hinge_bit")) {
            Block hinged = state.with("door_hinge_bit", Block.bit(hingesRight(world, target, placer.facing())));
            if (hinged != null) {
                state = hinged;
            }
        }
        state = BlockConnections.joined(world, target, state);

        // Blocks that take two spaces: tall plants and doors reach up, and beds the way the player faces.
        if (BlockSupport.isTwoTall(state) && !state.isSet(UPPER_BLOCK)) {
            Vector3i above = Face.UP.from(target);
            Block upper = state.with(UPPER_BLOCK, Block.bit(true));
            if (upper == null || !inWorld(above) || !BlockShapes.isReplaceable(at(world, above))) {
                return List.of();
            }
            return List.of(new Placed(target, state), new Placed(above, upper));
        }
        if (state.hasProperty(HEAD_PIECE) && !state.isSet(HEAD_PIECE)) {
            Vector3i headAt = placer.facing().from(target);
            Block head = state.with(HEAD_PIECE, Block.bit(true));
            if (head == null || !BlockShapes.isReplaceable(at(world, headAt))
                    || BlockShapes.isReplaceable(at(world, Face.DOWN.from(headAt)))) {
                return List.of();
            }
            return List.of(new Placed(target, state), new Placed(headAt, head));
        }
        return List.of(new Placed(target, state));
    }

    /**
     * Scaffolding built onto scaffolding, at the target Scaffolding found for it, if there's
     * room there and it's in reach of something to hold it up.
     */
    private static List<Placed> scaffoldingPlan(World world, Block scaffolding, Vector3i target) {
        if (target == null || !inWorld(target) || !BlockShapes.isReplaceable(at(world, target))
                || Scaffolding.distance(world, target) >= Scaffolding.MAX_DISTANCE) {
            return List.of();
        }
        return List.of(new Placed(target, Scaffolding.shaped(world, target, scaffolding)));
    }

    /**
     * The state a block takes once placed against a face by a player who clicked clickY up
     * it. Blocks that turn follow the game's rules for the properties they have:
     * <ul>
     *   <li>pillars, like logs, lie along the face's axis;</li>
     *   <li>slabs, stairs and trapdoors take the top half of a space when the player clicks a
     *   block's underside or the top half of its side, and stairs climb the way they face;</li>
     *   <li>blocks with a front, like furnaces, chests and glazed terracotta, turn it to the
     *   player; doors and fence gates face the way the player does, trapdoors hinge away from
     *   them, and anvils lie across the way they face. Pistons, dispensers, droppers, barrels
     *   and crafters face the player from above or below too, observers look away from them,
     *   and hoppers into the block they were placed against;</li>
     *   <li>attachments, like buttons, levers, ladders and wall signs, point out of the face,
     *   and a lever on a floor or ceiling lies the way the player faces;</li>
     *   <li>standing signs and banners turn to the player in sixteen steps;</li>
     *   <li>leaves a player places never decay.</li>
     * </ul>
     * Any other property keeps the item's value, as does a block whose rules give a state that
     * doesn't exist.
     */
    public static Block placedState(Block block, Vector3i position, Face face, float clickY, Placer placer) {
        String name = block.getIdentifier();
        Face facing = placer.facing();
        boolean top = face == Face.DOWN || (clickY > 0.5f && face != Face.UP);
        NbtMap states = block.getStates();
        NbtMapBuilder placed = states.toBuilder();
        for (String property : states.keySet()) {
            Object value = switch (property) {
                case "pillar_axis" -> face.axis();
                case VERTICAL_HALF -> top ? "top" : "bottom";
                case "upside_down_bit" -> Block.bit(top);
                case "weirdo_direction" -> BlockShapes.STAIRS_CLIMB.indexOf(facing);
                case CARDINAL -> cardinal(name, face, facing).id();
                case "direction" -> direction(name, face, facing);
                case "facing_direction" -> facingDirection(name, face, facing, placer.facingFrom(position), states);
                case "minecraft:facing_direction" -> placer.facingFrom(position).opposite().id();
                case "orientation" -> crafterOrientation(placer.facingFrom(position), facing);
                case "minecraft:block_face" -> face.id();
                case "lever_direction" -> face.isHorizontal() ? face.id()
                        : face.id() + (facing.axis().equals("x") ? "_east_west" : "_north_south");
                case "ground_sign_direction" -> placer.rotationTowards();
                case "attachment" -> face == Face.UP ? "standing" : face == Face.DOWN ? "hanging" : "side";
                case "hanging" -> Block.bit(face == Face.DOWN);
                case "persistent_bit" -> Block.bit(true);
                case "rail_direction" -> facing.axis().equals("x") ? 1 : 0;
                case "coral_direction" -> CORAL_WALL_ORDER.contains(face) ? CORAL_WALL_ORDER.indexOf(face)
                        : states.get(property);
                case "coral_fan_direction" -> facing.axis().equals("x") ? 1 : 0;
                case "multi_face_direction_bits" -> MULTI_FACE_BITS.getOrDefault(face.opposite(), 0);
                case "vine_direction_bits" -> VINE_BITS.getOrDefault(face.opposite(), 0);
                default -> states.get(property);
            };
            placed.put(property, value);
        }
        Block found = Blocks.find(name, placed.build());
        return found != null ? found : block;
    }

    /** Which side a block's "minecraft:cardinal_direction" names, for one placed by a player facing this way. */
    private static Face cardinal(String name, Face face, Face facing) {
        if (name.endsWith("_door")) {
            return facing.clockwise();
        }
        if (name.endsWith("fence_gate") || name.equals("minecraft:straw_bed")) {
            return facing;
        }
        if (name.endsWith("anvil")) {
            return facing.counterClockwise();
        }
        if (name.equals("minecraft:shelf_mushroom")) {
            return face.isHorizontal() ? face : facing.opposite();
        }
        return facing.opposite();
    }

    /** A block's "direction", which numbers the sides around in the older order. */
    private static int direction(String name, Face face, Face facing) {
        if (name.endsWith("trapdoor")) {
            // A trapdoor numbers the way it faces, from its hinge out, as stairs number the ways they climb.
            return BlockShapes.STAIRS_CLIMB.indexOf(facing.opposite());
        }
        if (name.equals("minecraft:bed")) {
            return facing.legacyIndex();
        }
        if (name.equals("minecraft:cocoa")) {
            // Cocoa names the log it hangs from.
            return face.opposite().legacyIndex();
        }
        if (name.equals("minecraft:tripwire_hook") || name.equals("minecraft:grindstone") || name.equals("minecraft:bell")) {
            return (face.isHorizontal() ? face : facing.opposite()).legacyIndex();
        }
        return facing.opposite().legacyIndex();
    }

    /** A block's "facing_direction", numbered as the client numbers faces. */
    private static Object facingDirection(String name, Face face, Face facing, Face towardsPlayer, NbtMap states) {
        if (name.endsWith("glazed_terracotta")) {
            return facing.opposite().ordinal();
        }
        if (name.endsWith("piston")) {
            return storedReversed(towardsPlayer);
        }
        if (FACES_PLAYER.contains(name)) {
            return towardsPlayer.ordinal();
        }
        if (name.equals("minecraft:hopper")) {
            return (face.isHorizontal() ? face.opposite() : Face.DOWN).ordinal();
        }
        if (name.equals("minecraft:end_rod")) {
            return storedReversed(face);
        }
        if (name.endsWith("_stem")) {
            return states.get("facing_direction");
        }
        return face.ordinal();
    }

    /** A face as end rods and pistons store it: up and down as other blocks do, the four sides around reversed. */
    private static int storedReversed(Face face) {
        return face.isHorizontal() ? face.opposite().ordinal() : face.ordinal();
    }

    /** A crafter's orientation: its front, then its top - up for one facing a player beside it. */
    private static String crafterOrientation(Face front, Face facing) {
        Face top = switch (front) {
            case DOWN -> facing.opposite();
            case UP -> facing;
            default -> Face.UP;
        };
        return front.id() + "_" + top.id();
    }

    /** The kind of a sign, banner or coral fan that goes on a wall, or null for a block that has none. */
    private static Block wallVariantOf(Block block) {
        String name = block.getIdentifier();
        String wall = null;
        if (name.endsWith("standing_sign")) {
            wall = name.substring(0, name.length() - "standing_sign".length()) + "wall_sign";
        } else if (name.equals("minecraft:standing_banner")) {
            wall = "minecraft:wall_banner";
        } else if (name.endsWith("_coral_fan")) {
            wall = name.substring(0, name.length() - "_coral_fan".length()) + "_coral_wall_fan";
        }
        return wall != null ? Blocks.byName(wall) : null;
    }

    /**
     * A torch leaning on the block it was placed against, if that can hold it; if not, on the
     * first neighbour that can, ending with the floor. Null if nothing around can hold it.
     */
    private static Block torchAt(World world, Vector3i position, Face face, Block torch) {
        List<Face> candidates = new ArrayList<>();
        // Torches don't hang from ceilings.
        if (face != Face.DOWN) {
            candidates.add(face.opposite());
        }
        candidates.addAll(TORCH_SUPPORTS);
        for (Face side : candidates) {
            Block neighbour = at(world, side.from(position));
            boolean holds = side == Face.DOWN ? BlockShapes.holdsTorchOnTop(neighbour)
                    : BlockShapes.hasFullFace(neighbour, side.opposite());
            if (holds) {
                // A torch names the side it leans on, or "top" when it stands on the floor.
                return torch.with("torch_facing_direction", side == Face.DOWN ? "top" : side.id());
            }
        }
        return null;
    }

    /**
     * A lantern hanging from the block above when placed against its underside, or when
     * there's nothing below to stand on, and standing otherwise. Null if neither holds it.
     */
    private static Block lanternAt(World world, Vector3i position, Face face, Block lantern) {
        boolean below = BlockShapes.holdsTorchOnTop(at(world, Face.DOWN.from(position)));
        boolean above = BlockSupport.holdsCenterBelow(at(world, Face.UP.from(position)));
        boolean hanging;
        if (face == Face.DOWN && above) {
            hanging = true;
        } else if (below) {
            hanging = false;
        } else if (above) {
            hanging = true;
        } else {
            return null;
        }
        return lantern.with("hanging", Block.bit(hanging));
    }

    /** A bell standing on a floor, hanging from a ceiling, or fixed to a wall - or to walls on both sides. */
    private static Block bellAt(World world, Vector3i position, Face face, Block bell) {
        String attachment = switch (face) {
            case UP -> "standing";
            case DOWN -> "hanging";
            default -> BlockShapes.isReplaceable(at(world, face.from(position))) ? "side" : "multiple";
        };
        Block attached = bell.with("attachment", attachment);
        return attached != null ? attached : bell;
    }

    /** Whether a door hinges on its right: a door to its left to pair with, or a wall to its right and not its left. */
    private static boolean hingesRight(World world, Vector3i position, Face facing) {
        Block left = at(world, facing.counterClockwise().from(position));
        Block right = at(world, facing.clockwise().from(position));
        return (left != null && left.getIdentifier().endsWith("_door"))
                || (BlockShapes.isFullCube(right) && !BlockShapes.isFullCube(left));
    }

    /** The double slab two of this slab make, or null if it isn't a slab that does. */
    private static Block doubleSlabOf(Block slab) {
        String name = slab.getIdentifier();
        Object half = slab.getProperty(VERTICAL_HALF);
        if (half == null || !name.endsWith("_slab") || name.contains("double")) {
            return null;
        }
        // Copper slabs put "double" first.
        String doubled = name.endsWith("cut_copper_slab")
                ? name.replace("cut_copper_slab", "double_cut_copper_slab")
                : name.substring(0, name.length() - "_slab".length()) + "_double_slab";
        NbtMapBuilder states = NbtMap.builder();
        states.put(VERTICAL_HALF, half);
        return Blocks.find(doubled, states.build());
    }

    /**
     * One more of a block piled onto it: candles, sea pickles, turtle eggs, petals, leaf litter
     * and wildflowers, up to four, and snow up to eight layers - snow only onto its top. Null if
     * it doesn't pile up, or there's no room.
     */
    private static Block piledOnto(Block existing, Face face) {
        if (existing.getProperty("candles") instanceof Integer candles) {
            return candles < 3 ? existing.with("candles", candles + 1) : null;
        }
        if (existing.getProperty("cluster_count") instanceof Integer count) {
            return count < 3 ? existing.with("cluster_count", count + 1) : null;
        }
        String name = existing.getIdentifier();
        if (name.equals("minecraft:snow_layer") && existing.getProperty("height") instanceof Integer height) {
            return face == Face.UP && height < 7 ? existing.with("height", height + 1) : null;
        }
        if (PILES_BY_GROWTH.contains(name) && existing.getProperty("growth") instanceof Integer growth) {
            return growth < 3 ? existing.with("growth", growth + 1) : null;
        }
        if (name.equals("minecraft:turtle_egg") && existing.getProperty("turtle_egg_count") instanceof String count) {
            List<String> counts = List.of("one_egg", "two_egg", "three_egg", "four_egg");
            int next = counts.indexOf(count) + 1;
            return next > 0 && next < counts.size() ? existing.with("turtle_egg_count", counts.get(next)) : null;
        }
        return null;
    }

    /**
     * Glow lichen, sculk veins, resin clumps or vines spread over one more face of their space:
     * the one the player placed against. Null if they cover it already, or aren't those.
     */
    private static Block spreadOver(Block existing, Face face) {
        String property;
        Map<Face, Integer> bits;
        if (existing.hasProperty("multi_face_direction_bits")) {
            property = "multi_face_direction_bits";
            bits = MULTI_FACE_BITS;
        } else if (existing.hasProperty("vine_direction_bits")) {
            property = "vine_direction_bits";
            bits = VINE_BITS;
        } else {
            return null;
        }
        int value = (Integer) existing.getProperty(property);
        Integer bit = bits.get(face.opposite());
        if (bit == null || (value & bit) != 0) {
            return null;
        }
        return existing.with(property, value | bit);
    }

    private static boolean inWorld(Vector3i position) {
        return position.getY() >= Chunk.MIN_Y && position.getY() <= Chunk.MAX_Y;
    }

    private static Block at(World world, Vector3i position) {
        if (!inWorld(position)) {
            return null;
        }
        Block block = world.getBlock(position.getX(), position.getY(), position.getZ());
        return block != null ? block : Blocks.AIR;
    }
}
