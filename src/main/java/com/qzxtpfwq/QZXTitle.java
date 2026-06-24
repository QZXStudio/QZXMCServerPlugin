package com.qzxtpfwq;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class QZXTitle implements CommandExecutor, TabCompleter, Listener {

    private final JavaPlugin plugin;
    private final NapCatWS napCatWS;
    private final String serverName;
    private final List<Long> groupIds;
    private final Map<UUID, String> titles = new ConcurrentHashMap<>();
    private final File titlesFile;

    public QZXTitle(JavaPlugin plugin, NapCatWS napCatWS, String serverName, List<Long> groupIds) {
        this.plugin = plugin;
        this.napCatWS = napCatWS;
        this.serverName = serverName;
        this.groupIds = groupIds;
        this.titlesFile = new File(plugin.getDataFolder(), "titles.yml");
        loadTitles();
    }

    private void loadTitles() {
        if (!titlesFile.exists()) return;
        YamlConfiguration config = YamlConfiguration.loadConfiguration(titlesFile);
        for (String key : config.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(key);
                String title = config.getString(key);
                if (title != null && !title.isEmpty()) {
                    titles.put(uuid, title);
                }
            } catch (IllegalArgumentException ignored) {}
        }
    }

    public void saveTitles() {
        YamlConfiguration config = new YamlConfiguration();
        for (Map.Entry<UUID, String> entry : titles.entrySet()) {
            config.set(entry.getKey().toString(), entry.getValue());
        }
        try {
            config.save(titlesFile);
        } catch (IOException e) {
            plugin.getLogger().warning("保存称号数据失败: " + e.getMessage());
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.isOp()) return true;

        if (args.length == 0) {
            sender.sendMessage(ChatColor.YELLOW + "用法: /qzx title <玩家名> [称号内容]  或  /qzx dtitle <玩家名>");
            return true;
        }

        String subCmd = args[0].toLowerCase();

        if (subCmd.equals("title")) {
            if (args.length < 2) {
                sender.sendMessage(ChatColor.RED + "用法: /qzx title <玩家名> [称号内容]");
                return true;
            }
            String targetName = args[1];
            OfflinePlayer target = Bukkit.getOfflinePlayer(targetName);
            if (target.getName() == null) {
                sender.sendMessage(ChatColor.RED + "找不到玩家: " + targetName);
                return true;
            }

            if (args.length < 3) {
                String oldTitle = titles.remove(target.getUniqueId());
                if (oldTitle != null) {
                    saveTitles();
                    broadcastChange(target.getName(), sender.getName(), null, oldTitle);
                } else {
                    sender.sendMessage(ChatColor.YELLOW + target.getName() + " 没有称号");
                }
                return true;
            }

            String newTitle = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
            String oldTitle = titles.put(target.getUniqueId(), newTitle);
            saveTitles();

            broadcastChange(target.getName(), sender.getName(), newTitle, oldTitle);
            return true;
        }

        if (subCmd.equals("dtitle")) {
            if (args.length < 2) {
                sender.sendMessage(ChatColor.RED + "用法: /qzx dtitle <玩家名>");
                return true;
            }
            String targetName = args[1];
            OfflinePlayer target = Bukkit.getOfflinePlayer(targetName);
            if (target.getName() == null) {
                sender.sendMessage(ChatColor.RED + "找不到玩家: " + targetName);
                return true;
            }
            String oldTitle = titles.remove(target.getUniqueId());
            if (oldTitle != null) {
                saveTitles();
                broadcastChange(target.getName(), sender.getName(), null, oldTitle);
            } else {
                sender.sendMessage(ChatColor.YELLOW + target.getName() + " 没有称号");
            }
            return true;
        }

        return false;
    }

    private void broadcastChange(String targetName, String setterName, String newTitle, String oldTitle) {
        String msg;
        if (newTitle != null && oldTitle != null) {
            msg = targetName + "被的称号被" + setterName + "从[" + oldTitle + "]改为[" + newTitle + "]";
        } else if (newTitle != null) {
            msg = targetName + "被" + setterName + "设置称号[" + newTitle + "]";
        } else {
            msg = targetName + "被" + setterName + "取消称号[" + oldTitle + "]，现在这个入没有称号啦";
        }

        String gameMsg = ChatColor.translateAlternateColorCodes('&', "&e[称号] &f" + msg);
        Bukkit.getOnlinePlayers().forEach(p -> p.sendMessage(gameMsg));
        Bukkit.getConsoleSender().sendMessage(gameMsg);

        napCatWS.sendGroupMessage(groupIds, "【" + serverName + "消息】" + msg);

        Player targetPlayer = Bukkit.getPlayer(targetName);
        if (targetPlayer != null) {
            refreshTabName(targetPlayer);
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.isOp()) return Collections.emptyList();

        if (args.length == 1) {
            return filterStartsWith(Arrays.asList("title", "dtitle"), args[0]);
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("title") || args[0].equalsIgnoreCase("dtitle"))) {
            return filterStartsWith(
                    Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(),
                    args[1]);
        }
        return Collections.emptyList();
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        String prefix = getGamePrefix(player);
        String format = event.getFormat();
        if (format.contains("%1$s")) {
            event.setFormat(format.replace("%1$s", prefix + "%1$s"));
        } else {
            event.setFormat(prefix + format);
        }
    }

    public String getGameDisplayName(Player player) {
        return getGamePrefix(player) + player.getName();
    }

    public String getPlainDisplayName(Player player) {
        return getPlainPrefix(player) + " " + player.getName();
    }

    public String getGamePrefix(Player player) {
        String rawTitle = titles.get(player.getUniqueId());
        boolean isOp = player.isOp();

        if (isOp && rawTitle != null) {
            return "§9[§9op§9]§f[" + formatTitleColor(rawTitle, true) + "§f]§r";
        } else if (isOp) {
            return "§9[§9op§9]§r";
        } else if (rawTitle != null) {
            return "§f[" + formatTitleColor(rawTitle, false) + "§f]§r";
        } else {
            return "§7[普通玩家]§r";
        }
    }

    public String getPlainPrefix(Player player) {
        String rawTitle = titles.get(player.getUniqueId());
        boolean isOp = player.isOp();
        String titleText = rawTitle != null ? ChatColor.stripColor(ChatColor.translateAlternateColorCodes('&', rawTitle)) : null;

        if (isOp && titleText != null) {
            return "[op][" + titleText + "]";
        } else if (isOp) {
            return "[op]";
        } else if (titleText != null) {
            return "[" + titleText + "]";
        } else {
            return "[普通玩家]";
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        refreshTabName(event.getPlayer());
    }

    public void refreshTabName(Player player) {
        String fullDisplay = getGamePrefix(player) + player.getName();
        player.setDisplayName(fullDisplay);

        String tabName = fullDisplay;
        if (tabName.length() > 16) {
            tabName = tabName.substring(0, 16);
        }
        player.setPlayerListName(tabName);

        String prefix = getGamePrefix(player);
        Scoreboard board = Bukkit.getScoreboardManager().getMainScoreboard();
        String teamName = "qzxt_" + player.getName();
        if (teamName.length() > 16) {
            teamName = teamName.substring(0, 16);
        }
        Team team = board.getTeam(teamName);
        if (team == null) {
            team = board.registerNewTeam(teamName);
        }
        team.setPrefix(prefix);
        team.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.ALWAYS);
        if (!team.hasEntry(player.getName())) {
            team.addEntry(player.getName());
        }
    }

    public String replaceAllGameDisplayNames(String text) {
        String result = text;
        List<Player> sorted = new ArrayList<>(Bukkit.getOnlinePlayers());
        sorted.sort((a, b) -> Integer.compare(b.getName().length(), a.getName().length()));
        for (Player p : sorted) {
            result = result.replace(p.getName(), getGamePrefix(p) + p.getName() + "§r");
        }
        return result;
    }

    public String replaceAllPlainDisplayNames(String text) {
        String result = text;
        List<Player> sorted = new ArrayList<>(Bukkit.getOnlinePlayers());
        sorted.sort((a, b) -> Integer.compare(b.getName().length(), a.getName().length()));
        for (Player p : sorted) {
            result = result.replace(p.getName(),  " " + p.getName());
//            getPlainPrefix(p) +
        }
        return result;
    }

    private String formatTitleColor(String rawTitle, boolean isOp) {
        String converted = ChatColor.translateAlternateColorCodes('&', rawTitle);
        if (converted.contains("§")) {
            return converted;
        }
        return (isOp ? "§d" : "§6") + converted;
    }

    private List<String> filterStartsWith(List<String> list, String prefix) {
        if (prefix == null || prefix.isEmpty()) return list;
        return list.stream().filter(s -> s.toLowerCase().startsWith(prefix.toLowerCase())).toList();
    }
}
