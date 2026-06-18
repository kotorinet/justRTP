package eu.kotori.justRTP.addons;

import eu.kotori.justRTP.JustRTP;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.logging.Logger;

public abstract class JustRTPAddon {

    private JustRTP plugin;
    private String name;
    private String version;
    private String author;
    private File dataFolder;
    private Logger logger;

    public abstract void onEnable();

    public abstract void onDisable();

    public JustRTP getPlugin() {
        return plugin;
    }

    public void setPlugin(JustRTP plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getAuthor() {
        return author;
    }

    public void setAuthor(String author) {
        this.author = author;
    }

    public File getDataFolder() {
        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
        }
        return dataFolder;
    }

    public void setDataFolder(File dataFolder) {
        this.dataFolder = dataFolder;
    }

    public Logger getLogger() {
        return logger;
    }

    public void saveDefaultConfig(String fileName) {
        File file = new File(getDataFolder(), fileName);
        if (!file.exists()) {
            try {
                InputStream input = getClass().getClassLoader().getResourceAsStream(fileName);
                if (input != null) {
                    Files.copy(input, file.toPath());
                    input.close();
                }
            } catch (IOException e) {
                getLogger().severe("Could not save default config: " + fileName);
                e.printStackTrace();
            }
        }
    }

    public FileConfiguration loadConfig(String fileName) {
        File file = new File(getDataFolder(), fileName);
        if (!file.exists()) {
            saveDefaultConfig(fileName);
        }
        return YamlConfiguration.loadConfiguration(file);
    }

    public void saveConfig(FileConfiguration config, String fileName) {
        File file = new File(getDataFolder(), fileName);
        try {
            config.save(file);
        } catch (IOException e) {
            getLogger().severe("Could not save config: " + fileName);
            e.printStackTrace();
        }
    }
}
