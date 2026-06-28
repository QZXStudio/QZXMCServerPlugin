package com.qzxtpfwq;

import io.papermc.paper.advancement.AdvancementDisplay;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.weather.WeatherChangeEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.FileReader;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public final class QZXBotSync extends JavaPlugin implements Listener {

    private NapCatWS napCatWS;
    private QZXTitle qzxTitle;
    private List<Long> groupIds = new ArrayList<>();
    private String serverName = "默认服务器";
    private String wsUrl = "ws://192.168.1.186:20000/?access_token=000000";
    private int reconnectInterval = 10;
    private boolean syncJoin = true;
    private boolean syncQuit = true;
    private boolean syncChat = true;
    private boolean syncDeath = true;
    private boolean syncWeather = true;
    private boolean syncAdvancement = true;
    private boolean reverseSync = true;
    private boolean syncRecall = true;
    private boolean statsShowAllGroups = false;
    private long startTime;

    // 认证系统
    private DatabaseManager dbManager;
    private AuthManager authManager;
    private AuthListener authListener;
    private boolean authEnabled = true;
    private String authMode = "main";
    private String authServerAddress = "localhost:25566";
    private String mainServerAddress = "localhost:25565";
    private String sharedDataDir = "plugins/QZXBotSync/shared";
    private int authMinPasswordLength = 4;
    private int authMaxPasswordLength = 32;
    private int authMaxLoginAttempts = 3;
    private int authSessionTimeoutMinutes = 5;

    private final Map<Pattern, String> deathTranslationMap = new LinkedHashMap<>();
    private final LinkedHashMap<String, String> entityNameMap = new LinkedHashMap<>();
    private final LinkedHashMap<String, String> advancementTitleMap = new LinkedHashMap<>();

    // 转发统计（按群隔离）
    private final Map<Long, long[]> groupStats = new ConcurrentHashMap<>(); // [0]=MC→QQ, [1]=QQ→MC

    private void countMCToQQ() {
        for (long gid : groupIds) {
            groupStats.computeIfAbsent(gid, k -> new long[2])[0]++;
        }
    }

    private long[] getStats(long groupId) {
        return groupStats.getOrDefault(groupId, new long[2]);
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        startTime = System.currentTimeMillis();
        buildEntityNameMap();
        buildDeathTranslations();
        buildAdvancementTitleMap();
        loadConfig(); // 只读配置，不调 napCatWS

        // ──── 认证子服模式 ────
        if ("verify".equalsIgnoreCase(authMode)) {
            getLogger().info("运行模式: 正版认证子服 (online-mode=true 的 Paper 实例)");
            getLogger().info("认证完成后将 transfer 到主服: " + mainServerAddress);

            File sharedDir = new File(sharedDataDir);
            if (!sharedDir.exists()) sharedDir.mkdirs();

            new PremiumAuthVerifyListener(this, sharedDataDir, mainServerAddress).register();
            printBanner();
            getLogger().info("QZXBotSync 认证子服模式已就绪");
            return;
        }

        // ──── 主服模式 ────
        // napCatWS 必须在 initAuthSystem 之前创建，initAuthSystem 里的 AuthListener 构造会用到
        napCatWS = new NapCatWS(getLogger());
        napCatWS.setMonitoredGroups(groupIds);
        napCatWS.configure(wsUrl, reconnectInterval);

        qzxTitle = new QZXTitle(this, napCatWS, serverName, groupIds);
        Bukkit.getPluginManager().registerEvents(qzxTitle, this);
        getCommand("qzx").setExecutor(qzxTitle);
        getCommand("qzx").setTabCompleter(qzxTitle);

        Bukkit.getPluginManager().registerEvents(this, this);

        // 初始化认证系统（创建dbManager + AuthListener）
        initAuthSystem();

        // QQ群回调
        napCatWS.setGroupMessageCallback((groupName, senderQQ, senderName, message) -> {
            String display;
            if (dbManager != null && authEnabled) {
                String boundPlayer = dbManager.getBoundPlayerName(String.valueOf(senderQQ));
                if (boundPlayer != null) {
                    display = ChatColor.translateAlternateColorCodes('&',
                            "&b[QQ群:&f" + groupName + "&b] &e@" + senderName
                                    + "&7(&f" + boundPlayer + "&7): &f" + message);
                } else {
                    display = ChatColor.translateAlternateColorCodes('&',
                            "&b[QQ群:&f" + groupName + "&b] &e" + senderName + "&7: &f" + message);
                }
            } else {
                display = ChatColor.translateAlternateColorCodes('&',
                        "&b[QQ群:&f" + groupName + "&b] &e" + senderName + "&7: &f" + message);
            }
            String finalDisplay = display;
            Bukkit.getOnlinePlayers().forEach(p -> p.sendMessage(finalDisplay));
        });

        napCatWS.setRecallCallback((groupName, senderName, messageId) -> {
            String display = ChatColor.translateAlternateColorCodes('&',
                    "&b[QQ群:&f" + groupName + "&b] &7" + senderName + " &7撤回了一条消息");
            Bukkit.getOnlinePlayers().forEach(p -> p.sendMessage(display));
        });

        napCatWS.setStatsCallback(new NapCatWS.StatsCallback() {
            @Override public void onMCToQQ(long groupId) {
                try { groupStats.computeIfAbsent(groupId, k -> new long[2])[0]++; } catch (Exception ignored) {}
            }
            @Override public void onQQToMC(long groupId) {
                try { groupStats.computeIfAbsent(groupId, k -> new long[2])[1]++; } catch (Exception ignored) {}
            }
        });

        napCatWS.setBotCommandCallback((groupId, userId, commandText) -> {
            if (commandText.contains("MC状态")) {
                return buildStatusReply(groupId);
            }
            if (commandText.contains("MC绑定") || commandText.contains("mc绑定")) {
                return handleQQBindVerify(commandText);
            }
            return null;
        });

        napCatWS.connect();

        printBanner();
        getLogger().info("QZXBotSync 主服模式已加载 (正版认证服: " + authServerAddress + ")");
    }

    @Override
    public void onDisable() {
        if (qzxTitle != null) {
            qzxTitle.saveTitles();
        }
        if (napCatWS != null) {
            napCatWS.shutdown();
        }
        if (dbManager != null) {
            dbManager.close();
        }
        getLogger().info("QZXBotSync 已卸载");
    }

    private void loadConfig() {
        reloadConfig();
        FileConfiguration config = getConfig();
        wsUrl = config.getString("napcat.ws-url", "ws://192.168.1.186:20000/?access_token=000000");
        reconnectInterval = config.getInt("napcat.reconnect-interval", 10);
        groupIds = config.getLongList("qq-groups");

        String configuredName = config.getString("server-name", "");
        if (configuredName != null && !configuredName.isEmpty() && !configuredName.equals("默认服务器")) {
            serverName = configuredName;
        } else {
            serverName = readServerNameFromProperties();
        }
        serverName = ChatColor.stripColor(serverName);

        syncJoin = config.getBoolean("sync.join", true);
        syncQuit = config.getBoolean("sync.quit", true);
        syncChat = config.getBoolean("sync.chat", true);
        syncDeath = config.getBoolean("sync.death", true);
        syncWeather = config.getBoolean("sync.weather", true);
        syncAdvancement = config.getBoolean("sync.advancement", true);
        reverseSync = config.getBoolean("sync.reverse", true);
        syncRecall = config.getBoolean("sync.recall", true);
        statsShowAllGroups = config.getBoolean("stats.show-all-groups", false);

        // napCatWS 在 onEnable 主服分支才创建，这里不能直接调
        if (napCatWS != null) {
            napCatWS.setMonitoredGroups(groupIds);
            napCatWS.configure(wsUrl, reconnectInterval);
        }

        // 读取认证配置
        authEnabled = config.getBoolean("auth.enabled", true);
        authMode = config.getString("auth.mode", "main");
        authServerAddress = config.getString("auth.auth-server-address", "localhost:25566");
        mainServerAddress = config.getString("auth.main-server-address", "localhost:25565");
        sharedDataDir = config.getString("auth.shared-data-dir", "plugins/QZXBotSync/shared");
        authMinPasswordLength = config.getInt("auth.min-password-length", 4);
        authMaxPasswordLength = config.getInt("auth.max-password-length", 32);
        authMaxLoginAttempts = config.getInt("auth.max-login-attempts", 3);
        authSessionTimeoutMinutes = config.getInt("auth.session-timeout-minutes", 5);
        if (authManager != null) {
            authManager.configure(authMinPasswordLength, authMaxPasswordLength,
                    authMaxLoginAttempts, authSessionTimeoutMinutes);
        }
    }

    private void initAuthSystem() {
        if (!authEnabled) {
            getLogger().info("认证系统已禁用");
            return;
        }
        try {
            dbManager = new DatabaseManager(getDataFolder(), getLogger());
            dbManager.init();
        } catch (Exception e) {
            getLogger().severe("初始化认证数据库失败: " + e.getMessage());
            getLogger().severe("认证系统将被禁用！");
            authEnabled = false;
            return;
        }

        authManager = new AuthManager(dbManager, getLogger());
        authManager.configure(authMinPasswordLength, authMaxPasswordLength,
                authMaxLoginAttempts, authSessionTimeoutMinutes);

        authManager.setAuthCallback(new AuthManager.AuthCallback() {
            @Override
            public void onRegister(Player player) {
                // 注册广播由 AuthListener.broadcastAuth 处理（含加入消息）
            }

            @Override
            public void onLogin(Player player) {
                // 登录广播由 AuthListener.broadcastAuth 处理（含加入消息）
            }
        });

        authListener = new AuthListener(authManager, this, napCatWS, qzxTitle, serverName, groupIds, dbManager,
                () -> { for (long gid : groupIds) groupStats.computeIfAbsent(gid, k -> new long[2])[0]++; },
                authServerAddress, sharedDataDir);
        Bukkit.getPluginManager().registerEvents(authListener, this);

        getLogger().info("认证系统已启用 (SQLite)");
    }

    private String readServerNameFromProperties() {
        File propsFile = new File("server.properties");
        if (!propsFile.exists()) return "默认服务器";
        try (FileReader reader = new FileReader(propsFile, java.nio.charset.StandardCharsets.UTF_8)) {
            Properties props = new Properties();
            props.load(reader);
            String motd = props.getProperty("motd", "");
            if (!motd.isEmpty()) return motd;
            String name = props.getProperty("server-name", "");
            if (!name.isEmpty()) return name;
        } catch (Exception e) {
            getLogger().warning("读取 server.properties 失败: " + e.getMessage());
        }
        return "默认服务器";
    }

    private String handleQQBindVerify(String commandText) {
        if (dbManager == null) return "数据库未初始化，无法绑定";

        // 从 "MC绑定 1234567" 中提取验证码
        String code = commandText.replaceAll("(?i)mc绑定\\s*", "").trim();
        if (code.isEmpty()) return "用法: MC绑定 <7位验证码>";

        String qqNumber = dbManager.getQQByVerifyCode(code);
        if (qqNumber == null) return "验证码无效或已过期，请重新在游戏内使用 /bindqq 获取";

        dbManager.confirmBindByCode(code);
        String playerName = dbManager.getBoundPlayerName(qqNumber);
        return "QQ绑定成功！QQ: " + qqNumber + " ⇄ MC玩家: " + playerName;
    }

    private String buildStatusReply(long groupId) {
        String version = Bukkit.getMinecraftVersion();
        String tps;
        try {
            double[] tpsValues = Bukkit.getTPS();
            tps = String.format("%.1f, %.1f, %.1f", tpsValues[0], tpsValues[1], tpsValues[2]);
        } catch (Exception e) {
            tps = "N/A";
        }

        int online = Bukkit.getOnlinePlayers().size();
        int max = Bukkit.getMaxPlayers();

        String playerList = Bukkit.getOnlinePlayers().stream()
                .map(Player::getName)
                .collect(Collectors.joining(", "));
        if (playerList.isEmpty()) playerList = "无";

        Runtime runtime = Runtime.getRuntime();
        long usedMB = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024;
        long maxMB = runtime.maxMemory() / 1024 / 1024;

        StringBuilder sb = new StringBuilder();
        sb.append("【").append(serverName).append("状态】\n");
        sb.append("版本:Paper ").append(version).append("\n");
        sb.append("TPS:").append(tps).append("\n");
        sb.append("玩家数量:(").append(online).append("/").append(max).append(")\n");
        sb.append("玩家列表:").append(playerList).append("\n");
        sb.append("JVM:").append(usedMB).append("/").append(maxMB).append("MB\n");
        sb.append("------------------------------------------------\n");
        long[] stats = getStats(groupId);
        String groupLabel = napCatWS.getGroupName(groupId);
        sb.append("今日转发\n");
        long mcOut, qqIn;
        if (statsShowAllGroups) {
            mcOut = groupStats.values().stream().mapToLong(s -> s[0]).sum();
            qqIn  = groupStats.values().stream().mapToLong(s -> s[1]).sum();
            sb.append("  ").append(serverName).append(" ➜ 全部群: ").append(mcOut).append(" 条\n");
            sb.append("  全部群 ➜ ").append(serverName).append(": ").append(qqIn).append(" 条\n");
        } else {
            mcOut = stats[0];
            qqIn  = stats[1];
            sb.append("  ").append(serverName).append(" ➜ ").append(groupLabel).append(": ").append(mcOut).append(" 条\n");
            sb.append("  ").append(groupLabel).append(" ➜ ").append(serverName).append(": ").append(qqIn).append(" 条\n");
        }
        sb.append("------------------------------------------------\n");
        sb.append("已经运行了").append(formatUptime()).append("喵");
        return sb.toString();
    }

    private void buildEntityNameMap() {
        entityNameMap.put("Wither Skeleton", "凋零骷髅");
        entityNameMap.put("Ender Dragon", "末影龙");
        entityNameMap.put("Elder Guardian", "远古守卫者");
        entityNameMap.put("Cave Spider", "洞穴蜘蛛");
        entityNameMap.put("Magma Cube", "岩浆怪");
        entityNameMap.put("Iron Golem", "铁傀儡");
        entityNameMap.put("Snow Golem", "雪傀儡");
        entityNameMap.put("Zombie Villager", "僵尸村民");
        entityNameMap.put("Zombified Piglin", "僵尸猪灵");
        entityNameMap.put("Zombie Horse", "僵尸马");
        entityNameMap.put("Skeleton Horse", "骷髅马");
        entityNameMap.put("Wandering Trader", "流浪商人");
        entityNameMap.put("Trader Llama", "行商羊驼");
        entityNameMap.put("Piglin Brute", "猪灵蛮兵");
        entityNameMap.put("Polar Bear", "北极熊");
        entityNameMap.put("Glow Squid", "发光鱿鱼");
        entityNameMap.put("Endermite", "末影螨");
        entityNameMap.put("Silverfish", "蠹虫");
        entityNameMap.put("Hoglin", "疣猪兽");
        entityNameMap.put("Zoglin", "僵尸疣猪兽");
        entityNameMap.put("Ravager", "劫掠兽");
        entityNameMap.put("Vindicator", "卫道士");
        entityNameMap.put("Evoker", "唤魔者");
        entityNameMap.put("Pillager", "掠夺者");
        entityNameMap.put("Guardian", "守卫者");
        entityNameMap.put("Shulker", "潜影贝");
        entityNameMap.put("Phantom", "幻翼");
        entityNameMap.put("Drowned", "溺尸");
        entityNameMap.put("Blaze", "烈焰人");
        entityNameMap.put("Ghast", "恶魂");
        entityNameMap.put("Slime", "史莱姆");
        entityNameMap.put("Enderman", "末影人");
        entityNameMap.put("Creeper", "苦力怕");
        entityNameMap.put("Skeleton", "骷髅");
        entityNameMap.put("Zombie", "僵尸");
        entityNameMap.put("Spider", "蜘蛛");
        entityNameMap.put("Witch", "女巫");
        entityNameMap.put("Piglin", "猪灵");
        entityNameMap.put("Husk", "尸壳");
        entityNameMap.put("Stray", "流浪者");
        entityNameMap.put("Warden", "监守者");
        entityNameMap.put("Sniffer", "嗅探兽");
        entityNameMap.put("Breeze", "旋风人");
        entityNameMap.put("Bogged", "沼骷");
        entityNameMap.put("Allay", "悦灵");
        entityNameMap.put("Villager", "村民");
        entityNameMap.put("Axolotl", "美西螈");
        entityNameMap.put("Dolphin", "海豚");
        entityNameMap.put("Squid", "鱿鱼");
        entityNameMap.put("Armadillo", "犰狳");
        entityNameMap.put("Strider", "炽足兽");
        entityNameMap.put("Mooshroom", "哞菇");
        entityNameMap.put("Fox", "狐狸");
        entityNameMap.put("Panda", "熊猫");
        entityNameMap.put("Llama", "羊驼");
        entityNameMap.put("Goat", "山羊");
        entityNameMap.put("Turtle", "海龟");
        entityNameMap.put("Parrot", "鹦鹉");
        entityNameMap.put("Rabbit", "兔子");
        entityNameMap.put("Ocelot", "豹猫");
        entityNameMap.put("Wolf", "狼");
        entityNameMap.put("Cat", "猫");
        entityNameMap.put("Donkey", "驴");
        entityNameMap.put("Mule", "骡");
        entityNameMap.put("Horse", "马");
        entityNameMap.put("Cow", "牛");
        entityNameMap.put("Pig", "猪");
        entityNameMap.put("Sheep", "羊");
        entityNameMap.put("Chicken", "鸡");
        entityNameMap.put("Bat", "蝙蝠");
        entityNameMap.put("Frog", "青蛙");
        entityNameMap.put("Tadpole", "蝌蚪");
        entityNameMap.put("Camel", "骆驼");
        entityNameMap.put("Bee", "蜜蜂");
        entityNameMap.put("Wither", "凋零");
        entityNameMap.put("Vex", "恼鬼");
    }

    private void buildDeathTranslations() {
        deathTranslationMap.put(Pattern.compile("^(.+) was slain by (.+)$"), "$1 被 $2 砂似了");
        deathTranslationMap.put(Pattern.compile("^(.+)wasslainby(.+)$"), "$1 被 $2 砂似了");
        deathTranslationMap.put(Pattern.compile("^(.+) was shot by (.+)$"), "$1 被 $2 射砂");
        deathTranslationMap.put(Pattern.compile("^(.+) was fireballed by (.+)$"), "$1 被 $2 的火球烧似了");
        deathTranslationMap.put(Pattern.compile("^(.+) was killed by (.+) using (.+)$"), "$1 被 $2 用 $3 砂似了");
        deathTranslationMap.put(Pattern.compile("^(.+) was killed by (.+) trying to hurt (.+)$"), "$1 在试图伤害 $3 时被 $2 砂似了");
        deathTranslationMap.put(Pattern.compile("^(.+) was killed by (.+)$"), "$1 被 $2 砂似了");
        deathTranslationMap.put(Pattern.compile("^(.+) was killed trying to hurt (.+)$"), "$1 在试图伤害 $2 时被反砂");
        deathTranslationMap.put(Pattern.compile("^(.+) was killed by magic$"), "$1 被魔法砂似了");
        deathTranslationMap.put(Pattern.compile("^(.+) was killed by even more magic$"), "$1 被更强的魔法砂似了");
        deathTranslationMap.put(Pattern.compile("^(.+) was blown up by (.+)$"), "$1 被 $2 炸似了");
        deathTranslationMap.put(Pattern.compile("^(.+) was doomed to fall by (.+)$"), "$1 被 $2 击落");
        deathTranslationMap.put(Pattern.compile("^(.+) was doomed to fall$"), "$1 注定要摔似");
        deathTranslationMap.put(Pattern.compile("^(.+) was frozen to death by (.+)$"), "$1 被 $2 冻似了");
        deathTranslationMap.put(Pattern.compile("^(.+) was roasted in dragon's breath$"), "$1 被龙息烤似了");
        deathTranslationMap.put(Pattern.compile("^(.+) walked into danger zone due to (.+)$"), "$1 因 $2 走入了危险地带");
        deathTranslationMap.put(Pattern.compile("^(.+) walked into fire whilst fighting (.+)$"), "$1 在与 $2 战斗时走入了火中");
        deathTranslationMap.put(Pattern.compile("^(.+) was shot by a skull from (.+)$"), "$1 被 $2 的凋零头颅射砂");
        deathTranslationMap.put(Pattern.compile("^(.+) was impaled by (.+)$"), "$1 被 $2 刺穿了");
        deathTranslationMap.put(Pattern.compile("^(.+) was pummeled by (.+)$"), "$1 被 $2 锤似了");
        deathTranslationMap.put(Pattern.compile("^(.+) was squashed by (.+)$"), "$1 被 $2 压扁了");
        deathTranslationMap.put(Pattern.compile("^(.+) was stung to death by (.+)$"), "$1 被 $2 蛰似了");
        deathTranslationMap.put(Pattern.compile("^(.+) was obliterated by a sonically-charged shriek whilst trying to escape (.+)$"), "$1 在试图逃离 $2 时被监守者的远程音波攻击消灭了");
        deathTranslationMap.put(Pattern.compile("^(.+) was skewered by a falling stalactite whilst fighting (.+)$"), "$1 在与 $2 战斗时被掉落的钟乳石刺穿了");
        deathTranslationMap.put(Pattern.compile("^(.+) didn't want to live in the same world as (.+)$"), "$1 不想和 $2 活在同一世界");
        deathTranslationMap.put(Pattern.compile("^(.+) drowned whilst trying to escape (.+)$"), "$1 在试图逃离 $2 时溺似了");
        deathTranslationMap.put(Pattern.compile("^(.+) tried to swim in lava to escape (.+)$"), "$1 试图在熔岩中游泳以逃离 $2");
        deathTranslationMap.put(Pattern.compile("^(.+) fell from a high place$"), "$1 从高处摔了下来");
        deathTranslationMap.put(Pattern.compile("^(.+) hit the ground too hard$"), "$1 落地过猛");
        deathTranslationMap.put(Pattern.compile("^(.+) fell off a ladder$"), "$1 从梯子上摔了下来");
        deathTranslationMap.put(Pattern.compile("^(.+) fell off some vines$"), "$1 从藤蔓上摔了下来");
        deathTranslationMap.put(Pattern.compile("^(.+) fell off some twisting vines$"), "$1 从缠怨藤上摔了下来");
        deathTranslationMap.put(Pattern.compile("^(.+) fell off some weeping vines$"), "$1 从垂泪藤上摔了下来");
        deathTranslationMap.put(Pattern.compile("^(.+) fell while climbing$"), "$1 在攀爬时摔了下来");
        deathTranslationMap.put(Pattern.compile("^(.+) drowned$"), "$1 溺似了");
        deathTranslationMap.put(Pattern.compile("^(.+) suffocated in a wall$"), "$1 在墙里窒息而似");
        deathTranslationMap.put(Pattern.compile("^(.+) burned to death$"), "$1 被烧似了");
        deathTranslationMap.put(Pattern.compile("^(.+) went up in flames$"), "$1 在火焰中升天");
        deathTranslationMap.put(Pattern.compile("^(.+) tried to swim in lava$"), "$1 试图在熔岩里游泳");
        deathTranslationMap.put(Pattern.compile("^(.+) starved to death$"), "$1 饿似了");
        deathTranslationMap.put(Pattern.compile("^(.+) blew up$"), "$1 爆炸了");
        deathTranslationMap.put(Pattern.compile("^(.+) was killed by intentional game design$"), "$1 被游戏特性砂似了");
        deathTranslationMap.put(Pattern.compile("^(.+) withered away$"), "$1 凋零了");
        deathTranslationMap.put(Pattern.compile("^(.+) was pricked to death$"), "$1 被刺似了");
        deathTranslationMap.put(Pattern.compile("^(.+) was squished too much$"), "$1 被挤扁了");
        deathTranslationMap.put(Pattern.compile("^(.+) was poked to death by a sweet berry bush$"), "$1 被甜浆果丛刺似了");
        deathTranslationMap.put(Pattern.compile("^(.+) was stung to death$"), "$1 被蛰似了");
        deathTranslationMap.put(Pattern.compile("^(.+) was skewered by a falling stalactite$"), "$1 被掉落的钟乳石刺穿了");
        deathTranslationMap.put(Pattern.compile("^(.+) was impaled on a stalagmite$"), "$1 撞上了石笋");
        deathTranslationMap.put(Pattern.compile("^(.+) was obliterated by a sonically-charged shriek$"), "$1 被监守者的远程音波攻击消灭了");
        deathTranslationMap.put(Pattern.compile("^(.+) froze to death$"), "$1 冻似了");
        deathTranslationMap.put(Pattern.compile("^(.+) died$"), "$1 似了");
        deathTranslationMap.put(Pattern.compile("^(.+) experienced kinetic energy$"), "$1 感受到了动能");
        deathTranslationMap.put(Pattern.compile("^(.+) was killed$"), "$1 似了");
    }

    private void buildAdvancementTitleMap() {
        advancementTitleMap.put("Stone Age", "石器时代");
        advancementTitleMap.put("Getting an Upgrade", "获得升级");
        advancementTitleMap.put("Acquire Hardware", "来硬的");
        advancementTitleMap.put("Suit Up", "整装上阵");
        advancementTitleMap.put("Hot Stuff", "热腾腾的");
        advancementTitleMap.put("Is It a Bird?", "是鸟吗？");
        advancementTitleMap.put("Is It a Balloon?", "是气球吗？");
        advancementTitleMap.put("Is It a Plane?", "是飞机吗？");
        advancementTitleMap.put("Isn't It Iron Pick", "这不是铁镐吗");
        advancementTitleMap.put("Ice Bucket Challenge", "冰桶挑战");
        advancementTitleMap.put("Diamonds!", "钻石！");
        advancementTitleMap.put("We Need to Go Deeper", "勇往直下");
        advancementTitleMap.put("Cover Me with Diamonds", "用钻石包裹我");
        advancementTitleMap.put("Enchanter", "附魔师");
        advancementTitleMap.put("Zombie Doctor", "僵尸医生");
        advancementTitleMap.put("Eye Spy", "隔墙有眼");
        advancementTitleMap.put("The End?", "结束了？");
        advancementTitleMap.put("The End.", "末地");
        advancementTitleMap.put("The End... Again...", "再探末地");
        advancementTitleMap.put("Free the End", "解放末地");
        advancementTitleMap.put("The Next Generation", "下一世代");
        advancementTitleMap.put("Remote Gateway", "远程折跃");
        advancementTitleMap.put("Great View From Up Here", "这上面的风景不错");
        advancementTitleMap.put("Withering Heights", "凋零山庄");
        advancementTitleMap.put("Spooky Scary Skeleton", "悚怖惊魂骷髅");
        advancementTitleMap.put("Bring Home the Beacon", "引信入家");
        advancementTitleMap.put("Beaconator", "深造灯塔");
        advancementTitleMap.put("How Did We Get Here?", "为什么会变成这样呢？");
        advancementTitleMap.put("Adventuring Time", "探索的时光");
        advancementTitleMap.put("Monster Hunter", "怪物猎人");
        advancementTitleMap.put("Monsters Hunted", "资深怪物猎人");
        advancementTitleMap.put("A Throwaway Joke", "无用的玩笑");
        advancementTitleMap.put("A Furious Cocktail", "杯酒之力");
        advancementTitleMap.put("A Complete Catalogue", "狂乱的鸡尾酒");
        advancementTitleMap.put("A Balanced Diet", "均衡饮食");
        advancementTitleMap.put("Subspace Bubble", "子空间泡泡");
        advancementTitleMap.put("Serious Dedication", "终极奉献");
        advancementTitleMap.put("Postmortal", "超越生死");
        advancementTitleMap.put("Overpowered", "不堪重负");
        advancementTitleMap.put("Return to Sender", "见鬼去吧");
        advancementTitleMap.put("Sweet Dreams", "甜蜜的梦");
        advancementTitleMap.put("You Need a Mint", "你需要来点薄荷糖");
        advancementTitleMap.put("Uneasy Alliance", "难以共存的同盟");
        advancementTitleMap.put("Arbalistic", "强弩矢绝");
        advancementTitleMap.put("Sniper Duel", "狙击手的对决");
        advancementTitleMap.put("Bullseye", "正中靶心");
        advancementTitleMap.put("Ol' Betsy", "老友闺枪");
        advancementTitleMap.put("Two by Two", "成双成对");
        advancementTitleMap.put("Whatever Floats Your Goat!", "漂羊过海！");
        advancementTitleMap.put("Total Beelocation", "全面送蜂");
        advancementTitleMap.put("Sticky Situation", "胶着状态");
        advancementTitleMap.put("Best Friends Forever", "永恒的伙伴");
        advancementTitleMap.put("The Parrots and the Bats", "鹦鹉与蝙蝠");
        advancementTitleMap.put("Birthday Song", "生日快乐歌");
        advancementTitleMap.put("Hired Help", "招募帮派");
        advancementTitleMap.put("Star Trader", "星际商人");
        advancementTitleMap.put("What a Deal!", "这买卖不错！");
        advancementTitleMap.put("Take Aim", "瞄准目标");
        advancementTitleMap.put("Not Today, Thank You", "今天不行，谢谢");
        advancementTitleMap.put("Who's the Pillager Now?", "现在谁才是掠夺者？");
        advancementTitleMap.put("Hero of the Village", "村庄英雄");
        advancementTitleMap.put("Voluntary Exile", "自我放逐");
        advancementTitleMap.put("Bee Our Guest", "蜜蜂以客为尊");
        advancementTitleMap.put("Bukkit Bukkit", "噗咕噗咕");
        advancementTitleMap.put("Fishy Business", "腥味十足的生意");
        advancementTitleMap.put("Tactical Fishing", "战术性钓鱼");
        advancementTitleMap.put("Oh Shiny", "哦，亮晶晶");
        advancementTitleMap.put("Cover Me in Debris", "残骸裹身");
        advancementTitleMap.put("Country Lode, Take Me Home", "送向归乡的矿车");
        advancementTitleMap.put("Hidden in the Depths", "深藏不露");
        advancementTitleMap.put("This Boat Has Legs", "抖包袱");
        advancementTitleMap.put("Soul Speed", "灵魂疾行");
        advancementTitleMap.put("Wax On", "涂蜡");
        advancementTitleMap.put("Wax Off", "除蜡");
        advancementTitleMap.put("The Power of Books", "知识就是力量");
        advancementTitleMap.put("It Spreads", "它蔓延了");
        advancementTitleMap.put("Light as a Rabbit", "轻功雪上飘");
        advancementTitleMap.put("Surge Protector", "电涌保护器");
        advancementTitleMap.put("When the Squad Hops into Town", "跳跃大队进城");
        advancementTitleMap.put("Locomotive", "火车头");
        advancementTitleMap.put("Smells like Power", "一股力量");
        advancementTitleMap.put("Sneak 100", "潜行100级");
        advancementTitleMap.put("Caves & Cliffs", "洞穴与山崖");
        advancementTitleMap.put("Feels Like Home", "温暖如家");
        advancementTitleMap.put("Sound of Music", "音乐之声");
        advancementTitleMap.put("Star Light", "群星闪耀");
        advancementTitleMap.put("The Cutest Predator", "最萌捕食者");
        advancementTitleMap.put("Under the Sea", "海底之下");
        advancementTitleMap.put("The Healing Powers of Friendship", "友谊的治愈力量");
        advancementTitleMap.put("A Seedy Place", "充满种子的地方");
        advancementTitleMap.put("Planting the Past", "植入过往");
        advancementTitleMap.put("Little Sniffs", "小小嗅探");
        advancementTitleMap.put("With Our Powers Combined!", "力量合一！");
        advancementTitleMap.put("The Low and Floaty", "低伏漂浮");
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        // 认证启用时，加入广播由 AuthListener 处理
        if (!syncJoin || (authEnabled && authManager != null)) return;
        Player player = event.getPlayer();
        int online = Bukkit.getOnlinePlayers().size();
        int max = Bukkit.getMaxPlayers();

        String gameDisplay = qzxTitle.getGameDisplayName(player);
        event.joinMessage(LegacyComponentSerializer.legacySection().deserialize("§e" + gameDisplay + "§e 加入了服务器"));

        String plainDisplay = qzxTitle.getPlainDisplayName(player);
        String msg = "【服务器状态变化】" + plainDisplay + " 加入了" + serverName + "(" + online + "/" + max + ")";
        countMCToQQ();
        napCatWS.sendGroupMessage(groupIds, msg);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        // 认证启用时，退出广播由 AuthListener 处理
        if (!syncQuit || (authEnabled && authManager != null)) return;
        Player player = event.getPlayer();
        int online = Bukkit.getOnlinePlayers().size() - 1;
        if (online < 0) online = 0;
        int max = Bukkit.getMaxPlayers();

        String gameDisplay = qzxTitle.getGameDisplayName(player);
        event.quitMessage(LegacyComponentSerializer.legacySection().deserialize("§e" + gameDisplay + "§e 离开了服务器"));

        String plainDisplay = qzxTitle.getPlainDisplayName(player);
        String msg = "【服务器状态变化】" + plainDisplay + " 离开了" + serverName + "(" + online + "/" + max + ")";
        countMCToQQ();
        napCatWS.sendGroupMessage(groupIds, msg);
    }

    @EventHandler
    private String buildQQAtPrefix(Player player) {
        try {
            if (dbManager != null && authEnabled) {
                String qq = dbManager.getBoundQQ(player.getUniqueId());
                if (qq != null) return "[CQ:at,qq=" + qq + "] ";
            }
        } catch (Exception ignored) {}
        return "";
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) {
        if (!syncChat || event.isCancelled()) return;
        String display = qzxTitle.getPlainDisplayName(event.getPlayer());
        String msg = "【" + serverName + "消息】" + buildQQAtPrefix(event.getPlayer())
                + "<" + display + "> " + event.getMessage();
        countMCToQQ();
        napCatWS.sendGroupMessage(groupIds, msg);
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        if (!syncDeath) return;
        String deathMsg;
        try {
            deathMsg = PlainTextComponentSerializer.plainText().serialize(event.deathMessage());
        } catch (Exception e) {
            deathMsg = LegacyComponentSerializer.legacySection().serialize(event.deathMessage());
            deathMsg = ChatColor.stripColor(deathMsg);
        }
        if (deathMsg == null || deathMsg.isEmpty()) return;

        List<Player> sorted = new ArrayList<>(Bukkit.getOnlinePlayers());
        sorted.sort((a, b) -> Integer.compare(
                qzxTitle.getPlainPrefix(b).length(),
                qzxTitle.getPlainPrefix(a).length()));
        for (Player p : sorted) {
            String prefix = qzxTitle.getPlainPrefix(p);
            deathMsg = deathMsg.replace(prefix + " ", "");
        }

        String translated = translateDeath(deathMsg);
        translated = translateEntityNames(translated);

        String translatedGame = qzxTitle.replaceAllGameDisplayNames(translated);
        event.deathMessage(LegacyComponentSerializer.legacySection().deserialize(translatedGame));

        translated = qzxTitle.replaceAllPlainDisplayNames(translated);

        String msg = "【" + serverName + "消息】" + buildQQAtPrefix(event.getPlayer()) + translated;
        countMCToQQ();
        napCatWS.sendGroupMessage(groupIds, msg);
    }

    @EventHandler
    public void onWeatherChange(WeatherChangeEvent event) {
        if (!syncWeather) return;
        String worldName = event.getWorld().getName();
        boolean isRaining = event.toWeatherState();
        String weatherMsg;
        if (isRaining) {
            boolean isThunder = event.getWorld().isThundering();
            weatherMsg = isThunder ? "雷暴来袭" : "开始下雨了";
        } else {
            weatherMsg = "天气转晴了";
        }
        String msg = "【" + serverName + "消息】" + worldName + "世界 " + weatherMsg;
        countMCToQQ();
        napCatWS.sendGroupMessage(groupIds, msg);
    }

    @EventHandler
    public void onAdvancementDone(PlayerAdvancementDoneEvent event) {
        if (!syncAdvancement) return;
        AdvancementDisplay display = event.getAdvancement().getDisplay();
        if (display == null) return;
        String title;
        try {
            title = PlainTextComponentSerializer.plainText().serialize(display.title());
        } catch (Exception e) {
            title = LegacyComponentSerializer.legacySection().serialize(display.title());
            title = ChatColor.stripColor(title);
        }
        if (title == null || title.isEmpty()) return;

        title = translateAdvancementTitle(title);

        String frameName;
        try {
            switch (display.frame()) {
                case CHALLENGE: frameName = "挑战"; break;
                case GOAL: frameName = "目标"; break;
                default: frameName = "进度"; break;
            }
        } catch (Exception e) {
            frameName = "进度";
        }

        String gameDisplay = qzxTitle.getGameDisplayName(event.getPlayer());
        String gameMsg = gameDisplay + " §f取得了" + frameName + "「" + title + "」";

        String plainDisplay = qzxTitle.getPlainDisplayName(event.getPlayer());
        String msg = "【" + serverName + "】" + buildQQAtPrefix(event.getPlayer())
                + plainDisplay + " 取得了" + frameName + "「" + title + "」";
        countMCToQQ();
        napCatWS.sendGroupMessage(groupIds, msg);

        Bukkit.broadcast(LegacyComponentSerializer.legacySection().deserialize(gameMsg));
    }

    private String translateDeath(String englishDeath) {
        for (Map.Entry<Pattern, String> entry : deathTranslationMap.entrySet()) {
            Matcher m = entry.getKey().matcher(englishDeath);
            if (m.matches()) {
                return m.replaceFirst(entry.getValue());
            }
        }
        return englishDeath.replaceAll("(?i)\\bwas\\b", "被")
                .replaceAll("(?i)\\bkilled\\b", "击败")
                .replaceAll("(?i)\\bdead\\b", "失败")
                .replaceAll("(?i)\\bdied\\b", "失败了")
                .replaceAll("(?i)\\bdeath\\b", "失败")
                .replaceAll("(?i)\\bshot\\b", "射击")
                .replaceAll("(?i)\\bslain\\b", "击败");
    }

    private String translateEntityNames(String text) {
        for (Map.Entry<String, String> entry : entityNameMap.entrySet()) {
            text = text.replace(entry.getKey(), entry.getValue());
        }
        return text;
    }

    private String translateAdvancementTitle(String title) {
        if (advancementTitleMap.containsKey(title)) {
            return advancementTitleMap.get(title);
        }
        return title;
    }

    private String formatUptime() {
        long uptime = System.currentTimeMillis() - startTime;
        long days = TimeUnit.MILLISECONDS.toDays(uptime);
        long hours = TimeUnit.MILLISECONDS.toHours(uptime) % 24;
        long minutes = TimeUnit.MILLISECONDS.toMinutes(uptime) % 60;

        StringBuilder sb = new StringBuilder();
        if (days > 0) sb.append(days).append("天");
        if (hours > 0) sb.append(hours).append("小时");
        if (minutes > 0 || sb.length() == 0) sb.append(minutes).append("分钟");
        return sb.toString();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sendHelp(sender);
            return true;
        }

        // bindqq、status、help 不需要 OP 权限
        boolean needsAdmin = !args[0].equalsIgnoreCase("bindqq")
                && !args[0].equalsIgnoreCase("status")
                && !args[0].equalsIgnoreCase("help");
        if (needsAdmin && !sender.hasPermission("qzxbot.admin") && !sender.isOp()) {
            sender.sendMessage(ChatColor.RED + "你没有权限使用此命令！");
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "ws":
                if (args.length < 2) {
                    sender.sendMessage(ChatColor.RED + "用法: /qzxbot ws <ws地址>");
                    sender.sendMessage(ChatColor.GRAY + "当前: " + wsUrl);
                    return true;
                }
                wsUrl = args[1];
                getConfig().set("napcat.ws-url", wsUrl);
                saveConfig();
                napCatWS.disconnect();
                napCatWS.configure(wsUrl, reconnectInterval);
                napCatWS.connect();
                sender.sendMessage(ChatColor.GREEN + "WebSocket 地址已更新为: " + wsUrl);
                break;

            case "group":
                if (args.length < 2) {
                    sendGroupHelp(sender);
                    return true;
                }
                switch (args[1].toLowerCase()) {
                    case "add":
                        if (args.length < 3) {
                            sender.sendMessage(ChatColor.RED + "用法: /qzxbot group add <群号>");
                            return true;
                        }
                        try {
                            long id = Long.parseLong(args[2]);
                            if (!groupIds.contains(id)) {
                                groupIds.add(id);
                                saveGroupConfig();
                                sender.sendMessage(ChatColor.GREEN + "已添加群: " + id);
                            } else {
                                sender.sendMessage(ChatColor.YELLOW + "该群已存在: " + id);
                            }
                        } catch (NumberFormatException e) {
                            sender.sendMessage(ChatColor.RED + "无效的群号");
                        }
                        break;
                    case "remove":
                        if (args.length < 3) {
                            sender.sendMessage(ChatColor.RED + "用法: /qzxbot group remove <群号>");
                            return true;
                        }
                        try {
                            long id = Long.parseLong(args[2]);
                            if (groupIds.remove(id)) {
                                saveGroupConfig();
                                sender.sendMessage(ChatColor.GREEN + "已移除群: " + id);
                            } else {
                                sender.sendMessage(ChatColor.YELLOW + "未找到该群: " + id);
                            }
                        } catch (NumberFormatException e) {
                            sender.sendMessage(ChatColor.RED + "无效的群号");
                        }
                        break;
                    case "list":
                        if (groupIds.isEmpty()) {
                            sender.sendMessage(ChatColor.GRAY + "当前未配置任何群");
                        } else {
                            sender.sendMessage(ChatColor.GREEN + "当前同步群列表:");
                            for (long id : groupIds) {
                                sender.sendMessage(ChatColor.GRAY + "  - " + id);
                            }
                        }
                        break;
                    default:
                        sendGroupHelp(sender);
                        break;
                }
                break;

            case "sync":
                if (args.length < 2) {
                    sender.sendMessage(ChatColor.YELLOW + "用法: /qzxbot sync <类型> <on|off>");
                    sender.sendMessage(ChatColor.GRAY + "类型: join quit chat death weather advancement reverse recall");
                    return true;
                }
                if (args.length < 3) {
                    sender.sendMessage(ChatColor.RED + "请指定 on 或 off");
                    return true;
                }
                boolean value = args[2].equalsIgnoreCase("on");
                switch (args[1].toLowerCase()) {
                    case "join":
                        syncJoin = value; getConfig().set("sync.join", syncJoin); break;
                    case "quit":
                        syncQuit = value; getConfig().set("sync.quit", syncQuit); break;
                    case "chat":
                        syncChat = value; getConfig().set("sync.chat", syncChat); break;
                    case "death":
                        syncDeath = value; getConfig().set("sync.death", syncDeath); break;
                    case "weather":
                        syncWeather = value; getConfig().set("sync.weather", syncWeather); break;
                    case "advancement":
                        syncAdvancement = value; getConfig().set("sync.advancement", syncAdvancement); break;
                    case "reverse":
                        reverseSync = value; getConfig().set("sync.reverse", reverseSync);
                        napCatWS.setMonitoredGroups(value ? groupIds : new ArrayList<>());
                        break;
                    case "recall":
                        syncRecall = value; getConfig().set("sync.recall", syncRecall); break;
                    default:
                        sender.sendMessage(ChatColor.RED + "未知类型: " + args[1]);
                        return true;
                }
                saveConfig();
                sender.sendMessage(ChatColor.GREEN + "同步开关 " + args[1] + " 已设为 " + (value ? "开启" : "关闭"));
                break;

            case "reload":
                loadConfig();
                napCatWS.disconnect();
                napCatWS.configure(wsUrl, reconnectInterval);
                napCatWS.connect();
                sender.sendMessage(ChatColor.GREEN + "QZXBotSync 配置已重载");
                break;

            case "status":
                sender.sendMessage(ChatColor.GREEN + "QZXBotSync 状态:");
                sender.sendMessage(ChatColor.GRAY + "  WS连接: " + (napCatWS.isConnected() ? ChatColor.GREEN + "已连接" : ChatColor.RED + "未连接"));
                sender.sendMessage(ChatColor.GRAY + "  WS地址: " + wsUrl);
                sender.sendMessage(ChatColor.GRAY + "  同步群: " + (groupIds.isEmpty() ? "无" : groupIds.toString()));
                sender.sendMessage(ChatColor.GRAY + "  服务器名: " + serverName);
                sender.sendMessage(ChatColor.GRAY + "  同步开关: 加入=" + yn(syncJoin) + " 退出=" + yn(syncQuit) + " 聊天=" + yn(syncChat) + " 击杀=" + yn(syncDeath) + " 天气=" + yn(syncWeather) + " 进度=" + yn(syncAdvancement) + " 反向=" + yn(reverseSync) + " 撤回=" + yn(syncRecall));
                sender.sendMessage(ChatColor.GRAY + "  运行时长: " + formatUptime());
                break;

            case "reconnect":
                napCatWS.disconnect();
                napCatWS.configure(wsUrl, reconnectInterval);
                napCatWS.connect();
                sender.sendMessage(ChatColor.GREEN + "正在重新连接...");
                break;

            case "bindqq":
                handleBindQQ(sender, args);
                break;

            default:
                sendHelp(sender);
                break;
        }
        return true;
    }

    private void handleBindQQ(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "只有玩家可以使用此命令！");
            return;
        }
        if (dbManager == null || !authEnabled) {
            sender.sendMessage(ChatColor.RED + "认证系统未启用，无法绑定QQ");
            return;
        }
        if (!authManager.isLoggedIn(player.getUniqueId())) {
            sender.sendMessage(ChatColor.RED + "请先完成认证后再绑定QQ！");
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(ChatColor.YELLOW + "用法: /qzxbot bindqq <QQ号>");
            return;
        }
        String qqNumber = args[1];
        if (!qqNumber.matches("\\d{5,11}")) {
            sender.sendMessage(ChatColor.RED + "无效的QQ号！请输入5-11位数字");
            return;
        }
        if (dbManager.isQQBound(qqNumber)) {
            sender.sendMessage(ChatColor.RED + "该QQ号已被其他玩家绑定！");
            return;
        }

        String code = String.format("%07d", (int) (Math.random() * 10000000));
        dbManager.saveVerifyCode(player.getUniqueId(), player.getName(), qqNumber, code);

        player.sendMessage("");
        player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                "&e▐▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▌"));
        player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                "&e  &lQQ绑定验证"));
        player.sendMessage("");
        player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                "&f你的验证码: &e&l" + code));
        player.sendMessage("");
        player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                "&7在QQ群 @机器人 发送: &fMC绑定 " + code));
        player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                "&7验证码仅自己可见，请勿泄露"));
        player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                "&e▐▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▌"));
        player.sendMessage("");
    }

    private void saveGroupConfig() {
        getConfig().set("qq-groups", groupIds);
        saveConfig();
    }

    private String yn(boolean b) { return b ? "§a是" : "§c否"; }

    private void sendGroupHelp(CommandSender sender) {
        sender.sendMessage(ChatColor.YELLOW + "===== /qzxbot group 子命令 =====");
        sender.sendMessage(ChatColor.GRAY + "/qzxbot group add <群号> - 添加同步群");
        sender.sendMessage(ChatColor.GRAY + "/qzxbot group remove <群号> - 移除同步群");
        sender.sendMessage(ChatColor.GRAY + "/qzxbot group list - 查看同步群列表");
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(ChatColor.YELLOW + "===== QZXBotSync 命令帮助 =====");
        sender.sendMessage(ChatColor.GRAY + "/qzxbot ws <地址> - 设置 NapCat WebSocket 地址");
        sender.sendMessage(ChatColor.GRAY + "/qzxbot group <add|remove|list> - 管理同步群");
        sender.sendMessage(ChatColor.GRAY + "/qzxbot sync <类型> <on|off> - 开关各类消息同步");
        sender.sendMessage(ChatColor.GRAY + "/qzxbot reload - 重载配置文件");
        sender.sendMessage(ChatColor.GRAY + "/qzxbot status - 查看连接状态");
        sender.sendMessage(ChatColor.GRAY + "/qzxbot reconnect - 强制重新连接");
        sender.sendMessage(ChatColor.GRAY + "/qzxbot bindqq <QQ号> - 绑定QQ账号");
        sender.sendMessage(ChatColor.GRAY + "/qzxbot help - 显示此帮助");
    }

    private void printBanner() {
        ConsoleCommandSender console = Bukkit.getConsoleSender();
        String[] lines = {
                "&b  ___  ________ __   __  ____        __  ____  __            ",
                "&b / _ \\/  _/_  // /  / / / __ )____  / /_/ __ \\/ /___  __     ",
                "&b/ // // /  / // _ \\/ _ \\/ __  / __ \\/ __/ /_/ / __/ / / /  ",
                "&b/____/___/ /_//_//_//_/_____/\\___/\\__/\\____/\\__/_/ /_/   ",
                "&b              QZXBotSync v" + getDescription().getVersion() + " - QQ群消息同步"
        };
        for (String line : lines) {
            console.sendMessage(ChatColor.translateAlternateColorCodes('&', line));
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filterStartsWith(Arrays.asList("ws", "group", "sync", "reload", "status", "reconnect", "bindqq", "help"), args[0]);
        } else if (args.length == 2) {
            if (args[0].equalsIgnoreCase("group")) {
                return filterStartsWith(Arrays.asList("add", "remove", "list"), args[1]);
            } else if (args[0].equalsIgnoreCase("sync")) {
                return filterStartsWith(Arrays.asList("join", "quit", "chat", "death", "weather", "advancement", "reverse", "recall"), args[1]);
            }
        } else if (args.length == 3 && args[0].equalsIgnoreCase("group") && args[1].equalsIgnoreCase("remove")) {
            return groupIds.stream().map(String::valueOf).filter(s -> s.startsWith(args[2])).collect(Collectors.toList());
        } else if (args.length == 3 && args[0].equalsIgnoreCase("sync")) {
            return filterStartsWith(Arrays.asList("on", "off"), args[2]);
        }
        return Collections.emptyList();
    }

    private List<String> filterStartsWith(List<String> list, String prefix) {
        if (prefix == null || prefix.isEmpty()) return list;
        return list.stream().filter(s -> s.toLowerCase().startsWith(prefix.toLowerCase())).collect(Collectors.toList());
    }
}
