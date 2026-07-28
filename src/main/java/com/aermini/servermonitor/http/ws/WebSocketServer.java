package com.aermini.servermonitor.http.ws;

import com.aermini.servermonitor.config.PluginConfig;
import com.aermini.servermonitor.http.handler.AuthHandler;
import com.aermini.servermonitor.util.HmacUtil;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.google.gson.JsonObject;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.websocketx.*;
import io.netty.util.CharsetUtil;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

public class WebSocketServer {

    private final PluginConfig config;
    private final AuthHandler authHandler;
    private final Logger logger;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;
    private final Set<Channel> sessions = ConcurrentHashMap.newKeySet();

    private static final String WS_AUTH_CONTEXT = "ws-handshake";

    public WebSocketServer(PluginConfig config, AuthHandler authHandler, Logger logger) {
        this.config = config;
        this.authHandler = authHandler;
        this.logger = logger;
    }

    public void start() throws InterruptedException {
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup(4);

        ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ChannelPipeline p = ch.pipeline();
                        p.addLast(new HttpServerCodec());
                        p.addLast(new HttpObjectAggregator(65536));
                        p.addLast(new WebSocketHandler());
                    }
                });

        serverChannel = bootstrap.bind(config.getWsPort()).sync().channel();
        logger.info("[WS] WebSocket server listening on port " + config.getWsPort()
                + " (auth: JWT + HMAC-SHA256, max-connections=" + config.getWsMaxConnections() + ")");
    }

    public void stop() {
        sessions.forEach(Channel::close);
        sessions.clear();
        // 热重载需要同步等端口释放, 否则新实例bind会撞Address already in use
        if (serverChannel != null) serverChannel.close().syncUninterruptibly();
        if (bossGroup != null) bossGroup.shutdownGracefully(0, 2, java.util.concurrent.TimeUnit.SECONDS).syncUninterruptibly();
        if (workerGroup != null) workerGroup.shutdownGracefully(0, 2, java.util.concurrent.TimeUnit.SECONDS).syncUninterruptibly();
    }

    public void broadcast(String event, JsonObject data) {
        JsonObject msg = new JsonObject();
        msg.addProperty("event", event);
        msg.addProperty("time", System.currentTimeMillis());
        msg.add("data", data);
        broadcastRaw(msg);
    }

    public void broadcastRaw(JsonObject msg) {
        if (sessions.isEmpty()) return;
        String json = msg.toString();
        TextWebSocketFrame frame = new TextWebSocketFrame(json);

        int live = 0;
        for (Channel ch : sessions) {
            if (ch.isActive()) {
                ch.write(frame.retainedDuplicate());
                live++;
            } else {
                sessions.remove(ch);
            }
        }

        if (live > 0) {
            for (Channel ch : sessions) {
                if (ch.isActive()) ch.flush();
            }
        }
        frame.release();
    }

    private class WebSocketHandler extends SimpleChannelInboundHandler<Object> {

        private WebSocketServerHandshaker handshaker;

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, Object msg) {
            if (msg instanceof HttpRequest) {
                handleHttpRequest(ctx, (HttpRequest) msg);
            } else if (msg instanceof WebSocketFrame) {
                handleWebSocketFrame(ctx, (WebSocketFrame) msg);
            }
        }

        private void handleHttpRequest(ChannelHandlerContext ctx, HttpRequest req) {

            if (sessions.size() >= config.getWsMaxConnections()) {
                logger.warning("[WS] rejected: max connections reached (" + config.getWsMaxConnections() + "), remote="
                        + ctx.channel().remoteAddress());
                sendHttpResponse(ctx, req, new DefaultFullHttpResponse(
                        HttpVersion.HTTP_1_1, HttpResponseStatus.SERVICE_UNAVAILABLE));
                return;
            }

            String remoteIp = remoteIp(ctx);
            if (!authenticate(ctx, req, remoteIp)) {

                return;
            }

            WebSocketServerHandshakerFactory factory = new WebSocketServerHandshakerFactory(
                    "ws://" + req.headers().get(HttpHeaderNames.HOST), null, true);
            handshaker = factory.newHandshaker(req);
            if (handshaker == null) {
                WebSocketServerHandshakerFactory.sendUnsupportedVersionResponse(ctx.channel());
                return;
            }
            handshaker.handshake(ctx.channel(), req);
            sessions.add(ctx.channel());
        }

        private boolean authenticate(ChannelHandlerContext ctx, HttpRequest req, String remoteIp) {

            Map<String, String> params;
            try {
                params = parseQuery(req.uri());
            } catch (Exception e) {
                logger.warning("[WS] rejected: invalid URI " + req.uri() + ", remote=" + remoteIp);
                sendHttpResponse(ctx, req, new DefaultFullHttpResponse(
                        HttpVersion.HTTP_1_1, HttpResponseStatus.BAD_REQUEST));
                return false;
            }

            String token = params.get("token");
            String a = params.get("a");
            String b = params.get("b");

            if (token == null || a == null || b == null) {
                logger.warning("[WS] rejected: missing token/a/b in query, remote=" + remoteIp + ", uri=" + req.uri());
                sendHttpResponse(ctx, req, unauthorizedResponse("missing credentials"));
                return false;
            }

            DecodedJWT jwt = authHandler.verifyTokenString(token);
            if (jwt == null) {
                logger.info("[WS] rejected: invalid/expired JWT, remote=" + remoteIp);
                sendHttpResponse(ctx, req, unauthorizedResponse("invalid token"));
                return false;
            }

            long requestTime;
            try {
                requestTime = Long.parseLong(a);
            } catch (NumberFormatException e) {
                logger.info("[WS] rejected: invalid a (not a number): " + a + ", remote=" + remoteIp);
                sendHttpResponse(ctx, req, unauthorizedResponse("invalid timestamp"));
                return false;
            }
            long now = System.currentTimeMillis();
            if (Math.abs(now - requestTime) > config.getHeaderATimeOffset()) {
                logger.info("[WS] rejected: a time skew too large, skew=" + (now - requestTime) + "ms, remote=" + remoteIp);
                sendHttpResponse(ctx, req, unauthorizedResponse("timestamp expired"));
                return false;
            }

            String expectedB = HmacUtil.hmac("HmacSHA256", token, WS_AUTH_CONTEXT + ":" + a);
            if (!expectedB.equalsIgnoreCase(b)) {
                logger.warning("[WS] rejected: b HMAC mismatch, remote=" + remoteIp
                        + " (expected=" + expectedB.substring(0, 8) + "..., got=" + b.substring(0, Math.min(8, b.length())) + "...)");
                sendHttpResponse(ctx, req, unauthorizedResponse("hmac mismatch"));
                return false;
            }

            io.netty.util.AttributeKey<Object> adminIdKey = io.netty.util.AttributeKey.valueOf("admin_id");
            io.netty.util.AttributeKey<Object> adminNameKey = io.netty.util.AttributeKey.valueOf("admin_name");
            ctx.channel().attr(adminIdKey).set(jwt.getClaim("admin_id").asInt());
            ctx.channel().attr(adminNameKey).set(jwt.getClaim("username").asString());

            return true;
        }

        private FullHttpResponse unauthorizedResponse(String reason) {
            FullHttpResponse resp = new DefaultFullHttpResponse(
                    HttpVersion.HTTP_1_1, HttpResponseStatus.UNAUTHORIZED);
            ByteBuf body = Unpooled.copiedBuffer(
                    "{\"code\":401,\"errcode\":50001,\"message\":\"" + reason + "\"}",
                    CharsetUtil.UTF_8);
            resp.content().writeBytes(body);
            body.release();
            resp.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=utf-8");
            HttpUtil.setContentLength(resp, resp.content().readableBytes());
            return resp;
        }

        private void handleWebSocketFrame(ChannelHandlerContext ctx, WebSocketFrame frame) {
            if (frame instanceof CloseWebSocketFrame) {
                handshaker.close(ctx.channel(), (CloseWebSocketFrame) frame.retain());
                sessions.remove(ctx.channel());
                return;
            }
            if (frame instanceof PingWebSocketFrame) {
                ctx.channel().writeAndFlush(new PongWebSocketFrame(frame.content().retain()));
                return;
            }
            if (frame instanceof TextWebSocketFrame) {
                String text = ((TextWebSocketFrame) frame).text();
                try {
                    JsonObject req = com.aermini.servermonitor.util.JsonUtil.parse(text);
                    if ("subscribe".equals(req.get("action").getAsString())) {
                        ctx.channel().attr(io.netty.util.AttributeKey.valueOf("subscribed")).set("all");
                    }
                } catch (Exception ignored) {}
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            sessions.remove(ctx.channel());
            ctx.close();
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            sessions.remove(ctx.channel());
        }

        private void sendHttpResponse(ChannelHandlerContext ctx, HttpRequest req, FullHttpResponse res) {
            ChannelFuture f = ctx.channel().writeAndFlush(res);
            if (!HttpUtil.isKeepAlive(req) || res.status().code() != 200) {
                f.addListener(ChannelFutureListener.CLOSE);
            }
        }

        private String remoteIp(ChannelHandlerContext ctx) {
            try {
                InetSocketAddress addr = (InetSocketAddress) ctx.channel().remoteAddress();
                return addr.getAddress().getHostAddress();
            } catch (Exception e) {
                return "unknown";
            }
        }

        private Map<String, String> parseQuery(String uri) throws URISyntaxException {
            Map<String, String> result = new HashMap<>();
            URI u = new URI(uri);
            String query = u.getRawQuery();
            if (query == null || query.isEmpty()) return result;
            for (String pair : query.split("&")) {
                int idx = pair.indexOf('=');
                if (idx > 0) {
                    String k = URLDecoder.decode(pair.substring(0, idx), java.nio.charset.StandardCharsets.UTF_8);
                    String v = URLDecoder.decode(pair.substring(idx + 1), java.nio.charset.StandardCharsets.UTF_8);
                    result.put(k, v);
                } else {
                    result.put(URLDecoder.decode(pair, java.nio.charset.StandardCharsets.UTF_8), "");
                }
            }
            return result;
        }
    }
}
