package dev.chang.spl;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/*
  simple daily playtime limiter for paper servers
  tracks minutes per day in players.yml and enforces a configurable daily limit
  supports warnings a bypass permission a whitelist and optional ui via bossbar and actionbar
*/
public final class SimplePlaytimeLimiter extends JavaPlugin {

    private PlayerDataStore store;
    private ZoneId zone;
    private int dailyLimitMin;
    private List<Integer> warnAt;
    private String kickMsg;
    private String broadcastMsg;
    private Set<UUID> whitelist;
    private int saveIntervalSec;

    // ui state per player
    private final Map<UUID, BossBar> bossBars = new HashMap<>();

    // ui config loaded from config.yml
    private boolean uiBossbar;
    private boolean uiActionbar;
    private int uiGreenAbove;
    private int uiYellowAbove;
    private String uiTitle;
    private String uiActionbarMsg;
    private int uiUpdateIntervalSec;

    // background tasks
    private BukkitRunnable uiTickTask;
    private BukkitRunnable autosaveTask;
    private BukkitRunnable midnightTask;

    // session start timestamps for online players
    private final Map<UUID, Long> sessionStart = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadLocalConfig();

        this.store = new PlayerDataStore(this);

        // register listeners
        Bukkit.getPluginManager().registerEvents(store, this);

        // register commands
        Objects.requireNonNull(getCommand("pt")).setExecutor(new Commands(this));
        Objects.requireNonNull(getCommand("pt")).setTabCompleter(new PtTabCompleter(this));

        // initialize session baselines for players already online
        for (Player p : Bukkit.getOnlinePlayers()) {
            sessionStart.put(p.getUniqueId(), System.currentTimeMillis());
        }

        startAutosave();
        scheduleMidnightReset();

        // start ui ticker after config load
        startUiTicker();

        getLogger().info("SimplePlaytimeLimiter enabled.");
    }

    @Override
    public void onDisable() {
        // flush session minutes into storage before shutdown
        flushAllSessions();
        store.save();

        if (autosaveTask != null) {
            autosaveTask.cancel();
        }
        if (midnightTask != null) {
            midnightTask.cancel();
        }
        if (uiTickTask != null) {
            uiTickTask.cancel();
        }

        // remove bossbars from all players
        for (BossBar bar : bossBars.values()) {
            bar.removeAll();
            bar.setVisible(false);
        }
        bossBars.clear();
    }

    // reload config.yml values into runtime fields and restart ui ticker if needed
    void reloadLocalConfig() {
        reloadConfig();
        FileConfiguration c = getConfig();

        this.zone = ZoneId.of(c.getString("timezone", "Europe/Berlin"));
        this.dailyLimitMin = c.getInt("dailyLimitMinutes", 120);
        this.warnAt = new ArrayList<>(c.getIntegerList("warnings"));
        this.kickMsg = c.getString("kickMessage", "Daily limit reached.");
        this.broadcastMsg = c.getString("broadcast", "{player} reached daily limit.");
        this.saveIntervalSec = c.getInt("saveIntervalSeconds", 60);

        this.whitelist = new HashSet<>();
        for (String s : c.getStringList("whitelist")) {
            try {
                whitelist.add(UUID.fromString(s));
            } catch (Exception ignored) {
            }
        }

        // ui options
        this.uiBossbar = c.getBoolean("ui.bossbar", true);
        this.uiActionbar = c.getBoolean("ui.actionbarOnWarn", true);
        this.uiGreenAbove = c.getInt("ui.colors.greenAboveMinutes", 30);
        this.uiYellowAbove = c.getInt("ui.colors.yellowAboveMinutes", 5);
        this.uiTitle = c.getString("ui.title", "Spielzeit: {remaining} min");
        this.uiActionbarMsg = c.getString("ui.actionbar", "Noch {remaining} min");
        this.uiUpdateIntervalSec = c.getInt("ui.updateIntervalSeconds", 5);

        // apply interval changes immediately
        startUiTicker();
    }

    // updates bossbar for one player or hides it if disabled
    private void updateUi(Player p, int remaining, boolean unlimited) {
        if (uiBossbar && (dailyLimitMin > 0 || unlimited)) {
            BossBar bar = bossBars.computeIfAbsent(
                p.getUniqueId(),
                id -> Bukkit.createBossBar("", BarColor.GREEN, BarStyle.SEGMENTED_10)
            );

            if (!bar.getPlayers().contains(p)) {
                bar.addPlayer(p);
            }
            bar.setVisible(true);

            // show infinity for unlimited players
            String title = uiTitle.replace("{remaining}", unlimited ? "∞" : String.valueOf(remaining));
            bar.setTitle(colorize(title));

            double progress = unlimited
                ? 1.0
                : Math.max(0d, Math.min(1d, (remaining / (double) dailyLimitMin)));
            bar.setProgress(progress);

            BarColor col = unlimited
                ? BarColor.BLUE
                : (remaining > uiGreenAbove) ? BarColor.GREEN
                : (remaining > uiYellowAbove) ? BarColor.YELLOW
                : BarColor.RED;
            bar.setColor(col);
        } else {
            hideUi(p);
        }
    }

    // removes bossbar for a player if present
    private void hideUi(Player p) {
        BossBar bar = bossBars.remove(p.getUniqueId());
        if (bar != null) {
            bar.removeAll();
            bar.setVisible(false);
        }
    }

    // allow legacy color codes in bossbar title
    private String colorize(String s) {
        return s == null ? "" : s.replace('&', '§');
    }

    // getters used by other classes
    public int getDailyLimitMin() {
        return dailyLimitMin;
    }

    public ZoneId getZone() {
        return zone;
    }

    public Set<UUID> getWhitelist() {
        return whitelist;
    }

    public List<Integer> getWarnAt() {
        return warnAt;
    }

    public String getKickMsg() {
        return kickMsg;
    }

    public String getBroadcastMsg() {
        return broadcastMsg;
    }

    public PlayerDataStore getStore() {
        return store;
    }

    public Map<UUID, Long> getSessionStartMap() {
        return sessionStart;
    }

    // periodically flushes session time into players.yml and saves it
    void startAutosave() {
        if (autosaveTask != null) {
            autosaveTask.cancel();
        }

        autosaveTask = new BukkitRunnable() {
            @Override
            public void run() {
                flushAllSessions();
                store.save();
            }
        };

        autosaveTask.runTaskTimer(this, 20L * saveIntervalSec, 20L * saveIntervalSec);
    }

    // schedules a daily reset at the next midnight for the configured timezone
    void scheduleMidnightReset() {
        if (midnightTask != null) {
            midnightTask.cancel();
        }

        long ticksUntil = TimeUtil.ticksUntilNextMidnight(zone);
        midnightTask = new BukkitRunnable() {
            @Override
            public void run() {
                getLogger().info("Daily reset…");
                flushAllSessions();
                store.resetToday();
                scheduleMidnightReset();
            }
        };

        midnightTask.runTaskLater(this, ticksUntil);
    }

    // periodic ui ticker that recalculates remaining time and updates bossbars
    void startUiTicker() {
        if (uiTickTask != null) {
            uiTickTask.cancel();
        }

        if (!uiBossbar) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                hideUi(p);
            }
            uiTickTask = null;
            return;
        }

        uiTickTask = new BukkitRunnable() {
            @Override
            public void run() {
                long now = System.currentTimeMillis();

                for (Player p : Bukkit.getOnlinePlayers()) {
                    UUID id = p.getUniqueId();
                    boolean unlimited = p.hasPermission("spl.bypass") || whitelist.contains(id);

                    int remaining = 0;
                    if (!unlimited) {
                        int used = store.getMinutesToday(id);
                        Long start = sessionStart.get(id);
                        if (start != null) {
                            used += (int) ((now - start) / 60000L);
                        }
                        remaining = Math.max(0, dailyLimitMin - used);
                    }

                    updateUi(p, remaining, unlimited);
                }
            }
        };

        uiTickTask.runTaskTimer(this, 20L, 20L * Math.max(1, uiUpdateIntervalSec));
    }

    // adds elapsed minutes since last baseline into storage for all online players
    // also enforces limits and refreshes ui
    void flushAllSessions() {
        long now = System.currentTimeMillis();

        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID id = p.getUniqueId();

            Long start = sessionStart.get(id);
            if (start == null) {
                continue;
            }

            long minutes = Math.max(0, (now - start) / 60000L);
            if (minutes > 0) {
                store.addMinutesToday(id, (int) minutes);
                sessionStart.put(id, now);
            }

            enforceLimit(p);

            boolean unlimited = p.hasPermission("spl.bypass") || whitelist.contains(id);
            int usedNow = store.getMinutesToday(id);
            int remainingNow = unlimited ? 0 : Math.max(0, dailyLimitMin - usedNow);
            updateUi(p, remainingNow, unlimited);
        }
    }

    // called by PlayerDataStore on join
    public void onJoin(Player p) {
        sessionStart.put(p.getUniqueId(), System.currentTimeMillis());
        enforceLimit(p);
    }

    // called by PlayerDataStore on quit
    public void onQuit(Player p) {
        UUID id = p.getUniqueId();

        Long start = sessionStart.remove(id);
        if (start == null) {
            return;
        }

        long minutes = Math.max(0, (System.currentTimeMillis() - start) / 60000L);
        if (minutes > 0) {
            store.addMinutesToday(id, (int) minutes);
        }

        hideUi(p);
    }

    // checks remaining time sends warnings and kicks when the daily limit is reached
    public void enforceLimit(Player p) {
        UUID id = p.getUniqueId();

        // unlimited players skip limit logic but still get ui updates
        if (p.hasPermission("spl.bypass") || whitelist.contains(id)) {
            updateUi(p, 0, true);
            return;
        }

        long now = System.currentTimeMillis();

        int used = store.getMinutesToday(id);
        Long start = sessionStart.get(id);
        if (start != null) {
            used += (int) ((now - start) / 60000L);
        }

        int remaining = Math.max(0, dailyLimitMin - used);

        // update ui immediately so it feels responsive
        updateUi(p, remaining, false);

        for (int w : warnAt) {
            if (remaining == w && !store.warnedToday(id, w)) {
                p.sendMessage("§eDu hast noch §6" + w + "§e Minuten für heute.");
                store.markWarnedToday(id, w);

                if (uiActionbar) {
                    p.sendActionBar(Component.text(
                        uiActionbarMsg.replace("{remaining}", String.valueOf(remaining))
                    ));
                }
            }
        }

        if (remaining <= 0) {
            String km = getKickMsg();
            String b = getBroadcastMsg().replace("{player}", p.getName());

            Bukkit.getOnlinePlayers().forEach(op -> op.sendMessage(b));
            p.kick(Component.text(km));
        }
    }
}
