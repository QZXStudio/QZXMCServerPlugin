package com.qzxtpfwq;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * 正版账号验证模块——通过 Mojang API 检查用户名是否对应正版账号。
 */
public class PremiumVerifier {

    private static final String MOJANG_PROFILE_API = "https://api.mojang.com/users/profiles/minecraft/";
    private static final String ASHCON_API = "https://api.ashcon.app/mojang/v2/user/";
    private static final int TIMEOUT_MS = 3000;

    private final Logger logger;

    public PremiumVerifier(Logger logger) {
        this.logger = logger;
    }

    /** null=超时/网络错误/非正版，其他=正版UUID */
    public String lookupPremiumUUID(String username) {
        long start = System.currentTimeMillis();
        logger.info("[正版验证] ▸ 开始验证玩家: " + username);

        logger.info("[正版验证] ▸ 第1步: 查询 Mojang 官方 API ...");
        long t1 = System.currentTimeMillis();
        String mojangResult = tryApi("Mojang官方", MOJANG_PROFILE_API + username);
        long mojangCost = System.currentTimeMillis() - t1;

        if (mojangResult != null && !mojangResult.isEmpty()) {
            logger.info("[正版验证] ✔ Mojang 返回有效 UUID: " + mojangResult
                    + " (耗时 " + mojangCost + "ms, 总耗时 " + (System.currentTimeMillis() - start) + "ms)");
            return mojangResult;
        }

        logger.info("[正版验证] ✘ Mojang 未返回有效结果 (result="
                + (mojangResult == null ? "null(网络错误/超时)" : "空串(404/204非正版或API格式变化)")
                + ", 耗时 " + mojangCost + "ms)，回退到 Ashcon");

        logger.info("[正版验证] ▸ 第2步: 查询 Ashcon API ...");
        long t2 = System.currentTimeMillis();
        String ashconResult = tryApi("Ashcon", ASHCON_API + username);
        long ashconCost = System.currentTimeMillis() - t2;

        if (ashconResult != null && !ashconResult.isEmpty()) {
            logger.info("[正版验证] ✔ Ashcon 返回有效 UUID: " + ashconResult
                    + " (耗时 " + ashconCost + "ms, 总耗时 " + (System.currentTimeMillis() - start) + "ms)");
            return ashconResult;
        }

        logger.warning("[正版验证] ✘ 两个 API 均未返回有效结果 (Mojang="
                + (mojangResult == null ? "null" : "空串") + ", Ashcon="
                + (ashconResult == null ? "null" : "空串")
                + ", 总耗时 " + (System.currentTimeMillis() - start) + "ms) → 判定为无法验证");
        return null;
    }

    private String tryApi(String apiName, String urlStr) {
        long start = System.currentTimeMillis();
        logger.info("[正版验证]   → " + apiName + " 请求: " + urlStr);
        try {
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "QZXBotSync/1.0");

            int code = conn.getResponseCode();
            long elapsed = System.currentTimeMillis() - start;
            logger.info("[正版验证]   ← " + apiName + " HTTP 响应码: " + code + " (耗时 " + elapsed + "ms)");

            if (code == 200) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder body = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) body.append(line);
                reader.close();
                logger.info("[正版验证]   ← " + apiName + " 响应体: " + body);

                JsonObject obj = JsonParser.parseString(body.toString()).getAsJsonObject();
                String id;
                if (obj.has("id")) {
                    id = obj.get("id").getAsString();
                    logger.info("[正版验证]   ← " + apiName + " 解析到 'id' 字段: " + id);
                } else if (obj.has("uuid")) {
                    id = obj.get("uuid").getAsString().replace("-", "");
                    logger.info("[正版验证]   ← " + apiName + " 解析到 'uuid' 字段: " + id + " (已去连字符)");
                } else {
                    logger.warning("[正版验证]   ← " + apiName + " 响应体中未找到 'id' 或 'uuid' 字段！返回空串");
                    return "";
                }
                String dashed = id.replaceFirst(
                        "(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})",
                        "$1-$2-$3-$4-$5");
                logger.info("[正版验证]   ← " + apiName + " 最终 UUID: " + dashed);
                return dashed;
            }
            if (code == 204 || code == 404) {
                logger.info("[正版验证]   ← " + apiName + " 返回 " + code + " (用户不存在 / 非正版 / API 已废弃)");
                return "";
            }
            logger.warning("[正版验证]   ← " + apiName + " 意外 HTTP 状态码: " + code + "，返回空串");
            return "";
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            logger.warning("[正版验证]   ← " + apiName + " 请求异常 (耗时 " + elapsed + "ms): "
                    + e.getClass().getSimpleName() + " - " + e.getMessage());
        }
        return null;
    }

    /**
     * 判断该用户名是否为正版账号。
     */
    public boolean isPremium(String username) {
        return lookupPremiumUUID(username) != null;
    }

    /**
     * 将无连字符的 Mojang UUID 转为带连字符格式。
     */
    public static UUID parseMojangUUID(String rawId) {
        String dashed = rawId.replaceFirst(
                "(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})",
                "$1-$2-$3-$4-$5");
        return UUID.fromString(dashed);
    }
}
