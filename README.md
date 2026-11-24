# Mindy AI

Mindy adds an autonomous teammate to your private Mindustry co-op session. It gathers ores, builds starter mining lines, helps finish nearby construction your team has started, and fires at nearby enemies. When no build or mining job is available, it patrols around the team core. It follows players only when commanded.

Each bot joins as a player through the stock desktop client with a client-side mod. The host and human players can use vanilla Mindustry.

## Start a session

You’ll need Python 3.11+, a JDK 17+, and a desktop with working graphics for the bot windows. This project targets Mindustry **v160.8**. Use that same version on the host.

```sh
git clone https://github.com/admiraly/mindy-ai.git
cd mindy-ai
python mindy.py build
```

1. Enter a campaign sector or load a custom game in Mindustry. Open the pause menu and choose **Host Multiplayer**.
2. Run the command below. Use `127.0.0.1` if the host is on this machine, or its LAN address if it’s on another machine. Replace `YourPlayerName` with your exact in-game name, without color codes.

   ```sh
   python mindy.py run --host 127.0.0.1 --owner "YourPlayerName"
   ```

3. Mindy starts picking jobs as soon as it joins. Use `!mindy Mindy status` to see what it is doing.

Give each bot a distinct name. Add `--port` if the host uses a port other than 6567. Keep the bot windows open while they work. Close a window to disconnect its bot, or press Ctrl+C in the launcher to stop the group. The launcher prints a log path for each bot and gives it a separate data folder under `build/`.

## Chat commands

| Command | What the bot does |
| --- | --- |
| `!mindy Mindy auto` | Resumes autonomous task selection after a manual command. |
| `!mindy CopperBot mine copper` | Finds mineable copper and deposits it at the nearest team core. Use other item names, such as `lead`, when the bot’s unit can mine them. |
| `!mindy Mindy follow` | Follows you until you give it another task or use `auto`. |
| `!mindy all hold` | Stops the current task on every bot. |
| `!mindy all status` | Reports what each bot is doing or waiting for. |

Bots start in autonomous mode and clear their current task when a new map loads. All commands accept a bot name or `all`. Use a private session with people you trust. The controller check uses the configured display name and team, and anyone who can join that team with the same name can issue commands.

Anyone on the same team can address Mindy in German. Try `Mindy, was machst du gerade?`, `@Mindy wie viel Kupfer haben wir?`, or `Mindy, sag ein Meme`. Mindy answers these topics using game state and a fixed list of meme quotes.

## Current scope

Mindy uses deterministic rules and Mindustry’s movement and ore APIs. It prioritizes visible enemies threatening the core, leads moving targets, and tries to keep enough room to fire. When badly damaged, it falls back toward a powered repair point or away from the threat. It resumes an interrupted building project once it disengages.

In wave sectors it looks for a place to build a self-supplying outpost on the approach from the nearest enemy spawn. It rotates the layout to face the approach, checks that the drill sits on copper, and reserves 20 copper for the team before starting. It skips an outpost when the sector has no suitable site or there's already a Duo near the core.

Both blueprints are native Mindustry schematics you can import individually. Place the mechanical drill over copper ore so it feeds the adjacent Duo.

- [Copper Outpost](src/main/resources/blueprints/copper-outpost.msch) uses a drill and a Duo.
- [Fortified Outpost](src/main/resources/blueprints/fortified-outpost.msch) adds two copper walls on the outer edge.

When no outpost fits, Mindy plans resource extraction. It scores ore patches by estimated drill output, core stock, existing supply, and conveyor cost. It can connect a standalone drill or place a new mechanical drill, then route up to 18 new conveyors through empty tiles. When an existing friendly belt already leads to the core, Mindy can join it without replacing it. It rechecks each placement during construction. The planner handles standard conveyors and direct core delivery.

Mindy remembers the blocks it builds during the current map. Once combat settles, it prioritizes replacing nearby blocks destroyed during play when the site is clear and the team can afford them. Manual deconstruction does not queue a repair. Advanced factories, research, and campaign decisions still require a player. Each bot runs in a graphical client.

When mining by hand, Mindy weighs the shortage against the distance to the ore and the return trip. It fills its construction buffers first, then keeps mining useful ore while the core has room. It skips low-priority ores such as sand. Use `!mindy Mindy status` to see what it is doing.

Tested with two bots in a Serpulo campaign sector and on a vanilla dedicated server. Campaign sector transitions and platforms other than Windows still need playtesting.

## References

- [Mindustry source](https://github.com/Anuken/Mindustry)
- [Mindustry AI Player](https://github.com/DonatoDiaz/mindustry-ai-player)
- [AssistBots](https://github.com/Ilya246/assistBots)

Mindy AI is an independent community project under the [MIT License](LICENSE).
