package mindy;

import arc.math.geom.Geometry;
import mindustry.content.Blocks;
import mindustry.entities.units.BuildPlan;
import mindustry.gen.Building;
import mindustry.gen.Unit;
import mindustry.type.Item;
import mindustry.world.Block;
import mindustry.world.Build;
import mindustry.world.Tile;
import mindustry.world.blocks.distribution.Conveyor.ConveyorBuild;
import mindustry.world.blocks.production.Drill;
import mindustry.world.blocks.production.Drill.DrillBuild;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;

import static mindustry.Vars.*;

final class MiningPlanner {
    private record Route(Tile tile, int rotation, Route downstream, int length) {}
    private static final int maxNewBelts = 18;
    private static final int maxNetworkDistance = 32;

    static BuildProject plan(Unit unit, Building core) {
        var drill = (Drill) Blocks.mechanicalDrill;
        var belt = Blocks.conveyor;
        if (!drill.unlockedNowHost() || !belt.unlockedNowHost()
            || !drill.environmentBuildable() || !belt.environmentBuildable()) return null;

        var frontier = connectedInputs(core);
        var visited = new HashSet<Integer>();
        var connected = new HashSet<Building>();
        for (Route input : frontier) connected.add(input.tile.build);
        float[] incoming = new float[content.items().size];
        indexer.eachBlock(unit.team, core.x, core.y, maxNetworkDistance * tilesize,
            block -> block instanceof DrillBuild drillBuild && drillBuild.dominantItem != null,
            block -> {
                var existing = (DrillBuild) block;
                if (existing.proximity.contains(connected::contains)) {
                    incoming[existing.dominantItem.id] += rate((Drill) existing.block,
                        existing.dominantItem, existing.dominantItems);
                }
            });

        BuildProject best = null;
        float bestScore = Float.NEGATIVE_INFINITY;
        while (!frontier.isEmpty()) {
            Route end = frontier.remove();
            for (int direction = 0; direction < 4; direction++) {
                if ((end.length > 0 || end.tile.build instanceof ConveyorBuild)
                    && ((direction + 2) & 3) == end.rotation) continue;
                var offset = Geometry.d4(direction);
                Tile neighbor = world.tile(end.tile.x - offset.x, end.tile.y - offset.y);
                if (neighbor != null && end.length > 0 && neighbor.build instanceof DrillBuild existing
                    && existing.team == unit.team && existing.dominantItem != null
                    && existing.proximity.count(other -> other.team == unit.team && other.block.hasItems) == 0) {
                    Item item = existing.dominantItem;
                    float score = score(core, item, rate((Drill) existing.block, item, existing.dominantItems),
                        incoming[item.id], end.length) + 5f;
                    if (score > bestScore && useful(core, item)) {
                        var candidate = new BuildProject("connection for an existing " + item.name + " drill", belts(end));
                        if (candidate.affordable(unit, core, null, 0)) {
                            best = candidate;
                            bestScore = score;
                        }
                    }
                }
                if (!empty(neighbor)) continue;

                // A size-two drill can touch this edge in either of two alignments.
                for (int alignment = 0; alignment < 2; alignment++) {
                    int x = neighbor.x - (offset.x > 0 ? 1 : offset.x == 0 ? alignment : 0);
                    int y = neighbor.y - (offset.y > 0 ? 1 : offset.y == 0 ? alignment : 0);
                    Item item = null;
                    int oreTiles = 0;
                    boolean clear = true;
                    for (int dx = 0; dx < 2; dx++) {
                        for (int dy = 0; dy < 2; dy++) {
                            Tile tile = world.tile(x + dx, y + dy);
                            if (!empty(tile) || overlapsRoute(tile, end)) {
                                clear = false;
                                continue;
                            }
                            Item drop = tile.drop();
                            if (drop == null || drop.hardness > drill.tier) continue;
                            if (item != null && item != drop) clear = false;
                            item = drop;
                            oreTiles++;
                        }
                    }
                    if (!clear || item == null || !useful(core, item)
                        || !Build.validPlace(drill, unit.team, x, y, 0)) continue;
                    float score = score(core, item, rate(drill, item, oreTiles), incoming[item.id], end.length);
                    if (score <= bestScore) continue;
                    var plans = belts(end);
                    plans.add(new BuildPlan(x, y, 0, drill));
                    var candidate = new BuildProject(item.name + " mining line", plans);
                    if (!candidate.affordable(unit, core, null, 0)) continue;
                    best = candidate;
                    bestScore = score;
                }

                if (end.length < maxNewBelts && neighbor.within(core, maxNetworkDistance * tilesize)
                    && Build.validPlace(belt, unit.team, neighbor.x, neighbor.y, direction)
                    && visited.add(neighbor.pos())) {
                    frontier.add(new Route(neighbor, direction, end, end.length + 1));
                }
            }
        }
        return best;
    }

    private static ArrayDeque<Route> connectedInputs(Building core) {
        var search = new ArrayDeque<Route>();
        var inputs = new ArrayDeque<Route>();
        var visited = new HashSet<Integer>();
        core.tile.getLinkedTiles(tile -> search.add(new Route(tile, 0, null, 0)));
        while (!search.isEmpty()) {
            Route input = search.remove();
            inputs.add(input);
            for (var offset : Geometry.d4) {
                Tile adjacent = world.tile(input.tile.x + offset.x, input.tile.y + offset.y);
                if (adjacent != null && adjacent.build instanceof ConveyorBuild conveyor
                    && conveyor.team == core.team && conveyor.enabled && conveyor.front() == input.tile.build
                    && adjacent.within(core, maxNetworkDistance * tilesize) && visited.add(adjacent.pos())) {
                    search.add(new Route(adjacent, conveyor.rotation, null, 0));
                }
            }
        }
        return inputs;
    }

    private static ArrayList<BuildPlan> belts(Route end) {
        var plans = new ArrayList<BuildPlan>();
        for (Route part = end; part.length > 0; part = part.downstream) {
            plans.add(new BuildPlan(part.tile.x, part.tile.y, part.rotation, Blocks.conveyor));
        }
        Collections.reverse(plans);
        return plans;
    }

    private static boolean useful(Building core, Item item) {
        return !item.lowPriority && core.items.get(item) < Math.min(240, core.getMaximumAccepted(item));
    }

    private static float rate(Drill drill, Item item, int tiles) {
        return 60f * tiles / drill.getDrillTime(item);
    }

    private static float score(Building core, Item item, float rate, float incoming, int belts) {
        float deficit = 1f - core.items.get(item) / 240f;
        return 12f * deficit + 10f * rate / (0.35f + incoming) - belts * 0.65f;
    }

    private static boolean empty(Tile tile) {
        return tile != null && tile.block() == Blocks.air;
    }

    static boolean clearFootprint(BuildPlan plan) {
        Block block = plan.block;
        for (int dx = 0; dx < block.size; dx++) {
            for (int dy = 0; dy < block.size; dy++) {
                if (!empty(world.tile(plan.x + dx + block.sizeOffset, plan.y + dy + block.sizeOffset))) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean overlapsRoute(Tile tile, Route route) {
        for (Route part = route; part != null; part = part.downstream) {
            if (part.tile == tile) return true;
        }
        return false;
    }

}
