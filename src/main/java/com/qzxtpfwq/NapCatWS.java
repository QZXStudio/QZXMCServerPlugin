package com.qzxtpfwq;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

public class NapCatWS {

    public interface GroupMessageCallback {
        void onGroupMessage(String groupName, long senderQQ, String senderName, String message);
    }
    public interface StatsCallback {
        void onMCToQQ(long groupId);
        void onQQToMC(long groupId);
    }

    public interface RecallCallback {
        void onGroupRecall(String groupName, String senderName, long messageId);
    }

    public interface BotCommandCallback {
        String onBotCommand(long groupId, long userId, String commandText);
    }

    private final Logger logger;
    private final Gson gson = new Gson();
    private final HttpClient httpClient;
    private final ScheduledExecutorService scheduler;
    private volatile WebSocket webSocket;
    private String wsUrl;
    private int reconnectInterval;
    private volatile boolean connected = false;
    private boolean intentionalClose = false;
    private ScheduledFuture<?> reconnectTask;

    private GroupMessageCallback groupMessageCallback;
    private RecallCallback recallCallback;
    private BotCommandCallback botCommandCallback;
    private StatsCallback statsCallback;
    private final Set<Long> monitoredGroupIds = ConcurrentHashMap.newKeySet();

    private final Map<String, String> nickCache = new ConcurrentHashMap<>();
    private final Map<Long, String> groupNameCache = new ConcurrentHashMap<>();

    private long selfId = 0;

    public NapCatWS(Logger logger) {
        this.logger = logger;
        this.httpClient = HttpClient.newHttpClient();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "QZXBotSync-WS");
            t.setDaemon(true);
            return t;
        });
    }

    public void setGroupMessageCallback(GroupMessageCallback callback) {
        this.groupMessageCallback = callback;
    }

    public void setRecallCallback(RecallCallback callback) {
        this.recallCallback = callback;
    }

    public void setBotCommandCallback(BotCommandCallback callback) {
        this.botCommandCallback = callback;
    }

    public void setStatsCallback(StatsCallback callback) {
        this.statsCallback = callback;
    }

    public void setMonitoredGroups(List<Long> groupIds) {
        this.monitoredGroupIds.clear();
        this.monitoredGroupIds.addAll(groupIds);
    }

    public void configure(String wsUrl, int reconnectInterval) {
        this.wsUrl = wsUrl;
        this.reconnectInterval = reconnectInterval;
    }

    public void connect() {
        if (wsUrl == null || wsUrl.isEmpty()) {
            logger.warning("WebSocket URL unconfigured");
            return;
        }
        intentionalClose = false;
        try {
            URI uri = URI.create(wsUrl);
            CompletableFuture<WebSocket> future = httpClient.newWebSocketBuilder()
                    .buildAsync(uri, new Listener());
            future.thenAccept(ws -> {
                webSocket = ws;
                connected = true;
                logger.info("Connected to NapCat WebSocket: " + wsUrl);
            }).exceptionally(ex -> {
                logger.warning("WebSocket connect failed: " + ex.getMessage());
                scheduleReconnect();
                return null;
            });
        } catch (Exception e) {
            logger.warning("WebSocket creation error: " + e.getMessage());
            scheduleReconnect();
        }
    }

    public void disconnect() {
        intentionalClose = true;
        cancelReconnect();
        if (webSocket != null) {
            try { webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "Plugin disabling"); } catch (Exception ignored) {}
        }
        connected = false;
        webSocket = null;
    }

    public boolean isConnected() {
        return connected;
    }

    public void sendGroupMessage(long groupId, String message) {
        if (!connected || webSocket == null) {
            logger.warning("WebSocket not connected, message discarded");
            return;
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("action", "send_group_msg");
        JsonObject params = new JsonObject();
        params.addProperty("group_id", groupId);
        params.addProperty("message", message);
        payload.add("params", params);
        String json = gson.toJson(payload);
        webSocket.sendText(json, true);
    }

    public void sendGroupMessage(List<Long> groupIds, String message) {
        for (long groupId : groupIds) {
            sendGroupMessage(groupId, message);
        }
    }

    public void shutdown() {
        disconnect();
        scheduler.shutdown();
    }

    private void cacheSenderNickname(long groupId, long userId, String card, String nickname) {
        String key = groupId + "_" + userId;
        String displayName = (card != null && !card.isEmpty()) ? card : nickname;
        nickCache.put(key, displayName);
    }

    private String getCachedNickname(long groupId, long userId) {
        String key = groupId + "_" + userId;
        String cached = nickCache.get(key);
        if (cached != null) return cached;
        return "用户" + userId;
    }

    public String getGroupName(long groupId) {
        return getCachedGroupName(groupId);
    }

    private String getCachedGroupName(long groupId) {
        String cached = groupNameCache.get(groupId);
        if (cached != null) return cached;
        return "群:" + groupId;
    }

    private void handleIncomingMessage(String text) {
        try {
            JsonObject obj = JsonParser.parseString(text).getAsJsonObject();
            String postType = obj.has("post_type") ? obj.get("post_type").getAsString() : "";

            if ("notice".equals(postType)) {
                handleNotice(obj);
                return;
            }

            if (!"message".equals(postType)) return;
            String messageType = obj.has("message_type") ? obj.get("message_type").getAsString() : "";
            if (!"group".equals(messageType)) return;

            if (selfId == 0 && obj.has("self_id")) {
                selfId = obj.get("self_id").getAsLong();
            }

            long groupId = obj.get("group_id").getAsLong();
            if (!monitoredGroupIds.contains(groupId)) return;

            JsonObject sender = obj.getAsJsonObject("sender");
            long userId = sender.get("user_id").getAsLong();
            String nickname = sender.has("nickname") ? sender.get("nickname").getAsString() : "未知";
            String card = sender.has("card") && !sender.get("card").getAsString().isEmpty()
                    ? sender.get("card").getAsString() : nickname;
            cacheSenderNickname(groupId, userId, card, nickname);

            String groupName = obj.has("group_name") ? obj.get("group_name").getAsString() : "群:" + groupId;
            groupNameCache.put(groupId, groupName);

            if (groupMessageCallback == null && botCommandCallback == null) return;

            StringBuilder msgBuilder = new StringBuilder();
            boolean atBot = false;
            JsonElement msgElement = obj.get("message");
            if (msgElement.isJsonArray()) {
                JsonArray msgArray = msgElement.getAsJsonArray();
                for (JsonElement el : msgArray) {
                    if (el.isJsonObject()) {
                        JsonObject seg = el.getAsJsonObject();
                        String type = seg.has("type") ? seg.get("type").getAsString() : "";
                        switch (type) {
                            case "text":
                                msgBuilder.append(seg.getAsJsonObject("data").get("text").getAsString());
                                break;
                            case "image":
                                if (msgBuilder.length() > 0) msgBuilder.append(" ");
                                JsonObject imgData = seg.getAsJsonObject("data");
                                int subType = imgData.has("sub_type") ? imgData.get("sub_type").getAsInt() : 0;
                                msgBuilder.append(subType == 1 ? "[动画表情]" : "[图片]");
                                break;
                            case "at":
                                String atQQ = seg.getAsJsonObject("data").get("qq").getAsString();
                                if (selfId > 0 && atQQ.equals(String.valueOf(selfId))) {
                                    atBot = true;
                                } else {
                                    if (msgBuilder.length() > 0) msgBuilder.append(" ");
                                    msgBuilder.append("[@" + atQQ + "]");
                                }
                                break;
                            case "video":
                                if (msgBuilder.length() > 0) msgBuilder.append(" ");
                                msgBuilder.append("[视频]");
                                break;
                            case "json":
                                if (msgBuilder.length() > 0) msgBuilder.append(" ");
                                String jsonLabel = extractJsonLabel(seg);
                                msgBuilder.append(jsonLabel);
                                break;
                            case "face":
                            case "emoji":
                                if (msgBuilder.length() > 0) msgBuilder.append(" ");
                                msgBuilder.append("[表情]");
                                break;
                            case "reply":
                                break;
                            default:
                                if (msgBuilder.length() > 0) msgBuilder.append(" ");
                                msgBuilder.append("[不支持的消息类型]");
                                break;
                        }
                    }
                }
            } else if (msgElement.isJsonPrimitive()) {
                msgBuilder.append(msgElement.getAsString());
            }
            String message = msgBuilder.toString().trim();

            boolean isSlashCommand = message.startsWith("/");
            boolean isBotCommand = atBot || isSlashCommand;

            if (isBotCommand && botCommandCallback != null && selfId > 0) {
                String commandText = isSlashCommand ? message.substring(1).trim() : message;
                String reply = botCommandCallback.onBotCommand(groupId, userId, commandText);
                if (reply != null) {
                    sendGroupMessage(groupId, reply);
                }
                return;
            }

            if (message.isEmpty()) return;

            if (message.length() > 100) {
                message = message.substring(0, 100) + "...";
            }

            if (statsCallback != null) {
                try { statsCallback.onQQToMC(groupId); } catch (Exception ignored) {}
            }
            if (groupMessageCallback != null) {
                groupMessageCallback.onGroupMessage(groupName, userId, card, message);
            }
        } catch (Exception e) {
            logger.warning("解析群消息事件失败: " + e.getMessage());
        }
    }

    private String extractJsonLabel(JsonObject seg) {
        try {
            JsonObject data = seg.getAsJsonObject("data");
            String innerJson = data.get("data").getAsString();
            JsonObject inner = JsonParser.parseString(innerJson).getAsJsonObject();
            if (inner.has("prompt")) {
                return inner.get("prompt").getAsString();
            }
            if (inner.has("meta")) {
                JsonObject meta = inner.getAsJsonObject("meta");
                if (meta.has("mannounce")) {
                    return "[群公告]";
                }
            }
        } catch (Exception ignored) {}
        return "[卡片消息]";
    }

    private void handleNotice(JsonObject obj) {
        String noticeType = obj.has("notice_type") ? obj.get("notice_type").getAsString() : "";
        if (!"group_recall".equals(noticeType)) return;

        long groupId = obj.get("group_id").getAsLong();
        if (!monitoredGroupIds.contains(groupId)) return;

        long userId = obj.get("user_id").getAsLong();
        long messageId = obj.has("message_id") ? obj.get("message_id").getAsLong() : 0;

        String groupName = getCachedGroupName(groupId);
        String senderName = getCachedNickname(groupId, userId);

        if (recallCallback != null) {
            recallCallback.onGroupRecall(groupName, senderName, messageId);
        }
    }

    private void scheduleReconnect() {
        if (intentionalClose || reconnectInterval <= 0) return;
        cancelReconnect();
        logger.info("Reconnecting in " + reconnectInterval + " seconds...");
        reconnectTask = scheduler.schedule(this::connect, reconnectInterval, TimeUnit.SECONDS);
    }

    private void cancelReconnect() {
        if (reconnectTask != null && !reconnectTask.isDone()) {
            reconnectTask.cancel(false);
            reconnectTask = null;
        }
    }

    private class Listener implements WebSocket.Listener {
        @Override
        public void onOpen(WebSocket ws) {
            logger.info("NapCat WebSocket connection established");
            ws.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            handleIncomingMessage(data.toString());
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
            connected = false;
            webSocket = null;
            logger.warning("WebSocket closed (code=" + statusCode + ", reason=" + reason + ")");
            scheduleReconnect();
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            logger.warning("WebSocket error: " + error.getMessage());
            connected = false;
        }
    }
}
