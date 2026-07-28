package com.aermini.servermonitor;

import com.aermini.servermonitor.command.MonitorCommand;
import com.aermini.servermonitor.command.VerifyCommand;
import com.aermini.servermonitor.config.PluginConfig;
import com.aermini.servermonitor.database.DatabaseManager;
import com.aermini.servermonitor.database.RedisManager;
import com.aermini.servermonitor.database.dao.SkinDatabaseManager;
import com.aermini.servermonitor.database.dao.*;
import com.aermini.servermonitor.event.EventListener;
import com.aermini.servermonitor.http.HttpApiServer;
import com.aermini.servermonitor.http.handler.*;
import com.aermini.servermonitor.http.ws.WebSocketServer;
import com.aermini.servermonitor.http.ws.WsSessionManager;
import com.aermini.servermonitor.log.LogManager;
import com.aermini.servermonitor.post.PostManager;
import net.md_5.bungee.api.event.ServerConnectEvent;
import net.md_5.bungee.api.plugin.Plugin;

import java.util.UUID;
import java.util.concurrent.*;
import java.util.logging.Logger;

public class AerServerMonitor extends Plugin {

    public static class PendingBind {
        public final String task;
        public final String playerName;
        public final String uuid;
        public final String code;
        public PendingBind(String task, String playerName, String uuid, String code) {
            this.task = task;
            this.playerName = playerName;
            this.uuid = uuid;
            this.code = code;
        }
    }

    private PluginConfig pluginConfig;
    private DatabaseManager databaseManager;
    private RedisManager redisManager;
    private SkinDatabaseManager skinDB;
    private FastLoginDatabaseManager fastLoginDB;
    private LogManager logManager;
    private PlayerDAO playerDAO;
    private ChatDAO chatDAO;
    private CmdLogDAO cmdLogDAO;
    private WebhookLogDAO webhookLogDAO;
    private AdminDAO adminDAO;
    private PostManager postManager;
    private HttpApiServer httpServer;
    private WebSocketServer wsServer;
    private WsSessionManager wsSessionManager;
    private ExecutorService asyncExecutor;
    private BlockingQueue<Object[]> chatBuffer;
    private Thread chatFlusher;
    private ServerHandler serverHandler;

    private final ConcurrentHashMap<String, ServerConnectEvent> pendingConnects = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, PendingBind> pendingBinds = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        Logger logger = getLogger();

        try {
            pluginConfig = new PluginConfig(getDataFolder());
            pluginConfig.load();

            asyncExecutor = new ThreadPoolExecutor(
                    pluginConfig.getAsyncEventPoolSize(),
                    pluginConfig.getAsyncEventPoolSize(),
                    60L, TimeUnit.SECONDS,
                    new LinkedBlockingQueue<>(pluginConfig.getAsyncEventPoolQueue()),
                    r -> { Thread t = new Thread(r, "AerMonitor-Async"); t.setDaemon(true); return t; },
                    new ThreadPoolExecutor.DiscardOldestPolicy()
            );

            logManager = new LogManager(pluginConfig, logger);
            logManager.start();

            databaseManager = new DatabaseManager(pluginConfig, logger);
            databaseManager.init();

            redisManager = new RedisManager(pluginConfig, logger);
            redisManager.init();

            skinDB = new SkinDatabaseManager(pluginConfig, logger, databaseManager);
            skinDB.init();

            fastLoginDB = new FastLoginDatabaseManager(pluginConfig, logger, databaseManager);
            fastLoginDB.init();

            playerDAO = new PlayerDAO(databaseManager, redisManager);

            // 启动时清掉崩溃残留的在线状态, 再把当前真实在线的补回去
            playerDAO.resetAllOnline();
            for (net.md_5.bungee.api.connection.ProxiedPlayer p : getProxy().getPlayers()) {
                playerDAO.upsertPlayer(p.getUniqueId().toString(), p.getName(),
                        p.getAddress().getAddress().getHostAddress(),
                        p.getServer() != null ? p.getServer().getInfo().getName() : null,
                        true, com.aermini.servermonitor.util.TimeUtil.now());
            }

            chatDAO = new ChatDAO(databaseManager);
            chatDAO.setSkinDatabaseManager(skinDB);
            cmdLogDAO = new CmdLogDAO(databaseManager);
            webhookLogDAO = new WebhookLogDAO(databaseManager);
            adminDAO = new AdminDAO(databaseManager);

            adminDAO.ensureDefaultAdmin(pluginConfig.getAdminUsername(), pluginConfig.getAdminPassword());

            postManager = new PostManager(pluginConfig, webhookLogDAO, logManager, logger);

            chatBuffer = new LinkedBlockingQueue<>(10000);
            startChatBatchFlusher();

            AuthHandler authHandler = new AuthHandler(pluginConfig, adminDAO, logManager);

            wsServer = new WebSocketServer(pluginConfig, authHandler, logger);
            wsServer.start();
            wsSessionManager = new WsSessionManager(wsServer, logger, 100);

            PlayerHandler playerHandler = new PlayerHandler(this, playerDAO, skinDB, logManager, pluginConfig, pendingBinds);
            serverHandler = new ServerHandler(this, playerDAO, skinDB, logManager,
                    new com.aermini.servermonitor.http.middleware.WhitelistMiddleware(pluginConfig));
            ChatHandler chatHandler = new ChatHandler(this, chatDAO, logManager);
            SystemHandler systemHandler = new SystemHandler(logManager, redisManager, databaseManager);
            LogHandler logHandler = new LogHandler(logManager, chatDAO, webhookLogDAO, getDataFolder());
            DashboardHandler dashboardHandler = new DashboardHandler(pluginConfig.getDashboardPath(), logManager);
            SnapshotHandler snapshotHandler = new SnapshotHandler(this, playerDAO, skinDB, logManager);

            httpServer = new HttpApiServer(this, pluginConfig, logManager, logger);
            httpServer.setHandlers(playerHandler, serverHandler, chatHandler, systemHandler,
                    logHandler, authHandler, dashboardHandler);
            httpServer.setSnapshotHandler(snapshotHandler);
            httpServer.start();

            EventListener eventListener = new EventListener(this, logManager, playerDAO, chatDAO,
                    cmdLogDAO, postManager, wsSessionManager, chatBuffer);
            getProxy().getPluginManager().registerListener(this, eventListener);

            getProxy().registerChannel("aermonitor:main");
            getProxy().getPluginManager().registerListener(this, new com.aermini.servermonitor.channel.ChannelHandler(this, playerDAO, chatDAO, wsSessionManager, postManager));

            getProxy().getPluginManager().registerCommand(this, new MonitorCommand(this));
            getProxy().getPluginManager().registerCommand(this, new VerifyCommand(this, pluginConfig, playerDAO, skinDB, fastLoginDB, pendingBinds, logger));

            logger.info("v1.0 loaded.");
            logger.info("HTTP: " + pluginConfig.getHttpPort() + " | WS: " + pluginConfig.getWsPort() + " | Dashboard: http://localhost:" + pluginConfig.getHttpPort() + pluginConfig.getDashboardPath());
            logger.info("Docs: http://localhost:" + pluginConfig.getHttpPort() + "/docs");

            if (adminDAO.hasDefaultAdmin()) {
                logger.warning("WARNING: Default admin password detected. Login and change it.");
            }

        } catch (Exception e) {
            logger.severe("Failed to start: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @Override
    public void onDisable() {
        getProxy().unregisterChannel("aermonitor:main");
        if (chatFlusher != null) chatFlusher.interrupt();
        if (postManager != null) postManager.shutdown();
        if (wsSessionManager != null) wsSessionManager.shutdown();
        if (httpServer != null) httpServer.stop();
        if (serverHandler != null) serverHandler.shutdown();
        if (wsServer != null) wsServer.stop();
        if (logManager != null) logManager.stop();
        if (skinDB != null) skinDB.shutdown();
        if (fastLoginDB != null) fastLoginDB.shutdown();
        if (redisManager != null) redisManager.shutdown();
        if (databaseManager != null) databaseManager.shutdown();
        if (asyncExecutor != null) asyncExecutor.shutdown();
    }

    public void reloadPluginConfig() throws Exception {
        pluginConfig.reload();

    }

    public PluginConfig getConfig() {
        return pluginConfig;
    }

    public RedisManager getRedisManager() {
        return redisManager;
    }

    public ExecutorService getAsyncExecutor() {
        return asyncExecutor;
    }

    public void setPendingConnect(String uuid, ServerConnectEvent event) {
        pendingConnects.put(uuid, event);
    }

    public ServerConnectEvent removePendingConnect(String uuid) {
        return pendingConnects.remove(uuid);
    }

    private void startChatBatchFlusher() {
        chatFlusher = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(pluginConfig.getAsyncChatBatchInterval());
                    java.util.List<Object[]> batch = new java.util.ArrayList<>();
                    chatBuffer.drainTo(batch, pluginConfig.getAsyncChatBatchSize());
                    if (!batch.isEmpty()) {
                        chatDAO.insertChatBatch(batch);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    getLogger().warning("Chat batch flush error: " + e.getMessage());
                }
            }
        }, "AerMonitor-ChatFlush");
        chatFlusher.setDaemon(true);
        chatFlusher.start();
    }
}
