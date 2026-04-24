package eu.kotori.justRTP.addons;

import eu.kotori.justRTP.JustRTP;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public class AddonManager {

    private final JustRTP plugin;
    private final File addonsFolder;
    private final Map<String, JustRTPAddon> loadedAddons = new LinkedHashMap<>();
    private final Map<String, Boolean> addonStates = new HashMap<>();

    public AddonManager(JustRTP plugin) {
        this.plugin = plugin;
        this.addonsFolder = new File(plugin.getDataFolder(), "addons");

        if (!addonsFolder.exists()) {
            addonsFolder.mkdirs();
        }
    }

    public void loadAddons() {
        File[] files = addonsFolder.listFiles((dir, name) -> name.endsWith(".jar"));

        if (files == null || files.length == 0) {
            plugin.getLogger().info("No addons found in addons folder.");
            return;
        }

        plugin.getLogger().info("Loading addons...");

        for (File file : files) {
            try {
                loadAddon(file);
            } catch (Exception e) {
                plugin.getLogger().severe("Failed to load addon: " + file.getName());
                e.printStackTrace();
            }
        }

        plugin.getLogger().info("Loaded " + loadedAddons.size() + " addon(s).");
    }

    private void loadAddon(File file) throws Exception {
        JarFile jarFile = new JarFile(file);

        JarEntry addonYml = jarFile.getJarEntry("addon.yml");
        if (addonYml == null) {
            plugin.getLogger().warning("No addon.yml found in " + file.getName());
            jarFile.close();
            return;
        }

        Properties props = new Properties();
        props.load(jarFile.getInputStream(addonYml));

        String mainClass = props.getProperty("main");
        String name = props.getProperty("name");
        String version = props.getProperty("version");
        String author = props.getProperty("author", "Unknown");

        if (mainClass == null || name == null) {
            plugin.getLogger().warning("Invalid addon.yml in " + file.getName());
            jarFile.close();
            return;
        }

        jarFile.close();

        try (URLClassLoader classLoader = new URLClassLoader(
            new URL[]{file.toURI().toURL()},
            plugin.getClass().getClassLoader()
        )) {
            Class<?> addonClass = classLoader.loadClass(mainClass);
            Object addonInstance = addonClass.getDeclaredConstructor().newInstance();

            if (!(addonInstance instanceof JustRTPAddon)) {
                plugin.getLogger().warning("Main class does not extend JustRTPAddon: " + file.getName());
                return;
            }

            JustRTPAddon addon = (JustRTPAddon) addonInstance;
            addon.setPlugin(plugin);
            addon.setName(name);
            addon.setVersion(version);
            addon.setAuthor(author);
            addon.setDataFolder(new File(addonsFolder, name));

            if (addonStates.containsKey(name.toLowerCase()) && !addonStates.get(name.toLowerCase())) {
                plugin.getLogger().info("Addon '" + name + "' is disabled, skipping...");
                return;
            }

            try {
                addon.onEnable();
                loadedAddons.put(name.toLowerCase(), addon);
                addonStates.put(name.toLowerCase(), true);
                plugin.getLogger().info("Enabled addon: " + name + " v" + version + " by " + author);
            } catch (Exception e) {
                plugin.getLogger().severe("Failed to enable addon: " + name);
                e.printStackTrace();
            }
        }
    }

    public void disableAddons() {
        for (JustRTPAddon addon : loadedAddons.values()) {
            try {
                addon.onDisable();
                plugin.getLogger().info("Disabled addon: " + addon.getName());
            } catch (Exception e) {
                plugin.getLogger().severe("Error disabling addon: " + addon.getName());
                e.printStackTrace();
            }
        }
        loadedAddons.clear();
    }

    public Collection<JustRTPAddon> getLoadedAddons() {
        return Collections.unmodifiableCollection(loadedAddons.values());
    }

    public JustRTPAddon getAddon(String name) {
        return loadedAddons.get(name.toLowerCase());
    }

    public boolean isAddonLoaded(String name) {
        return loadedAddons.containsKey(name.toLowerCase());
    }

    public boolean disableAddon(String name) {
        JustRTPAddon addon = loadedAddons.get(name.toLowerCase());
        if (addon == null) {
            return false;
        }

        try {
            addon.onDisable();
            loadedAddons.remove(name.toLowerCase());
            addonStates.put(name.toLowerCase(), false);
            plugin.getLogger().info("Disabled addon: " + addon.getName());
            return true;
        } catch (Exception e) {
            plugin.getLogger().severe("Error disabling addon: " + addon.getName());
            e.printStackTrace();
            return false;
        }
    }

    public List<String> getAddonNames() {
        return new ArrayList<>(loadedAddons.keySet());
    }

    public File getAddonsFolder() {
        return addonsFolder;
    }
}
