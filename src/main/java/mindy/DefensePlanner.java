package mindy;

import arc.math.Angles;
import arc.util.Log;
import mindustry.content.Blocks;
import mindustry.content.Items;
import mindustry.entities.units.BuildPlan;
import mindustry.game.Schematic;
import mindustry.game.Schematics;
import mindustry.gen.Building;
import mindustry.gen.Unit;
import mindustry.world.Build;
import mindustry.world.Tile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static mindustry.Vars.*;

final class DefensePlanner {
    private final List<Schematic> blueprints = new ArrayList<>();
    private boolean loaded;

    BuildProject plan(Unit unit, Building core) {
        if (!state.rules.waves || !Blocks.duo.unlockedNowHost() || !Blocks.mechanicalDrill.unlockedNowHost()) return null;
        Tile spawn = spawner.getSpawns().min(tile -> tile.dst2(core.x, core.y));
        if (spawn == null || indexer.eachBlock(unit.team, core.x, core.y, 160f,
            block -> block.block == Blocks.duo, ignored -> {})) return null;

        if (!loaded) {
            loaded = true;
            load("fortified-outpost");
            load("copper-outpost");
        }
        BuildProject best = null;
        float bestScore = Float.NEGATIVE_INFINITY;
        float front = core.angleTo(spawn);

        for (Schematic blueprint : blueprints) {
            if (blueprint.tiles.contains(tile -> !tile.block.unlockedNowHost() || !tile.block.environmentBuildable())) {
                continue;
            }
            for (int rotation = 0; rotation < 4; rotation++) {
                if (Angles.angleDist(front, rotation * 90f) > 60f) continue;
                Schematic layout = Schematics.rotate(blueprint, rotation);
                var budget = new ArrayList<BuildPlan>();
                for (var tile : layout.tiles) budget.add(new BuildPlan(0, 0, 0, tile.block));
                if (!new BuildProject("", budget).affordable(unit, core, Items.copper, 20)) continue;

                for (int dx = -16; dx <= 16; dx++) {
                    for (int dy = -16; dy <= 16; dy++) {
                        Tile anchor = world.tile(core.tileX() + dx, core.tileY() + dy);
                        if (anchor == null || !anchor.within(core, 128f) || anchor.within(core, 48f)
                            || anchor.dst2(spawn) >= core.dst2(spawn)) continue;

                        List<BuildPlan> plans = place(layout, anchor, unit);
                        if (plans == null) continue;
                        float score = plans.size() * 6f - Math.abs(anchor.dst(core) - 88f) / tilesize
                            - Angles.angleDist(core.angleTo(anchor), front) / 10f;
                        if (score > bestScore) {
                            best = new BuildProject(blueprint.name(), plans);
                            bestScore = score;
                        }
                    }
                }
            }
        }
        return best;
    }

    private List<BuildPlan> place(Schematic layout, Tile anchor, Unit unit) {
        var plans = new ArrayList<BuildPlan>();
        for (var tile : layout.tiles) {
            int x = anchor.x + tile.x - layout.width / 2;
            int y = anchor.y + tile.y - layout.height / 2;
            var plan = new BuildPlan(x, y, tile.block.rotate ? tile.rotation : 0, tile.block, tile.config);
            if (!MiningPlanner.clearFootprint(plan) || !Build.validPlace(plan.block, unit.team, x, y, plan.rotation)) {
                return null;
            }
            if (tile.block == Blocks.mechanicalDrill) {
                int copperTiles = 0;
                for (int dx = 0; dx < 2; dx++) {
                    for (int dy = 0; dy < 2; dy++) {
                        var ore = world.tile(x + dx, y + dy).drop();
                        if (ore == Items.copper) copperTiles++;
                        else if (ore != null && ore.hardness <= 2) return null;
                    }
                }
                if (copperTiles < 2) return null;
            }
            plans.add(plan);
        }
        return plans;
    }

    private void load(String name) {
        try (var resource = DefensePlanner.class.getResourceAsStream("/blueprints/" + name + ".msch")) {
            if (resource == null) throw new IOException("missing schematic");
            blueprints.add(Schematics.read(resource));
        } catch (IOException error) {
            Log.err("Mindy could not load blueprint @: @", name, error.getMessage());
        }
    }
}
