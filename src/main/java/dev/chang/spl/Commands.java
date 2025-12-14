package dev.chang.spl;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.UUID;

/*
  handles the /pt command for admins
  manages playtime inspection edits daily limit updates and whitelist entries
*/
public class Commands implements CommandExecutor {

    // main plugin reference for store config and runtime state
    private final SimplePlaytimeLimiter plugin;

    // keep a plugin reference so commands can read config and update state
    public Commands(SimplePlaytimeLimiter plugin) {
        this.plugin = plugin;
    }

    // command dispatcher for /pt
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        // only allow admins to use /pt
        if (!sender.hasPermission("spl.admin")) {
            sender.sendMessage("§cKeine Berechtigung.");
            return true;
        }

        // show help if no subcommand is provided
        if (args.length == 0) {
            sender.sendMessage("§7/pt get <spieler|uuid>");
            sender.sendMessage("§7/pt set <spieler|uuid> <min>");
            sender.sendMessage("§7/pt limit <minuten>");
            sender.sendMessage("§7/pt whitelist <add|remove|list> <spieler|uuid>");
            sender.sendMessage("§7/pt whitelist addme");
            sender.sendMessage("§7/pt reload");
            return true;
        }

        // supported subcommands are get set limit whitelist reload
        switch (args[0].toLowerCase()) {
            case "get": {
                // usage /pt get <player|uuid>
                if (args.length < 2) {
                    sender.sendMessage("§cNutzung: /pt get <spieler|uuid>");
                    return true;
                }

                UUID id = resolveUuid(args[1]);
                if (id == null) {
                    sender.sendMessage("§cSpieler/UUID nicht gefunden: " + args[1]);
                    return true;
                }

                int used = plugin.getStore().getMinutesToday(id);

                // treat whitelist and spl.bypass as unlimited
                boolean isWhitelisted = plugin.getWhitelist().contains(id);
                Player online = Bukkit.getPlayer(id);
                boolean hasBypass = online != null && online.hasPermission("spl.bypass");

                if (isWhitelisted || hasBypass) {
                    sender.sendMessage("§aHeute: §e" + used + "§a Minuten. §7(Limit: §aunbegrenzt§7 – Whitelist/Berechtigung)");
                } else {
                    sender.sendMessage("§aHeute: §e" + used + "§a / §e" + plugin.getDailyLimitMin() + " §aMinuten.");
                }
                return true;
            }

            case "set": {
                // usage /pt set <player|uuid> <minutes>
                if (args.length < 3) {
                    sender.sendMessage("§cNutzung: /pt set <spieler|uuid> <min>");
                    return true;
                }

                UUID id = resolveUuid(args[1]);
                if (id == null) {
                    sender.sendMessage("§cSpieler/UUID nicht gefunden: " + args[1]);
                    return true;
                }

                int min;
                try {
                    min = Integer.parseInt(args[2]);
                } catch (NumberFormatException e) {
                    sender.sendMessage("§cMinuten sind keine Zahl.");
                    return true;
                }

                plugin.getStore().setMinutesToday(id, min);
                sender.sendMessage("§aHeute für §e" + args[1] + "§a gesetzt auf §e" + min + "§a Minuten.");

                // if the player is online reset session baseline and enforce immediately
                Player p = Bukkit.getPlayer(id);
                if (p != null) {
                    plugin.getSessionStartMap().put(id, System.currentTimeMillis());
                    plugin.enforceLimit(p);
                }
                return true;
            }

            case "limit": {
                // usage /pt limit <minutes>
                if (args.length < 2) {
                    sender.sendMessage("§cNutzung: /pt limit <minuten>");
                    return true;
                }

                int min;
                try {
                    min = Integer.parseInt(args[1]);
                } catch (NumberFormatException e) {
                    sender.sendMessage("§cMinuten sind keine Zahl.");
                    return true;
                }

                // persist new limit and reload runtime config values
                plugin.getConfig().set("dailyLimitMinutes", min);
                plugin.saveConfig();
                plugin.reloadLocalConfig();

                sender.sendMessage("§aTageslimit auf §e" + min + "§a Minuten gesetzt.");
                return true;
            }

            case "whitelist": {
                // usage /pt whitelist <add|remove|list|addme> [player|uuid]
                if (args.length < 2) {
                    sender.sendMessage("§cNutzung: /pt whitelist <add|remove|list> <spieler|uuid>");
                    return true;
                }

                switch (args[1].toLowerCase()) {
                    case "addme": {
                        // add the executing player to the whitelist
                        if (!(sender instanceof Player sp)) {
                            sender.sendMessage("§cNur ingame nutzbar.");
                            return true;
                        }

                        UUID id = sp.getUniqueId();
                        if (plugin.getWhitelist().add(id)) {
                            persistWhitelist();
                            sender.sendMessage("§aZur Whitelist hinzugefügt: §e" + sp.getName());
                        } else {
                            sender.sendMessage("§7War bereits auf der Whitelist: §e" + sp.getName());
                        }
                        return true;
                    }

                    case "list": {
                        // list all whitelisted entries using names when available
                        if (plugin.getWhitelist().isEmpty()) {
                            sender.sendMessage("§7Whitelist ist leer.");
                            return true;
                        }

                        StringBuilder sb = new StringBuilder("§aWhitelist: ");
                        boolean first = true;

                        for (UUID id : plugin.getWhitelist()) {
                            OfflinePlayer op = Bukkit.getOfflinePlayer(id);
                            String name = (op != null && op.getName() != null) ? op.getName() : id.toString();

                            if (!first) {
                                sb.append("§7, ");
                            }
                            sb.append("§e").append(name);
                            first = false;
                        }

                        sender.sendMessage(sb.toString());
                        return true;
                    }

                    case "add": {
                        // add a player or uuid to the whitelist
                        if (args.length < 3) {
                            sender.sendMessage("§cNutzung: /pt whitelist add <spieler|uuid>");
                            return true;
                        }

                        UUID id = resolveUuid(args[2]);
                        if (id == null) {
                            sender.sendMessage("§cSpieler/UUID nicht gefunden: §e" + args[2]);
                            return true;
                        }

                        if (plugin.getWhitelist().add(id)) {
                            persistWhitelist();
                            sender.sendMessage("§aZur Whitelist hinzugefügt: §e" + printable(id));
                        } else {
                            sender.sendMessage("§7War bereits auf der Whitelist: §e" + printable(id));
                        }
                        return true;
                    }

                    case "remove": {
                        // remove a player or uuid from the whitelist
                        if (args.length < 3) {
                            sender.sendMessage("§cNutzung: /pt whitelist remove <spieler|uuid>");
                            return true;
                        }

                        UUID id = resolveUuid(args[2]);
                        if (id == null) {
                            sender.sendMessage("§cSpieler/UUID nicht gefunden: " + args[2]);
                            return true;
                        }

                        if (plugin.getWhitelist().remove(id)) {
                            persistWhitelist();
                            sender.sendMessage("§aVon der Whitelist entfernt: §e" + printable(id));
                        } else {
                            sender.sendMessage("§7War nicht auf der Whitelist: §e" + printable(id));
                        }
                        return true;
                    }

                    default:
                        sender.sendMessage("§cNutzung: /pt whitelist <add|remove|list> <spieler|uuid>");
                        return true;
                }
            }

            case "reload": {
                // reload config.yml values into runtime variables
                plugin.reloadLocalConfig();
                sender.sendMessage("§aKonfiguration neu geladen.");
                return true;
            }

            default:
                sender.sendMessage("§cUnbekannter Subcommand. Nutze §e/pt§c für Hilfe.");
                return true;
        }
    }

    /*
      resolves a player name or uuid string to a uuid
      order is uuid string exact online name case insensitive online match cached offline match offline scan and final bukkit fallback
    */
    private UUID resolveUuid(String input) {
        // try parsing as uuid first
        try {
            return UUID.fromString(input);
        } catch (IllegalArgumentException ignore) {
        }

        // exact online player name lookup
        Player exact = Bukkit.getPlayerExact(input);
        if (exact != null) {
            return exact.getUniqueId();
        }

        // case insensitive online match
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getName().equalsIgnoreCase(input)) {
                return p.getUniqueId();
            }
        }

        // cached offline player if available
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(input);
        if (cached != null && cached.getUniqueId() != null) {
            return cached.getUniqueId();
        }

        // scan offline players list for a name match
        for (OfflinePlayer op : Bukkit.getOfflinePlayers()) {
            if (op.getName() != null && op.getName().equalsIgnoreCase(input)) {
                return op.getUniqueId();
            }
        }

        // last fallback may return a uuid even if the player never joined
        OfflinePlayer op = Bukkit.getOfflinePlayer(input);
        if (op != null && (op.hasPlayedBefore() || (op.getName() != null && op.getName().equals(input)))) {
            return op.getUniqueId();
        }

        return null;
    }

    // prefer player name for chat output otherwise print the uuid
    private String printable(UUID id) {
        OfflinePlayer op = Bukkit.getOfflinePlayer(id);
        return (op != null && op.getName() != null) ? op.getName() : id.toString();
    }

    // persist whitelist uuids into config.yml under the key whitelist
    private void persistWhitelist() {
        var list = plugin.getConfig().getStringList("whitelist");
        list.clear();

        for (UUID id : plugin.getWhitelist()) {
            list.add(id.toString());
        }

        plugin.getConfig().set("whitelist", list);
        plugin.saveConfig();
    }
}
