# Manhunt

Simple server-side Fabric mod creating a manhunt in Minecraft.

Made for Minecraft 1.21.

Disable the spawn of Piglin Brute and revert loot_table of Piglin bartering's update in Minecraft 1.16.2 (Nether is the
same as Minecraft 1.16.1).

:warning: When you break a lodestone, the compass is still focused on this lodestone!

## Why did I create this mod?

Because there was no server-side Minecraft mod for the latest Minecraft version.
In addition to this, these mods do not revert the Nether which is essential for speedrunning the game, and they do not 
update automatically the compass.

## Usage

`/manhunt` is the main command.

`/manhunt team` edits the team.
- `/manhunt team <player> hunter` adds the player to the hunters.
- `/manhunt team <player> speedrunner` adds the player to the speedrunners.

`/manhunt track <player>` sets the compass to track the player.

`/manhunt start` starts the manhunt.

`/manhunt reset-timer` resets all timers (useful after a server crash).

## Config file

You can config the time before the release of the hunters and the time between two compass' updates by modifying the config 
file `config/manhunt.json`.

The default config file is:
```json
{
  "secondsBeforeRelease": 30,
  "updateCompassEach": 15
}
```

- `secondsBeforeRelease` is the time before the release of the hunters
- `updateCompassEach` is the time between two compass' updates (fewer means more updates)

## Technos

- Fabric
- Fabric API
- Yarn Mappings
- MidnightLib (embedded in jar)
