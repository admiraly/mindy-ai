package mindy;

import arc.Core;
import arc.Events;
import arc.math.geom.Vec2;
import arc.util.Log;
import arc.util.Strings;
import arc.util.Time;
import mindustry.core.NetClient;
import mindustry.core.World;
import mindustry.entities.units.BuildPlan;
import mindustry.game.EventType.ClientLoadEvent;
import mindustry.game.EventType.BlockDestroyEvent;
import mindustry.game.EventType.PlayerChatEvent;
import mindustry.game.EventType.WorldLoadEvent;
import mindustry.gen.Call;
import mindustry.gen.Building;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.gen.Unit;
import mindustry.input.DesktopInput;
import mindustry.mod.Mod;
import mindustry.type.Item;
import mindustry.world.Tile;
import mindustry.world.Build;
import mindustry.world.blocks.ConstructBlock.ConstructBuild;

import static mindustry.Vars.*;

public class MindyMod extends Mod {
    @Override
    public void init() {
        String name = System.getProperty("mindy.name");
        if (headless || name == null) return;

        Events.on(ClientLoadEvent.class, event -> Core.app.post(() -> {
            String owner = System.getProperty("mindy.owner");
            String host = System.getProperty("mindy.host");
            int port = Integer.getInteger("mindy.port", 6567);
            if (owner == null || host == null) {
                Log.err("Mindy needs a host and a controller name. Start it with mindy.py.");
                return;
            }

            BotInput input = new BotInput(owner);
            control.setInput(input);
            Events.on(PlayerChatEvent.class, input::onChat);
            Events.on(WorldLoadEvent.class, ignored -> {
                input.recovery.clear();
                input.clearTask();
            });
            Events.on(BlockDestroyEvent.class, destroyed -> input.recovery.destroyed(destroyed.tile));
            player.name = name;
            Core.settings.put("name", name);
            Log.info("Mindy: @ joining @:@ (controller: @)", name, host, port, owner);
            NetClient.connect(host, port);
        }));
    }

    private static class BotInput extends DesktopInput {
        private final String owner;
        private final Vec2 destination = new Vec2();
        private final Vec2 velocity = new Vec2();
        private Player leader;
        private Item ore;
        private Tile mine;
        private float nextSearch;
        private float nextDeposit;
        private boolean hauling;
        private boolean autonomous = true;
        private float nextDecision;
        private ConstructBuild construction;
        private boolean building;
        private final DefensePlanner defensePlanner = new DefensePlanner();
        private final TacticalController tactics = new TacticalController();
        private final ProjectRecovery recovery = new ProjectRecovery();
        private BuildProject project;
        private int projectStep;
        private float projectDeadline;
        private float nextProjectSearch;
        private String status = "holding position";

        BotInput(String owner) {
            this.owner = owner;
        }

        void clearTask() {
            leader = null;
            ore = null;
            mine = null;
            hauling = false;
            nextSearch = 0;
            nextDeposit = 0;
            nextDecision = 0;
            construction = null;
            project = null;
            projectStep = 0;
            nextProjectSearch = 0;
            tactics.reset();
            status = "holding position";
            if (!player.dead()) player.unit().mineTile(null);
        }

        void onChat(PlayerChatEvent event) {
            Player sender = event.player;
            if (!net.client() || sender == player || sender.team() != player.team()
                || !owner.equals(Strings.stripColors(sender.name))) return;

            String[] words = event.message.trim().split("\\s+");
            if (words.length < 3 || !words[0].equalsIgnoreCase("!mindy")) return;
            boolean broadcast = words[1].equalsIgnoreCase("all");
            if (!broadcast && !words[1].equalsIgnoreCase(player.name)) return;

            switch (words[2].toLowerCase(java.util.Locale.ROOT)) {
                case "auto" -> {
                    clearTask();
                    autonomous = true;
                    reply("Picking my own tasks.");
                }
                case "hold" -> {
                    clearTask();
                    autonomous = false;
                    reply("Holding position.");
                }
                case "follow" -> {
                    clearTask();
                    autonomous = false;
                    leader = sender;
                    reply("Following " + owner + ".");
                }
                case "mine" -> {
                    Item requested = words.length == 4 ? content.item(words[3].toLowerCase(java.util.Locale.ROOT)) : null;
                    if (requested == null) {
                        reply("Use mine <item>, for example mine copper.");
                        return;
                    }
                    clearTask();
                    autonomous = false;
                    ore = requested;
                    status = "looking for " + ore.name;
                    reply("Mining " + ore.name + ".");
                }
                case "status" -> reply(status + ".");
                default -> reply("Commands: auto, follow, hold, mine <item>, status.");
            }
        }

        private void reply(String message) {
            Log.info("Mindy: @", message);
            Call.sendChatMessage(message);
        }

        @Override
        protected void updateMovement(Unit unit) {
            if (!net.client()) {
                super.updateMovement(unit);
                return;
            }

            velocity.setZero();
            player.shooting = false;
            player.boosting = false;
            unit.controlWeapons(false, false);
            if (autonomous) {
                updateAutonomous(unit);
            } else if (ore != null) {
                updateMining(unit);
            } else if (leader != null) {
                if (!Groups.player.contains(other -> other == leader) || leader.team() != player.team()) {
                    clearTask();
                } else if (leader.dead()) {
                    status = "waiting for " + owner + " to respawn";
                } else {
                    status = "following " + owner;
                    moveToward(unit, leader.x, leader.y, 48f);
                }
            }

            if ((!autonomous || construction == null && project == null) && building) {
                unit.clearBuilding();
                building = false;
            }
            unit.movePref(velocity);
            unit.lookAt(player.shooting && unit.type.faceTarget ? unit.angleTo(unit.aimX(), unit.aimY()) : unit.prefRotation());
        }

        private void updateAutonomous(Unit unit) {
            var core = unit.closestCore();
            var order = tactics.decide(unit, core);
            if (order != null) {
                unit.mineTile(null);
                unit.clearBuilding();
                if (project != null) projectDeadline = Time.time + 1800f;
                var aim = order.aim();
                if (aim != null) {
                    unit.aim(aim.x, aim.y, true);
                    player.mouseX = unit.aimX();
                    player.mouseY = unit.aimY();
                    player.shooting = true;
                    unit.controlWeapons(true, true);
                }
                status = order.status();
                var goal = order.destination();
                if (world.isInMapArea(World.toTile(goal.x), World.toTile(goal.y))) {
                    moveToward(unit, goal.x, goal.y, 12f);
                }
                return;
            }

            if (core == null) {
                status = "waiting for a team core";
                unit.mineTile(null);
                return;
            }

            if (unit.canBuild() && updateProject(unit, core)) return;

            if (Time.time >= nextDecision) {
                nextDecision = Time.time + 60f;
                construction = null;
                if (unit.canBuild()) {
                    indexer.eachBlock(unit, 240f, block -> block instanceof ConstructBuild site
                        && site.wasConstructing && !site.activeDeconstruct
                        && (state.rules.infiniteResources || unit.team.rules().infiniteResources
                        || core.items.has(site.current.requirements, state.rules.buildCostMultiplier)),
                        block -> {
                            if (construction == null || unit.dst2(block) < unit.dst2(construction)) {
                                construction = (ConstructBuild) block;
                            }
                        });
                }
                if (!unit.canMine() || unit.stack.amount == 0 && (ore == null || core.items.get(ore) >= 200
                    || unit.getMineResult(mine) != ore)) {
                    ore = null;
                    mine = null;
                    int lowest = 200;
                    if (unit.canMine()) {
                        for (Item item : content.items()) {
                            int stock = core.items.get(item);
                            if (stock >= lowest || !unit.canMine(item)) continue;
                            Tile candidate = unit.type.mineFloor ? indexer.findClosestOre(unit, item) : null;
                            if (candidate == null && unit.type.mineWalls) candidate = indexer.findClosestWallOre(unit, item);
                            if (unit.getMineResult(candidate) != item) continue;
                            ore = item;
                            mine = candidate;
                            lowest = stock;
                        }
                    }
                }
            }

            if (construction != null && construction.isValid() && !construction.activeDeconstruct) {
                unit.mineTile(null);
                if (unit.buildPlan() == null || unit.buildPlan().tile() != construction.tile) {
                    unit.clearBuilding();
                    unit.addBuild(new BuildPlan(construction.tileX(), construction.tileY(), construction.rotation,
                        construction.current, construction.lastConfig));
                }
                building = true;
                isBuilding = true;
                status = "helping build " + construction.current.name;
                moveToward(unit, construction.x, construction.y, unit.type.buildRange - tilesize);
            } else {
                if (building) unit.clearBuilding();
                building = false;
                if (ore != null) {
                    updateMining(unit);
                } else {
                    unit.mineTile(null);
                    status = "waiting near the core";
                    moveToward(unit, core.x, core.y, 64f);
                }
            }
        }

        private boolean updateProject(Unit unit, Building core) {
            if (project == null && Time.time >= nextProjectSearch) {
                nextProjectSearch = Time.time + 600f;
                // Invest in a self-supplying defense before expanding production when waves threaten this core.
                project = recovery.next(unit, core);
                if (project == null) project = defensePlanner.plan(unit, core);
                if (project == null) project = MiningPlanner.plan(unit, core);
                projectStep = 0;
                if (project != null) {
                    projectDeadline = Time.time + 1800f;
                    Log.info("Mindy: planned @ with @ blocks", project.name(), project.plans().size());
                }
            }
            if (project == null) return false;

            while (projectStep < project.plans().size()) {
                BuildPlan plan = project.plans().get(projectStep);
                Tile tile = plan.tile();
                if (tile.block() == plan.block && tile.team() == unit.team
                    && (!plan.block.rotate || tile.build.rotation == plan.rotation)) {
                    recovery.remember(unit.team, plan);
                    projectStep++;
                    projectDeadline = Time.time + 1800f;
                    continue;
                }

                boolean matchingSite = tile.build instanceof ConstructBuild site && site.team == unit.team
                    && site.current == plan.block && site.rotation == plan.rotation && !site.activeDeconstruct;
                if (Time.time > projectDeadline || !matchingSite && (!MiningPlanner.clearFootprint(plan)
                    || !Build.validPlace(plan.block, unit.team, plan.x, plan.y, plan.rotation))) {
                    Log.info("Mindy: paused @ because placement was blocked or stalled", project.name());
                    break;
                }
                unit.mineTile(null);
                if (unit.buildPlan() != plan) {
                    unit.clearBuilding();
                    unit.addBuild(plan);
                }
                building = true;
                isBuilding = true;
                status = "building " + project.name();
                moveToward(unit, tile.worldx(), tile.worldy(), unit.type.buildRange - tilesize);
                return true;
            }

            if (projectStep == project.plans().size()) {
                Log.info("Mindy: completed @", project.name());
            }
            unit.clearBuilding();
            building = false;
            project = null;
            nextProjectSearch = Time.time + 900f;
            return false;
        }

        private void updateMining(Unit unit) {
            if (!unit.canMine() || !unit.canMine(ore)) {
                unit.mineTile(null);
                status = "this unit cannot mine " + ore.name;
                return;
            }
            var core = unit.closestCore();
            if (core == null) {
                unit.mineTile(null);
                status = "waiting for a team core";
                return;
            }

            if (unit.stack.amount == 0) hauling = false;
            else if (!unit.acceptsItem(ore)) hauling = true;
            if (hauling) {
                unit.mineTile(null);
                status = "returning to the core";
                if (!unit.within(core, itemTransferRange - tilesize)) {
                    moveToward(unit, core.x, core.y, itemTransferRange - tilesize);
                } else if (core.acceptStack(unit.item(), unit.stack.amount, unit) == 0) {
                    status = "waiting for space in the core";
                } else if (Time.time >= nextDeposit) {
                    Call.transferInventory(player, core);
                    nextDeposit = Time.time + Math.max(60f, state.rules.itemDepositCooldown * 60f);
                }
                return;
            }

            if (unit.getMineResult(mine) != ore && Time.time >= nextSearch) {
                mine = unit.type.mineFloor ? indexer.findClosestOre(unit, ore) : null;
                if (mine == null && unit.type.mineWalls) mine = indexer.findClosestWallOre(unit, ore);
                nextSearch = Time.time + 60f;
            }
            if (unit.getMineResult(mine) != ore) {
                unit.mineTile(null);
                status = "waiting for accessible " + ore.name + " ore";
            } else if (unit.validMine(mine)) {
                unit.mineTile(mine);
                status = "mining " + ore.name;
            } else {
                unit.mineTile(null);
                status = "traveling to " + ore.name;
                moveToward(unit, mine.worldx(), mine.worldy(), unit.type.mineRange - tilesize);
            }
        }

        private void moveToward(Unit unit, float x, float y, float stopDistance) {
            if (unit.within(x, y, stopDistance)) return;
            destination.set(x, y);
            if (!unit.isFlying()) {
                var path = controlPath.getPathPosition(unit, destination);
                if (!path.move) {
                    if (path.unreachable) status = "cannot reach the destination";
                    return;
                }
                destination.set(path.dest);
            }
            velocity.set(destination).sub(unit.x, unit.y).limit(unit.speed());
        }
    }
}
