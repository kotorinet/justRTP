package eu.kotori.justRTP.utils;

import eu.kotori.justRTP.JustRTP;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

public class ConfigUpdater {

    public static void update(JustRTP plugin, String fileName, int latestVersion) {
        File configFile = new File(plugin.getDataFolder(), fileName);
        if (!configFile.exists()) {
            return;
        }

        int currentVersion = readVersionFromFile(configFile);
        if (currentVersion >= latestVersion) {
            return;
        }

        plugin.getLogger().info("Your " + fileName + " is outdated! Updating from version " + currentVersion + " to " + latestVersion + "...");

        File backupFile = new File(plugin.getDataFolder(), fileName + ".v" + currentVersion + ".old");
        try {
            Files.copy(configFile.toPath(), backupFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not create backup for " + fileName + "! Aborting update.");
            e.printStackTrace();
            return;
        }

        if (fileName.equals("rtp_zones.yml") || fileName.equals("cache.yml")) {
            updateVersionInPlace(configFile, latestVersion);
            plugin.getLogger().info(fileName + " version updated. Your old file was saved as " + backupFile.getName());
            return;
        }

        FileConfiguration userConfig = YamlConfiguration.loadConfiguration(configFile);

        try (InputStream defaultStream = plugin.getResource(fileName)) {
            if (defaultStream == null) {
                plugin.getLogger().severe("Could not find default " + fileName + " in JAR! Aborting update.");
                return;
            }
            FileConfiguration defaultConfig = YamlConfiguration.loadConfiguration(new InputStreamReader(defaultStream));

            for (String key : defaultConfig.getKeys(true)) {
                if (defaultConfig.isConfigurationSection(key)) {
                    continue;
                }

                if (userConfig.contains(key)) {
                    defaultConfig.set(key, userConfig.get(key));
                }
            }

            defaultConfig.set("config-version", latestVersion);
            defaultConfig.save(configFile);

            plugin.getLogger().info(fileName + " has been successfully updated. Your old file was saved as " + backupFile.getName());
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save the updated " + fileName + "!");
            e.printStackTrace();
        }
    }

    private static int readVersionFromFile(File file) {
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.startsWith("config-version:")) {
                    String value = trimmed.substring("config-version:".length()).trim();
                    return Integer.parseInt(value);
                }
            }
        } catch (Exception e) {

        }
        return 0;
    }

    private static void updateVersionInPlace(File file, int newVersion) {
        try {
            java.util.List<String> lines = Files.readAllLines(file.toPath());
            boolean updated = false;
            for (int i = 0; i < lines.size(); i++) {
                String trimmed = lines.get(i).trim();
                if (trimmed.startsWith("config-version:")) {

                    String indent = lines.get(i).substring(0, lines.get(i).indexOf("config-version:"));
                    lines.set(i, indent + "config-version: " + newVersion);
                    updated = true;
                    break;
                }
            }
            if (!updated) {
                lines.add(0, "config-version: " + newVersion);
            }
            Files.write(file.toPath(), lines);
        } catch (IOException e) {

            try {
                FileConfiguration config = YamlConfiguration.loadConfiguration(file);
                config.set("config-version", newVersion);
                config.save(file);
            } catch (IOException ex) {
                ex.printStackTrace();
            }
        }
    }
}