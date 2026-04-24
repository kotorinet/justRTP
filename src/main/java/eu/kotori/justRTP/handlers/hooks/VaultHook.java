package eu.kotori.justRTP.handlers.hooks;

import eu.kotori.justRTP.JustRTP;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;

public class VaultHook {
    private final JustRTP plugin;
    private Economy economy = null;

    public VaultHook(JustRTP plugin) {
        this.plugin = plugin;
        if (plugin.getServer().getPluginManager().getPlugin("Vault") != null) {
            setupEconomy();
        }
    }

    public boolean setupEconomy() {
        if (plugin.getServer().getPluginManager().getPlugin("Vault") == null) {
            return false;
        }
        RegisteredServiceProvider<Economy> rsp = plugin.getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            return false;
        }
        economy = rsp.getProvider();
        return economy != null;
    }

    public boolean hasEconomy() {
        return economy != null;
    }

    public double getBalance(Player player) {
        if (economy == null) return 0.0;
        return economy.getBalance(player);
    }

    public boolean withdrawPlayer(Player player, double amount) {
        if (economy == null || amount <= 0) return true;
        EconomyResponse response = economy.withdrawPlayer(player, amount);
        return response.transactionSuccess();
    }

    public boolean depositPlayer(Player player, double amount) {
        if (economy == null || amount <= 0) return true;
        EconomyResponse response = economy.depositPlayer(player, amount);
        return response.transactionSuccess();
    }
}