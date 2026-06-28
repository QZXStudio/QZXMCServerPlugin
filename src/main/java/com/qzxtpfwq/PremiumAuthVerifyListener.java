package com.qzxtpfwq;

import com.google.gson.JsonObject;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.logging.Logger;

/**
 * 正版认证子服监听器 (mode=verify)。
 * 子服必须以 online-mode=true 运行，Mojang 验证成功后 UUID 才是真实的。
 */
public class PremiumAuthVerifyListener implements Listener {

    private final JavaPlugin plugin;
    private final Logger logger;
    private final File resultsDir;
    private final String mainServerAddress;
    private final int mainServerPort;

    public PremiumAuthVerifyListener(JavaPlugin plugin, String sharedDataDir, String mainServerAddress) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.resultsDir = new File(sharedDataDir, "results");
        if (!resultsDir.exists()) resultsDir.mkdirs();

        String[] parts = mainServerAddress.split(":");
        this.mainServerAddress = parts[0];
        this.mainServerPort = parts.length > 1 ? Integer.parseInt(parts[1]) : 25565;
    }

    public void register() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        String playerName = player.getName();

        // online-mode=true 的服务器上，UUID 是 Mojang 官方下发的正版 UUID
        String premiumUuid = player.getUniqueId().toString();
        event.joinMessage(null);

        logger.info("[正版认证子服] ┌─ 正版玩家加入: " + playerName);
        logger.info("[正版认证子服] │ Mojang 验证通过 — 真实 UUID: " + premiumUuid);

        // 写入验证结果到共享目录
        writeResult(playerName, premiumUuid);

        // 1 秒后 transfer 回主服
        player.sendMessage(Component.text("§a§l正版验证通过！§r §7正在传回主服..."));
        logger.info("[正版认证子服] │ 1秒后 transfer 到主服 " + mainServerAddress + ":" + mainServerPort);

        new BukkitRunnable() {
            @Override
            public void run() {
                if (!player.isOnline()) return;
                try {
                    Class<?> clz = player.getClass();
                    for (java.lang.reflect.Method m : clz.getMethods()) {
                        if (!m.getName().equals("transferTo") && !m.getName().equals("transfer")) continue;
                        if (m.getParameterCount() != 2) continue;
                        try {
                            Class<?> p0 = m.getParameterTypes()[0];
                            if (p0 == String.class) {
                                m.invoke(player, mainServerAddress, mainServerPort);
                            } else {
                                m.invoke(player, InetSocketAddress.createUnresolved(mainServerAddress, mainServerPort));
                            }
                            logger.info("[正版认证子服] └─ 已 transfer " + playerName + " 回主服");
                            return;
                        } catch (Exception ignored) {}
                    }
                    throw new NoSuchMethodException("transferTo not found");
                } catch (Exception e) {
                    logger.severe("[正版认证子服] └─ transfer 失败: " + e.getClass().getSimpleName() + " - " + e.getMessage());
                    player.kick(Component.text("§c传回主服失败，请重新连接主服"));
                }
            }
        }.runTaskLater(plugin, 20L);
    }

    private void writeResult(String playerName, String premiumUuid) {
        // 先写临时文件再 rename，保证原子性
        File resultFile = new File(resultsDir, playerName + ".json");
        File tmpFile = new File(resultsDir, playerName + ".tmp");
        try {
            JsonObject obj = new JsonObject();
            obj.addProperty("playerName", playerName);
            obj.addProperty("premiumUuid", premiumUuid);
            obj.addProperty("timestamp", System.currentTimeMillis());

            try (FileWriter w = new FileWriter(tmpFile)) {
                w.write(obj.toString());
            }
            Files.move(tmpFile.toPath(), resultFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            logger.info("[正版认证子服] │ 验证结果已写入: " + resultFile.getAbsolutePath());
        } catch (IOException e) {
            logger.severe("[正版认证子服] │ 写入结果文件失败: " + e.getMessage());
        }
    }
}
