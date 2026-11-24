package mindy;

import mindustry.entities.units.BuildPlan;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.gen.Unit;
import mindustry.world.Build;
import mindustry.world.Tile;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;

final class ProjectRecovery {
    private record RecordedBlock(Team team, BuildPlan plan) {}

    private final HashMap<Integer, RecordedBlock> installed = new HashMap<>();
    private final LinkedHashSet<Integer> damaged = new LinkedHashSet<>();

    void clear() {
        installed.clear();
        damaged.clear();
    }

    void remember(Team team, BuildPlan plan) {
        int position = plan.tile().pos();
        installed.put(position, new RecordedBlock(team, plan.copy()));
        damaged.remove(position);
    }

    void destroyed(Tile tile) {
        var entry = installed.get(tile.pos());
        if (entry != null && tile.team() == entry.team && tile.block() == entry.plan.block) {
            damaged.add(tile.pos());
        }
    }

    BuildProject next(Unit unit, Building core) {
        var iterator = damaged.iterator();
        while (iterator.hasNext()) {
            int position = iterator.next();
            var entry = installed.get(position);
            var plan = entry.plan;
            Tile tile = plan.tile();
            if (entry.team != unit.team || tile.team() == unit.team && tile.block() == plan.block) {
                iterator.remove();
                continue;
            }
            if (!unit.within(tile, 320f) || !plan.block.unlockedNowHost() || !plan.block.environmentBuildable()
                || !MiningPlanner.clearFootprint(plan) || !Build.validPlace(plan.block, unit.team, plan.x, plan.y, plan.rotation)) {
                continue;
            }
            var project = new BuildProject("replacement " + plan.block.name, List.of(plan.copy()));
            if (project.affordable(unit, core, null, 0)) return project;
        }
        return null;
    }
}
