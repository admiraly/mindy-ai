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
import mindustry.world.blocks.production.Drill;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

import static mindustry.Vars.*;

final class MiningPlanner {
    record Project(Item item, List<BuildPlan> plans) {}
    private record Route(Tile tile, int rotation, Route downstream, int length) {}
    private static final int maxBelts = 8;

    static Project plan(Unit unit, Building core) {
        var drill = (Drill) Blocks.mechanicalDrill;
        var belt = Blocks.conveyor;
        if (!drill.unlockedNowHost() || !belt.unlockedNowHost()
            || !drill.environmentBuildable() || !belt.environmentBuildable()) return null;

        var nearbyProduction = new HashSet<Item>();
        indexer.eachBlock(unit.team, core.x, core.y, 320f,
            block -> block instanceof Drill.DrillBuild mining && mining.dominantItem != null,
            block -> nearbyProduction.add(((Drill.DrillBuild) block).dominantItem));

        var frontier = new ArrayDeque<Route>();
        var visited = new HashSet<Integer>();
        core.tile.getLinkedTiles(tile -> frontier.add(new Route(tile, 0, null, 0)));
        Project best = null;
        float bestScore = Float.NEGATIVE_INFINITY;
        while (!frontier.isEmpty()) {
            Route end = frontier.remove();
            for (int direction = 0; direction < 4; direction++) {
                var offset = Geometry.d4(direction);
                Tile neighbor = world.tile(end.tile.x - offset.x, end.tile.y - offset.y);
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
                    if (!clear || item == null || nearbyProduction.contains(item)
                        || !Build.validPlace(drill, unit.team, x, y, 0)
                        || !affordable(unit, core, end.length)) continue;
                    float score = oreTiles * 4f - end.length - Math.min(core.items.get(item), 200) / 25f;
                    if (score <= bestScore) continue;
                    var plans = new ArrayList<BuildPlan>();
                    for (Route part = end; part.length > 0; part = part.downstream) {
                        plans.add(new BuildPlan(part.tile.x, part.tile.y, part.rotation, belt));
                    }
                    Collections.reverse(plans);
                    plans.add(new BuildPlan(x, y, 0, drill));
                    best = new Project(item, plans);
                    bestScore = score;
                }

                if (end.length < maxBelts && Build.validPlace(belt, unit.team, neighbor.x, neighbor.y, direction)
                    && visited.add(neighbor.pos())) {
                    frontier.add(new Route(neighbor, direction, end, end.length + 1));
                }
            }
        }
        return best;
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

    private static boolean affordable(Unit unit, Building core, int belts) {
        if (state.rules.infiniteResources || unit.team.rules().infiniteResources) return true;
        int[] cost = new int[content.items().size];
        for (var stack : Blocks.mechanicalDrill.requirements) {
            cost[stack.item.id] += Math.round(stack.amount * state.rules.buildCostMultiplier);
        }
        for (var stack : Blocks.conveyor.requirements) {
            cost[stack.item.id] += belts * Math.round(stack.amount * state.rules.buildCostMultiplier);
        }
        for (Item item : content.items()) {
            if (core.items.get(item) < cost[item.id]) return false;
        }
        return true;
    }
}
