package eu.kotori.justRTP.commands;

import eu.kotori.justRTP.JustRTP;
import eu.kotori.justRTP.events.PlayerRTPEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import eu.kotori.justRTP.utils.TimeUtils;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

public class RTPCommand implements CommandExecutor {
    private final JustRTP plugin;

    public RTPCommand(JustRTP plugin) {
        this.plugin = plugin;
    }

    public record ParsedCommand(Player targetPlayer, World targetWorld, String targetServer, String proxyTargetWorld,
            Optional<Integer> minRadius, Optional<Integer> maxRadius, boolean isValid, String errorMessageKey,
            Map<String, String> errorPlaceholders, boolean applyCooldown, boolean isExplicitWorld) {
        public ParsedCommand(Player targetPlayer, World targetWorld, String targetServer, String proxyTargetWorld,
                Optional<Integer> minRadius, Optional<Integer> maxRadius, boolean isValid, String errorMessageKey) {
            this(targetPlayer, targetWorld, targetServer, proxyTargetWorld, minRadius, maxRadius, isValid,
                    errorMessageKey, new HashMap<>(), false, false);
        }

        public ParsedCommand(Player targetPlayer, World targetWorld, String targetServer, String proxyTargetWorld,
                Optional<Integer> minRadius, Optional<Integer> maxRadius, boolean isValid, String errorMessageKey,
                Map<String, String> errorPlaceholders) {
            this(targetPlayer, targetWorld, targetServer, proxyTargetWorld, minRadius, maxRadius, isValid,
                    errorMessageKey, errorPlaceholders, false, false);
        }

        public ParsedCommand(Player targetPlayer, World targetWorld, String targetServer, String proxyTargetWorld,
                Optional<Integer> minRadius, Optional<Integer> maxRadius, boolean isValid, String errorMessageKey,
                boolean isExplicitWorld) {
            this(targetPlayer, targetWorld, targetServer, proxyTargetWorld, minRadius, maxRadius, isValid,
                    errorMessageKey, new HashMap<>(), false, isExplicitWorld);
        }

        public ParsedCommand(Player targetPlayer, World targetWorld, String targetServer, String proxyTargetWorld,
                Optional<Integer> minRadius, Optional<Integer> maxRadius, boolean isValid, String errorMessageKey,
                Map<String, String> errorPlaceholders, boolean applyCooldown) {
            this(targetPlayer, targetWorld, targetServer, proxyTargetWorld, minRadius, maxRadius, isValid,
                    errorMessageKey, errorPlaceholders, applyCooldown, false);
        }
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label,
            @NotNull String[] args) {
        if (!plugin.getCommandManager().isAliasEnabled(label)) {
            return true;
        }

        if (sender instanceof Player player) {
            List<String> disabledWorlds = plugin.getConfig().getStringList("disabled_worlds");
            if (disabledWorlds.contains(player.getWorld().getName())) {
                boolean showMessage = plugin.getConfig().getBoolean("disabled_worlds_show_message", true);
                if (showMessage) {
                    plugin.getLocaleManager().sendMessage(sender, "command.command_disabled_in_world");
                }
                return true;
            }
        }

        if (args.length > 0) {
            switch (args[0].toLowerCase()) {
                case "reload":
                    handleReload(sender);
                    return true;
                case "credits":
                    handleCredits(sender);
                    return true;
                case "proxystatus":
                    handleProxyStatus(sender);
                    return true;
                case "confirm":
                    handleConfirm(sender);
                    return true;
                case "help":
                    handleHelp(sender);
                    return true;
                case "location":
                    handleLocation(sender, args);
                    return true;
                case "nearplayer":
                    handleNearPlayer(sender, args);
                    return true;
                case "gui":
                    handleGui(sender);
                    return true;
                case "spectator":
                    handleSpectator(sender);
                    return true;
                case "queue":
                case "matchmaking":
                    handleMatchmaking(sender, args);
                    return true;
                case "sendlocation":
                    handleSendLocation(sender, args);
                    return true;
                default:
                    String nearClaimAlias = plugin.getConfig().getString("near_claim_rtp.command_alias", "nearclaim");
                    if (args[0].equalsIgnoreCase(nearClaimAlias)) {
                        handleNearClaim(sender);
                        return true;
                    }
                    break;
            }
        }

        if (sender instanceof Player) {
            boolean isBasicRtpRequest = true;
            if (args.length > 0) {
                String firstArg = args[0].toLowerCase();
                Player potentialTarget = Bukkit.getPlayer(firstArg);
                if (potentialTarget != null && !potentialTarget.equals(sender)) {
                    isBasicRtpRequest = false;
                }
                if (Bukkit.getWorld(plugin.getConfigManager().resolveWorldAlias(firstArg)) != null) {
                    isBasicRtpRequest = false;
                }
                if (firstArg.contains(":") || plugin.getConfigManager().getProxyServers().stream()
                        .anyMatch(s -> s.equalsIgnoreCase(firstArg))) {
                    isBasicRtpRequest = false;
                }
            }

            if (isBasicRtpRequest && !sender.hasPermission("justrtp.command.rtp")) {
                plugin.getLocaleManager().sendMessage(sender, "command.no_permission");
                return true;
            }

            if (args.length == 0 && sender instanceof Player) {
                boolean guiEnabled = plugin.getConfig().getBoolean("rtp_gui.enabled", true);
                boolean autoOpenGui = plugin.getConfig().getBoolean("rtp_gui.auto_open_gui", false);

                if (guiEnabled && autoOpenGui && sender.hasPermission("justrtp.command.rtp.gui")) {
                    handleGui(sender);
                    return true;
                }
            }
        }

        processRtpRequest(sender, null, args, false);
        return true;
    }

    public CompletableFuture<Boolean> processRtpRequest(CommandSender sender, Player targetPlayer, String[] args,
            boolean crossServerNoDelay) {
        plugin.getRTPLogger().debug("COMMAND", "Parsing RTP command arguments: " + String.join(" ", args));
        ParsedCommand parsed = parseArgs(sender, args, targetPlayer);
        if (!parsed.isValid()) {
            plugin.getLocaleManager().sendMessage(sender, parsed.errorMessageKey(), parsed.errorPlaceholders());
            return CompletableFuture.completedFuture(false);
        }

        plugin.getRTPLogger().debug("COMMAND",
                "Parsed command: targetPlayer=" + parsed.targetPlayer().getName() + ", targetWorld="
                        + (parsed.targetWorld() != null ? parsed.targetWorld().getName() : "null") + ", targetServer="
                        + parsed.targetServer() + ", proxyTargetWorld=" + parsed.proxyTargetWorld());

        if (parsed.targetServer() != null
                && parsed.targetServer().equalsIgnoreCase(plugin.getConfigManager().getProxyThisServerName())) {
            String worldName = parsed.proxyTargetWorld() != null ? parsed.proxyTargetWorld() : "world";
            plugin.getLocaleManager().sendMessage(sender, "proxy.same_server_error",
                    Placeholder.unparsed("this_server", parsed.targetServer()),
                    Placeholder.unparsed("world", worldName));
            return CompletableFuture.completedFuture(false);
        }

        if (parsed.targetServer() != null
                && !parsed.targetServer().equalsIgnoreCase(plugin.getConfigManager().getProxyThisServerName())) {
            return validateAndInitiateProxyRtp(sender, parsed, args);
        } else {
            if (sender instanceof Player && parsed.targetPlayer() != null && sender.equals(parsed.targetPlayer())) {
                if (!sender.hasPermission("justrtp.command.rtp")) {
                    plugin.getLocaleManager().sendMessage(sender, "command.no_permission");
                    return CompletableFuture.completedFuture(false);
                }
            }

            if (sender instanceof Player && parsed.targetPlayer() != null && !sender.equals(parsed.targetPlayer())
                    && !sender.hasPermission("justrtp.command.rtp.others")) {
                plugin.getLocaleManager().sendMessage(sender, "command.no_permission");
                return CompletableFuture.completedFuture(false);
            }

            if (parsed.isExplicitWorld() && sender instanceof Player player) {
                plugin.getRTPLogger().debug("PERM",
                        "Checking explicit world permissions for " + player.getName() + " -> "
                                + parsed.targetWorld().getName());

                if (player.isPermissionSet("justrtp.command.rtp.world")
                        && !player.hasPermission("justrtp.command.rtp.world")) {
                    plugin.getRTPLogger().debug("PERM", "Player explicitly denied justrtp.command.rtp.world");
                    plugin.getLocaleManager().sendMessage(sender, "command.no_permission");
                    return CompletableFuture.completedFuture(false);
                }

                String worldPerm = "justrtp.command.rtp." + parsed.targetWorld().getName();
                if (player.isPermissionSet(worldPerm) && !player.hasPermission(worldPerm)) {
                    plugin.getRTPLogger().debug("PERM", "Player explicitly denied " + worldPerm);
                    plugin.getLocaleManager().sendMessage(sender, "command.no_permission");
                    return CompletableFuture.completedFuture(false);
                }
            }

            if (sender instanceof Player && plugin.getDelayManager().isDelayed(parsed.targetPlayer().getUniqueId())) {
                plugin.getLocaleManager().sendMessage(sender, "teleport.already_in_progress");
                return CompletableFuture.completedFuture(false);
            }

            return validateAndInitiateLocalRtp(sender, parsed, crossServerNoDelay);
        }
    }

    public ParsedCommand parseArgs(CommandSender sender, String[] args, Player predefTarget) {
        List<String> remainingArgs = new ArrayList<>(Arrays.asList(args));
        Player targetPlayer = predefTarget;
        World targetWorld = null;
        String targetServer = null;
        String proxyTargetWorld = null;
        List<Integer> radii = new ArrayList<>();
        List<String> unparsedArgs = new ArrayList<>();
        boolean applyCooldown = false;
        boolean isExplicitWorld = false;

        Iterator<String> flagIt = remainingArgs.iterator();
        while (flagIt.hasNext()) {
            String arg = flagIt.next();
            if (arg.equalsIgnoreCase("-c")) {
                applyCooldown = true;
                flagIt.remove();
            }
        }

        List<String> availableServers = plugin.getConfigManager().getProxyEnabled()
                ? plugin.getConfigManager().getProxyServers()
                : Collections.emptyList();
        Iterator<String> it = remainingArgs.iterator();
        while (it.hasNext()) {
            String arg = it.next();
            boolean consumed = false;

            Player p = Bukkit.getPlayer(arg);
            if (targetPlayer == null && p != null) {
                targetPlayer = p;
                consumed = true;
            }

            if (!consumed && targetServer == null && arg.contains(":")) {
                String[] parts = arg.split(":", 2);
                if (availableServers.stream().anyMatch(s -> s.equalsIgnoreCase(parts[0]))) {
                    targetServer = parts[0];
                    proxyTargetWorld = parts.length > 1 && !parts[1].isEmpty() ? parts[1] : null;
                    consumed = true;
                }
            }

            if (!consumed && targetServer == null && availableServers.stream().anyMatch(s -> s.equalsIgnoreCase(arg))) {
                targetServer = arg;
                proxyTargetWorld = "world";
                consumed = true;
            }

            if (!consumed && targetWorld == null) {
                String resolvedWorldName = plugin.getConfigManager().resolveWorldAlias(arg);
                World w = Bukkit.getWorld(resolvedWorldName);
                if (w != null) {
                    targetWorld = w;
                    isExplicitWorld = true;
                    consumed = true;
                }
            }

            if (!consumed) {
                unparsedArgs.add(arg);
            }
        }

        it = unparsedArgs.iterator();
        while (it.hasNext()) {
            String arg = it.next();
            try {
                radii.add(Integer.parseInt(arg));
                it.remove();
            } catch (NumberFormatException ignored) {
            }
        }

        if (targetServer != null && proxyTargetWorld == null && unparsedArgs.size() == 1) {
            proxyTargetWorld = unparsedArgs.remove(0);
        }

        if (!unparsedArgs.isEmpty()) {
            return new ParsedCommand(null, null, null, null, Optional.empty(), Optional.empty(), false,
                    "command.usage", isExplicitWorld);
        }

        if (targetPlayer == null) {
            if (sender instanceof Player p) {
                targetPlayer = p;
            } else {
                return new ParsedCommand(null, null, null, null, Optional.empty(), Optional.empty(), false,
                        "command.player_only", isExplicitWorld);
            }
        }

        if (targetWorld == null && targetServer == null) {
            String configuredDefaultWorld = plugin.getConfig().getString("settings.default_world", "").trim();

            if (!configuredDefaultWorld.isEmpty()) {
                if (sender instanceof Player p && p.hasPermission("justrtp.bypass.default_world")) {
                    targetWorld = targetPlayer.getWorld();
                } else {
                    World defaultWorldObj = Bukkit.getWorld(configuredDefaultWorld);
                    if (defaultWorldObj != null) {
                        targetWorld = defaultWorldObj;
                    } else {
                        plugin.getRTPLogger().debug("COMMAND",
                                "Configured default RTP world not found: " + configuredDefaultWorld
                                        + ", falling back to player world");
                        targetWorld = targetPlayer.getWorld();
                    }
                }
            } else {
                targetWorld = targetPlayer.getWorld();
            }
        }

        if (targetWorld != null && plugin.getConfigManager().isSpawnRedirectEnabled()) {
            String spawnWorldName = plugin.getConfigManager().getSpawnWorldName();
            String redirectTargetWorldName = plugin.getConfigManager().getSpawnRedirectTargetWorld();

            if (targetWorld.getName().equalsIgnoreCase(spawnWorldName) && args.length == 0) {
                World redirectWorld = Bukkit.getWorld(redirectTargetWorldName);
                if (redirectWorld != null) {
                    targetWorld = redirectWorld;
                    plugin.getRTPLogger().debug("REDIRECT",
                            "Spawn redirect: " + spawnWorldName + " -> " + redirectTargetWorldName);

                    if (plugin.getConfigManager().shouldNotifySpawnRedirect() && sender instanceof Player) {
                        plugin.getLocaleManager().sendMessage(sender, "spawn_redirect.redirected",
                                Placeholder.unparsed("from_world", spawnWorldName),
                                Placeholder.unparsed("to_world", redirectTargetWorldName));
                    }
                } else {
                    plugin.getRTPLogger().debug("REDIRECT",
                            "Spawn redirect target world not found: " + redirectTargetWorldName);
                }
            }
        }

        if (targetWorld != null && targetServer != null) {
            return new ParsedCommand(null, null, null, null, Optional.empty(), Optional.empty(), false,
                    "command.usage", isExplicitWorld);
        }

        Optional<Integer> minRadius = Optional.empty();
        Optional<Integer> maxRadius = Optional.empty();
        if (!radii.isEmpty()) {
            if (!sender.hasPermission("justrtp.command.rtp.radius")) {
                return new ParsedCommand(null, null, null, null, Optional.empty(), Optional.empty(), false,
                        "command.no_permission", isExplicitWorld);
            }
            radii.sort(Comparator.naturalOrder());
            if (radii.size() == 1) {
                maxRadius = Optional.of(radii.get(0));
            } else {
                minRadius = Optional.of(radii.get(0));
                maxRadius = Optional.of(radii.get(1));
            }
        }

        return new ParsedCommand(targetPlayer, targetWorld, targetServer, proxyTargetWorld, minRadius, maxRadius, true,
                "", new HashMap<>(), applyCooldown, isExplicitWorld);
    }

    private CompletableFuture<Boolean> validateAndInitiateProxyRtp(CommandSender sender, ParsedCommand parsed,
            String[] rawArgs) {
        plugin.getRTPLogger().debug("PROXY", "Validating and initiating proxy RTP.");
        Player target = parsed.targetPlayer();

        if (!plugin.getProxyManager().isProxyEnabled()) {
            plugin.getLocaleManager().sendMessage(sender, "proxy.disabled");
            return CompletableFuture.completedFuture(false);
        }
        if (!sender.hasPermission("justrtp.command.rtp.server")) {
            plugin.getLocaleManager().sendMessage(sender, "command.no_permission");
            return CompletableFuture.completedFuture(false);
        }
        if (sender instanceof Player && !sender.equals(target) && !sender.hasPermission("justrtp.command.rtp.others")) {
            plugin.getLocaleManager().sendMessage(sender, "command.no_permission");
            return CompletableFuture.completedFuture(false);
        }

        if (plugin.getDelayManager().isDelayed(target.getUniqueId())) {
            plugin.getLocaleManager().sendMessage(sender, "teleport.already_in_progress");
            return CompletableFuture.completedFuture(false);
        }

        if (plugin.getTeleportQueueManager().isPlayerInProgress(target.getUniqueId())) {
            plugin.getRTPLogger().debug("PROXY",
                    "Player " + target.getName() + " already has a local teleport in queue");
            plugin.getLocaleManager().sendMessage(sender, "teleport.already_in_progress");
            return CompletableFuture.completedFuture(false);
        }

        String worldName = target.getWorld().getName();
        if (!target.isOp() && !target.hasPermission("justrtp.cooldown.bypass")) {
            long remainingCooldown = plugin.getCooldownManager().getRemaining(target.getUniqueId(), worldName);
            if (remainingCooldown > 0) {
                plugin.getLocaleManager().sendMessage(target, "teleport.cooldown",
                        Placeholder.unparsed("time", TimeUtils.formatDuration(remainingCooldown)));
                return CompletableFuture.completedFuture(false);
            }
        }

        if (!target.isOp() && !target.hasPermission("justrtp.cooldown.bypass") || parsed.applyCooldown()) {
            int cooldown = plugin.getConfigManager().getCooldown(target, target.getWorld());
            plugin.getCooldownManager().setCooldown(target.getUniqueId(), worldName, cooldown);
            plugin.getRTPLogger().debug("PROXY",
                    "Set cooldown for " + target.getName() + " in world " + worldName + ": " + cooldown + " seconds"
                            + (parsed.applyCooldown() ? " (FORCED via -c)" : ""));
        } else {
            plugin.getRTPLogger().debug("PROXY",
                    "Skipped proxy cooldown for " + target.getName() + " (OP or bypass permission)");
        }

        String serverAlias = plugin.getConfigManager().getProxyServerAlias(parsed.targetServer());
        plugin.getLocaleManager().sendMessage(sender, "proxy.searching", Placeholder.unparsed("server", serverAlias));

        Optional<Integer> resolvedMin = parsed.minRadius();
        Optional<Integer> resolvedMax = parsed.maxRadius();
        String targetWorldName = parsed.proxyTargetWorld() != null ? parsed.proxyTargetWorld() : "world";
        if (resolvedMin.isEmpty() || resolvedMax.isEmpty()) {
            int groupMin = plugin.getConfigManager().getIntByWorldName(target, targetWorldName, "min_radius", -1);
            int groupMax = plugin.getConfigManager().getIntByWorldName(target, targetWorldName, "max_radius", -1);
            if (resolvedMin.isEmpty() && groupMin > 0) {
                resolvedMin = Optional.of(groupMin);
                plugin.getRTPLogger().debug("PROXY",
                        "Resolved min_radius from permission group on origin server for " + target.getName()
                                + ": " + groupMin);
            }
            if (resolvedMax.isEmpty() && groupMax > 0) {
                resolvedMax = Optional.of(groupMax);
                plugin.getRTPLogger().debug("PROXY",
                        "Resolved max_radius from permission group on origin server for " + target.getName()
                                + ": " + groupMax);
            }
        }

        plugin.getCrossServerManager().sendFindLocationRequest(target, parsed.targetServer(), parsed.proxyTargetWorld(),
                resolvedMin, resolvedMax, rawArgs);
        return CompletableFuture.completedFuture(true);
    }

    private CompletableFuture<Boolean> validateAndInitiateLocalRtp(CommandSender sender, ParsedCommand parsed,
            boolean crossServerNoDelay) {
        plugin.getRTPLogger().debug("TELEPORT",
                "Validating and initiating local RTP for " + parsed.targetPlayer().getName()
                        + " (crossServerNoDelay=" + crossServerNoDelay + ")");
        Player targetPlayer = parsed.targetPlayer();
        World targetWorld = parsed.targetWorld();

        if (targetWorld == null) {
            plugin.getLocaleManager().sendMessage(sender, "command.world_not_found",
                    Placeholder.unparsed("world", "null"),
                    Placeholder.unparsed("worlds", getAvailableWorldsList()));
            return CompletableFuture.completedFuture(false);
        }

        if (!plugin.getRtpService().isRtpEnabled(targetWorld)) {
            plugin.getLocaleManager().sendMessage(sender, "command.world_disabled",
                    Placeholder.unparsed("world", targetWorld.getName()));
            return CompletableFuture.completedFuture(false);
        }

        if (plugin.getDelayManager().isDelayed(targetPlayer.getUniqueId())) {
            plugin.getRTPLogger().debug("TELEPORT",
                    "Player " + targetPlayer.getName() + " is already delayed (in progress)");
            plugin.getLocaleManager().sendMessage(sender, "teleport.already_in_progress");
            return CompletableFuture.completedFuture(false);
        }

        if (plugin.getTeleportQueueManager().isPlayerInProgress(targetPlayer.getUniqueId())) {
            plugin.getRTPLogger().debug("TELEPORT",
                    "Player " + targetPlayer.getName() + " is already in teleport queue");
            plugin.getLocaleManager().sendMessage(sender, "teleport.already_in_progress");
            return CompletableFuture.completedFuture(false);
        }

        if (!(sender instanceof ConsoleCommandSender) && !crossServerNoDelay) {
            if (!targetPlayer.isOp() && !targetPlayer.hasPermission("justrtp.cooldown.bypass")) {
                String worldName = targetWorld.getName();
                long remainingCooldown = plugin.getCooldownManager().getRemaining(targetPlayer.getUniqueId(),
                        worldName);
                if (remainingCooldown > 0) {
                    plugin.getLocaleManager().sendMessage(sender, "teleport.cooldown",
                            Placeholder.unparsed("time", TimeUtils.formatDuration(remainingCooldown)));
                    return CompletableFuture.completedFuture(false);
                }
            }
        }

        double cost = plugin.getConfigManager().getEconomyCost(targetPlayer, targetWorld);

        if (parsed.maxRadius().isPresent()) {
            double radiusCost = plugin.getConfigManager().getRadiusBasedCost(targetWorld, parsed.maxRadius().get());
            cost += radiusCost;
        }

        final double finalCost = Math.max(0, cost);

        PlayerRTPEvent rtpEvent = new PlayerRTPEvent(
                targetPlayer,
                targetWorld,
                parsed.minRadius().orElse(null),
                parsed.maxRadius().orElse(null),
                finalCost,
                false,
                null);
        Bukkit.getPluginManager().callEvent(rtpEvent);

        if (rtpEvent.isCancelled()) {
            plugin.getRTPLogger().debug("TELEPORT",
                    "PlayerRTPEvent was cancelled by another plugin for " + targetPlayer.getName());
            return CompletableFuture.completedFuture(false);
        }

        World eventTargetWorld = rtpEvent.getTargetWorld();
        if (!eventTargetWorld.equals(targetWorld)) {
            plugin.getRTPLogger().debug("TELEPORT",
                    "Target world changed by PlayerRTPEvent: " + targetWorld.getName() + " -> "
                            + eventTargetWorld.getName());
            targetWorld = eventTargetWorld;
        }

        boolean requireConfirmation = plugin.getConfig().getBoolean("economy.require_confirmation", true);

        if (plugin.getConfig().getBoolean("economy.enabled") && finalCost > 0 && plugin.getVaultHook().hasEconomy()) {
            if (plugin.getVaultHook().getBalance(targetPlayer) < finalCost) {
                plugin.getLocaleManager().sendMessage(targetPlayer, "economy.not_enough_money",
                        Placeholder.unparsed("cost", eu.kotori.justRTP.utils.FormatUtils.formatCost(finalCost)));
                return CompletableFuture.completedFuture(false);
            }
            if (requireConfirmation && sender instanceof Player && sender.equals(targetPlayer)
                    && !plugin.getConfirmationManager().hasPendingConfirmation(targetPlayer)) {
                CompletableFuture<Boolean> confirmationFuture = new CompletableFuture<>();
                plugin.getConfirmationManager().addPendingConfirmation(targetPlayer, () -> {
                    if (sender instanceof Player && sender.equals(targetPlayer)) {
                        plugin.getLocaleManager().sendMessage(sender, "teleport.start_self");
                    } else if (parsed.targetPlayer() != null) {
                        plugin.getLocaleManager().sendMessage(sender, "teleport.start_other",
                                Placeholder.unparsed("player", parsed.targetPlayer().getName()));
                    }
                    executeTeleportationLogic(sender, parsed, crossServerNoDelay, finalCost, true)
                            .thenAccept(confirmationFuture::complete);
                });
                plugin.getLocaleManager().sendMessage(targetPlayer, "economy.needs_confirmation",
                        Placeholder.unparsed("cost", eu.kotori.justRTP.utils.FormatUtils.formatCost(finalCost)));
                return confirmationFuture;
            }
        }

        if (sender instanceof Player && sender.equals(targetPlayer)) {
            plugin.getLocaleManager().sendMessage(sender, "teleport.start_self");
        } else if (parsed.targetPlayer() != null) {

            plugin.getLocaleManager().sendMessage(sender, "teleport.start_other",
                    Placeholder.unparsed("player", parsed.targetPlayer().getName()));

            if (sender instanceof ConsoleCommandSender) {
                plugin.getLocaleManager().sendMessage(parsed.targetPlayer(), "teleport.console_initiated");
            }
        }

        return executeTeleportationLogic(sender, parsed, crossServerNoDelay, finalCost, false);
    }

    public CompletableFuture<Boolean> executeTeleportationLogic(CommandSender sender, ParsedCommand parsed,
            boolean crossServerNoDelay) {
        return executeTeleportationLogic(sender, parsed, crossServerNoDelay, 0.0, false);
    }

    private CompletableFuture<Boolean> executeTeleportationLogic(CommandSender sender, ParsedCommand parsed,
            boolean crossServerNoDelay, double cost, boolean wasConfirmed) {
        Player targetPlayer = parsed.targetPlayer();
        World targetWorld = parsed.targetWorld();
        plugin.getRTPLogger().debug("TELEPORT",
                "Executing teleport logic for " + targetPlayer.getName() + " to world " + targetWorld.getName());

        CompletableFuture<Boolean> future = new CompletableFuture<>();
        boolean isSelfTeleport = sender instanceof Player && targetPlayer.equals(sender);
        boolean shouldApplyDelay = (isSelfTeleport && !crossServerNoDelay)
                || (parsed.applyCooldown());

        if (shouldApplyDelay && !parsed.applyCooldown() && targetPlayer.hasPermission("justrtp.delay.bypass")) {
            shouldApplyDelay = false;
        }

        int delay = shouldApplyDelay
                ? plugin.getConfigManager().getDelay(targetPlayer, targetWorld)
                : 0;
        int cooldown = plugin.getConfigManager().getCooldown(targetPlayer, targetWorld);

        plugin.getDelayManager().startDelay(targetPlayer, () -> {
            if (plugin.getConfig().getBoolean("economy.enabled") && cost > 0 && plugin.getVaultHook().hasEconomy()) {
                if (!plugin.getVaultHook().withdrawPlayer(targetPlayer, cost)) {
                    plugin.getLocaleManager().sendMessage(targetPlayer, "economy.not_enough_money",
                            Placeholder.unparsed("cost", eu.kotori.justRTP.utils.FormatUtils.formatCost(cost)));
                    future.complete(false);
                    return;
                }
                if (wasConfirmed || !plugin.getConfig().getBoolean("economy.require_confirmation", true)) {
                    plugin.getLocaleManager().sendMessage(targetPlayer, "economy.payment_success",
                            Placeholder.unparsed("cost", eu.kotori.justRTP.utils.FormatUtils.formatCost(cost)));
                }
            }

            boolean shouldApplyCooldown = (!(sender instanceof ConsoleCommandSender) && !crossServerNoDelay)
                    || parsed.applyCooldown();
            if (shouldApplyCooldown) {
                if (parsed.applyCooldown()
                        || (!targetPlayer.isOp() && !targetPlayer.hasPermission("justrtp.cooldown.bypass"))) {
                    String worldName = targetWorld.getName();
                    plugin.getCooldownManager().setCooldown(targetPlayer.getUniqueId(), worldName, cooldown);
                    plugin.getRTPLogger().debug("COOLDOWN",
                            "Set per-world cooldown for " + targetPlayer.getName() + " in " + worldName + ": "
                                    + cooldown + "s" + (parsed.applyCooldown() ? " (FORCED via -c)" : ""));
                } else {
                    plugin.getRTPLogger().debug("COOLDOWN",
                            "Skipped cooldown for " + targetPlayer.getName() + " (OP or bypass permission)");
                }
            } else {
                plugin.getRTPLogger().debug("COOLDOWN",
                        "Skipped cooldown for " + targetPlayer.getName() + " (console/cross-server without -c flag)");
            }

            plugin.getTeleportQueueManager()
                    .requestTeleport(targetPlayer, targetWorld, parsed.minRadius(), parsed.maxRadius(), cost)
                    .thenAccept(future::complete);
        }, delay);
        return future;
    }

    private void handleConfirm(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            plugin.getLocaleManager().sendMessage(sender, "command.player_only");
            return;
        }
        if (!player.hasPermission("justrtp.command.confirm")) {
            plugin.getLocaleManager().sendMessage(sender, "command.no_permission");
            return;
        }
        plugin.getConfirmationManager().confirm(player);
    }

    private void handleProxyStatus(CommandSender sender) {
        if (!sender.hasPermission("justrtp.admin")) {
            plugin.getLocaleManager().sendMessage(sender, "command.no_permission");
            return;
        }

        MiniMessage mm = MiniMessage.miniMessage();
        String thisServerName = plugin.getConfigManager().getProxyThisServerName();
        boolean isProxyEnabled = plugin.getProxyManager().isProxyEnabled();

        sender.sendMessage(
                mm.deserialize("<br><gradient:#20B2AA:#7FFFD4><b>JustRTP Proxy & Network Status</b></gradient>"));
        sender.sendMessage(mm.deserialize("<gray>━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━</gray>"));
        sender.sendMessage(mm.deserialize(""));

        sender.sendMessage(mm.deserialize("<gradient:#20B2AA:#7FFFD4>▶ Proxy Configuration</gradient>"));
        sender.sendMessage(
                isProxyEnabled ? mm.deserialize("  <green>✔</green> Proxy feature: <green><b>ENABLED</b></green>")
                        : mm.deserialize("  <red>✖</red> Proxy feature: <red><b>DISABLED</b></red>"));

        if (!isProxyEnabled) {
            sender.sendMessage(
                    mm.deserialize("  <gray>└─ Enable in <white>config.yml<gray> -> <white>proxy.enabled: true"));
            sender.sendMessage(mm.deserialize(""));
            sender.sendMessage(mm.deserialize("<gray>━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━</gray>"));
            return;
        }

        sender.sendMessage(thisServerName.isEmpty() || thisServerName.equals("server-name")
                ? mm.deserialize("  <red>✖</red> Server name: <red><b>NOT SET</b></red> <gray>(Required!)")
                : mm.deserialize("  <green>✔</green> Server name: <gold>" + thisServerName + "</gold>"));

        sender.sendMessage(
                mm.deserialize("  <gray>└─ Tip: Use <white>/rtp " + thisServerName + ":world<gray> for local RTP"));
        sender.sendMessage(mm.deserialize(""));

        sender.sendMessage(mm.deserialize("<gradient:#20B2AA:#7FFFD4>▶ MySQL Database</gradient>"));
        boolean mysqlEnabled = plugin.getConfigManager().isProxyMySqlEnabled();
        sender.sendMessage(mysqlEnabled ? mm.deserialize("  <green>✔</green> MySQL: <green><b>ENABLED</b></green>")
                : mm.deserialize("  <red>✖</red> MySQL: <red><b>DISABLED</b></red>"));

        if (mysqlEnabled && plugin.getDatabaseManager() != null) {
            boolean mysqlConnected = plugin.getDatabaseManager().isConnected();
            sender.sendMessage(
                    mysqlConnected ? mm.deserialize("  <green>✔</green> Connection: <green><b>ACTIVE</b></green>")
                            : mm.deserialize("  <red>✖</red> Connection: <red><b>FAILED</b></red>"));

            if (mysqlConnected) {
                Map<String, String> dbInfo = plugin.getDatabaseManager().getConnectionInfo();
                sender.sendMessage(mm.deserialize("  <gray>├─ Host: <white>" + dbInfo.getOrDefault("host", "N/A") + ":"
                        + dbInfo.getOrDefault("port", "N/A")));
                sender.sendMessage(
                        mm.deserialize("  <gray>├─ Database: <white>" + dbInfo.getOrDefault("database", "N/A")));
                sender.sendMessage(mm.deserialize("  <gray>└─ Pool: <white>" + dbInfo.getOrDefault("pool_size", "0")
                        + "<gray> active connections"));
            } else {
                sender.sendMessage(mm.deserialize("  <gray>└─ Check credentials/firewall in <white>mysql.yml"));
            }
        } else {
            sender.sendMessage(mm.deserialize("  <gray>└─ Enable in <white>mysql.yml<gray> for cross-server support"));
        }
        sender.sendMessage(mm.deserialize(""));

        sender.sendMessage(mm.deserialize("<gradient:#20B2AA:#7FFFD4>▶ Redis Cache</gradient> <gray>(Optional)"));
        boolean redisEnabled = plugin.getConfigManager().isRedisEnabled();
        sender.sendMessage(redisEnabled
                ? mm.deserialize(
                        "  <green>✔</green> Redis: <green><b>ENABLED</b></green> <gray>(Check redis.yml for details)")
                : mm.deserialize(
                        "  <gray>-</gray> Redis: <gray><b>DISABLED</b></gray> <gray>(Using memory/MySQL fallback)"));

        if (redisEnabled) {
            String redisHost = plugin.getConfig().getString("redis.connection.host", "localhost");
            int redisPort = plugin.getConfig().getInt("redis.connection.port", 6379);
            sender.sendMessage(mm.deserialize("  <gray>├─ Host: <white>" + redisHost + ":" + redisPort));

            List<String> storageTypes = new ArrayList<>();
            if (plugin.getConfig().getBoolean("redis.storage.cooldowns"))
                storageTypes.add("cooldowns");
            if (plugin.getConfig().getBoolean("redis.storage.delays"))
                storageTypes.add("delays");
            if (plugin.getConfig().getBoolean("redis.storage.cache"))
                storageTypes.add("cache");
            if (plugin.getConfig().getBoolean("redis.storage.teleport_requests"))
                storageTypes.add("requests");

            if (!storageTypes.isEmpty()) {
                sender.sendMessage(
                        mm.deserialize("  <gray>├─ Storage: <aqua>" + String.join("<gray>, <aqua>", storageTypes)));
            }

            boolean pubsubEnabled = plugin.getConfig().getBoolean("redis.pubsub.enabled", false);
            sender.sendMessage(
                    mm.deserialize("  <gray>└─ PubSub: " + (pubsubEnabled ? "<green>enabled" : "<gray>disabled")));
        } else {
            sender.sendMessage(mm.deserialize("  <gray>└─ Plugin works fully without Redis"));
        }

        sender.sendMessage(mm.deserialize(""));
        sender.sendMessage(mm.deserialize("<gray>━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━</gray>"));
        sender.sendMessage(mm.deserialize(
                "<gradient:#20B2AA:#7FFFD4>💡 Tip:</gradient> <gray>Use <white>/rtp <gold>server<white>:<aqua>world<gray> for cross-server RTP"));
        sender.sendMessage(mm.deserialize("<gray>━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━</gray>"));
    }

    private void handleHelp(CommandSender sender) {
        if (sender instanceof Player && !sender.hasPermission("justrtp.command.help")) {
            plugin.getLocaleManager().sendMessage(sender, "command.no_permission");
            return;
        }

        MiniMessage mm = MiniMessage.miniMessage();
        String thisServer = plugin.getConfigManager().getProxyThisServerName();
        boolean proxyEnabled = plugin.getConfigManager().getProxyEnabled();
        boolean filterByPermission = plugin.getConfig().getBoolean("help_command.filter_by_permission", true);

        sender.sendMessage(mm.deserialize(""));
        sender.sendMessage(
                mm.deserialize("<gradient:#20B2AA:#7FFFD4>━━━━━━━━━━━━ JustRTP Command Guide ━━━━━━━━━━━━</gradient>"));
        sender.sendMessage(mm.deserialize(""));

        if (!filterByPermission || !(sender instanceof Player) || sender.hasPermission("justrtp.command.rtp")) {
            sender.sendMessage(mm.deserialize("<yellow><b>Basic Usage:</b>"));
            sender.sendMessage(
                    mm.deserialize(
                            "  <white>/rtp                    <dark_gray>→ <gray>Random location (current world)"));
        }

        if (!filterByPermission || !(sender instanceof Player) || sender.hasPermission("justrtp.command.rtp.world")) {
            sender.sendMessage(mm.deserialize(
                    "  <white>/rtp <aqua>world_nether        <dark_gray>→ <gray>Random location in world_nether"));
        }

        if (!filterByPermission || !(sender instanceof Player) || sender.hasPermission("justrtp.command.rtp.world")) {
            sender.sendMessage(mm
                    .deserialize(
                            "  <white>/rtp <aqua>world_the_end       <dark_gray>→ <gray>Random location in the end"));
        }
        sender.sendMessage(mm.deserialize(""));

        if (proxyEnabled) {
            sender.sendMessage(mm.deserialize("<gold><b>Cross-Server Usage:</b>"));
            sender.sendMessage(mm
                    .deserialize("  <white>/rtp <gold>lobby2              <dark_gray>→ <gray>Default world on lobby2"));
            sender.sendMessage(mm.deserialize(
                    "  <white>/rtp <gold>lobby2<white>:<aqua>world_nether <dark_gray>→ <gray>world_nether on lobby2"));
            sender.sendMessage(mm.deserialize(
                    "  <white>/rtp <gold>factions<white>:<aqua>world       <dark_gray>→ <gray>world on factions server"));
            sender.sendMessage(mm.deserialize(""));
            sender.sendMessage(mm.deserialize(
                    "<gray>💡 <white>Important: <gray>For <yellow>same server<gray>, use <white>/rtp <aqua>world"));
            sender.sendMessage(mm.deserialize("<gray>   Current server: <gold>" + thisServer));
            sender.sendMessage(mm.deserialize(""));
        }

        if (!filterByPermission || !(sender instanceof Player) || sender.hasPermission("justrtp.command.rtp.others")) {
            sender.sendMessage(mm.deserialize("<aqua><b>Advanced Usage:</b>"));
            sender.sendMessage(
                    mm.deserialize("  <white>/rtp <player>           <dark_gray>→ <gray>Teleport another player"));
            sender.sendMessage(
                    mm.deserialize(
                            "  <white>/rtp <player> <yellow>-c       <dark_gray>→ <gray>Teleport player + apply cooldown"));
        }

        if (!filterByPermission || !(sender instanceof Player) || sender.hasPermission("justrtp.command.rtp.params")) {
            sender.sendMessage(mm.deserialize("  <white>/rtp <radius>           <dark_gray>→ <gray>Custom max radius"));
            sender.sendMessage(
                    mm.deserialize("  <white>/rtp <min> <max>        <dark_gray>→ <gray>Custom min/max radius"));
        }

        if (!filterByPermission || !(sender instanceof Player) || sender.hasPermission("justrtp.command.location")) {
            sender.sendMessage(
                    mm.deserialize(
                            "  <white>/rtp location <gold><name>  <dark_gray>→ <gray>Teleport to custom location"));
        }

        if (!filterByPermission || !(sender instanceof Player)
                || sender.hasPermission("justrtp.command.rtp.nearplayer")) {
            sender.sendMessage(mm.deserialize(
                    "  <white>/rtp nearplayer <aqua>[world] <dark_gray>→ <gray>RTP near a random online player"));
        }

        if (!filterByPermission || !(sender instanceof Player)
                || sender.hasPermission("justrtp.command.rtp.nearclaim")) {
            sender.sendMessage(mm.deserialize(
                    "  <white>/rtp nearclaim           <dark_gray>→ <gray>RTP near a claimed territory"));
        }

        if (!filterByPermission || !(sender instanceof Player)
                || sender.hasPermission("justrtp.command.rtp.gui")) {
            sender.sendMessage(mm.deserialize(
                    "  <white>/rtp gui                <dark_gray>→ <gray>Open RTP world selection GUI"));
        }

        if (!filterByPermission || !(sender instanceof Player)
                || sender.hasPermission("justrtp.command.rtp.spectator")) {
            sender.sendMessage(mm.deserialize(
                    "  <white>/rtp spectator          <dark_gray>→ <gray>Open spectator-style world selector"));
        }
        sender.sendMessage(mm.deserialize(""));

        sender.sendMessage(mm.deserialize("<green><b>Special Commands:</b>"));
        sender.sendMessage(mm.deserialize("  <white>/rtp confirm            <dark_gray>→ <gray>Confirm paid teleport"));

        if (!filterByPermission || !(sender instanceof Player) || sender.hasPermission("justrtp.admin.proxy")) {
            sender.sendMessage(
                    mm.deserialize("  <white>/rtp proxystatus        <dark_gray>→ <gray>Check proxy/database status"));
        }

        if (!filterByPermission || !(sender instanceof Player) || sender.hasPermission("justrtp.command.reload")) {
            sender.sendMessage(mm
                    .deserialize(
                            "  <white>/rtp reload             <dark_gray>→ <gray>Reload configuration <gray>(admin)"));
        }

        sender.sendMessage(mm.deserialize(""));
        sender.sendMessage(
                mm.deserialize("<gradient:#20B2AA:#7FFFD4>━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━</gradient>"));
    }

    private void handleNearPlayer(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.getLocaleManager().sendMessage(sender, "command.player_only");
            return;
        }

        if (!sender.hasPermission("justrtp.command.rtp.nearplayer")) {
            plugin.getLocaleManager().sendMessage(sender, "command.no_permission");
            return;
        }

        if (!plugin.getConfig().getBoolean("nearplayer.enabled", true)) {
            plugin.getLocaleManager().sendMessage(sender, "nearplayer.feature_disabled");
            return;
        }

        if (plugin.getDelayManager().isDelayed(player.getUniqueId())) {
            plugin.getLocaleManager().sendMessage(sender, "teleport.already_in_progress");
            return;
        }

        if (plugin.getTeleportQueueManager().isPlayerInProgress(player.getUniqueId())) {
            plugin.getLocaleManager().sendMessage(sender, "teleport.already_in_progress");
            return;
        }

        World targetWorld;
        if (args.length >= 2) {
            String resolvedWorldName = plugin.getConfigManager().resolveWorldAlias(args[1]);
            targetWorld = Bukkit.getWorld(resolvedWorldName);
            if (targetWorld == null) {
                plugin.getLocaleManager().sendMessage(sender, "command.world_not_found",
                        Placeholder.unparsed("world", args[1]),
                        Placeholder.unparsed("worlds", getAvailableWorldsList()));
                return;
            }
        } else {
            targetWorld = player.getWorld();
        }

        if (!plugin.getRtpService().isRtpEnabled(targetWorld)) {
            plugin.getLocaleManager().sendMessage(sender, "command.world_disabled",
                    Placeholder.unparsed("world", targetWorld.getName()));
            return;
        }

        if (!player.isOp() && !player.hasPermission("justrtp.cooldown.bypass")) {
            long remainingCooldown = plugin.getCooldownManager().getRemaining(player.getUniqueId(),
                    targetWorld.getName());
            if (remainingCooldown > 0) {
                plugin.getLocaleManager().sendMessage(sender, "teleport.cooldown",
                        Placeholder.unparsed("time", TimeUtils.formatDuration(remainingCooldown)));
                return;
            }
        }

        boolean allowSelf = plugin.getConfig().getBoolean("nearplayer.allow_self", false);
        List<Player> candidates = targetWorld.getPlayers().stream()
                .filter(p -> !p.equals(player) || allowSelf)
                .filter(p -> !p.isDead())
                .collect(Collectors.toList());

        if (candidates.isEmpty()) {
            plugin.getLocaleManager().sendMessage(sender, "nearplayer.no_players",
                    Placeholder.unparsed("world", targetWorld.getName()));
            return;
        }

        Player target = candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
        Location targetLoc = target.getLocation();
        int centerX = targetLoc.getBlockX();
        int centerZ = targetLoc.getBlockZ();

        int minRadius = plugin.getConfig().getInt("nearplayer.min_radius", 100);
        int maxRadius = plugin.getConfig().getInt("nearplayer.max_radius", 500);

        plugin.getRTPLogger().debug("NEARPLAYER",
                "Player " + player.getName() + " using nearplayer RTP in " + targetWorld.getName()
                        + ", target player: " + target.getName() + " at " + centerX + ", " + centerZ
                        + " (radius: " + minRadius + "-" + maxRadius + ")");

        plugin.getLocaleManager().sendMessage(sender, "nearplayer.searching",
                Placeholder.unparsed("world", targetWorld.getName()));

        if (!player.isOp() && !player.hasPermission("justrtp.cooldown.bypass")) {
            int cooldown = plugin.getConfigManager().getCooldown(player, targetWorld);
            plugin.getCooldownManager().setCooldown(player.getUniqueId(), targetWorld.getName(), cooldown);
        }

        plugin.getRtpService()
                .findSafeLocation(player, targetWorld, 0, Optional.of(minRadius), Optional.of(maxRadius),
                        centerX, centerZ)
                .thenAccept(locationOpt -> {
                    if (locationOpt.isPresent()) {
                        Location safeLoc = locationOpt.get();
                        plugin.getFoliaScheduler().runAtEntity(player, () -> {
                            player.teleportAsync(safeLoc).thenAccept(success -> {
                                if (!success) return;
                                plugin.getFoliaScheduler().runAtEntity(player, () -> {
                                    plugin.getLocaleManager().sendMessage(player, "nearplayer.success",
                                            Placeholder.unparsed("world", targetWorld.getName()));

                                    boolean silent = plugin.getConfig().getBoolean("nearplayer.silent", false);
                                    if (!silent && target.isOnline()) {
                                        plugin.getLocaleManager().sendMessage(target, "nearplayer.notify_target",
                                                Placeholder.unparsed("player", player.getName()));
                                    }

                                    plugin.getRTPLogger().debug("NEARPLAYER",
                                            "Successfully teleported " + player.getName() + " near " + target.getName()
                                                    + " to " + safeLoc.getBlockX() + ", " + safeLoc.getBlockY() + ", "
                                                    + safeLoc.getBlockZ());
                                });
                            });
                        });
                    } else {
                        plugin.getFoliaScheduler().runAtEntity(player, () -> {
                            plugin.getLocaleManager().sendMessage(player, "teleport.no_location_found");
                            plugin.getCooldownManager().clearCooldown(player.getUniqueId());
                        });
                    }
                });
    }

    private void handleGui(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            plugin.getLocaleManager().sendMessage(sender, "command.player_only");
            return;
        }

        if (!sender.hasPermission("justrtp.command.rtp.gui")) {
            plugin.getLocaleManager().sendMessage(sender, "command.no_permission");
            return;
        }

        plugin.getRtpGuiManager().openMainGui(player);
    }

    private void handleSpectator(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            plugin.getLocaleManager().sendMessage(sender, "command.player_only");
            return;
        }

        if (!sender.hasPermission("justrtp.command.rtp.spectator")) {
            plugin.getLocaleManager().sendMessage(sender, "command.no_permission");
            return;
        }

        plugin.getSpectatorSwitchManager().openSpectatorSwitch(player);
    }

    private void handleNearClaim(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            plugin.getLocaleManager().sendMessage(sender, "command.player_only");
            return;
        }

        if (!player.hasPermission("justrtp.command.rtp.nearclaim")) {
            plugin.getLocaleManager().sendMessage(sender, "command.no_permission");
            return;
        }

        plugin.getNearClaimRTPManager().performNearClaimRTP(player);
    }

    private void handleMatchmaking(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.getLocaleManager().sendMessage(sender, "command.player_only");
            return;
        }

        if (!player.hasPermission("justrtp.command.rtp.matchmaking")) {
            plugin.getLocaleManager().sendMessage(sender, "command.no_permission");
            return;
        }

        if (args.length < 2) {
            plugin.getLocaleManager().sendMessage(sender, "matchmaking.usage");
            return;
        }

        String action = args[1].toLowerCase();
        switch (action) {
            case "join" -> {
                World world = args.length > 2 ? Bukkit.getWorld(plugin.getConfigManager().resolveWorldAlias(args[2])) : player.getWorld();
                if (world == null) {
                    plugin.getLocaleManager().sendMessage(sender, "command.world_not_found");
                    return;
                }
                plugin.getMatchmakingManager().joinQueue(player, world, Optional.empty(), Optional.empty());
            }
            case "leave" -> plugin.getMatchmakingManager().leaveQueue(player);
            case "status" -> sendMatchmakingStatus(player);
            default -> plugin.getLocaleManager().sendMessage(sender, "matchmaking.usage");
        }
    }

    private void sendMatchmakingStatus(Player player) {
        World world = player.getWorld();
        var manager = plugin.getMatchmakingManager();
        int queueSize = manager.getQueueSize(world);
        int teamSize = manager.getTeamSize();
        boolean inQueue = manager.isInQueue(player);

        plugin.getLocaleManager().sendMessage(player, "matchmaking.status_header");

        if (!manager.isEnabled()) {
            plugin.getLocaleManager().sendMessage(player, "matchmaking.status_disabled");
            plugin.getLocaleManager().sendMessage(player, "matchmaking.status_footer");
            return;
        }

        plugin.getLocaleManager().sendMessage(player, "matchmaking.status_world",
                Placeholder.unparsed("world", world.getName()));
        plugin.getLocaleManager().sendMessage(player, "matchmaking.status_queue",
                Placeholder.unparsed("queue_size", String.valueOf(queueSize)),
                Placeholder.unparsed("team_size", String.valueOf(teamSize)));

        if (inQueue) {
            int position = manager.getQueuePosition(player, world);
            long waited = manager.getWaitedSeconds(player);
            plugin.getLocaleManager().sendMessage(player, "matchmaking.status_in_queue",
                    Placeholder.unparsed("position", String.valueOf(Math.max(1, position))));
            if (waited >= 0) {
                plugin.getLocaleManager().sendMessage(player, "matchmaking.status_waited",
                        Placeholder.unparsed("waited", TimeUtils.formatDuration((int) waited)));
            }
        } else {
            plugin.getLocaleManager().sendMessage(player, "matchmaking.status_not_in_queue");
        }

        if (queueSize >= teamSize) {
            plugin.getLocaleManager().sendMessage(player, "matchmaking.status_ready");
        } else {
            int needed = teamSize - queueSize;
            plugin.getLocaleManager().sendMessage(player, "matchmaking.status_eta_more",
                    Placeholder.unparsed("needed", String.valueOf(needed)));
        }

        Map<World, Integer> others = manager.getActiveQueueSizes();
        others.remove(world);
        plugin.getLocaleManager().sendMessage(player, "matchmaking.status_other_header");
        if (others.isEmpty()) {
            plugin.getLocaleManager().sendMessage(player, "matchmaking.status_other_none");
        } else {
            for (Map.Entry<World, Integer> entry : others.entrySet()) {
                plugin.getLocaleManager().sendMessage(player, "matchmaking.status_other_entry",
                        Placeholder.unparsed("other_world", entry.getKey().getName()),
                        Placeholder.unparsed("other_size", String.valueOf(entry.getValue())),
                        Placeholder.unparsed("team_size", String.valueOf(teamSize)));
            }
        }

        plugin.getLocaleManager().sendMessage(player, "matchmaking.status_footer");
    }

    private void handleSendLocation(CommandSender sender, String[] args) {
        if (!sender.hasPermission("justrtp.admin")) {
            plugin.getLocaleManager().sendMessage(sender, "command.no_permission");
            return;
        }

        if (args.length < 6) {
            plugin.getLocaleManager().sendMessage(sender, "sendlocation.usage");
            return;
        }

        try {
            double x = Double.parseDouble(args[1]);
            double y = Double.parseDouble(args[2]);
            double z = Double.parseDouble(args[3]);
            String worldName = args[4];
            String playerName = args[5];

            World world = Bukkit.getWorld(worldName);
            if (world == null) {
                plugin.getLocaleManager().sendMessage(sender, "command.world_not_found");
                return;
            }

            Player target = Bukkit.getPlayer(playerName);
            if (target == null || !target.isOnline()) {
                plugin.getLocaleManager().sendMessage(sender, "command.player_not_found",
                        Placeholder.unparsed("player", playerName));
                return;
            }

            Location location = new Location(world, x, y, z);

            plugin.getRtpService().teleportPlayer(target, location, null, null, 0.0, false, null)
                    .thenAccept(success -> {
                        if (success) {
                            plugin.getLocaleManager().sendMessage(sender, "sendlocation.success",
                                    Placeholder.unparsed("player", target.getName()),
                                    Placeholder.unparsed("x", String.valueOf((int) x)),
                                    Placeholder.unparsed("y", String.valueOf((int) y)),
                                    Placeholder.unparsed("z", String.valueOf((int) z)),
                                    Placeholder.unparsed("world", worldName));
                            plugin.getLocaleManager().sendMessage(target, "sendlocation.teleported",
                                    Placeholder.unparsed("x", String.valueOf((int) x)),
                                    Placeholder.unparsed("y", String.valueOf((int) y)),
                                    Placeholder.unparsed("z", String.valueOf((int) z)),
                                    Placeholder.unparsed("world", worldName));
                        } else {
                            plugin.getLocaleManager().sendMessage(sender, "sendlocation.failed");
                        }
                    });
        } catch (NumberFormatException e) {
            plugin.getLocaleManager().sendMessage(sender, "sendlocation.invalid_coordinates");
        }
    }

    private void handleLocation(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.getLocaleManager().sendMessage(sender, "command.player_only");
            return;
        }

        if (!sender.hasPermission("justrtp.command.rtp.location")) {
            plugin.getLocaleManager().sendMessage(sender, "command.no_permission");
            return;
        }

        if (args.length < 2) {
            plugin.getLocaleManager().sendMessage(sender, "custom_locations.usage");
            return;
        }

        String locationName = args[1].toLowerCase();
        plugin.getCustomLocationManager().teleportToLocation(player, locationName);
    }

    private void handleReload(CommandSender sender) {
        if (!sender.hasPermission("justrtp.command.reload")) {
            plugin.getLocaleManager().sendMessage(sender, "command.no_permission");
            return;
        }
        plugin.reload();
        plugin.getLocaleManager().sendMessage(sender, "command.reload");
    }

    private void handleCredits(CommandSender sender) {
        boolean permissionRequired = plugin.getConfig().getBoolean("settings.credits_command_requires_permission",
                true);
        if (permissionRequired && !sender.hasPermission("justrtp.command.credits")) {
            plugin.getLocaleManager().sendMessage(sender, "command.no_permission");
            return;
        }
        sendCredits(sender);
    }

    private void sendCredits(CommandSender sender) {
        String version = plugin.getPluginMeta().getVersion();
        MiniMessage mm = MiniMessage.miniMessage();
        String link = "https://builtbybit.com/resources/justrtp-lightweight-fast-randomtp.70322/";
        List<String> creditsMessage = Arrays.asList("",
                "<gradient:#20B2AA:#7FFFD4>JustRTP</gradient> <gray>v" + version, "",
                "<gray>Developed by <white>kotori</white>.",
                "<click:open_url:'" + link
                        + "'><hover:show_text:'<green>Click to visit!'><#7FFFD4><u>Click here to check for updates!</u></hover></click>",
                "");
        sender.sendMessage(mm
                .deserialize("<gradient:#20B2AA:#7FFFD4>--------------------------------------------------<gradient>"));
        creditsMessage.forEach(line -> sender.sendMessage(mm.deserialize(" " + line)));
        sender.sendMessage(mm
                .deserialize("<gradient:#7FFFD4:#20B2AA>--------------------------------------------------<gradient>"));
    }

    private String getAvailableWorldsList() {
        return Bukkit.getWorlds().stream()
                .filter(w -> plugin.getRtpService().isRtpEnabled(w))
                .map(World::getName)
                .collect(java.util.stream.Collectors.joining(", "));
    }
}