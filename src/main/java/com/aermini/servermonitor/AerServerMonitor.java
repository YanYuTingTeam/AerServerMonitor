package com.aermini.servermonitor;

import com.aermini.servermonitor.command.IrcChatCommand;
import com.aermini.servermonitor.command.MonitorCommand;
import com.aermini.servermonitor.command.VerifyCommand;
import com.aermini.servermonitor.config.MessageManager;
import com.aermini.servermonitor.config.PluginConfig;
import com.aermini.servermonitor.database.DatabaseManager;
import com.aermini.servermonitor.database.RedisManager;
import com.aermini.servermonitor.database.dao.SkinDatabaseManager;
import com.aermini.servermonitor.database.dao.*;
import com.aermini.servermonitor.database.redis.BindStore;
import com.aermini.servermonitor.database.redis.ChatQueue;
import com.aermini.servermonitor.database.redis.LoginGuard;
import com.aermini.servermonitor.database.redis.PresenceStore;
import com.aermini.servermonitor.event.EventListener;
import com.aermini.servermonitor.http.HttpApiServer;
import com.aermini.servermonitor.http.handler.*;
import com.aermini.servermonitor.http.ws.WebSocketServer;
import com.aermini.servermonitor.http.ws.WsSessionManager;
import com.aermini.servermonitor.irc.IrcManager;
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
    private MessageManager messageManager;
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
    private ChatQueue chatQueue;
    private ServerHandler serverHandler;
    private IrcManager ircManager;

    private final ConcurrentHashMap<String, ServerConnectEvent> pendingConnects = new ConcurrentHashMap<>();
    private LoginGuard loginGuard;
    private BindStore bindStore;
    private PresenceStore presenceStore;

    @Override
    public void onEnable() {
        Logger logger = getLogger();

        try {
            pluginConfig = new PluginConfig(getDataFolder());
            pluginConfig.load();

            messageManager = new MessageManager(getDataFolder(), logger);
            messageManager.load();

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

            skinDB = new SkinDatabaseManager(pluginConfig, logger, databaseManager, redisManager);
            skinDB.init();

            fastLoginDB = new FastLoginDatabaseManager(pluginConfig, logger, databaseManager);
            fastLoginDB.init();

            presenceStore = new PresenceStore(pluginConfig, redisManager, logger);
            presenceStore.start();

            playerDAO = new PlayerDAO(databaseManager, redisManager, presenceStore);

            // 启动时清掉崩溃残留的在线状态, 再把当前真实在线的补回去
            if (!pluginConfig.isRedisFeatureEnabled("presence")) {
                playerDAO.resetAllOnline();
            }
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

            loginGuard = new LoginGuard(pluginConfig, redisManager, logger);
            bindStore = new BindStore(pluginConfig, redisManager, logger);
            postManager = new PostManager(pluginConfig, webhookLogDAO, logManager, logger, redisManager);
            postManager.start();

            chatQueue = new ChatQueue(pluginConfig, redisManager, chatDAO, logger);
            chatQueue.start();

            AuthHandler authHandler = new AuthHandler(pluginConfig, adminDAO, logManager, loginGuard);

            wsServer = new WebSocketServer(pluginConfig, authHandler, logger);
            wsServer.start();
            wsSessionManager = new WsSessionManager(wsServer, logger, 100);

            PlayerHandler playerHandler = new PlayerHandler(this, playerDAO, skinDB, logManager, pluginConfig, bindStore);
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
                    cmdLogDAO, postManager, wsSessionManager, chatQueue);
            getProxy().getPluginManager().registerListener(this, eventListener);

            getProxy().registerChannel("aermonitor:main");
            getProxy().getPluginManager().registerListener(this, new com.aermini.servermonitor.channel.ChannelHandler(this, playerDAO, chatDAO, wsSessionManager, postManager));

            getProxy().getPluginManager().registerCommand(this, new MonitorCommand(this));
            getProxy().getPluginManager().registerCommand(this, new VerifyCommand(this, pluginConfig, playerDAO, skinDB, fastLoginDB, bindStore, logger));

            ircManager = new IrcManager(messageManager);
            getProxy().getPluginManager().registerCommand(this, new IrcChatCommand(ircManager, messageManager));

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
        if (chatQueue != null) chatQueue.shutdown();
        if (postManager != null) postManager.shutdown();
        if (presenceStore != null) presenceStore.shutdown();
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
        messageManager.load();
    }

    public PluginConfig getConfig() {
        return pluginConfig;
    }

    public MessageManager getMessages() {
        return messageManager;
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
}
