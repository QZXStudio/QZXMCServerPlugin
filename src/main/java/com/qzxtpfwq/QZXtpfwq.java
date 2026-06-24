package com.qzxtpfwq;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.InitialDirContext;
import java.io.*;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class QZXtpfwq extends JavaPlugin {

    private static QZXtpfwq instance;
    private String noPermissionMsg;
    private String successMsg;
    private String cooldownMsg;
    private String invalidAddressMsg;
    private String transferFailedMsg;
    private int cooldownSeconds;
    private final Map<UUID, Long> cooldowns = new HashMap<>();

    private Map<String, ServerInfo> serverAliases = new HashMap<>();
    private ServerStatusChecker statusChecker;
    private int cacheSeconds;
    private int connectTimeout = 3000;
    private int protocolVersion = 765;
    private String localServerDomain = null;
    private boolean debug = false;
    private boolean useApiForExternal = false;

    public static class ServerInfo {
        public final String address;
        public final int port;
        public ServerInfo(String address, int port) {
            this.address = address;
            this.port = port;
        }
    }

    public static class ServerStatus {
        private final boolean online;
        private final int onlinePlayers;
        private final int maxPlayers;
        private final String motd;
        private final String errorMessage;

        public ServerStatus(boolean online, int onlinePlayers, int maxPlayers, String motd, String errorMessage) {
            this.online = online;
            this.onlinePlayers = onlinePlayers;
            this.maxPlayers = maxPlayers;
            this.motd = motd;
            this.errorMessage = errorMessage;
        }

        public boolean isOnline() { return online; }
        public int getOnlinePlayers() { return onlinePlayers; }
        public int getMaxPlayers() { return maxPlayers; }
        public String getMotd() { return motd; }
        public String getErrorMessage() { return errorMessage; }

        public static ServerStatus offline(String error) {
            return new ServerStatus(false, 0, 0, null, error);
        }

        public static ServerStatus online(int online, int max, String motd) {
            return new ServerStatus(true, online, max, motd, null);
        }
    }

    public interface ServerStatusCallback {
        void onResult(ServerStatus status);
    }

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        reloadConfigSettings();
        loadServerAliases();
        statusChecker = new ServerStatusChecker(this, cacheSeconds, connectTimeout);
        printStartupBanner();

        checkAndDownloadPlaceholderAPI();

        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            try {
                new PlaceholderAPIExpansion(this).register();
                getLogger().info("已注册 PlaceholderAPI 扩展");
            } catch (Throwable t) {
                getLogger().warning("无法注册 PlaceholderAPI 扩展: " + t.getMessage());
            }
        } else {
            getLogger().info("PlaceholderAPI 未安装，状态查询占位符功能不可用。可重启服务器后自动生效。");
        }

        getLogger().info("QZXtpfwq 已加载 - 作者 ytsj");
    }

    @Override
    public void onDisable() {
        cooldowns.clear();
        getLogger().info("QZXtpfwq 已卸载");
    }

    private void checkAndDownloadPlaceholderAPI() {
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) return;
        getLogger().info("未检测到 PlaceholderAPI，正在尝试自动下载...");
        new BukkitRunnable() {
            @Override
            public void run() {
                String downloadUrl = "https://github.com/PlaceholderAPI/PlaceholderAPI/releases/download/2.11.6/PlaceholderAPI-2.11.6.jar";
                File targetFile = new File(getDataFolder().getParentFile(), "PlaceholderAPI-2.11.6.jar");
                try {
                    URL url = new URL(downloadUrl);
                    HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                    connection.setRequestProperty("User-Agent", "QZXtpfwq Plugin");
                    connection.setConnectTimeout(10000);
                    connection.setReadTimeout(10000);
                    int responseCode = connection.getResponseCode();
                    if (responseCode == HttpURLConnection.HTTP_OK) {
                        try (InputStream in = connection.getInputStream()) {
                            Files.copy(in, targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                        }
                        getLogger().info("PlaceholderAPI 下载成功！文件保存在: " + targetFile.getAbsolutePath());
                        getLogger().warning("请重启服务器以加载 PlaceholderAPI，之后 QZXtpfwq 的占位符功能将生效。");
                    } else {
                        getLogger().warning("PlaceholderAPI 下载失败，HTTP 响应码: " + responseCode);
                    }
                } catch (Exception e) {
                    getLogger().warning("PlaceholderAPI 自动下载失败: " + e.getMessage());
                    getLogger().warning("请手动从 https://www.spigotmc.org/resources/placeholderapi.6245/ 下载并放入 plugins 文件夹。");
                }
            }
        }.runTaskAsynchronously(this);
    }

    private void reloadConfigSettings() {
        reloadConfig();
        FileConfiguration config = getConfig();
        debug = config.getBoolean("debug", false);
        useApiForExternal = config.getBoolean("use-api-for-external", false);
        noPermissionMsg = colorize(config.getString("messages.no-permission", "&c你没有权限使用此命令！"));
        successMsg = colorize(config.getString("messages.success", "&a正在将你传送至目标服务器..."));
        cooldownMsg = colorize(config.getString("messages.cooldown", "&c请等待 {seconds} 秒后再试"));
        invalidAddressMsg = colorize(config.getString("messages.invalid-address", "&c无效的服务器地址！正确格式: /qzxtpfwq fwq <域名或IP:端口>"));
        transferFailedMsg = colorize(config.getString("messages.transfer-failed", "&c传送失败，请检查地址是否正确"));
        cooldownSeconds = config.getInt("cooldown-seconds", 0);
        cacheSeconds = config.getInt("status-cache-seconds", 30);
        connectTimeout = config.getInt("connect-timeout-millis", 3000);
        protocolVersion = config.getInt("protocol-version", 765);
        localServerDomain = config.getString("local-server-domain", null);
        if (localServerDomain != null && localServerDomain.isEmpty()) localServerDomain = null;
    }

    private void loadServerAliases() {
        serverAliases.clear();
        ConfigurationSection section = getConfig().getConfigurationSection("servers");
        if (section != null) {
            for (String alias : section.getKeys(false)) {
                String address = section.getString(alias + ".address");
                int port = section.getInt(alias + ".port", 25565);
                if (port <= 0) port = 25565;
                if (address != null && !address.isEmpty()) {
                    serverAliases.put(alias.toLowerCase(), new ServerInfo(address, port));
                }
            }
        }
    }

    public ServerInfo getServerInfo(String alias) {
        return serverAliases.get(alias.toLowerCase());
    }

    public void queryServerStatus(String host, int port, ServerStatusCallback callback) {
        statusChecker.queryStatus(host, port, callback);
    }

    private static class ResolvedAddress {
        final String host;
        final int port;
        final String originalHost;
        ResolvedAddress(String host, int port, String originalHost) {
            this.host = host;
            this.port = port;
            this.originalHost = originalHost;
        }
    }

    private ResolvedAddress resolveWithSrv(String input) {
        if (input.contains(":")) {
            InetSocketAddress addr = parseAddress(input);
            if (addr == null) return null;
            return new ResolvedAddress(addr.getHostString(), addr.getPort(), input.split(":")[0]);
        }
        try {
            Hashtable<String, String> env = new Hashtable<>();
            env.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
            env.put("java.naming.provider.url", "dns:");
            InitialDirContext ctx = new InitialDirContext(env);
            Attributes attrs = ctx.getAttributes("_minecraft._tcp." + input, new String[]{"SRV"});
            Attribute srvAttr = attrs.get("SRV");
            if (srvAttr != null) {
                String srvRecord = (String) srvAttr.get();
                String[] parts = srvRecord.split(" ");
                if (parts.length >= 4) {
                    int port = Integer.parseInt(parts[2]);
                    String target = parts[3];
                    if (target.endsWith(".")) target = target.substring(0, target.length() - 1);
                    debug("SRV 解析成功: " + input + " -> " + target + ":" + port);
                    ctx.close();
                    return new ResolvedAddress(target, port, input);
                }
            }
            ctx.close();
        } catch (Exception e) {
            debug("SRV 查询失败: " + e.getMessage());
        }
        return new ResolvedAddress(input, 25565, input);
    }

    private InetSocketAddress parseAddress(String input) {
        if (input == null || input.trim().isEmpty()) return null;
        String host;
        int port = 25565;
        if (input.contains(":")) {
            String[] parts = input.split(":", 2);
            host = parts[0];
            try {
                port = Integer.parseInt(parts[1]);
                if (port <= 0) port = 25565;
                if (port < 1 || port > 65535) return null;
            } catch (NumberFormatException e) {
                return null;
            }
        } else {
            host = input;
        }
        if (host.isEmpty()) return null;
        return InetSocketAddress.createUnresolved(host, port);
    }

    private boolean isLocalServer(String host, int port) {
        if (localServerDomain != null && (host.equalsIgnoreCase(localServerDomain) || host.equalsIgnoreCase(localServerDomain.split(":")[0]))) {
            debug("本地域名匹配: " + host);
            return true;
        }
        int localPort = Bukkit.getServer().getPort();
        if (host.equalsIgnoreCase("localhost") || host.equals("127.0.0.1")) return port == localPort;
        try {
            InetAddress localHost = InetAddress.getLocalHost();
            InetAddress[] addresses = InetAddress.getAllByName(host);
            for (InetAddress addr : addresses) {
                if (addr.equals(localHost) || addr.isLoopbackAddress()) return port == localPort;
            }
        } catch (Exception e) {
            debug("本地地址解析失败: " + e.getMessage());
        }
        String serverIp = Bukkit.getServer().getIp();
        if (serverIp != null && !serverIp.isEmpty()) {
            if (host.equals(serverIp) || host.equals(serverIp.split(":")[0])) return port == localPort;
        }
        return false;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sendHelp(sender);
            return true;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("qzxtpfwq.reload") && !sender.isOp()) {
                sender.sendMessage(colorize("&c你没有权限重载配置！"));
                return true;
            }
            reloadConfigSettings();
            loadServerAliases();
            statusChecker = new ServerStatusChecker(this, cacheSeconds, connectTimeout);
            sender.sendMessage(colorize("&aQZXtpfwq 配置已重载"));
            return true;
        }

        if (args[0].equalsIgnoreCase("status")) {
            if (args.length < 2) {
                sender.sendMessage(colorize("&c用法: /qzxtpfwq status <服务器别名或地址>"));
                return true;
            }
            if (!sender.hasPermission("qzxtpfwq.status")) {
                sender.sendMessage(noPermissionMsg);
                return true;
            }
            String target = args[1];
            ServerInfo info = getServerInfo(target);
            if (info != null) {
                if (isLocalServer(info.address, info.port)) {
                    sendLocalStatus(sender);
                    return true;
                }
                if (useApiForExternal) {
                    queryViaApi(target, sender);
                    return true;
                }
                sender.sendMessage(colorize("&e正在查询服务器 " + target + " (" + info.address + ":" + info.port + ") 的状态..."));
                statusChecker.queryStatusWithHandshakeHost(info.address, info.port, info.address, status -> sendStatusResult(sender, status));
                return true;
            } else {
                if (localServerDomain != null && target.equalsIgnoreCase(localServerDomain)) {
                    sendLocalStatus(sender);
                    return true;
                }
                ResolvedAddress resolved = resolveWithSrv(target);
                if (resolved == null) {
                    sender.sendMessage(colorize("&c无效的服务器地址！"));
                    return true;
                }
                if (isLocalServer(resolved.host, resolved.port)) {
                    sendLocalStatus(sender);
                    return true;
                }
                if (useApiForExternal) {
                    queryViaApi(target, sender);
                    return true;
                }
                sender.sendMessage(colorize("&e正在查询服务器 " + resolved.host + ":" + resolved.port + " ..."));
                statusChecker.queryStatusWithHandshakeHost(resolved.host, resolved.port, resolved.originalHost, status -> {
                    if (!status.isOnline() && !resolved.originalHost.equals(resolved.host)) {
                        debug("原始域名握手失败，尝试使用解析后的主机作为握手 Host: " + resolved.host);
                        statusChecker.queryStatusWithHandshakeHost(resolved.host, resolved.port, resolved.host, status2 -> sendStatusResult(sender, status2));
                    } else {
                        sendStatusResult(sender, status);
                    }
                });
                return true;
            }
        }

        if (args[0].equalsIgnoreCase("fwq")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage(colorize("&c只有玩家可以使用此命令！"));
                return true;
            }
            Player player = (Player) sender;
            if (!player.hasPermission("qzxtpfwq.use")) {
                player.sendMessage(noPermissionMsg);
                return true;
            }
            if (args.length < 2) {
                player.sendMessage(colorize("&e用法: /qzxtpfwq fwq <服务器地址[:端口]>"));
                player.sendMessage(colorize("&e示例: /qzxtpfwq fwq mc.ytsjmc.cn 或 /qzxtpfwq fwq 192.168.1.100:25565"));
                return true;
            }
            String addressArg = args[1];
            ResolvedAddress resolved = resolveWithSrv(addressArg);
            if (resolved == null) {
                player.sendMessage(invalidAddressMsg);
                return true;
            }
            InetSocketAddress targetAddress = InetSocketAddress.createUnresolved(resolved.host, resolved.port);
            if (cooldownSeconds > 0) {
                UUID uuid = player.getUniqueId();
                long now = System.currentTimeMillis();
                Long last = cooldowns.get(uuid);
                if (last != null && (now - last) < cooldownSeconds * 1000L) {
                    long remaining = (cooldownSeconds - (now - last) / 1000);
                    player.sendMessage(cooldownMsg.replace("{seconds}", String.valueOf(remaining)));
                    return true;
                }
                cooldowns.put(uuid, now);
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        cooldowns.remove(uuid);
                    }
                }.runTaskLaterAsynchronously(this, cooldownSeconds * 20L);
            }
            player.sendMessage(successMsg);
            try {
                Method transferMethod = player.getClass().getMethod("transferTo", InetSocketAddress.class);
                transferMethod.invoke(player, targetAddress);
            } catch (Exception e) {
                player.sendMessage(transferFailedMsg);
                getLogger().warning("传送失败: " + e.getMessage());
            }
            return true;
        }

        sendHelp(sender);
        return true;
    }

    private void queryViaApi(String hostname, CommandSender sender) {
        String pureHost = hostname.split(":")[0];
        sender.sendMessage(colorize("&e正在通过 API 查询服务器 " + pureHost + " 的状态..."));
        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    URL url = new URL("https://api.mcsrvstat.us/2/" + pureHost);
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("GET");
                    conn.setConnectTimeout(5000);
                    conn.setReadTimeout(5000);
                    int responseCode = conn.getResponseCode();
                    if (responseCode == 200) {
                        BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                        StringBuilder json = new StringBuilder();
                        String line;
                        while ((line = reader.readLine()) != null) json.append(line);
                        reader.close();
                        JsonObject obj = JsonParser.parseString(json.toString()).getAsJsonObject();
                        if (obj.has("online") && obj.get("online").getAsBoolean()) {
                            JsonObject players = obj.getAsJsonObject("players");
                            int online = players.get("online").getAsInt();
                            int max = players.get("max").getAsInt();
                            String motd = "";
                            if (obj.has("motd") && obj.get("motd").isJsonObject()) {
                                motd = obj.get("motd").getAsJsonObject().get("clean").getAsString();
                            }
                            ServerStatus status = ServerStatus.online(online, max, motd);
                            new BukkitRunnable() {
                                @Override
                                public void run() {
                                    sendStatusResult(sender, status);
                                }
                            }.runTask(QZXtpfwq.this);
                            return;
                        }
                    }
                } catch (Exception e) {
                    debug("API 查询失败: " + e.getMessage());
                }
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        sender.sendMessage(colorize("&c服务器离线或无法查询"));
                    }
                }.runTask(QZXtpfwq.this);
            }
        }.runTaskAsynchronously(this);
    }

    private void sendLocalStatus(CommandSender sender) {
        int online = Bukkit.getOnlinePlayers().size();
        int max = Bukkit.getMaxPlayers();
        String motd = Bukkit.getMotd();
        sender.sendMessage(colorize("&a服务器在线（本地）"));
        sender.sendMessage(colorize("&7在线人数: &e" + online + "&7/&e" + max));
        sender.sendMessage(colorize("&7MOTD: &f" + motd));
    }

    private void sendStatusResult(CommandSender sender, ServerStatus status) {
        if (status.isOnline()) {
            sender.sendMessage(colorize("&a服务器在线"));
            sender.sendMessage(colorize("&7在线人数: &e" + status.getOnlinePlayers() + "&7/&e" + status.getMaxPlayers()));
            if (status.getMotd() != null && !status.getMotd().isEmpty()) {
                sender.sendMessage(colorize("&7MOTD: &f" + status.getMotd()));
            }
        } else {
            sender.sendMessage(colorize("&c服务器离线"));
            if (status.getErrorMessage() != null) {
                sender.sendMessage(colorize("&7原因: &f" + status.getErrorMessage()));
            }
        }
    }

    private void sendHelp(CommandSender sender) {
        if (sender instanceof Player) {
            Player player = (Player) sender;
            player.spigot().sendMessage(new TextComponent(colorize("&6========== QZXtpfwq 命令帮助 ==========")));
            sendClickableLine(player, "/qzxtpfwq fwq <地址>", "传送到指定服务器（地址:端口）", "/qzxtpfwq fwq ");
            sendClickableLine(player, "/qzxtpfwq status <别名|地址>", "查询服务器状态（在线人数等）", "/qzxtpfwq status ");
            sendClickableLine(player, "/qzxtpfwq reload", "重载配置文件", "/qzxtpfwq reload");
            sendClickableLine(player, "/qzxtpfwq help", "显示此帮助信息", "/qzxtpfwq help");
            player.spigot().sendMessage(new TextComponent(colorize("&6=========================================")));
            player.spigot().sendMessage(new TextComponent(colorize("&e提示：点击上方命令即可自动填入聊天栏")));
        } else {
            sender.sendMessage(colorize("&6========== QZXtpfwq 命令帮助 =========="));
            sender.sendMessage(colorize("&e/qzxtpfwq fwq <地址> §7- 传送到指定服务器（地址:端口）"));
            sender.sendMessage(colorize("&e/qzxtpfwq status <别名|地址> §7- 查询服务器状态（在线人数等）"));
            sender.sendMessage(colorize("&e/qzxtpfwq reload §7- 重载配置文件"));
            sender.sendMessage(colorize("&e/qzxtpfwq help §7- 显示此帮助信息"));
            sender.sendMessage(colorize("&6========================================="));
        }
    }

    private void sendClickableLine(Player player, String display, String description, String suggest) {
        TextComponent commandPart = new TextComponent(colorize("&e" + display));
        commandPart.setClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, suggest));
        TextComponent separator = new TextComponent(colorize(" &7- "));
        TextComponent descPart = new TextComponent(colorize("&7" + description));
        commandPart.addExtra(separator);
        commandPart.addExtra(descPart);
        player.spigot().sendMessage(commandPart);
    }

    public static String colorize(String message) {
        if (message == null) return null;
        Pattern hexPattern = Pattern.compile("(&?#([0-9a-fA-F]{6}))");
        Matcher matcher = hexPattern.matcher(message);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String hex = matcher.group(2);
            StringBuilder replacement = new StringBuilder("§x");
            for (char c : hex.toCharArray()) {
                replacement.append('§').append(c);
            }
            matcher.appendReplacement(sb, replacement.toString());
        }
        matcher.appendTail(sb);
        return ChatColor.translateAlternateColorCodes('&', sb.toString());
    }

    private void printStartupBanner() {
        ConsoleCommandSender console = Bukkit.getConsoleSender();
        String[] lines = {
                "&e██╗   ██╗████████╗████████╗██████╗ ███████╗██╗    ██╗ ██████╗",
                "&e╚██╗ ██╔╝╚══██╔══╝╚══██╔══╝██╔══██╗██╔════╝██║    ██║██╔══██╗",
                "&e ╚████╔╝    ██║      ██║   ██████╔╝█████╗  ██║ █╗ ██║██████╔╝",
                "&e  ╚██╔╝     ██║      ██║   ██╔═══╝ ██╔══╝  ██║███╗██║██╔═══╝",
                "&e   ██║      ██║      ██║   ██║     ██║     ╚███╔███╔╝██║",
                "&e   ╚═╝      ╚═╝      ╚═╝   ╚═╝     ╚═╝      ╚══╝╚══╝ ╚═╝",
                "&e                QZXtpfwq v" + getDescription().getVersion() + " - 跨服传送+状态查询"
        };
        for (String line : lines) {
            console.sendMessage(colorize(line));
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> subCommands = new ArrayList<>(Arrays.asList("fwq", "status", "help"));
            if (sender.hasPermission("qzxtpfwq.reload") || sender.isOp()) subCommands.add("reload");
            return filterStartingWith(subCommands, args[0]);
        } else if (args.length == 2) {
            String subCmd = args[0].toLowerCase();
            if (subCmd.equals("fwq") || subCmd.equals("status")) {
                List<String> aliases = new ArrayList<>(serverAliases.keySet());
                return filterStartingWith(aliases, args[1]);
            }
        }
        return Collections.emptyList();
    }

    private List<String> filterStartingWith(List<String> list, String prefix) {
        if (prefix == null || prefix.isEmpty()) return list;
        return list.stream().filter(s -> s.toLowerCase().startsWith(prefix.toLowerCase())).toList();
    }

    private class ServerStatusChecker {
        private final QZXtpfwq plugin;
        private final ConcurrentHashMap<String, CachedStatus> cache = new ConcurrentHashMap<>();
        private final int cacheSeconds;
        private final int connectTimeout;
        private final int[] fallbackVersions = {766, 765, 763, 762, 760, 759, 758, 757, 756, 754, 0};

        private static class CachedStatus {
            final ServerStatus status;
            final long expireTime;
            CachedStatus(ServerStatus status, long expireTime) {
                this.status = status;
                this.expireTime = expireTime;
            }
        }

        ServerStatusChecker(QZXtpfwq plugin, int cacheSeconds, int connectTimeout) {
            this.plugin = plugin;
            this.cacheSeconds = cacheSeconds;
            this.connectTimeout = connectTimeout;
        }

        void queryStatus(String host, int port, ServerStatusCallback callback) {
            queryStatusWithHandshakeHost(host, port, host, callback);
        }

        void queryStatusWithHandshakeHost(String host, int port, String handshakeHost, ServerStatusCallback callback) {
            String key = host + ":" + port + ":" + handshakeHost;
            CachedStatus cached = cache.get(key);
            if (cached != null && System.currentTimeMillis() < cached.expireTime) {
                callback.onResult(cached.status);
                return;
            }

            new BukkitRunnable() {
                @Override
                public void run() {
                    ServerStatus status = fetchStatusWithAutoProtocol(host, port, handshakeHost);
                    long expire = System.currentTimeMillis() + cacheSeconds * 1000L;
                    cache.put(key, new CachedStatus(status, expire));
                    new BukkitRunnable() {
                        @Override
                        public void run() {
                            callback.onResult(status);
                        }
                    }.runTask(plugin);
                }
            }.runTaskAsynchronously(plugin);
        }

        private ServerStatus fetchStatusWithAutoProtocol(String host, int port, String handshakeHost) {
            for (int ver : fallbackVersions) {
                ServerStatus status = fetchStatus(host, port, handshakeHost, ver);
                if (status.isOnline()) {
                    if (ver != protocolVersion) {
                        plugin.getLogger().info("自动协议版本探测成功: " + host + ":" + port + " 使用协议版本 " + ver);
                    }
                    return status;
                }
            }
            return ServerStatus.offline("所有协议版本均失败");
        }

        private ServerStatus fetchStatus(String host, int port, String handshakeHost, int protocolVersion) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(host, port), connectTimeout);
                socket.setSoTimeout(connectTimeout);
                DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                DataInputStream in = new DataInputStream(socket.getInputStream());

                ByteArrayOutputStream handshakeBytes = new ByteArrayOutputStream();
                DataOutputStream handshake = new DataOutputStream(handshakeBytes);
                handshake.writeByte(0x00);
                writeVarInt(handshake, protocolVersion);
                handshake.writeUTF(handshakeHost);
                handshake.writeShort(port);
                writeVarInt(handshake, 1);
                writeVarInt(out, handshakeBytes.size());
                out.write(handshakeBytes.toByteArray());

                writeVarInt(out, 1);
                out.writeByte(0x00);

                int size = readVarInt(in);
                int packetId = readVarInt(in);
                if (packetId == 0x00) {
                    int jsonLength = readVarInt(in);
                    byte[] jsonData = new byte[jsonLength];
                    in.readFully(jsonData);
                    String json = new String(jsonData, StandardCharsets.UTF_8);
                    JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
                    JsonObject players = obj.getAsJsonObject("players");
                    int online = players.get("online").getAsInt();
                    int max = players.get("max").getAsInt();
                    String motd = obj.get("description").toString();
                    return ServerStatus.online(online, max, motd);
                }
            } catch (Exception e) {
                debug("状态查询失败 " + host + ":" + port + " (握手Host=" + handshakeHost + ", 协议版本=" + protocolVersion + ") - " + e.getClass().getSimpleName() + ": " + e.getMessage());
                return ServerStatus.offline("连接失败: " + e.getMessage());
            }
            return ServerStatus.offline("未知错误");
        }

        private void writeVarInt(DataOutputStream out, int value) throws IOException {
            do {
                byte temp = (byte) (value & 0x7F);
                value >>>= 7;
                if (value != 0) temp |= 0x80;
                out.writeByte(temp);
            } while (value != 0);
        }

        private int readVarInt(DataInputStream in) throws IOException {
            int result = 0;
            int shift = 0;
            byte b;
            do {
                b = in.readByte();
                result |= (b & 0x7F) << shift;
                shift += 7;
            } while ((b & 0x80) != 0);
            return result;
        }
    }

    private static class PlaceholderAPIExpansion extends me.clip.placeholderapi.expansion.PlaceholderExpansion {
        private final QZXtpfwq plugin;
        PlaceholderAPIExpansion(QZXtpfwq plugin) { this.plugin = plugin; }
        @Override public String getIdentifier() { return "qzxtpfwq"; }
        @Override public String getAuthor() { return "ytsj"; }
        @Override public String getVersion() { return plugin.getDescription().getVersion(); }
        @Override public boolean persist() { return true; }
        @Override public String onPlaceholderRequest(Player player, String params) {
            String[] parts = params.split("_", 3);
            if (parts.length < 2) return null;
            String serverAlias = parts[0];
            String type = parts[1].toLowerCase();
            ServerInfo info = plugin.getServerInfo(serverAlias);
            if (info == null) return "未知服务器";
            AtomicReference<ServerStatus> statusRef = new AtomicReference<>();
            plugin.queryServerStatus(info.address, info.port, statusRef::set);
            long start = System.currentTimeMillis();
            while (statusRef.get() == null && System.currentTimeMillis() - start < 2000) {
                try { Thread.sleep(50); } catch (InterruptedException ignored) {}
            }
            ServerStatus status = statusRef.get();
            if (status == null) return "超时";
            switch (type) {
                case "status": return status.isOnline() ? "§a在线" : "§c离线";
                case "online": return status.isOnline() ? String.valueOf(status.getOnlinePlayers()) : "0";
                case "max": return status.isOnline() ? String.valueOf(status.getMaxPlayers()) : "0";
                default: return null;
            }
        }
    }

    public static QZXtpfwq getInstance() { return instance; }

    private void debug(String msg) {
        if (debug) getLogger().info("[DEBUG] " + msg);
    }
}
