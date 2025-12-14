package dev.chang.spl;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/*
  tab completion for the /pt command
  suggests subcommands player names and common minute values
  uses a small ttl cache for offline names to avoid expensive lookups on every key press
*/
public class PtTabCompleter implements TabCompleter {

    @SuppressWarnings("unused")
    private final SimplePlaytimeLimiter plugin;

    // cache of offline player names to reduce repeated bukkit offline scans
    private volatile List<String> offlineNameCache = Collections.emptyList();
    private volatile long offlineCacheMillis = 0L;
    private static final long OFFLINE_CACHE_TTL_MS = 60_000L;

    public PtTabCompleter(SimplePlaytimeLimiter plugin) {
        this.plugin = plugin;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (!sender.hasPermission("spl.admin")) {
            return Collections.emptyList();
        }

        // root level /pt <...>
        if (args.length == 1) {
            return prefixFilter(args[0], List.of("get", "set", "limit", "whitelist", "reload"));
        }

        // /pt get <player|uuid>
        if (args[0].equalsIgnoreCase("get")) {
            if (args.length == 2) {
                return playerLikeArgs(args[1]);
            }
            return Collections.emptyList();
        }

        // /pt set <player|uuid> <min>
        if (args[0].equalsIgnoreCase("set")) {
            if (args.length == 2) {
                return playerLikeArgs(args[1]);
            }
            if (args.length == 3) {
                return numberHints(args[2], 0, 3600);
            }
            return Collections.emptyList();
        }

        // /pt limit <min>
        if (args[0].equalsIgnoreCase("limit")) {
            if (args.length == 2) {
                return numberHints(args[1], 0, 1440);
            }
            return Collections.emptyList();
        }

        // /pt whitelist <add|remove|list|addme> ...
        if (args[0].equalsIgnoreCase("whitelist")) {
            if (args.length == 2) {
                return prefixFilter(args[1], List.of("add", "remove", "list", "addme"));
            }
            if (args.length == 3 && (args[1].equalsIgnoreCase("add") || args[1].equalsIgnoreCase("remove"))) {
                return playerLikeArgs(args[2]);
            }
            return Collections.emptyList();
        }

        return Collections.emptyList();
    }

    // returns online and cached offline names filtered by the typed prefix
    private List<String> playerLikeArgs(String prefix) {
        refreshOfflineCacheIfNeeded();

        Stream<String> online = Bukkit.getOnlinePlayers().stream().map(Player::getName);
        Stream<String> all = Stream.concat(online, offlineNameCache.stream());

        String p = prefix == null ? "" : prefix.toLowerCase();

        // keep the list small so the client ui does not get flooded
        return all
            .filter(Objects::nonNull)
            .distinct()
            .filter(n -> n.toLowerCase().startsWith(p))
            .limit(50)
            .collect(Collectors.toList());
    }

    // refreshes the offline name cache if ttl expired
    private void refreshOfflineCacheIfNeeded() {
        long now = System.currentTimeMillis();
        if (now - offlineCacheMillis < OFFLINE_CACHE_TTL_MS) {
            return;
        }

        List<String> names = new ArrayList<>();
        for (OfflinePlayer op : Bukkit.getOfflinePlayers()) {
            String n = op.getName();
            if (n != null && !n.isBlank()) {
                names.add(n);
            }
        }

        offlineNameCache = names;
        offlineCacheMillis = now;
    }

    // filters a list of options by prefix
    private List<String> prefixFilter(String prefix, Collection<String> options) {
        String p = prefix == null ? "" : prefix.toLowerCase();
        return options.stream()
            .filter(o -> o.toLowerCase().startsWith(p))
            .collect(Collectors.toList());
    }

    // returns a small set of common values as hints
    // min and max are kept for potential future use but not enforced here
    private List<String> numberHints(String typed, int min, int max) {
        List<String> base = List.of("15", "30", "60", "90", "120", "180");
        return prefixFilter(typed, base);
    }
}
