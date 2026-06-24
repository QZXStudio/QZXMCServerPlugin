package com.qzxtpfwq;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
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

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
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

    // 冻结
    private final Map<UUID, Location> frozenLocations = new ConcurrentHashMap<>();
    private final Set<UUID> teleportGuard = Collections.newSetFromMap(new ConcurrentHashMap<>());

    // 游戏模式保存/恢复
    private final Map<UUID, GameMode> savedGameModes = new ConcurrentHashMap<>();

    // 注册时第一次输入的密码暂存
    private final Map<UUID, String> pendingPasswords = new ConcurrentHashMap<>();
    // 标记玩家正在铁砧GUI中输入密码
    private final Set<UUID> inAnvilInput = Collections.newSetFromMap(new ConcurrentHashMap<>());

    // 未登录时允许的命令（极简白名单）
    private static final Set<String> ALLOWED_COMMANDS = Set.of("/help", "/?");

    public AuthListener(AuthManager authManager, JavaPlugin plugin, NapCatWS napCatWS,
                        QZXTitle qzxTitle, String serverName, List<Long> groupIds,
                        DatabaseManager dbManager, Runnable onMCToQQCounter) {
        this.authManager = authManager;
        this.plugin = plugin;
        this.napCatWS = napCatWS;
        this.qzxTitle = qzxTitle;
        this.serverName = serverName;
        this.groupIds = groupIds;
        this.dbManager = dbManager;
        this.onMCToQQCounter = onMCToQQCounter;
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

        // 会话有效 → 自动登录
        if (authManager.isRegistered(uuid) && authManager.hasValidSession(uuid, ip)) {
            authManager.setState(uuid, AuthManager.AuthState.LOGGED_IN);
            authManager.setLoggedIn(uuid, true);
            authManager.saveSession(uuid, ip);
            player.updateCommands();
            doJoinBroadcast(player);
            return;
        }

        authManager.setState(uuid,
                authManager.isRegistered(uuid) ? AuthManager.AuthState.NEEDS_LOGIN : AuthManager.AuthState.NEEDS_REGISTER);

        freeze(player);

        new BukkitRunnable() {
            @Override
            public void run() {
                if (player.isOnline() && !authManager.isLoggedIn(uuid)) {
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

        if (isNew) {
            inv.setItem(11, makeItem(Material.NAME_TAG, "§a§l注册账号",
                    "§7§o新玩家请点击这里",
                    "",
                    "§f在铁砧输入框输入密码",
                    "§f密码要求: §e" + authManager.getMinPasswordLength() + "§f-§e" + authManager.getMaxPasswordLength() + " §f位",
                    "§f支持字母+数字，不含空格"));
        } else {
            inv.setItem(11, makeItem(Material.GRAY_DYE, "§8注册账号（不可用）", "§7你已注册过账号"));
        }

        if (!isNew) {
            inv.setItem(15, makeItem(Material.ENDER_PEARL, "§e§l登录账号",
                    "§7§o已有账号请点击这里",
                    "",
                    "§f在铁砧输入框输入密码",
                    "",
                    "§c输错" + authManager.getMaxLoginAttempts() + "次将被踢出"));
        } else {
            inv.setItem(15, makeItem(Material.GRAY_DYE, "§8登录账号（不可用）", "§7你还没有注册账号"));
        }

        List<String> infoLore = new ArrayList<>();
        infoLore.add("");
        infoLore.add("§f在线玩家: §e" + Bukkit.getOnlinePlayers().size() + "§f/§e" + Bukkit.getMaxPlayers());
        infoLore.add("§f你的ID: §e" + player.getName());
        infoLore.add("");
        infoLore.add(isNew ? "§a§l▶ 点击命名牌注册" : "§e§l▶ 点击末影珍珠登录");
        inv.setItem(13, makeItem(Material.BOOK, "§6§l" + serverName, infoLore.toArray(new String[0])));

        player.openInventory(inv);
    }

    // ═══════════════════════════════════════════════
    // 铁砧 GUI 密码输入
    // ═══════════════════════════════════════════════

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
        if (inAnvilInput.contains(uuid)) return; // 铁砧关闭另有处理

        String title = getViewTitle(event.getView());
        if (!title.contains("欢迎来到")) return;

        // 玩家按ESC关闭了主菜单 → 踢出
        player.closeInventory();
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
        if (!title.contains("欢迎来到")) return;

        event.setCancelled(true);

        int slot = event.getRawSlot();
        if (slot < 0 || slot >= 27) return;

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.GRAY_STAINED_GLASS_PANE) return;

        AuthManager.AuthState state = authManager.getState(uuid);

        if (slot == 11 && state == AuthManager.AuthState.NEEDS_REGISTER) {
            pendingPasswords.remove(uuid);
            openAnvilInput(player, "&8请设置密码", "在铁砧输入框输入密码后关闭即提交");
        } else if (slot == 15 && state == AuthManager.AuthState.NEEDS_LOGIN) {
            pendingPasswords.remove(uuid);
            openAnvilInput(player, "&8请输入密码", "在铁砧输入框输入密码后关闭即提交");
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
        if (!title.contains("欢迎来到")) {
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
        if (!title.contains("欢迎来到")) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInvOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        UUID uuid = player.getUniqueId();
        if (authManager.isLoggedIn(uuid) || inAnvilInput.contains(uuid)) return;

        String title = getViewTitle(event.getView());
        if (!title.contains("欢迎来到")) {
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
        player.closeInventory();

        authManager.setState(uuid, AuthManager.AuthState.LOGGED_IN);
        authManager.setLoggedIn(uuid, true);
        unfreeze(player);
        player.updateCommands(); // 重发命令列表给客户端（补全恢复）

        if (isRegister) {
            player.showTitle(Title.title(
                    Component.text(ChatColor.translateAlternateColorCodes('&', "&a&l注册成功！")),
                    Component.text(ChatColor.translateAlternateColorCodes('&', "&f欢迎加入 &6" + serverName)),
                    Title.Times.times(Duration.ofMillis(500), Duration.ofMillis(3000), Duration.ofMillis(500))));
        } else {
            player.showTitle(Title.title(
                    Component.text(ChatColor.translateAlternateColorCodes('&', "&e&l登录成功！")),
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
                            "&a[认证] &f" + player.getName() + " &a注册成功！欢迎加入服务器喵~");
                    Bukkit.broadcastMessage(gameMsg);
                    if (groupIds != null && !groupIds.isEmpty()) {
                        napCatWS.sendGroupMessage(groupIds,
                                "【" + serverName + "消息】" + qqAtFor(player)
                                        + player.getName() + " 注册成功！欢迎加入服务器喵~");
                    }
                } else {
                    String gameMsg = ChatColor.translateAlternateColorCodes('&',
                            "&e[认证] &f" + player.getName() + " &e登录成功");
                    Bukkit.broadcastMessage(gameMsg);
                    if (groupIds != null && !groupIds.isEmpty()) {
                        napCatWS.sendGroupMessage(groupIds,
                                "【" + serverName + "消息】" + qqAtFor(player)
                                        + player.getName() + " 登录成功");
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

    // ═══════════════════════════════════════════════
    // 冻结 / 解冻（无失明，依靠事件拦截+背包清空+速度归零）
    // ═══════════════════════════════════════════════

    private void freeze(Player player) {
        UUID uuid = player.getUniqueId();
        frozenLocations.put(uuid, player.getLocation().clone());

        // 保存并设置游戏模式（不碰飞行属性，让gamemode自行管理能力）
        savedGameModes.put(uuid, player.getGameMode());
        player.setGameMode(GameMode.ADVENTURE);
        player.setWalkSpeed(0f);

        // 无敌 + 满血满饱
        player.setInvulnerable(true);
        player.setHealth(player.getAttribute(Attribute.GENERIC_MAX_HEALTH).getValue());
        player.setFoodLevel(20);
        player.setSaturation(5f);

        // 互不可见
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (other.getUniqueId().equals(uuid)) continue;
            if (!authManager.isLoggedIn(other.getUniqueId())) {
                player.hidePlayer(plugin, other);
                other.hidePlayer(plugin, player);
            } else {
                other.hidePlayer(plugin, player);
                player.hidePlayer(plugin, other);
            }
        }
    }

    private void unfreeze(Player player) {
        UUID uuid = player.getUniqueId();
        frozenLocations.remove(uuid);
        teleportGuard.remove(uuid);

        // 恢复游戏模式——setGameMode 自行设置 allowFlight/flySpeed 等能力
        GameMode gm = savedGameModes.remove(uuid);
        if (gm != null) player.setGameMode(gm);
        player.setWalkSpeed(0.2f);  // 恢复默认行走速度

        // 确保移除所有残留效果
        player.removePotionEffect(PotionEffectType.BLINDNESS);
        player.removePotionEffect(PotionEffectType.SLOWNESS);
        player.removePotionEffect(PotionEffectType.DARKNESS);
        player.setInvulnerable(false);

        // 恢复可见性
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (other.getUniqueId().equals(uuid)) continue;
            player.showPlayer(plugin, other);
            other.showPlayer(plugin, player);
        }
    }

    private void cleanup(Player player) {
        UUID uuid = player.getUniqueId();

        // ⚠ 先恢复状态再清理，防止退出时数据丢失
        frozenLocations.remove(uuid);
        teleportGuard.remove(uuid);

        GameMode gm = savedGameModes.remove(uuid);
        if (gm != null) player.setGameMode(gm);
        player.setWalkSpeed(0.2f);

        pendingPasswords.remove(uuid);
        inAnvilInput.remove(uuid);

        player.removePotionEffect(PotionEffectType.BLINDNESS);
        player.removePotionEffect(PotionEffectType.SLOWNESS);
        player.removePotionEffect(PotionEffectType.DARKNESS);
        player.setInvulnerable(false);

        // 恢复可见性
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (other.getUniqueId().equals(uuid)) continue;
            player.showPlayer(plugin, other);
            other.showPlayer(plugin, player);
        }
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
