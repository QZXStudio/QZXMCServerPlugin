package com.qzxtpfwq;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerCommandSendEvent;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class AuthListener implements Listener {

    private final AuthManager authManager;
    private final JavaPlugin plugin;
    private final NapCatWS napCatWS;
    private final QZXTitle qzxTitle;
    private final String serverName;
    private final List<Long> groupIds;
    private final DatabaseManager dbManager;
    private final Runnable onMCToQQCounter;

    // 正版验证子服 transfer 配置
    private final String authServerAddress;
    private final int authServerPort;
    private final File resultsDir;
    private final File choicesDir;

    // 冻结
    private final Map<UUID, Location> frozenLocations = new ConcurrentHashMap<>();
    private final Set<UUID> teleportGuard = Collections.newSetFromMap(new ConcurrentHashMap<>());

    // 注册时第一次输入的密码暂存
    private final Map<UUID, String> pendingPasswords = new ConcurrentHashMap<>();
    // 标记玩家正在铁砧GUI中输入密码
    private final Set<UUID> inAnvilInput = Collections.newSetFromMap(new ConcurrentHashMap<>());
    // 标记玩家正在进行正版验证（防止关闭主菜单时被踢/误判）
    private final Set<UUID> inPremiumTransfer = Collections.newSetFromMap(new ConcurrentHashMap<>());

    // 未登录时允许的命令（极简白名单）
    private static final Set<String> ALLOWED_COMMANDS = Set.of("/help", "/?");

    public AuthListener(AuthManager authManager, JavaPlugin plugin, NapCatWS napCatWS,
                        QZXTitle qzxTitle, String serverName, List<Long> groupIds,
                        DatabaseManager dbManager, Runnable onMCToQQCounter,
                        String authServerAddress, String sharedDataDir) {
        this.authManager = authManager;
        this.plugin = plugin;
        this.napCatWS = napCatWS;
        this.qzxTitle = qzxTitle;
        this.serverName = serverName;
        this.groupIds = groupIds;
        this.dbManager = dbManager;
        this.onMCToQQCounter = onMCToQQCounter;

        String[] parts = authServerAddress.split(":");
        this.authServerAddress = parts[0];
        this.authServerPort = parts.length > 1 ? Integer.parseInt(parts[1]) : 25565;

        this.resultsDir = new File(sharedDataDir, "results");
        if (!resultsDir.exists()) resultsDir.mkdirs();
        this.choicesDir = new File(sharedDataDir, "choices");
        if (!choicesDir.exists()) choicesDir.mkdirs();

        registerPremiumUuidHook();
    }

    // ═══════════════════════════════════════════════
    // PRE-LOGIN：正版返回时替换 UUID 实现数据独立
    // 因为 Paper 不同版本的 AsyncPlayerPreLoginEvent 路径不同，
    // 这里用反射在运行时动态查找事件类并注册处理器。
    // ═══════════════════════════════════════════════

    private void registerPremiumUuidHook() {
        // 按顺序尝试不同 Paper 版本的事件类路径
        String[] candidates = {
                "io.papermc.paper.event.player.AsyncPlayerPreLoginEvent",   // Paper 1.21+
                "com.destroystokyo.paper.event.player.AsyncPlayerPreLoginEvent", // Paper 1.20
                "org.bukkit.event.player.AsyncPlayerPreLoginEvent"          // 标准 Bukkit
        };
        for (String className : candidates) {
            try {
                Class<? extends org.bukkit.event.Event> eventClass =
                        Class.forName(className).asSubclass(org.bukkit.event.Event.class);
                Bukkit.getPluginManager().registerEvent(eventClass, new Listener() {},
                        EventPriority.LOWEST, (l, event) -> {
                            try {
                                String name = (String) event.getClass().getMethod("getName").invoke(event);
                                File resultFile = new File(resultsDir, name + ".json");
                                if (!resultFile.exists()) return;

                                String premiumUuid = readPremiumResult(resultFile);
                                if (premiumUuid == null) return;

                                String choice = readChoiceFile(name);
                                if ("merge".equals(choice)) {
                                    plugin.getLogger().info("[认证] AsyncPreLogin: " + name + " 选择合并 → 保持离线UUID");
                                    return;
                                }

                                UUID newUuid = UUID.fromString(premiumUuid);
                                java.lang.reflect.Method getProfile = event.getClass().getMethod("getPlayerProfile");
                                Object profile = getProfile.invoke(event);
                                profile.getClass().getMethod("setId", UUID.class).invoke(profile, newUuid);
                                plugin.getLogger().info("[认证] AsyncPreLogin: " + name + " → UUID 替换为 " + newUuid);
                            } catch (Exception ex) {
                                plugin.getLogger().warning("[认证] AsyncPreLogin 处理失败: " + ex.getMessage());
                            }
                        }, plugin);
                plugin.getLogger().info("[认证] 已注册 UUID 替换钩子: " + className);
                return; // 成功注册就退出
            } catch (ClassNotFoundException ignored) {}
        }
        plugin.getLogger().warning("[认证] 未找到 AsyncPlayerPreLoginEvent — UUID 替换不可用");
    }

    // ═══════════════════════════════════════════════
    // JOIN
    // ═══════════════════════════════════════════════

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        String ip = getPlayerIp(player);
        event.joinMessage(null);

        plugin.getLogger().info("[认证] ───── 玩家加入: " + player.getName()
                + " (UUID=" + uuid + ", IP=" + ip + ")");

        // 检查是否从正版认证子服返回 — 读取共享目录中的验证结果
        File resultFile = new File(resultsDir, player.getName() + ".json");
        if (resultFile.exists()) {
            plugin.getLogger().info("[认证] ───── 检测到正版验证结果文件: " + resultFile.getName());
            String premiumUuid = readPremiumResult(resultFile);
            resultFile.delete();
            if (premiumUuid != null) {
                String choice = readChoiceFile(player.getName());
                // 清理 choice 文件
                File cf = new File(choicesDir, player.getName() + ".json");
                if (cf.exists()) cf.delete();

                boolean isMerge = "merge".equals(choice);
                plugin.getLogger().info("[认证] ───── 正版验证成功! premiumUuid=" + premiumUuid
                        + ", choice=" + (choice != null ? choice : "无(新号)"));

                if (isMerge) {
                    // 合并：UUID 未变（保持离线UUID），覆盖密码为 PREMIUM，建立映射
                    authManager.forceLinkPremium(uuid, player.getName(), premiumUuid);
                } else {
                    // 新建 / 新号：AsyncPlayerPreLoginEvent 已把 UUID 换成正版 UUID
                    // uuid 就是 premiumUuid，playerdata 会存到 world/playerdata/<premiumUuid>.dat
                    authManager.linkPremiumOnly(uuid, player.getName(), premiumUuid);
                }

                authManager.setState(uuid, AuthManager.AuthState.LOGGED_IN);
                authManager.setLoggedIn(uuid, true);
                // 正版不存 session，每次进服都要重新验证
                player.updateCommands();

                // 广播加入
                doJoinBroadcast(player);
                // 广播正版验证
                broadcastPremiumAuth(player, isMerge);
                plugin.getLogger().info("[认证] ───── 正版玩家 " + player.getName() + " 登录完成");
                return;
            } else {
                plugin.getLogger().warning("[认证] ───── 验证结果文件内容无效，已删除");
            }
        }

        // 会话有效 → 自动登录
        if (authManager.isRegistered(uuid) && authManager.hasValidSession(uuid, ip)) {
            plugin.getLogger().info("[认证] ───── 会话有效 → 自动登录");
            authManager.setState(uuid, AuthManager.AuthState.LOGGED_IN);
            authManager.setLoggedIn(uuid, true);
            authManager.saveSession(uuid, ip);
            applyIdentityTag(player, uuid);
            player.updateCommands();
            doJoinBroadcast(player);
            return;
        }

        boolean isRegistered = authManager.isRegistered(uuid);
        plugin.getLogger().info("[认证] ───── 会话无效 (isRegistered=" + isRegistered
                + ") → 冻结玩家, 状态=" + (isRegistered ? "NEEDS_LOGIN" : "NEEDS_REGISTER"));

        authManager.setState(uuid,
                isRegistered ? AuthManager.AuthState.NEEDS_LOGIN : AuthManager.AuthState.NEEDS_REGISTER);

        freeze(player);

        new BukkitRunnable() {
            @Override
            public void run() {
                if (player.isOnline() && !authManager.isLoggedIn(uuid)) {
                    plugin.getLogger().info("[认证] ───── 8tick 后打开主菜单: " + player.getName());
                    openMainMenu(player);
                }
            }
        }.runTaskLater(plugin, 8L);
    }

    // ═══════════════════════════════════════════════
    // QUIT
    // ═══════════════════════════════════════════════

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        boolean wasLoggedIn = authManager.isLoggedIn(uuid);

        if (!wasLoggedIn) {
            event.quitMessage(null);
        } else {
            int online = Bukkit.getOnlinePlayers().size() - 1;
            if (online < 0) online = 0;
            int max = Bukkit.getMaxPlayers();
            String gameDisplay = qzxTitle.getGameDisplayName(player);
            event.quitMessage(LegacyComponentSerializer.legacySection()
                    .deserialize("§e" + gameDisplay + "§e 离开了服务器"));
            if (groupIds != null && !groupIds.isEmpty()) {
                if (onMCToQQCounter != null) onMCToQQCounter.run();
                String plainDisplay = qzxTitle.getPlainDisplayName(player);
                napCatWS.sendGroupMessage(groupIds,
                        "【服务器状态变化】" + qqAtFor(player) + plainDisplay
                                + " 离开了" + serverName + "(" + online + "/" + max + ")");
            }
        }

        authManager.logout(uuid);
        cleanup(player);
    }

    // ═══════════════════════════════════════════════
    // 主菜单 GUI（箱子）
    // ═══════════════════════════════════════════════

    private void openMainMenu(Player player) {
        UUID uuid = player.getUniqueId();
        boolean isNew = authManager.getState(uuid) == AuthManager.AuthState.NEEDS_REGISTER;

        Inventory inv = Bukkit.createInventory(null, 27,
                ChatColor.translateAlternateColorCodes('&', "&8〄 欢迎来到 &6" + serverName));

        ItemStack border = glassPane();
        for (int i = 0; i < 27; i++) inv.setItem(i, border);

        // 正版登录 (slot 12)
        inv.setItem(12, makeItem(Material.DIAMOND, "§b§l正版登录",
                "§7§o如果你是正版玩家请点击这里",
                "",
                "§f通过 Mojang 验证直接登录",
                "§f无需密码"));

        if (isNew) {
            inv.setItem(14, makeItem(Material.NAME_TAG, "§a§l离线注册",
                    "§7§o新玩家请点击这里设置密码",
                    "",
                    "§f密码要求: §e" + authManager.getMinPasswordLength() + "§f-§e" + authManager.getMaxPasswordLength() + " §f位"));
        } else {
            inv.setItem(14, makeItem(Material.ENDER_PEARL, "§e§l离线登录",
                    "§7§o已有账号请点击这里",
                    "",
                    "§f在铁砧输入框输入密码",
                    "",
                    "§c输错" + authManager.getMaxLoginAttempts() + "次将被踢出"));
        }

        List<String> infoLore = new ArrayList<>();
        infoLore.add("");
        infoLore.add("§f在线玩家: §e" + Bukkit.getOnlinePlayers().size() + "§f/§e" + Bukkit.getMaxPlayers());
        infoLore.add("§f你的ID: §e" + player.getName());
        infoLore.add("");
        infoLore.add("§b§l▶ 正版玩家点钻石");
        infoLore.add(isNew ? "§a§l▶ 新玩家点命名牌注册" : "§e§l▶ 老玩家点末影珍珠登录");
        inv.setItem(4, makeItem(Material.BOOK, "§6§l" + serverName, infoLore.toArray(new String[0])));

        player.openInventory(inv);
    }

    // ═══════════════════════════════════════════════
    // 铁砧 GUI 密码输入
    // ═══════════════════════════════════════════════

    @SuppressWarnings("deprecation")
    private void openAnvilInput(Player player, String title, String subtitle) {
        UUID uuid = player.getUniqueId();
        inAnvilInput.add(uuid);

        player.closeInventory();

        new BukkitRunnable() {
            @Override
            public void run() {
                if (!player.isOnline() || authManager.isLoggedIn(uuid)) return;

                player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                        "&e▐▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▌"));
                player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                        "&e  " + title));
                player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                        "&f在铁砧输入框输入密码，然后按 &eESC &f或点击结果物品提交"));
                player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                        "&7" + subtitle));
                player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                        "&e▐▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▌"));

                try {
                    InventoryView view = player.openAnvil(player.getLocation(), true);
                    AnvilInventory anvil = (AnvilInventory) view.getTopInventory();
                    anvil.setRepairCost(0);
                    anvil.setMaximumRepairCost(0);

                    // 标题
                    try { view.setTitle(ChatColor.translateAlternateColorCodes('&', title)); } catch (Exception ignored) {}

                    // 第一格放纸，提示文字写在 displayName 里
                    ItemStack paper = new ItemStack(Material.PAPER);
                    ItemMeta meta = paper.getItemMeta();
                    meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', "&7在此输入..."));
                    List<Component> lore = new ArrayList<>();
                    lore.add(LegacyComponentSerializer.legacySection()
                            .deserialize(ChatColor.translateAlternateColorCodes('&', "&e输入后点击右边结果格提交")));
                    meta.lore(lore);
                    paper.setItemMeta(meta);
                    anvil.setItem(0, paper);
                } catch (Exception e) {
                    plugin.getLogger().warning("无法打开铁砧界面: " + e.getMessage());
                    inAnvilInput.remove(uuid);
                    openMainMenu(player);
                }
            }
        }.runTaskLater(plugin, 2L);
    }

    @SuppressWarnings("deprecation")
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPrepareAnvil(org.bukkit.event.inventory.PrepareAnvilEvent event) {
        if (!(event.getView().getPlayer() instanceof Player player)) return;
        UUID uuid = player.getUniqueId();
        if (!inAnvilInput.contains(uuid)) return;

        AnvilInventory anvil = event.getInventory();
        anvil.setRepairCost(0);

        // 不覆盖result——让铁砧自然产出改名物品，后面从 result.displayName() 读取密码
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        UUID uuid = player.getUniqueId();
        if (!inAnvilInput.contains(uuid)) return;
        if (!(event.getInventory() instanceof AnvilInventory anvil)) return;

        inAnvilInput.remove(uuid);

        // 延迟处理
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!player.isOnline() || authManager.isLoggedIn(uuid)) return;

                // 从结果物品 displayName 读取密码——完全不调 getRenameText()
                String password = null;
                ItemStack result = anvil.getItem(2);
                if (result != null && result.hasItemMeta()) {
                    try {
                        password = LegacyComponentSerializer.legacySection()
                                .serialize(result.getItemMeta().displayName()).trim();
                    } catch (Exception e) {
                        password = "";
                    }
                }

                if (password == null || password.isEmpty()
                        || password.equals("输入密码...")
                        || password.equals(ChatColor.translateAlternateColorCodes('&', "&7输入密码..."))) {
                    player.sendMessage(ChatColor.RED + "你没有输入密码！");
                    backToMainMenu(player);
                    return;
                }

                processPasswordSubmit(player, uuid, password);
            }
        }.runTaskLater(plugin, 1L);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onMainMenuClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        UUID uuid = player.getUniqueId();
        if (authManager.isLoggedIn(uuid)) return;
        if (inAnvilInput.contains(uuid)) return;
        if (inPremiumTransfer.contains(uuid)) return; // 正版验证中，不踢

        String title = getViewTitle(event.getView());
        if (!title.contains("欢迎来到") && !title.contains("账号冲突") && !title.contains("检测到离线数据")) return;

        // 冲突菜单关闭 → 回到主菜单
        if (title.contains("账号冲突") || title.contains("检测到离线数据")) {
            pendingPasswords.remove(uuid);
            backToMainMenu(player);
            return;
        }

        // 玩家按ESC关闭了主菜单 → 踢出（别再closeInventory，已经在close事件里了，会无限递归）
        String kickMsg = ChatColor.translateAlternateColorCodes('&',
                "&e&l需要登录认证\n\n&f请重新进入服务器\n&f完成登录或注册");
        player.kick(LegacyComponentSerializer.legacySection().deserialize(kickMsg));
    }

    // ═══════════════════════════════════════════════
    // 主菜单点击
    // ═══════════════════════════════════════════════

    @EventHandler(priority = EventPriority.LOWEST)
    public void onMainMenuClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        UUID uuid = player.getUniqueId();
        if (authManager.isLoggedIn(uuid)) return;

        String title = getViewTitle(event.getView());
        if (!title.contains("欢迎来到") && !title.contains("账号冲突") && !title.contains("检测到离线数据")) return;

        event.setCancelled(true);

        int slot = event.getRawSlot();
        if (slot < 0 || slot >= 27) return;

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.GRAY_STAINED_GLASS_PANE) return;

        AuthManager.AuthState state = authManager.getState(uuid);

        // 冲突解决菜单（转移后）
        if (title.contains("账号冲突")) {
            handleConflictMenu(player, uuid, slot);
            return;
        }

        // 转移前冲突选择菜单
        if (title.contains("检测到离线数据")) {
            handlePreTransferConflict(player, uuid, slot);
            return;
        }

        // 正版登录
        if (slot == 12) {
            doPremiumLogin(player, uuid);
        }
        // 离线注册
        else if (slot == 14 && state == AuthManager.AuthState.NEEDS_REGISTER) {
            pendingPasswords.remove(uuid);
            openAnvilInput(player, "&8请设置密码", "在铁砧输入框输入密码后关闭即提交");
        }
        // 离线登录
        else if (slot == 14 && state == AuthManager.AuthState.NEEDS_LOGIN) {
            pendingPasswords.remove(uuid);
            openAnvilInput(player, "&8请输入密码", "在铁砧输入框输入密码后关闭即提交");
        }
    }

    // ═══════════════════════════════════════════════
    // 正版验证
    // ═══════════════════════════════════════════════

    /**
     * 从认证子服写入的 JSON 结果文件中读取正版 UUID。
     * 返回 null 表示文件损坏或不存在。
     */
    private String readPremiumResult(File resultFile) {
        try (FileReader reader = new FileReader(resultFile)) {
            JsonObject obj = JsonParser.parseReader(reader).getAsJsonObject();
            String uuid = obj.has("premiumUuid") ? obj.get("premiumUuid").getAsString() : null;
            String name = obj.has("playerName") ? obj.get("playerName").getAsString() : "?";
            plugin.getLogger().info("[正版认证] 读取验证结果: player=" + name + ", premiumUuid=" + uuid);
            return uuid;
        } catch (Exception e) {
            plugin.getLogger().warning("[正版认证] 读取结果文件失败: " + e.getMessage());
            return null;
        }
    }

    /**
     * 反射调用 transferTo。Paper 反射重写器会拦截 getMethod，这里先打印
     * CraftPlayer 上所有含 transfer 的方法名，再精确匹配并调用。
     */
    private void transferPlayer(Player player, String host, int port) throws Exception {
        Class<?> clz = player.getClass();

        // 诊断：打印所有 transfer 相关方法
        StringBuilder found = new StringBuilder("[正版认证] CraftPlayer transfer 相关方法: ");
        for (java.lang.reflect.Method m : clz.getMethods()) {
            String n = m.getName();
            if (n.contains("ransfer") || n.contains("onnect")) {
                found.append("\n  ").append(n).append("(");
                Class<?>[] pts = m.getParameterTypes();
                for (int i = 0; i < pts.length; i++) {
                    if (i > 0) found.append(", ");
                    found.append(pts[i].getSimpleName());
                }
                found.append(")");
            }
        }
        plugin.getLogger().info(found.toString());

        // 按方法名匹配，不限定具体参数类型
        for (java.lang.reflect.Method m : clz.getMethods()) {
            if (!m.getName().equals("transferTo") && !m.getName().equals("transfer")) continue;
            if (m.getParameterCount() != 2) continue;

            Class<?> p0 = m.getParameterTypes()[0];
            Class<?> p1 = m.getParameterTypes()[1];
            plugin.getLogger().info("[正版认证] 尝试调用: " + m.getName() + "(" + p0.getSimpleName() + ", " + p1.getSimpleName() + ")");

            try {
                if (p0 == String.class && (p1 == int.class || p1 == Integer.class)) {
                    m.invoke(player, host, Integer.valueOf(port));
                    return;
                }
                if (p0 == InetSocketAddress.class) {
                    m.invoke(player, InetSocketAddress.createUnresolved(host, port));
                    return;
                }
                // 其他两参数组合也试试
                if (p0 == String.class) {
                    m.invoke(player, host, Integer.valueOf(port));
                    return;
                }
            } catch (java.lang.reflect.InvocationTargetException e) {
                throw new Exception("transferTo 调用失败: " + e.getCause().getMessage(), e.getCause());
            } catch (Exception ignored) {
                plugin.getLogger().warning("[正版认证] 调用 " + m.getName() + " 失败: " + ignored.getMessage());
            }
        }
        throw new NoSuchMethodException("transferTo/transfer 在 " + clz.getName() + " 上未找到可用签名");
    }

    private void doPremiumLogin(Player player, UUID offlineUuid) {
        final String playerName = player.getName();
        plugin.getLogger().info("[正版认证] ┌─ 玩家 " + playerName + " 选择了正版登录");

        inPremiumTransfer.add(offlineUuid);
        player.closeInventory();

        // 检查是否有离线数据（密码不是 PREMIUM 才算离线注册）
        boolean hasOfflineData = dbManager.playerExists(offlineUuid)
                && !"PREMIUM".equals(dbManager.getPasswordHash(offlineUuid));

        if (hasOfflineData) {
            plugin.getLogger().info("[正版认证] │ 检测到已有离线数据 → 打开冲突选择菜单");
            openPreTransferConflictMenu(player, offlineUuid);
        } else {
            plugin.getLogger().info("[正版认证] │ 无离线数据 → 直接 transfer");
            doTransfer(player, offlineUuid, playerName);
        }
    }

    /** 转移前的冲突选择菜单 */
    private void openPreTransferConflictMenu(Player player, UUID offlineUuid) {
        Inventory inv = Bukkit.createInventory(null, 27,
                ChatColor.translateAlternateColorCodes('&', "&c⚠ 检测到离线数据 — " + player.getName()));

        ItemStack border = glassPane();
        for (int i = 0; i < 27; i++) inv.setItem(i, border);

        inv.setItem(11, makeItem(Material.PAPER, "§e§l合并账号",
                "§7离线数据合并到正版身份",
                "§7需验证离线密码",
                "",
                "§c⚠ 正版登录后将使用同一份数据"));
        inv.setItem(15, makeItem(Material.DIAMOND, "§b§l注册为独立正版",
                "§7创建新的正版身份",
                "§7不影响已有离线账号",
                "",
                "§a✓ 离线/正版两套身份独立"));
        inv.setItem(13, makeItem(Material.BOOK, "§6§l已有离线注册数据",
                "§7玩家 " + player.getName() + " 已离线注册",
                "",
                "§7请选择处理方式"));

        player.openInventory(inv);
        authManager.setState(offlineUuid, AuthManager.AuthState.AWAITING_CONFLICT_RESOLVE);
        // 用 pendingPasswords 暂存标记（非 premium UUID，仅占位表明冲突状态）
        pendingPasswords.put(offlineUuid, "PRE_TRANSFER_CONFLICT");
    }

    /** 执行 transfer 到认证子服 */
    private void doTransfer(Player player, UUID offlineUuid, String playerName) {
        player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                "&b▐▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▌"));
        player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                "&b  &l正在前往正版验证子服..."));
        player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                "&7验证通过后将自动返回主服"));
        player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                "&b▐▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▌"));

        plugin.getLogger().info("[正版认证] │ 正在 transfer → " + authServerAddress + ":" + authServerPort);

        try {
            transferPlayer(player, authServerAddress, authServerPort);
            plugin.getLogger().info("[正版认证] └─ transfer 已发送，等待玩家从认证子服返回");
        } catch (Exception e) {
            plugin.getLogger().severe("[正版认证] └─ transfer 失败: " + e.getClass().getSimpleName() + " - " + e.getMessage());
            inPremiumTransfer.remove(offlineUuid);
            pendingPasswords.remove(offlineUuid);
            player.sendMessage(ChatColor.RED + "无法连接到验证子服！请联系管理员");
            backToMainMenu(player);
        }
    }

    private void openConflictMenu(Player player, UUID offlineUuid, String premiumUuid) {
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!player.isOnline() || authManager.isLoggedIn(offlineUuid)) return;

                Inventory inv = Bukkit.createInventory(null, 27,
                        ChatColor.translateAlternateColorCodes('&', "&c⚠ 账号冲突: " + player.getName()));

                ItemStack border = glassPane();
                for (int i = 0; i < 27; i++) inv.setItem(i, border);

                inv.setItem(11, makeItem(Material.PAPER, "§e§l合并账号",
                        "§7验证离线密码后将数据合并",
                        "",
                        "§c⚠ 正版端存档将被覆盖"));
                inv.setItem(15, makeItem(Material.DIAMOND, "§b§l注册为独立正版",
                        "§7离线账号保持不变",
                        "§7同时创建新正版身份",
                        "",
                        "§a✓ 两个账号独立并存"));
                inv.setItem(13, makeItem(Material.BOOK, "§6§l检测到离线账号",
                        "§7用户名 " + player.getName() + " 已有离线注册",
                        "",
                        "§7请选择处理方式"));

                player.openInventory(inv);
                authManager.setState(offlineUuid, AuthManager.AuthState.AWAITING_CONFLICT_RESOLVE);
                pendingPasswords.put(offlineUuid, premiumUuid);
            }
        }.runTask(plugin);
    }

    /** 转移前的冲突选择："合并"需先验离线密码，"新建"直接 transfer */
    private void handlePreTransferConflict(Player player, UUID offlineUuid, int slot) {
        String playerName = player.getName();
        if (slot == 11) {
            // 合并：先验证离线密码
            pendingPasswords.remove(offlineUuid);
            pendingPasswords.put(offlineUuid, "PREMIUM_MERGE_AWAIT_PW");
            player.closeInventory();
            authManager.setState(offlineUuid, AuthManager.AuthState.AWAITING_LOGIN_PASSWORD);
            openAnvilInput(player, "&8输入离线密码以合并", "密码正确则合并到正版账号");
            return;
        }
        if (slot == 15) {
            // 新建独立正版：直接 transfer，不碰离线数据
            pendingPasswords.remove(offlineUuid);
            player.closeInventory();
            plugin.getLogger().info("[正版认证] │ 玩家选择: new");
            saveChoiceFile(playerName, "new");
            doTransfer(player, offlineUuid, playerName);
            return;
        }
    }

    private void saveChoiceFile(String playerName, String choice) {
        File f = new File(choicesDir, playerName + ".json");
        try (FileWriter w = new FileWriter(f)) {
            JsonObject obj = new JsonObject();
            obj.addProperty("choice", choice);
            w.write(obj.toString());
        } catch (IOException e) {
            plugin.getLogger().warning("[正版认证] 写入 choice 文件失败: " + e.getMessage());
        }
    }

    private String readChoiceFile(String playerName) {
        File f = new File(choicesDir, playerName + ".json");
        if (!f.exists()) return null;
        try (FileReader r = new FileReader(f)) {
            return JsonParser.parseReader(r).getAsJsonObject().get("choice").getAsString();
        } catch (Exception e) {
            return null;
        }
    }

    private void handleConflictMenu(Player player, UUID offlineUuid, int slot) {
        String premiumUuid = pendingPasswords.get(offlineUuid);
        if (premiumUuid == null) return;

        if (slot == 11) {
            player.closeInventory();
            player.sendMessage(ChatColor.YELLOW + "请输入离线账号密码以完成合并");
            openAnvilInput(player, "&8输入离线密码进行合并", "密码正确则合并到正版账号");
            authManager.setState(offlineUuid, AuthManager.AuthState.AWAITING_LOGIN_PASSWORD);
        } else if (slot == 15) {
            pendingPasswords.remove(offlineUuid);
            authManager.createPremiumAccount(offlineUuid, player.getName(), premiumUuid);
            new BukkitRunnable() {
                @Override public void run() { onAuthSuccess(player, false); }
            }.runTask(plugin);
        }
    }

    // ═══════════════════════════════════════════════
    // 背包/通用点击拦截
    // ═══════════════════════════════════════════════

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInvClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        UUID uuid = player.getUniqueId();
        if (authManager.isLoggedIn(uuid)) return;

        boolean inAnvil = inAnvilInput.contains(uuid);
        String title = getViewTitle(event.getView());

        // 铁砧密码输入——锁定格子，结果格=提交
        if (inAnvil && event.getInventory() instanceof AnvilInventory anvil) {
            event.setCancelled(true);
            int rawSlot = event.getRawSlot();
            // 结果格(slot 2) → 提交
            if (rawSlot == 2) {
                ItemStack result = anvil.getItem(2);
                if (result != null && result.hasItemMeta()) {
                    String password = null;
                    try {
                        password = LegacyComponentSerializer.legacySection()
                                .serialize(result.getItemMeta().displayName()).trim();
                    } catch (Exception e) { password = ""; }
                    if (password != null && !password.isEmpty()
                            && !password.equals("在此输入...")
                            && !password.equals(ChatColor.translateAlternateColorCodes('&', "&7在此输入..."))) {
                        inAnvilInput.remove(uuid);
                        player.closeInventory();
                        processPasswordSubmit(player, uuid, password);
                        return;
                    }
                }
            }
            // 其他格子（slot 0输入、slot 1材料、玩家背包）→ 禁止
            return;
        }

        // 非认证流程 → 拦截
        if (!title.contains("欢迎来到") && !title.contains("账号冲突") && !title.contains("检测到离线数据")) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInvDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        UUID uuid = player.getUniqueId();
        if (authManager.isLoggedIn(uuid)) return;

        boolean inAnvil = inAnvilInput.contains(uuid);
        // 铁砧中禁止拖拽
        if (inAnvil && event.getInventory() instanceof AnvilInventory) {
            event.setCancelled(true);
            return;
        }

        String title = getViewTitle(event.getView());
        if (!title.contains("欢迎来到") && !title.contains("账号冲突") && !title.contains("检测到离线数据")) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInvOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        UUID uuid = player.getUniqueId();
        if (authManager.isLoggedIn(uuid) || inAnvilInput.contains(uuid)) return;

        String title = getViewTitle(event.getView());
        if (!title.contains("欢迎来到") && !title.contains("账号冲突") && !title.contains("检测到离线数据")) {
            event.setCancelled(true);
        }
    }

    // ═══════════════════════════════════════════════
    // 密码提交处理
    // ═══════════════════════════════════════════════

    private void processPasswordSubmit(Player player, UUID uuid, String password) {
        AuthManager.AuthState state = authManager.getState(uuid);
        String ip = getPlayerIp(player);

        // ──── 注册流程 ────
        if (state == AuthManager.AuthState.NEEDS_REGISTER ||
                (state == AuthManager.AuthState.AWAITING_REGISTER_PASSWORD && pendingPasswords.containsKey(uuid))) {

            String firstPassword = pendingPasswords.get(uuid);
            if (firstPassword == null) {
                // 第一次输入 → 暂存并打开确认铁砧
                pendingPasswords.put(uuid, password);
                player.sendMessage(ChatColor.GREEN + "密码已记录，请再次输入以确认");
                openAnvilInput(player, "&8请再次输入密码确认", "两次输入必须一致");
                return;
            }

            // 确认密码
            if (!password.equals(firstPassword)) {
                player.sendMessage(ChatColor.RED + "两次密码不一致，请重新注册！");
                pendingPasswords.remove(uuid);
                backToMainMenu(player);
                return;
            }

            // 密码一致 → 注册
            AuthManager.AuthResult result = authManager.register(uuid, player.getName(), password, ip);
            if (result == AuthManager.AuthResult.SUCCESS) {
                onAuthSuccess(player, true);
            } else if (result == AuthManager.AuthResult.VALIDATION_FAILED) {
                player.sendMessage(ChatColor.RED + "密码格式不符合要求！");
                player.sendMessage(ChatColor.GRAY + "要求: " + authManager.getMinPasswordLength()
                        + "-" + authManager.getMaxPasswordLength() + " 位，不含空格");
                pendingPasswords.remove(uuid);
                backToMainMenu(player);
            } else {
                player.sendMessage(ChatColor.RED + "注册失败，请重试！");
                pendingPasswords.remove(uuid);
                backToMainMenu(player);
            }
            return;
        }

        // ──── 转移前合并密码验证 ────
        String preMergeMark = pendingPasswords.get(uuid);
        if ("PREMIUM_MERGE_AWAIT_PW".equals(preMergeMark)) {
            AuthManager.AuthResult result = authManager.login(uuid, password, ip);
            if (result == AuthManager.AuthResult.SUCCESS) {
                // 密码正确 → 登录状态（以便 transfer 回来时直接合并），但不广播
                authManager.setState(uuid, AuthManager.AuthState.LOGGED_IN);
                pendingPasswords.remove(uuid);
                player.closeInventory();
                player.sendMessage(ChatColor.GREEN + "密码验证通过！正在前往正版验证...");
                saveChoiceFile(player.getName(), "merge");
                doTransfer(player, uuid, player.getName());
            } else if (result == AuthManager.AuthResult.WRONG_PASSWORD) {
                int remaining = authManager.getRemainingAttempts(uuid);
                player.kick(LegacyComponentSerializer.legacySection().deserialize(
                        ChatColor.translateAlternateColorCodes('&',
                                "&e&l密码错误\n\n&f离线密码不正确\n&f剩余尝试: &c" + remaining + " &f次")));
            } else {
                player.kick(LegacyComponentSerializer.legacySection().deserialize(
                        ChatColor.translateAlternateColorCodes('&',
                                "&e&l密码错误次数过多\n\n&f请重新进入服务器")));
            }
            return;
        }

        // ──── 合并流程（正版冲突 → 输入离线密码合并）────
        String mergePremium = pendingPasswords.get(uuid);
        if (state == AuthManager.AuthState.AWAITING_LOGIN_PASSWORD && mergePremium != null
                && mergePremium.contains("-")) { // premium UUID 格式
            if (authManager.mergePremiumWithOffline(uuid, password, mergePremium)) {
                pendingPasswords.remove(uuid);
                onAuthSuccess(player, false);
            } else {
                player.sendMessage(ChatColor.RED + "离线密码错误，合并失败！");
                pendingPasswords.remove(uuid);
                backToMainMenu(player);
            }
            return;
        }

        // ──── 登录流程 ────
        if (state == AuthManager.AuthState.NEEDS_LOGIN ||
                state == AuthManager.AuthState.AWAITING_LOGIN_PASSWORD) {
            AuthManager.AuthResult result = authManager.login(uuid, password, ip);
            if (result == AuthManager.AuthResult.SUCCESS) {
                onAuthSuccess(player, false);
            } else if (result == AuthManager.AuthResult.WRONG_PASSWORD) {
                int remaining = authManager.getRemainingAttempts(uuid);
                String kickMsg = ChatColor.translateAlternateColorCodes('&',
                        "&e&l密码错误\n\n&f密码不正确，请重新进入服务器\n&f剩余尝试次数: &c" + remaining + " &f次");
                player.kick(LegacyComponentSerializer.legacySection().deserialize(kickMsg));
            } else if (result == AuthManager.AuthResult.TOO_MANY_ATTEMPTS) {
                String kickMsg = ChatColor.translateAlternateColorCodes('&',
                        "&e&l密码错误次数过多\n\n&f你已被踢出服务器\n&f请联系管理员重置密码");
                player.kick(LegacyComponentSerializer.legacySection().deserialize(kickMsg));
            } else {
                player.sendMessage(ChatColor.RED + "登录失败，请重试！");
                authManager.setState(uuid, AuthManager.AuthState.NEEDS_LOGIN);
                backToMainMenu(player);
            }
        }
    }

    private void backToMainMenu(Player player) {
        UUID uuid = player.getUniqueId();
        player.closeInventory();
        new BukkitRunnable() {
            @Override
            public void run() {
                if (player.isOnline() && !authManager.isLoggedIn(uuid)) {
                    openMainMenu(player);
                }
            }
        }.runTaskLater(plugin, 3L);
    }

    // ═══════════════════════════════════════════════
    // 认证成功
    // ═══════════════════════════════════════════════

    private void onAuthSuccess(Player player, boolean isRegister) {
        UUID uuid = player.getUniqueId();
        pendingPasswords.remove(uuid);
        inAnvilInput.remove(uuid);
        inPremiumTransfer.remove(uuid);
        player.closeInventory();

        authManager.setState(uuid, AuthManager.AuthState.LOGGED_IN);
        authManager.setLoggedIn(uuid, true);
        unfreeze(player);
        player.getScoreboardTags().remove("premium");
        player.getScoreboardTags().add("offline");
        player.updateCommands(); // 重发命令列表给客户端（补全恢复）

        if (isRegister) {
            player.showTitle(Title.title(
                    Component.text(ChatColor.translateAlternateColorCodes('&', "&a&l注册成功！")),
                    Component.text(ChatColor.translateAlternateColorCodes('&', "&f欢迎加入 &6" + serverName)),
                    Title.Times.times(Duration.ofMillis(500), Duration.ofMillis(3000), Duration.ofMillis(500))));
        } else {
            player.showTitle(Title.title(
                    Component.text(ChatColor.translateAlternateColorCodes('&', "&a&l登录成功！")),
                    Component.text(ChatColor.translateAlternateColorCodes('&', "&f欢迎回到 &6" + serverName)),
                    Title.Times.times(Duration.ofMillis(500), Duration.ofMillis(3000), Duration.ofMillis(500))));
        }
        player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);

        new BukkitRunnable() {
            @Override
            public void run() {
                // 1. 加入广播
                doJoinBroadcast(player);

                // 2. 登录/注册广播（独立于加入）
                if (isRegister) {
                    String gameMsg = ChatColor.translateAlternateColorCodes('&',
                            "&a[认证] &f" + player.getName() + " &a注册成功！欢迎加入服务器");
                    Bukkit.broadcastMessage(gameMsg);
                    if (groupIds != null && !groupIds.isEmpty()) {
                        napCatWS.sendGroupMessage(groupIds,
                                "【" + serverName + "消息】" + qqAtFor(player)
                                        + player.getName() + " 注册成功！欢迎加入服务器");
                    }
                } else {
                    String gameMsg = ChatColor.translateAlternateColorCodes('&',
                            "&a[认证] &f" + player.getName() + " &a通过离线账号密码验证登录成功");
                    Bukkit.broadcastMessage(gameMsg);
                    if (groupIds != null && !groupIds.isEmpty()) {
                        napCatWS.sendGroupMessage(groupIds,
                                "【" + serverName + "消息】" + qqAtFor(player)
                                        + player.getName() + " 通过离线账号密码验证登录成功");
                    }
                }
            }
        }.runTask(plugin);
    }

    private String qqAtFor(Player player) {
        if (dbManager != null) {
            String qq = dbManager.getBoundQQ(player.getUniqueId());
            if (qq != null) return "[CQ:at,qq=" + qq + "] ";
        }
        return "";
    }

    private void doJoinBroadcast(Player player) {
        int online = Bukkit.getOnlinePlayers().size();
        int max = Bukkit.getMaxPlayers();
        String gameDisplay = qzxTitle.getGameDisplayName(player);
        Bukkit.broadcast(LegacyComponentSerializer.legacySection()
                .deserialize("§e" + gameDisplay + "§e 加入了服务器"));

        if (groupIds != null && !groupIds.isEmpty()) {
            String plainDisplay = qzxTitle.getPlainDisplayName(player);
            napCatWS.sendGroupMessage(groupIds,
                    "【服务器状态变化】" + qqAtFor(player) + plainDisplay
                            + " 加入了" + serverName + "(" + online + "/" + max + ")");
        }
    }

    /** 根据 DB 记录给玩家打 identity tag（offline/premium） */
    private void applyIdentityTag(Player player, UUID uuid) {
        player.getScoreboardTags().remove("offline");
        player.getScoreboardTags().remove("premium");
        boolean isPremium = dbManager.getPremiumUUIDByOffline(uuid.toString()) != null
                || "PREMIUM".equals(dbManager.getPasswordHash(uuid));
        player.getScoreboardTags().add(isPremium ? "premium" : "offline");
    }

    private void broadcastPremiumAuth(Player player, boolean isMerge) {
        player.getScoreboardTags().remove("offline");
        player.getScoreboardTags().add("premium");
        String subtitle = isMerge ? "&f欢迎回到 &6" + serverName + " &7（已合并离线数据）"
                : "&f欢迎回到 &6" + serverName + " &7（独立正版身份）";
        player.showTitle(Title.title(
                Component.text(ChatColor.translateAlternateColorCodes('&', "&b&l正版登录成功！")),
                Component.text(ChatColor.translateAlternateColorCodes('&', subtitle)),
                Title.Times.times(Duration.ofMillis(500), Duration.ofMillis(3000), Duration.ofMillis(500))));
        player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);

        String extra = isMerge ? "（已合并离线数据）" : "（独立正版身份）";
        String gameMsg = ChatColor.translateAlternateColorCodes('&',
                "&b[认证] &f" + player.getName() + " &b通过正版账号验证登录成功" + extra);
        Bukkit.broadcastMessage(gameMsg);

        if (groupIds != null && !groupIds.isEmpty()) {
            if (onMCToQQCounter != null) onMCToQQCounter.run();
            napCatWS.sendGroupMessage(groupIds,
                    "【" + serverName + "消息】" + qqAtFor(player) + player.getName()
                            + " 通过正版账号验证登录成功" + extra);
        }
    }

    // ═══════════════════════════════════════════════
    // 冻结 / 解冻（无失明，依靠事件拦截+背包清空+速度归零）
    // ═══════════════════════════════════════════════

    private void freeze(Player player) {
        UUID uuid = player.getUniqueId();
        frozenLocations.put(uuid, player.getLocation().clone());
        player.setWalkSpeed(0f);
    }

    private void unfreeze(Player player) {
        UUID uuid = player.getUniqueId();
        frozenLocations.remove(uuid);
        teleportGuard.remove(uuid);

        player.setWalkSpeed(0.2f);

        // 确保移除所有残留效果
        player.removePotionEffect(PotionEffectType.BLINDNESS);
        player.removePotionEffect(PotionEffectType.SLOWNESS);
        player.removePotionEffect(PotionEffectType.DARKNESS);


    }

    private void cleanup(Player player) {
        UUID uuid = player.getUniqueId();

        // ⚠ 先恢复状态再清理，防止退出时数据丢失
        frozenLocations.remove(uuid);
        teleportGuard.remove(uuid);
        player.setWalkSpeed(0.2f);

        pendingPasswords.remove(uuid);
        inAnvilInput.remove(uuid);
        inPremiumTransfer.remove(uuid);

        player.removePotionEffect(PotionEffectType.BLINDNESS);
        player.removePotionEffect(PotionEffectType.SLOWNESS);
        player.removePotionEffect(PotionEffectType.DARKNESS);

    }

    // ═══════════════════════════════════════════════
    // 未登录限制
    // ═══════════════════════════════════════════════

    @EventHandler(priority = EventPriority.LOWEST)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        if (authManager.isLoggedIn(uuid)) return;
        if (teleportGuard.contains(uuid)) return;

        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) return;

        if (from.getX() != to.getX() || from.getY() != to.getY() || from.getZ() != to.getZ()) {
            Location frozen = frozenLocations.get(uuid);
            if (frozen != null) {
                teleportGuard.add(uuid);
                player.teleport(frozen, PlayerTeleportEvent.TeleportCause.PLUGIN);
                teleportGuard.remove(uuid);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBlockBreak(BlockBreakEvent event) {
        if (!authManager.isLoggedIn(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (!authManager.isLoggedIn(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player p && !authManager.isLoggedIn(p.getUniqueId()))
            event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDamageByEntity(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player p && !authManager.isLoggedIn(p.getUniqueId()))
            event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrop(PlayerDropItemEvent event) {
        if (!authManager.isLoggedIn(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player p && !authManager.isLoggedIn(p.getUniqueId()))
            event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent event) {
        if (!authManager.isLoggedIn(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (!authManager.isLoggedIn(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteractAtEntity(PlayerInteractAtEntityEvent event) {
        if (!authManager.isLoggedIn(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onSwap(PlayerSwapHandItemsEvent event) {
        if (!authManager.isLoggedIn(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onFood(FoodLevelChangeEvent event) {
        if (event.getEntity() instanceof Player p && !authManager.isLoggedIn(p.getUniqueId()))
            event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommandSend(PlayerCommandSendEvent event) {
        if (!authManager.isLoggedIn(event.getPlayer().getUniqueId())) {
            event.getCommands().clear();
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onServerCommand(ServerCommandEvent event) {
        String message = event.getCommand();
        String[] parts = message.split(" ");
        StringBuilder sb = new StringBuilder();

        for (int i = 1; i < parts.length; i++) {
            String word = parts[i];
            String baseName = word;

            // 后缀路由
            String suffix = null;
            if (word.endsWith("-o") || word.endsWith("_o") || word.endsWith("-online") || word.endsWith("_online")) {
                suffix = "premium";
                baseName = word.replaceAll("[-_](o|f|offline|online)$", "");
            } else if (word.endsWith("-f") || word.endsWith("_f") || word.endsWith("-offline") || word.endsWith("_offline")) {
                suffix = "offline";
                baseName = word.replaceAll("[-_](o|f|offline|online)$", "");
            }
            if (suffix != null) {
                parts[i] = "@a[name=" + baseName + ",tag=" + suffix + ",limit=1]";
                continue;
            }

            // 双身份检测
            UUID offlineUuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + baseName).getBytes());
            boolean hasOffline = dbManager.playerExists(offlineUuid)
                    && !"PREMIUM".equals(dbManager.getPasswordHash(offlineUuid));
            boolean hasPremium = dbManager.hasPremiumMappingByUsername(baseName);
            if (hasOffline && hasPremium) {
                if (sb.length() == 0) sb.append(ChatColor.translateAlternateColorCodes('&',
                        "&e同名账号 &f" + baseName + " &e存在双身份:"));
                else sb.append("\n");
                sb.append(" ").append(ChatColor.translateAlternateColorCodes('&',
                        "&7[离线] &f后缀: " + baseName + "-f  &b[正版] &f后缀: " + baseName + "-o"));
            }
        }

        if (sb.length() > 0) {
            sb.append("\n").append(ChatColor.translateAlternateColorCodes('&',
                    "&7原命令: &f" + message + "\n&7请加后缀区分后重试"));
            event.setCancelled(true);
            event.getSender().sendMessage(sb.toString());
            plugin.getLogger().info("[命令路由-终端] 拦截歧义命令: " + message);
            return;
        }

        // 无歧义但有后缀替换 → 重写命令
        String newCmd = String.join(" ", parts);
        if (!newCmd.equals(message)) {
            event.setCommand(newCmd);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (authManager.isLoggedIn(player.getUniqueId())) return;
        String message = event.getMessage().toLowerCase();
        String cmd = message.contains(" ") ? message.substring(0, message.indexOf(' ')) : message;
        if (ALLOWED_COMMANDS.contains(cmd)) return;
        event.setCancelled(true);
        player.sendMessage(ChatColor.RED + "请先完成认证后再使用命令！");
    }

    // ═══════════════════════════════════════════════
    // 同名双开命令路由：XD-o 路由到离线，XD-f 路由到正版，无后缀弹出选择
    // ═══════════════════════════════════════════════

    // 命令身份选择 — 多目标逐个选
    private final Set<UUID> pendingCommandChoice = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Map<UUID, PendingCommandState> pendingCommandStates = new ConcurrentHashMap<>();
    private final Set<UUID> advancingChoice = Collections.newSetFromMap(new ConcurrentHashMap<>());

    private static class PendingCommandState {
        final String originalMessage;
        final String[] parts;
        final List<Integer> ambiguousIndices = new ArrayList<>();
        final Map<Integer, PendingChoice> choices = new LinkedHashMap<>();
        final Map<Integer, String> resolved = new HashMap<>();
        int cursor = 0;
        PendingCommandState(String msg, String[] parts) { this.originalMessage = msg; this.parts = parts; }
    }

    private static class PendingChoice {
        final String baseName;
        final UUID offlineUuid;
        final Player offlineOnline;
        final Player premiumOnline;
        PendingChoice(String n, UUID ou, Player oo, Player po) { baseName=n; offlineUuid=ou; offlineOnline=oo; premiumOnline=po; }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onCommandIdentityRoute(PlayerCommandPreprocessEvent event) {
        Player sender = event.getPlayer();
        if (!authManager.isLoggedIn(sender.getUniqueId())) return;
        if (pendingCommandChoice.contains(sender.getUniqueId())) return;

        String message = event.getMessage();
        String[] parts = message.split(" ");
        boolean modified = false;
        PendingCommandState state = null;

        for (int i = 1; i < parts.length; i++) {
            String word = parts[i];
            // ── 后缀路由 ──
            String suffix = null;
            if (word.endsWith("-o") || word.endsWith("_o") || word.endsWith("-online") || word.endsWith("_online")) {
                suffix = "premium";
            } else if (word.endsWith("-f") || word.endsWith("_f") || word.endsWith("-offline") || word.endsWith("_offline")) {
                suffix = "offline";
            }
            if (suffix != null) {
                String baseName = word.replaceAll("[-_](o|f|offline|online)$", "");
                parts[i] = "@a[name=" + baseName + ",tag=" + suffix + ",limit=1]";
                modified = true;
                continue;
            }
            // ── 双身份检测 ──
            String baseName = word;
            UUID offlineUuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + baseName).getBytes());
            boolean hasOffline = dbManager.playerExists(offlineUuid)
                    && !"PREMIUM".equals(dbManager.getPasswordHash(offlineUuid));
            boolean hasPremium = dbManager.hasPremiumMappingByUsername(baseName);
            if (!hasOffline || !hasPremium) continue;

            Player offlineOnline = null, premiumOnline = null;
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (!p.getName().equals(baseName)) continue;
                if (p.getScoreboardTags().contains("premium")) premiumOnline = p;
                else if (p.getScoreboardTags().contains("offline")) offlineOnline = p;
                else { if (p.getUniqueId().equals(offlineUuid)) offlineOnline = p; else premiumOnline = p; }
            }

            if (state == null) state = new PendingCommandState(message, parts);
            state.ambiguousIndices.add(i);
            state.choices.put(i, new PendingChoice(baseName, offlineUuid, offlineOnline, premiumOnline));
            plugin.getLogger().info("[命令路由] 歧义#" + state.ambiguousIndices.size()
                    + ": " + baseName + " 离线=" + (offlineOnline != null ? "在线" : "不在线")
                    + " 正版=" + (premiumOnline != null ? "在线" : "不在线"));
        }

        // 无歧义 → 放行（含纯后缀替换）
        if (state == null) {
            if (modified) event.setMessage(String.join(" ", parts));
            return;
        }

        // 有歧义 → 取消命令，开始逐个选
        event.setCancelled(true);
        pendingCommandChoice.add(sender.getUniqueId());
        pendingCommandStates.put(sender.getUniqueId(), state);
        showNextCommandChoice(sender);
    }

    /** 弹出当前游标指向的歧义名选择 GUI */
    private void showNextCommandChoice(Player player) {
        PendingCommandState state = pendingCommandStates.get(player.getUniqueId());
        if (state == null) return;
        if (state.cursor >= state.ambiguousIndices.size()) {
            // 全部选完 → 构建并执行最终命令，去掉 /
            String[] finalParts = state.parts.clone();
            for (Map.Entry<Integer, String> e : state.resolved.entrySet()) finalParts[e.getKey()] = e.getValue();
            String finalCmd = String.join(" ", finalParts).substring(1);
            pendingCommandChoice.remove(player.getUniqueId());
            pendingCommandStates.remove(player.getUniqueId());
            Bukkit.dispatchCommand(player, finalCmd);
            return;
        }
        int idx = state.ambiguousIndices.get(state.cursor);
        PendingChoice c = state.choices.get(idx);
        openIdentityChoiceGUI(player, c.baseName, c.offlineOnline, c.premiumOnline, c.offlineUuid, idx);
    }

    private void openIdentityChoiceGUI(Player sender, String baseName, Player offlineOnline, Player premiumOnline,
                                        UUID offlineUuid, int argIndex) {
        PendingCommandState state = pendingCommandStates.get(sender.getUniqueId());
        if (state == null) return;
        int remaining = state.ambiguousIndices.size() - state.cursor;
        Inventory inv = Bukkit.createInventory(null, 9,
                ChatColor.translateAlternateColorCodes('&', "&6[" + (state.cursor + 1) + "/"
                        + state.ambiguousIndices.size() + "] &f" + baseName));

        String offlineStatus = offlineOnline != null ? "§a● 在线" : "§c○ 不在线";
        inv.setItem(2, makeItem(Material.IRON_SWORD, "§7" + baseName + " §f[§7离线账号§f]",
                "§7离线登录 · 密码验证",
                "§7UUID: " + offlineUuid.toString().substring(0, 8) + "…",
                offlineStatus));

        UUID premiumUuid = null;
        if (premiumOnline != null) premiumUuid = premiumOnline.getUniqueId();
        else {
            String pid = dbManager.getPremiumUUIDByOffline(offlineUuid.toString());
            if (pid != null) premiumUuid = UUID.fromString(pid);
        }
        String premiumStatus = premiumOnline != null ? "§a● 在线" : "§c○ 不在线";
        inv.setItem(6, makeItem(Material.DIAMOND_SWORD, "§b" + baseName + " §f[§b正版账号§f]",
                "§bMojang 验证 · 独立身份",
                "§7UUID: " + (premiumUuid != null ? premiumUuid.toString().substring(0, 8) : "?") + "…",
                premiumStatus,
                "§8剩余待选: " + (remaining - 1) + " 个"));

        sender.openInventory(inv);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onIdentityChoiceClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        UUID uuid = player.getUniqueId();
        if (!pendingCommandChoice.contains(uuid)) return;

        String title = getViewTitle(event.getView());
        if (title.isEmpty() || (!title.contains(baseNameFromTitle(title)) && !title.contains("选择"))) return;

        event.setCancelled(true);
        int slot = event.getRawSlot();
        if (slot != 2 && slot != 6) return;

        PendingCommandState state = pendingCommandStates.get(uuid);
        if (state == null || state.cursor >= state.ambiguousIndices.size()) return;

        int idx = state.ambiguousIndices.get(state.cursor);
        PendingChoice c = state.choices.get(idx);
        boolean chooseOffline = (slot == 2);

        String tag = chooseOffline ? "offline" : "premium";
        String selector = "@a[name=" + c.baseName + ",tag=" + tag + ",limit=1]";
        state.resolved.put(idx, selector);

        advancingChoice.add(uuid);
        player.closeInventory();
        state.cursor++;
        showNextCommandChoice(player);
        advancingChoice.remove(uuid);
    }

    private String baseNameFromTitle(String title) {
        int i = title.lastIndexOf(' ');
        return i >= 0 ? title.substring(i + 1) : title;
    }

    /** ESC 关闭选择 GUI 时清理状态，防止下次命令跳过选择直接放行 */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onIdentityChoiceClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        UUID uuid = player.getUniqueId();
        if (!pendingCommandChoice.contains(uuid)) return;
        if (advancingChoice.contains(uuid)) return; // 切到下一个选择，不是关闭
        String title = getViewTitle(event.getView());
        if (title.contains("/") && title.contains("]")) {
            pendingCommandChoice.remove(uuid);
            pendingCommandStates.remove(uuid);
            plugin.getLogger().info("[命令路由] GUI 被关闭，已清理待选状态");
        }
    }

    // ═══════════════════════════════════════════════
    // 工具
    // ═══════════════════════════════════════════════

    private ItemStack glassPane() {
        ItemStack item = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName("§7 ");
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack makeItem(Material material, String name, String... loreLines) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', name));
        if (loreLines.length > 0) {
            List<Component> lore = new ArrayList<>();
            for (String line : loreLines) {
                lore.add(LegacyComponentSerializer.legacySection()
                        .deserialize(ChatColor.translateAlternateColorCodes('&', line)));
            }
            meta.lore(lore);
        }
        item.setItemMeta(meta);
        return item;
    }

    private String getViewTitle(InventoryView view) {
        try {
            return LegacyComponentSerializer.legacySection().serialize(view.title());
        } catch (Exception e) {
            return "";
        }
    }

    private String getPlayerIp(Player player) {
        try {
            return player.getAddress().getAddress().getHostAddress();
        } catch (Exception e) {
            return "0.0.0.0";
        }
    }
}