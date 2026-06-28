package com.qzxtpfwq;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.logging.Logger;

public class DatabaseManager {

    private final String url;
    private Connection connection;
    private final Logger logger;

    public DatabaseManager(File dataFolder, Logger logger) {
        this.url = "jdbc:sqlite:" + new File(dataFolder, "auth.db").getAbsolutePath();
        this.logger = logger;
    }

    public void init() throws SQLException {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new SQLException("SQLite JDBC driver not found", e);
        }
        connection = DriverManager.getConnection(url);
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("PRAGMA journal_mode=WAL");
            stmt.execute("PRAGMA synchronous=NORMAL");
        }
        try (Statement stmt = connection.createStatement()) {
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS auth_players (" +
                "  uuid TEXT PRIMARY KEY," +
                "  username TEXT NOT NULL," +
                "  password_hash TEXT NOT NULL," +
                "  salt TEXT NOT NULL," +
                "  last_ip TEXT," +
                "  register_date INTEGER NOT NULL," +
                "  last_login INTEGER NOT NULL" +
                ")"
            );
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS qq_bindings (" +
                "  uuid TEXT PRIMARY KEY," +
                "  qq_number TEXT NOT NULL UNIQUE," +
                "  username TEXT NOT NULL," +
                "  verify_code TEXT," +
                "  bind_date INTEGER NOT NULL" +
                ")"
            );
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS premium_accounts (" +
                "  premium_uuid TEXT PRIMARY KEY," +
                "  offline_uuid TEXT NOT NULL UNIQUE," +
                "  username TEXT NOT NULL" +
                ")"
            );
        }
        logger.info("认证数据库已初始化: " + url);
    }

    public synchronized Connection getConnection() throws SQLException {
        if (connection == null || connection.isClosed()) {
            connection = DriverManager.getConnection(url);
        }
        return connection;
    }

    public void close() {
        if (connection != null) {
            try {
                if (!connection.isClosed()) {
                    connection.close();
                }
            } catch (SQLException e) {
                logger.warning("关闭数据库连接时出错: " + e.getMessage());
            }
        }
    }

    public boolean playerExists(UUID uuid) {
        String sql = "SELECT 1 FROM auth_players WHERE uuid = ?";
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            logger.warning("查询玩家是否存在失败: " + e.getMessage());
            return false;
        }
    }

    public void registerPlayer(UUID uuid, String username, String passwordHash, String salt, String ip) {
        String sql = "INSERT INTO auth_players (uuid, username, password_hash, salt, last_ip, register_date, last_login) VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            long now = System.currentTimeMillis();
            ps.setString(1, uuid.toString());
            ps.setString(2, username);
            ps.setString(3, passwordHash);
            ps.setString(4, salt);
            ps.setString(5, ip);
            ps.setLong(6, now);
            ps.setLong(7, now);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.warning("注册玩家失败: " + e.getMessage());
        }
    }

    public String getPasswordHash(UUID uuid) {
        String sql = "SELECT password_hash FROM auth_players WHERE uuid = ?";
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("password_hash");
                }
            }
        } catch (SQLException e) {
            logger.warning("获取密码哈希失败: " + e.getMessage());
        }
        return null;
    }

    public String getSalt(UUID uuid) {
        String sql = "SELECT salt FROM auth_players WHERE uuid = ?";
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("salt");
                }
            }
        } catch (SQLException e) {
            logger.warning("获取盐值失败: " + e.getMessage());
        }
        return null;
    }

    public String getUsername(UUID uuid) {
        String sql = "SELECT username FROM auth_players WHERE uuid = ?";
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("username");
                }
            }
        } catch (SQLException e) {
            logger.warning("获取用户名失败: " + e.getMessage());
        }
        return null;
    }

    public void updateLastLogin(UUID uuid, String ip) {
        String sql = "UPDATE auth_players SET last_login = ?, last_ip = ? WHERE uuid = ?";
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setLong(1, System.currentTimeMillis());
            ps.setString(2, ip);
            ps.setString(3, uuid.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.warning("更新最后登录时间失败: " + e.getMessage());
        }
    }

    public void changePassword(UUID uuid, String newHash, String newSalt) {
        String sql = "UPDATE auth_players SET password_hash = ?, salt = ? WHERE uuid = ?";
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setString(1, newHash);
            ps.setString(2, newSalt);
            ps.setString(3, uuid.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.warning("修改密码失败: " + e.getMessage());
        }
    }

    // ══════════ QQ 绑定 ══════════

    public boolean isQQBound(String qqNumber) {
        String sql = "SELECT 1 FROM qq_bindings WHERE qq_number = ?";
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setString(1, qqNumber);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            logger.warning("查询QQ绑定状态失败: " + e.getMessage());
            return false;
        }
    }

    public boolean isPlayerBound(UUID uuid) {
        String sql = "SELECT 1 FROM qq_bindings WHERE uuid = ?";
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            logger.warning("查询玩家绑定状态失败: " + e.getMessage());
            return false;
        }
    }

    public String getBoundQQ(UUID uuid) {
        String sql = "SELECT qq_number FROM qq_bindings WHERE uuid = ?";
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getString("qq_number");
            }
        } catch (SQLException e) {
            logger.warning("获取绑定QQ失败: " + e.getMessage());
        }
        return null;
    }

    public String getBoundPlayerName(String qqNumber) {
        String sql = "SELECT username FROM qq_bindings WHERE qq_number = ?";
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setString(1, qqNumber);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getString("username");
            }
        } catch (SQLException e) {
            logger.warning("获取绑定玩家失败: " + e.getMessage());
        }
        return null;
    }

    public void saveVerifyCode(UUID uuid, String username, String qqNumber, String code) {
        String sql = "INSERT OR REPLACE INTO qq_bindings (uuid, qq_number, username, verify_code, bind_date) VALUES (?, ?, ?, ?, ?)";
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, qqNumber);
            ps.setString(3, username);
            ps.setString(4, code);
            ps.setLong(5, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.warning("保存验证码失败: " + e.getMessage());
        }
    }

    public String verifyAndBind(UUID uuid, String code) {
        String sql = "SELECT verify_code FROM qq_bindings WHERE uuid = ?";
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String storedCode = rs.getString("verify_code");
                    if (storedCode != null && storedCode.equals(code)) {
                        // 清除验证码标记已绑定
                        String update = "UPDATE qq_bindings SET verify_code = NULL, bind_date = ? WHERE uuid = ?";
                        try (PreparedStatement up = getConnection().prepareStatement(update)) {
                            up.setLong(1, System.currentTimeMillis());
                            up.setString(2, uuid.toString());
                            up.executeUpdate();
                        }
                        return code; // 返回非空表示成功
                    }
                }
            }
        } catch (SQLException e) {
            logger.warning("验证绑定失败: " + e.getMessage());
        }
        return null;
    }

    public String getQQByVerifyCode(String code) {
        String sql = "SELECT qq_number FROM qq_bindings WHERE verify_code = ?";
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setString(1, code);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getString("qq_number");
            }
        } catch (SQLException e) {
            logger.warning("查询验证码失败: " + e.getMessage());
        }
        return null;
    }

    public void confirmBindByCode(String code) {
        String sql = "UPDATE qq_bindings SET verify_code = NULL WHERE verify_code = ?";
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setString(1, code);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.warning("确认绑定失败: " + e.getMessage());
        }
    }

    // ══════════ 正版账号 ══════════

    /** 存入正版账号映射（premium_uuid → offline_uuid） */
    public void linkPremiumAccount(String premiumUuid, String offlineUuid, String username) {
        String sql = "INSERT OR REPLACE INTO premium_accounts (premium_uuid, offline_uuid, username) VALUES (?, ?, ?)";
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setString(1, premiumUuid);
            ps.setString(2, offlineUuid);
            ps.setString(3, username);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.warning("保存正版账号映射失败: " + e.getMessage());
        }
    }

    /** 根据正版 UUID 查离线 UUID */
    public String getOfflineUUIDByPremium(String premiumUuid) {
        String sql = "SELECT offline_uuid FROM premium_accounts WHERE premium_uuid = ?";
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setString(1, premiumUuid);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getString("offline_uuid");
            }
        } catch (SQLException e) {
            logger.warning("查询正版映射失败: " + e.getMessage());
        }
        return null;
    }

    /** 根据离线 UUID 查正版 UUID */
    public String getPremiumUUIDByOffline(String offlineUuid) {
        String sql = "SELECT premium_uuid FROM premium_accounts WHERE offline_uuid = ?";
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setString(1, offlineUuid);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getString("premium_uuid");
            }
        } catch (SQLException e) {
            logger.warning("查询正版映射失败: " + e.getMessage());
        }
        return null;
    }

    /** 检查用户名是否有正版映射（独立或合并且） */
    public boolean hasPremiumMappingByUsername(String username) {
        String sql = "SELECT 1 FROM premium_accounts WHERE username = ?";
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            logger.warning("查询用户名正版映射失败: " + e.getMessage());
            return false;
        }
    }

    /** 删除正版映射 */
    public void unlinkPremiumAccount(String premiumUuid) {
        String sql = "DELETE FROM premium_accounts WHERE premium_uuid = ?";
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setString(1, premiumUuid);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.warning("删除正版映射失败: " + e.getMessage());
        }
    }
}
