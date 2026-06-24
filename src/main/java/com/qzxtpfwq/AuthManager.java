package com.qzxtpfwq;

import org.bukkit.entity.Player;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

public class AuthManager {

    public enum AuthState {
        NEEDS_REGISTER,
        NEEDS_LOGIN,
        AWAITING_REGISTER_PASSWORD,
        AWAITING_LOGIN_PASSWORD,
        LOGGED_IN
    }

    public enum AuthResult {
        SUCCESS,
        WRONG_PASSWORD,
        ALREADY_EXISTS,
        VALIDATION_FAILED,
        TOO_MANY_ATTEMPTS
    }

    public interface AuthCallback {
        void onRegister(Player player);
        void onLogin(Player player);
    }

    private static final int PBKDF2_ITERATIONS = 100000;
    private static final int KEY_LENGTH = 256;
    private static final int SALT_BYTES = 16;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final DatabaseManager db;
    private final Logger logger;
    private final Map<UUID, AuthState> playerStates = new ConcurrentHashMap<>();
    private final Set<UUID> loggedInPlayers = ConcurrentHashMap.newKeySet();
    private final Map<UUID, SessionInfo> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> loginAttempts = new ConcurrentHashMap<>();

    private AuthCallback authCallback;
    private int minPasswordLength = 4;
    private int maxPasswordLength = 32;
    private int maxLoginAttempts = 3;
    private int sessionTimeoutMinutes = 5;

    public AuthManager(DatabaseManager db, Logger logger) {
        this.db = db;
        this.logger = logger;
    }

    public void configure(int minPasswordLength, int maxPasswordLength, int maxLoginAttempts, int sessionTimeoutMinutes) {
        this.minPasswordLength = minPasswordLength;
        this.maxPasswordLength = maxPasswordLength;
        this.maxLoginAttempts = maxLoginAttempts;
        this.sessionTimeoutMinutes = sessionTimeoutMinutes;
    }

    public void setAuthCallback(AuthCallback callback) {
        this.authCallback = callback;
    }

    public AuthState getState(UUID uuid) {
        return playerStates.getOrDefault(uuid, AuthState.NEEDS_REGISTER);
    }

    public void setState(UUID uuid, AuthState state) {
        playerStates.put(uuid, state);
    }

    public void removeState(UUID uuid) {
        playerStates.remove(uuid);
        loginAttempts.remove(uuid);
    }

    public boolean isLoggedIn(UUID uuid) {
        return loggedInPlayers.contains(uuid);
    }

    public void setLoggedIn(UUID uuid, boolean loggedIn) {
        if (loggedIn) {
            loggedInPlayers.add(uuid);
        } else {
            loggedInPlayers.remove(uuid);
        }
    }

    public void logout(UUID uuid) {
        loggedInPlayers.remove(uuid);
        playerStates.remove(uuid);
        loginAttempts.remove(uuid);
        sessions.remove(uuid);
    }

    public void saveSession(UUID uuid, String ip) {
        long expireTime = System.currentTimeMillis() + (long) sessionTimeoutMinutes * 60 * 1000;
        sessions.put(uuid, new SessionInfo(ip, expireTime));
    }

    public boolean hasValidSession(UUID uuid, String ip) {
        SessionInfo info = sessions.get(uuid);
        if (info == null) return false;
        if (System.currentTimeMillis() > info.expireTime) {
            sessions.remove(uuid);
            return false;
        }
        if (!info.ip.equals(ip)) {
            sessions.remove(uuid);
            return false;
        }
        return true;
    }

    public boolean isRegistered(UUID uuid) {
        return db.playerExists(uuid);
    }

    public AuthResult register(UUID uuid, String username, String password, String ip) {
        if (db.playerExists(uuid)) {
            return AuthResult.ALREADY_EXISTS;
        }

        AuthResult validation = validatePassword(password);
        if (validation != AuthResult.SUCCESS) {
            return validation;
        }

        String salt = generateSalt();
        String hash = hashPassword(password, salt);
        if (hash == null) {
            return AuthResult.VALIDATION_FAILED;
        }

        db.registerPlayer(uuid, username, hash, salt, ip);
        loggedInPlayers.add(uuid);
        playerStates.put(uuid, AuthState.LOGGED_IN);
        loginAttempts.remove(uuid);
        saveSession(uuid, ip);

        if (authCallback != null) {
            authCallback.onRegister(BukkitShim.getPlayer(uuid));
        }

        logger.info("玩家 " + username + " 注册成功");
        return AuthResult.SUCCESS;
    }

    public AuthResult login(UUID uuid, String password, String ip) {
        if (!db.playerExists(uuid)) {
            return AuthResult.VALIDATION_FAILED;
        }

        String storedHash = db.getPasswordHash(uuid);
        String storedSalt = db.getSalt(uuid);
        if (storedHash == null || storedSalt == null) {
            return AuthResult.VALIDATION_FAILED;
        }

        String inputHash = hashPassword(password, storedSalt);
        if (inputHash == null) {
            return AuthResult.VALIDATION_FAILED;
        }

        if (!storedHash.equals(inputHash)) {
            int attempts = loginAttempts.merge(uuid, 1, Integer::sum);
            if (attempts >= maxLoginAttempts) {
                return AuthResult.TOO_MANY_ATTEMPTS;
            }
            return AuthResult.WRONG_PASSWORD;
        }

        db.updateLastLogin(uuid, ip);
        loggedInPlayers.add(uuid);
        playerStates.put(uuid, AuthState.LOGGED_IN);
        loginAttempts.remove(uuid);
        saveSession(uuid, ip);

        if (authCallback != null) {
            authCallback.onLogin(BukkitShim.getPlayer(uuid));
        }

        String username = db.getUsername(uuid);
        logger.info("玩家 " + username + " 登录成功");
        return AuthResult.SUCCESS;
    }

    public int getRemainingAttempts(UUID uuid) {
        return maxLoginAttempts - loginAttempts.getOrDefault(uuid, 0);
    }

    public int getMinPasswordLength() { return minPasswordLength; }
    public int getMaxPasswordLength() { return maxPasswordLength; }
    public int getMaxLoginAttempts() { return maxLoginAttempts; }

    private AuthResult validatePassword(String password) {
        if (password == null || password.isEmpty()) {
            return AuthResult.VALIDATION_FAILED;
        }
        if (password.length() < minPasswordLength) {
            return AuthResult.VALIDATION_FAILED;
        }
        if (password.length() > maxPasswordLength) {
            return AuthResult.VALIDATION_FAILED;
        }
        if (password.contains(" ")) {
            return AuthResult.VALIDATION_FAILED;
        }
        return AuthResult.SUCCESS;
    }

    private String generateSalt() {
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        return Base64.getEncoder().encodeToString(salt);
    }

    private String hashPassword(String password, String salt) {
        try {
            byte[] saltBytes = Base64.getDecoder().decode(salt);
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), saltBytes, PBKDF2_ITERATIONS, KEY_LENGTH);
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            byte[] hash = factory.generateSecret(spec).getEncoded();
            return Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            logger.severe("密码哈希失败: " + e.getMessage());
            return null;
        }
    }

    private static class SessionInfo {
        final String ip;
        final long expireTime;
        SessionInfo(String ip, long expireTime) {
            this.ip = ip;
            this.expireTime = expireTime;
        }
    }

    static class BukkitShim {
        static Player getPlayer(UUID uuid) {
            return org.bukkit.Bukkit.getPlayer(uuid);
        }
    }
}
