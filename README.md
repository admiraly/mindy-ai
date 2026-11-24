# Mindy AI

Mindy adds bot teammates to your private Mindustry co-op session. Give each bot a small job so you can focus on one thing at a time. Run one to gather copper while you build, or bring a couple of bots when you’re short on teammates.

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
   python mindy.py run --host 127.0.0.1 --owner "YourPlayerName" --bots CopperBot LeadBot
   ```

3. When the bots appear in the player list, open in-game chat and type `!mindy CopperBot mine copper`.

Give each bot a distinct name. Add `--port` if the host uses a port other than 6567. Keep the bot windows open while they work. Close a window to disconnect its bot, or press Ctrl+C in the launcher to stop the group. The launcher prints a log path for each bot and gives it a separate data folder under `build/`.

## Chat commands

| Command | What the bot does |
| --- | --- |
| `!mindy CopperBot mine copper` | Finds mineable copper and deposits it at the nearest team core. Use other item names, such as `lead`, when the bot’s unit can mine them. |
| `!mindy LeadBot follow` | Follows the player who sent the command. |
| `!mindy all hold` | Stops the current task on every bot. |
| `!mindy all status` | Reports what each bot is doing or waiting for. |

Bots start idle and reset their tasks when a new map loads. All commands accept a bot name or `all`. Use a private session with people you trust. The controller check uses the configured display name and team, and anyone who can join that team with the same name can issue commands.

## Current scope

This first version uses deterministic rules and Mindustry’s movement and ore APIs. It handles mining, core deposits, following and holding. It requires one graphical client per bot. The bot may need a new task if ore is blocked or the core is full.

Tested with two bots in a Serpulo campaign sector and on a vanilla dedicated server. Campaign sector transitions and platforms other than Windows still need playtesting.

## References

- [Mindustry source](https://github.com/Anuken/Mindustry)
- [Mindustry AI Player](https://github.com/DonatoDiaz/mindustry-ai-player)
- [AssistBots](https://github.com/Ilya246/assistBots)

Mindy AI is an independent community project under the [MIT License](LICENSE).
