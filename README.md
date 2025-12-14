# simpleplaytimelimiter

a small paper plugin that enforces a daily playtime limit per player  
built for paper 1.21.x and java 21

i originally wrote this for a parenting use case for my son so playtime stays healthy and predictable without constant discussions in the moment  
the goal is simple behavior simple config and data you can read without special tools

## features

- daily limit in minutes configurable via `dailyLimitMinutes` default is 120
- warning messages when remaining minutes hit configured thresholds default is 15 5 1
- automatic kick when the daily limit is reached
- broadcast message to all online players when someone hits the limit
- whitelist by uuid in config plus permission bypass via `spl.bypass`
- stores data per day in `plugins/SimplePlaytimeLimiter/players.yml`
- optional ui
  - bossbar showing remaining minutes or infinity for unlimited players
  - actionbar message when a warning triggers

## requirements

- paper 1.21.x
- java 21

## install

1. copy the jar into your server `plugins/` folder
2. start the server once to generate config files
3. edit `plugins/SimplePlaytimeLimiter/config.yml`
4. run `/pt reload` or restart the server

## build

```bash
gradlew clean build
````

the jar will be at:

* `build/libs/SimplePlaytimeLimiter-<version>.jar`

## configuration

file: `plugins/SimplePlaytimeLimiter/config.yml`

```yaml
dailyLimitMinutes: 120
timezone: "Europe/Berlin"
warnings: [15, 5, 1]
kickMessage: "§cTageslimit erreicht. Morgen geht's weiter!"
broadcast: "§e{player} hat das Tageslimit erreicht."
whitelist: []
saveIntervalSeconds: 60

ui:
  bossbar: true
  actionbarOnWarn: true
  colors:
    greenAboveMinutes: 30
    yellowAboveMinutes: 5
  title: "Spielzeit: {remaining} min"
  actionbar: "Noch {remaining} min"
  updateIntervalSeconds: 5
```

notes

* `timezone` controls when a new day starts and when the midnight reset happens
* `warnings` are minutes remaining and each value is only sent once per day per player
* `whitelist` expects uuid strings
* bossbar shows `∞` for players with `spl.bypass` or whitelist access
* color codes use `§` in messages and `&` is also supported for the bossbar title

## data storage

file: `plugins/SimplePlaytimeLimiter/players.yml`

* minutes are stored per day under a date key
* warning thresholds already sent are tracked per day as well
* data is saved on player quit and also periodically via `saveIntervalSeconds`

## commands

all commands require `spl.admin`

* `/pt`
  shows command help

* `/pt get <player|uuid>`
  shows todays used minutes and the configured limit
  shows unlimited if the player is whitelisted or has `spl.bypass`

* `/pt set <player|uuid> <min>`
  sets todays minutes directly for the player
  if the player is online the session baseline is reset and the limit is enforced immediately

* `/pt limit <minutes>`
  updates `dailyLimitMinutes` in `config.yml` and reloads it immediately

* `/pt whitelist list`
  lists all whitelist entries

* `/pt whitelist add <player|uuid>`
  adds a player to the whitelist and persists it to config

* `/pt whitelist remove <player|uuid>`
  removes a player from the whitelist and persists it to config

* `/pt whitelist addme`
  adds yourself to the whitelist ingame

* `/pt reload`
  reloads config values into runtime state and restarts the ui ticker if needed

## permissions

* `spl.admin`
  access to `/pt` commands
  default is op

* `spl.bypass`
  excluded from limit enforcement and shown as unlimited in the ui
  default is false

## how it works

* on join a session baseline timestamp is stored per player
* periodically the plugin flushes elapsed session minutes into the current day bucket
* remaining time is calculated as `dailyLimitMinutes - usedMinutes`
* when remaining time hits a warning threshold a message is sent once per day per threshold
* when remaining time reaches zero the player is kicked and a broadcast is sent
