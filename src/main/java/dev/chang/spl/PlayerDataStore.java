package dev.chang.spl;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/*
  stores per player usage data in players.yml
  keys are grouped by date so daily resets are simple
  also stores which warning thresholds were already shown for the day
*/
public class PlayerDataStore implements Listener {

    // main plugin reference for timezone and logging
    private final SimplePlaytimeLimiter plugin;

    // players.yml file on disk
    private final File file;

    // in memory yaml representation
    private final YamlConfiguration yaml;

    public PlayerDataStore(SimplePlaytimeLimiter plugin) {
        this.plugin = plugin;

        File folder = plugin.getDataFolder();
        if (!folder.exists()) {
            folder.mkdirs();
        }

        this.file = new File(folder, "players.yml");
        this.yaml = YamlConfiguration.loadConfiguration(file);
    }

    // returns the current date key based on the configured timezone
    private String todayKey() {
        ZoneId zone = plugin.getZone();
        LocalDate today = LocalDate.now(zone);
        return today.toString();
    }

    // read the stored minutes for today
    public int getMinutesToday(UUID id) {
        String k = "date." + todayKey() + ".players." + id;
        return yaml.getInt(k, 0);
    }

    // add minutes to todays value and clamp to zero minimum
    public void addMinutesToday(UUID id, int minutes) {
        String k = "date." + todayKey() + ".players." + id;
        int current = yaml.getInt(k, 0);
        yaml.set(k, Math.max(0, current + minutes));
    }

    // set todays value directly and persist it
    public void setMinutesToday(UUID id, int minutes) {
        String k = "date." + todayKey() + ".players." + id;
        yaml.set(k, Math.max(0, minutes));
        save();
    }

    // delete the entire section for todays date and persist it
    public void resetToday() {
        yaml.set("date." + todayKey(), null);
        save();
    }

    // check if a specific warning threshold was already sent today
    public boolean warnedToday(UUID id, int w) {
        String k = "date." + todayKey() + ".warned." + id;
        List<Integer> list = yaml.getIntegerList(k);
        return list.contains(w);
    }

    // mark a warning threshold as sent today
    public void markWarnedToday(UUID id, int w) {
        String k = "date." + todayKey() + ".warned." + id;
        List<Integer> list = new ArrayList<>(yaml.getIntegerList(k));

        if (!list.contains(w)) {
            list.add(w);
        }

        yaml.set(k, list);
    }

    // persist current yaml state to players.yml
    public void save() {
        try {
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("could not save players.yml: " + e.getMessage());
        }
    }

    // forward join event handling to the main plugin
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        plugin.onJoin(e.getPlayer());
    }

    // forward quit event handling to the main plugin and persist data
    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        plugin.onQuit(e.getPlayer());
        save();
    }
}
