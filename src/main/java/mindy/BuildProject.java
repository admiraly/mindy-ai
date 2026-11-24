package mindy;

import mindustry.entities.units.BuildPlan;
import mindustry.gen.Building;
import mindustry.gen.Unit;
import mindustry.type.Item;

import java.util.List;

import static mindustry.Vars.*;

record BuildProject(String name, List<BuildPlan> plans) {
    boolean affordable(Unit unit, Building core, Item reserveItem, int reserve) {
        if (state.rules.infiniteResources || unit.team.rules().infiniteResources) return true;
        int[] cost = new int[content.items().size];
        for (BuildPlan plan : plans) {
            for (var stack : plan.block.requirements) {
                cost[stack.item.id] += Math.round(stack.amount * state.rules.buildCostMultiplier);
            }
        }
        if (reserveItem != null) cost[reserveItem.id] += reserve;
        for (Item item : content.items()) {
            if (core.items.get(item) < cost[item.id]) return false;
        }
        return true;
    }
}
