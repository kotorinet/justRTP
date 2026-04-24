package eu.kotori.justRTP.commands;

import eu.kotori.justRTP.JustRTP;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.util.StringUtil;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.stream.Collectors;

public class RTPTabCompleter implements TabCompleter {
    private final JustRTP plugin;

    public RTPTabCompleter(JustRTP plugin) {
        this.plugin = plugin;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias,
            @NotNull String[] args) {
        if (!plugin.getCommandManager().isAliasEnabled(alias)) {
            return Collections.emptyList();
        }

        final List<String> completions = new ArrayList<>();
        final String currentArg = args[args.length - 1];
        final List<String> currentArgs = new ArrayList<>(Arrays.asList(args).subList(0, args.length - 1));

        Set<String> options = new HashSet<>();

        if (args.length == 1) {
            if (sender.hasPermission("justrtp.command.help")) {
                options.add("help");
            }

            if (sender.hasPermission("justrtp.command.reload"))
                options.add("reload");
            if (sender.hasPermission("justrtp.admin"))
                options.add("proxystatus");

            boolean economyEnabled = plugin.getConfig().getBoolean("economy.enabled", false);
            if (economyEnabled && sender.hasPermission("justrtp.command.confirm")) {
                options.add("confirm");
            }

            if (sender.hasPermission("justrtp.command.rtp.location") && sender instanceof Player player) {
                if (!plugin.getCustomLocationManager().getAvailableLocationIds(player).isEmpty()) {
                    options.add("location");
                }
            }

            if (sender.hasPermission("justrtp.command.rtp.nearplayer")
                    && plugin.getConfig().getBoolean("nearplayer.enabled", true)) {
                options.add("nearplayer");
            }

            if (sender.hasPermission("justrtp.command.rtp.gui")
                    && plugin.getConfig().getBoolean("rtp_gui.enabled", false)) {
                options.add("gui");
            }

            if (sender.hasPermission("justrtp.command.rtp.spectator")
                    && plugin.getConfig().getBoolean("spectator_switch.enabled", false)) {
                options.add("spectator");
            }

            if (sender.hasPermission("justrtp.command.rtp.nearclaim")
                    && plugin.getConfig().getBoolean("near_claim_rtp.enabled", false)) {
                String nearClaimAlias = plugin.getConfig().getString("near_claim_rtp.command_alias", "nearclaim");
                options.add(nearClaimAlias);
            }

            boolean creditsPermissionRequired = plugin.getConfig()
                    .getBoolean("settings.credits_command_requires_permission", true);
            if (!creditsPermissionRequired || sender.hasPermission("justrtp.command.credits")) {
                options.add("credits");
            }
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("nearplayer")) {
            if (sender.hasPermission("justrtp.command.rtp.nearplayer")) {
                Bukkit.getWorlds().stream()
                        .filter(plugin.getRtpService()::isRtpEnabled)
                        .map(World::getName)
                        .forEach(options::add);
            }
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("location")) {
            if (sender instanceof Player player && sender.hasPermission("justrtp.command.rtp.location")) {
                options.addAll(plugin.getCustomLocationManager().getAvailableLocationIds(player));
            }
        }

        boolean worldPermDenied = sender.isPermissionSet("justrtp.command.rtp.world")
                && !sender.hasPermission("justrtp.command.rtp.world");
        if (!worldPermDenied) {
            boolean worldAlreadyPresent = Bukkit.getWorlds().stream().anyMatch(w -> currentArgs.contains(w.getName()));
            if (!worldAlreadyPresent) {
                List<String> validWorlds = Bukkit.getWorlds().stream()
                        .filter(plugin.getRtpService()::isRtpEnabled)
                        .map(World::getName)
                        .collect(Collectors.toList());
                options.addAll(validWorlds);
                plugin.getConfigManager().getWorldAliases().forEach((aliasKey, worldName) -> {
                    if (validWorlds.contains(worldName)) {
                        options.add(aliasKey);
                    }
                });
            }
        }

        if (sender.hasPermission("justrtp.command.rtp.others")) {
            boolean playerAlreadyPresent = Bukkit.getOnlinePlayers().stream()
                    .anyMatch(p -> currentArgs.contains(p.getName()));
            if (!playerAlreadyPresent) {
                options.addAll(Bukkit.getOnlinePlayers().stream().map(Player::getName).collect(Collectors.toList()));
            }

            if (playerAlreadyPresent && !currentArgs.contains("-c")) {
                options.add("-c");
            }
        }

        if (plugin.getConfigManager().getProxyEnabled() && sender.hasPermission("justrtp.command.rtp.server")) {
            boolean serverAlreadyPresent = plugin.getConfigManager().getProxyServers().stream()
                    .anyMatch(s -> currentArgs.stream().anyMatch(ca -> ca.equalsIgnoreCase(s)));

            if (currentArg.contains(":")) {
                String[] parts = currentArg.split(":", 2);
                String serverPart = parts[0];
                String worldPart = parts.length > 1 ? parts[1] : "";

                Optional<String> matchingServer = plugin.getConfigManager().getProxyServers().stream()
                        .filter(s -> s.equalsIgnoreCase(serverPart))
                        .findFirst();

                if (matchingServer.isPresent()) {
                    for (String defaultWorld : new String[] { "world", "world_nether", "world_the_end" }) {
                        if (defaultWorld.toLowerCase().startsWith(worldPart.toLowerCase())) {
                            completions.add(serverPart + ":" + defaultWorld);
                        }
                    }
                    Collections.sort(completions);
                    return completions;
                }
            }

            if (!serverAlreadyPresent) {
                options.addAll(plugin.getConfigManager().getProxyServers());
                for (String server : plugin.getConfigManager().getProxyServers()) {
                    if ((server + ":").startsWith(currentArg.toLowerCase())) {
                        options.add(server + ":");
                    }
                }
            }
        }

        StringUtil.copyPartialMatches(currentArg, options, completions);
        Collections.sort(completions);
        return completions;
    }
}