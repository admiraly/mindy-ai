package mindy;

import arc.math.geom.Vec2;
import arc.util.Time;
import mindustry.entities.Predict;
import mindustry.entities.Units;
import mindustry.gen.Building;
import mindustry.gen.Unit;
import mindustry.world.blocks.units.RepairTurret.RepairPointBuild;

import static mindustry.Vars.*;

final class TacticalController {
    record Order(String status, Vec2 destination, Vec2 aim) {}

    private final Vec2 destination = new Vec2();
    private Unit target;
    private Unit controlled;
    private float targetScore;
    private float nextScan;
    private float retreatUntil;
    private float nextRetreat;

    void reset() {
        controlled = null;
        target = null;
        nextScan = 0;
        retreatUntil = 0;
        nextRetreat = 0;
    }

    Order decide(Unit unit, Building core) {
        if (unit != controlled) {
            reset();
            controlled = unit;
        }
        float range = unit.range();
        if (Time.time >= nextScan) {
            nextScan = Time.time + 12f;
            target = null;
            targetScore = Float.NEGATIVE_INFINITY;
            Units.nearbyEnemies(unit.team, unit.x, unit.y, Math.max(range * 1.45f, 64f), enemy -> {
                if (enemy.dead || !enemy.targetable(unit.team) || enemy.inFogTo(unit.team)
                    || !enemy.checkTarget(unit.type.targetAir, unit.type.targetGround)) return;
                float score = -unit.dst(enemy) - enemy.healthf() * 12f;
                if (core != null && enemy.within(core, 120f)) score += 40f;
                if (score > targetScore) {
                    target = enemy;
                    targetScore = score;
                }
            });
        }
        Unit enemy = target;
        if (enemy != null && (enemy.dead || enemy.inFogTo(unit.team))) enemy = null;
        if (enemy != null && unit.healthf() < 0.35f && Time.time >= nextRetreat) {
            retreatUntil = Time.time + 420f;
            nextRetreat = Time.time + 720f;
        }
        if (Time.time < retreatUntil && unit.healthf() < 0.8f) {
            Building repair = indexer.findTile(unit.team, unit.x, unit.y, 240f,
                build -> build instanceof RepairPointBuild point && point.efficiency > 0);
            if (repair != null) {
                destination.set(repair.x, repair.y);
                return new Order("falling back to a repair point", destination, aim(unit, enemy, range));
            }
            if (core != null && (enemy == null || core.dst2(enemy) > unit.dst2(enemy))) {
                destination.set(core.x, core.y);
                if (unit.within(core, 40f)) destination.set(unit.x, unit.y);
            } else if (enemy != null) {
                destination.set(unit.x - enemy.x, unit.y - enemy.y).setLength(64f).add(unit.x, unit.y);
            } else {
                return null;
            }
            return new Order("retreating to cover", destination, aim(unit, enemy, range));
        }
        if (enemy == null || !unit.type.hasWeapons() || !unit.within(enemy, range)) return null;

        // Give ground units room to fire and let flying units strafe across the enemy's aim.
        float distance = unit.dst(enemy);
        destination.set(unit.x, unit.y);
        if (distance < range * 0.55f) {
            destination.set(unit.x - enemy.x, unit.y - enemy.y).setLength(36f).add(unit.x, unit.y);
        } else if (unit.isFlying()) {
            destination.set(unit.x - enemy.x, unit.y - enemy.y)
                .rotate90((unit.id & 1) == 0 ? 1 : -1).setLength(24f).add(unit.x, unit.y);
        }
        return new Order("engaging " + enemy.type.localizedName, destination, aim(unit, enemy, range));
    }

    private Vec2 aim(Unit unit, Unit enemy, float range) {
        if (enemy == null || !unit.within(enemy, range) || !unit.type.hasWeapons()) return null;
        for (var weapon : unit.type.weapons) {
            if (weapon.bullet.damage > 0f && (enemy.isFlying() ? weapon.bullet.collidesAir : weapon.bullet.collidesGround)) {
                return Predict.intercept(unit, enemy, weapon.bullet);
            }
        }
        return null;
    }
}
