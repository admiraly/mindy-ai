package mindy;

import arc.Core;
import arc.Events;
import arc.math.geom.Vec2;
import arc.util.Log;
import arc.util.Strings;
import arc.util.Time;
import mindustry.core.NetClient;
import mindustry.game.EventType.ClientLoadEvent;
import mindustry.game.EventType.PlayerChatEvent;
import mindustry.game.EventType.WorldLoadEvent;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.gen.Unit;
import mindustry.input.DesktopInput;
import mindustry.mod.Mod;
import mindustry.type.Item;
import mindustry.world.Tile;

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
            Events.on(WorldLoadEvent.class, ignored -> input.clearTask());
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
                case "hold" -> {
                    clearTask();
                    reply("Holding position.");
                }
                case "follow" -> {
                    clearTask();
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
                    ore = requested;
                    status = "looking for " + ore.name;
                    reply("Mining " + ore.name + ".");
                }
                case "status" -> reply(status + ".");
                default -> reply("Commands: follow, hold, mine <item>, status.");
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
            if (ore != null) {
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

            unit.movePref(velocity);
            unit.lookAt(unit.prefRotation());
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
