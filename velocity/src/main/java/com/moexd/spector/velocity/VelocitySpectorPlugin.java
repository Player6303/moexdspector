package com.moexd.spector.velocity;

import com.google.inject.Inject;
import com.moexd.spector.protocol.SpectorProtocol;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import org.slf4j.Logger;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * moexdspector 的 Velocity 侧附属。
 * 接收后端插件的 PAIR/UNPAIR 登记观察会话；当被观察玩家切换服务器时，
 * 立即把观察者连接请求到同一目标服务器，并在观察者到达后向该后端发送 HANDOFF；
 * 处理后端发来的 OBSERVE 跨服观察请求（定向玩家或全局随机选人）；
 * 被观察玩家退出网络时向其所在后端发送 TARGET_GONE。
 * 每个后端服务器的会话记录以 server-id 为键保存在内存中，不落库。
 *
 * 安全：所有消息末尾附带共享密钥（config 目录 moexdspector.properties 的 secret），
 * 且只接受来自后端方向（ServerConnection）的消息，防止客户端伪造。
 * 协议见 {@link SpectorProtocol}：VERSION, SUB, ...字段..., SECRET。
 */
@Plugin(
        id = "moexdspector",
        name = "moexdspector",
        version = "1.0.1",
        description = "Server-follow companion for the moexdspector Folia plugin",
        authors = {"moexd"}
)
public final class VelocitySpectorPlugin {

    private static final MinecraftChannelIdentifier CHANNEL =
            MinecraftChannelIdentifier.create("moexd", "spector");

    private final ProxyServer proxy;
    private final Logger logger;
    private volatile String secret = "change-me";

    /** 观察者 UUID -> 会话 */
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    /** 被观察者 UUID -> 正在观察该目标的观察者集合（同一目标可被多个管理员同时观察） */
    private final Map<UUID, Set<UUID>> targetToObservers = new ConcurrentHashMap<>();
    /** 以 server-id 为键的会话记录：serverId -> 该后端发起的观察者 UUID 集合 */
    private final Map<String, Set<UUID>> recordsByServerId = new ConcurrentHashMap<>();
    /** 观察者 UUID -> 已被本插件移动且尚未送达（用于到达后补发 HANDOFF） */
    private final Map<UUID, Boolean> pendingHandoff = new ConcurrentHashMap<>();

    /** 一条跨服观察会话（纯内存，重启即清空）；used 为本轮已观察过的玩家 */
    private record Session(String serverId, UUID observer, UUID target, String mode, String used) { }

    @Inject
    public VelocitySpectorPlugin(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        loadSecret(dataDirectory);
    }

    private void loadSecret(Path dataDirectory) {
        Path file = dataDirectory.resolve("moexdspector.properties");
        try {
            Properties props = new Properties();
            if (Files.exists(file)) {
                try (InputStream in = Files.newInputStream(file)) {
                    props.load(in);
                }
            } else {
                Files.createDirectories(dataDirectory);
                props.setProperty("secret", "change-me");
                try (var out = Files.newOutputStream(file)) {
                    props.store(out, "moexdspector velocity config; must match velocity.secret in each backend config.yml");
                }
                logger.warn("已生成默认配置 {}，请修改 secret 并与各后端 config.yml 的 velocity.secret 保持一致", file);
            }
            String configured = props.getProperty("secret", "change-me");
            if (!configured.isBlank()) {
                this.secret = configured;
            }
        } catch (IOException e) {
            logger.warn("读取配置失败，使用默认 secret", e);
        }
    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (!CHANNEL.equals(event.getIdentifier())) {
            return;
        }
        // 只接受后端方向（服务器 -> 代理）的消息，忽略客户端直接发来的同通道消息
        if (!(event.getSource() instanceof ServerConnection)) {
            event.setResult(PluginMessageEvent.ForwardResult.handled());
            return;
        }
        // 拦截，不转发给客户端
        event.setResult(PluginMessageEvent.ForwardResult.handled());
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(event.getData()));
            if (!SpectorProtocol.VERSION.equals(in.readUTF())) {
                logger.warn("收到不兼容协议版本的插件消息，已忽略（请统一更新两端 jar）");
                return;
            }
            String sub = in.readUTF();
            String serverId = in.readUTF();
            UUID observer = UUID.fromString(in.readUTF());
            switch (sub) {
                case SpectorProtocol.PAIR -> {
                    UUID target = UUID.fromString(in.readUTF());
                    String mode = in.readUTF();
                    String used = in.readUTF();
                    if (!verifySecret(in)) {
                        logger.warn("[{}] 收到未通过密钥校验的 PAIR，已忽略（可能存在伪造）", serverId);
                        return;
                    }
                    Session previous = sessions.put(observer, new Session(serverId, observer, target, mode, used));
                    if (previous != null) {
                        removeTargetIndex(previous.target(), observer);
                    }
                    addTargetIndex(target, observer);
                    recordsByServerId.computeIfAbsent(serverId, k -> ConcurrentHashMap.newKeySet()).add(observer);
                    logger.info("[{}] 观察会话登记: observer={} target={} mode={}", serverId, observer, target, mode);
                }
                case SpectorProtocol.UNPAIR -> {
                    if (verifySecret(in)) {
                        removeSession(observer);
                    } else {
                        logger.warn("[{}] 收到未通过密钥校验的 UNPAIR，已忽略（可能存在伪造）", serverId);
                    }
                }
                case SpectorProtocol.OBSERVE -> {
                    String mode = in.readUTF();
                    String targetName = in.readUTF();
                    String used = in.readUTF();
                    if (!verifySecret(in)) {
                        logger.warn("[{}] 收到未通过密钥校验的 OBSERVE，已忽略（可能存在伪造）", serverId);
                        return;
                    }
                    handleObserve(serverId, observer, mode, targetName, used);
                }
                case SpectorProtocol.FORCE_STOP -> {
                    if (!verifySecret(in)) {
                        logger.warn("[{}] 收到未通过密钥校验的 FORCE_STOP，已忽略（可能存在伪造）", serverId);
                        return;
                    }
                    handleForceStop(observer);
                }
                default -> logger.warn("收到未知子通道的插件消息: {}", sub);
            }
        } catch (Exception e) {
            logger.warn("Failed to parse plugin message", e);
        }
    }

    private boolean verifySecret(DataInputStream in) throws IOException {
        String provided = in.readUTF();
        return java.security.MessageDigest.isEqual(
                provided.getBytes(StandardCharsets.UTF_8),
                secret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 跨服观察请求：targetName 为 * 时在全网随机选一名未被排除的玩家，
     * 否则按名字定向寻找；找到后把观察者传送到目标所在服务器，
     * 观察者到达后再移交会话（连接失败则回 OBSERVE_FAIL）。
     */
    private void handleObserve(String serverId, UUID observerId, String mode, String targetName, String usedCsv) {
        Optional<Player> observerOpt = proxy.getPlayer(observerId);
        if (observerOpt.isEmpty()) {
            return;
        }
        Player observer = observerOpt.get();
        Optional<Player> targetOpt;
        if ("*".equals(targetName)) {
            List<UUID> excluded = parseCsv(usedCsv);
            List<Player> candidates = new ArrayList<>();
            for (Player online : proxy.getAllPlayers()) {
                if (!online.getUniqueId().equals(observerId) && !excluded.contains(online.getUniqueId())) {
                    candidates.add(online);
                }
            }
            targetOpt = candidates.isEmpty()
                    ? Optional.empty()
                    : Optional.of(candidates.get(ThreadLocalRandom.current().nextInt(candidates.size())));
        } else {
            Player named = proxy.getPlayer(targetName).orElse(null);
            if (named == null) {
                for (Player online : proxy.getAllPlayers()) {
                    if (online.getUsername().equalsIgnoreCase(targetName)) {
                        named = online;
                        break;
                    }
                }
            }
            targetOpt = Optional.ofNullable(named);
        }
        if (targetOpt.isEmpty()) {
            logger.info("[{}] 跨服观察请求失败: 未找到玩家 {}", serverId, targetName);
            replyObserveFail(observer, serverId, observerId, mode);
            return;
        }
        Player target = targetOpt.get();
        Optional<ServerConnection> targetServer = target.getCurrentServer();
        if (targetServer.isEmpty()) {
            replyObserveFail(observer, serverId, observerId, mode);
            return;
        }
        if (targetServer.get().getServer().equals(
                observer.getCurrentServer().map(ServerConnection::getServer).orElse(null))) {
            // 观察者与目标已在同一服务器：直接登记并移交
            registerSession(serverId, observerId, target.getUniqueId(), mode, usedCsv);
            sendHandoff(targetServer.get(), serverId, observerId, mode, usedCsv, target.getUniqueId());
            return;
        }
        registerSession(serverId, observerId, target.getUniqueId(), mode, usedCsv);
        pendingHandoff.put(observerId, Boolean.TRUE);
        logger.info("[{}] 跨服观察: observer={} -> {} @ {}", serverId, observer.getUsername(),
                target.getUsername(), targetServer.get().getServerInfo().getName());
        observer.createConnectionRequest(targetServer.get().getServer())
                .connectWithIndication()
                .whenComplete((ok, error) -> {
                    if (!Boolean.TRUE.equals(ok) || error != null) {
                        // 传送失败：清理登记并告知来源后端
                        pendingHandoff.remove(observerId);
                        removeSession(observerId);
                        replyObserveFail(observer, serverId, observerId, mode);
                        logger.warn("[{}] 观察者传送失败，已回执 OBSERVE_FAIL", serverId);
                    }
                    // 成功时 HANDOFF 由 onServerPostConnect（观察者到达）补发
                });
    }

    /** 管理指令跨服转发：注销该观察者的代理会话，并通知其所在后端结束本地会话 */
    private void handleForceStop(UUID observerId) {
        Session session = sessions.remove(observerId);
        if (session != null) {
            removeTargetIndex(session.target(), observerId);
            removeFromRecords(session.serverId(), observerId);
            logger.info("[{}] 观察会话被管理员强制断开: observer={}", session.serverId(), observerId);
        }
        proxy.getPlayer(observerId).flatMap(Player::getCurrentServer).ifPresent(serverConnection -> {
            try {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                DataOutputStream out = new DataOutputStream(bos);
                out.writeUTF(SpectorProtocol.VERSION);
                out.writeUTF(SpectorProtocol.STOP);
                out.writeUTF(session != null ? session.serverId() : "unknown");
                out.writeUTF(observerId.toString());
                out.writeUTF(secret);
                serverConnection.sendPluginMessage(CHANNEL, bos.toByteArray());
            } catch (IOException e) {
                logger.warn("Failed to send STOP", e);
            }
        });
    }

    private void registerSession(String serverId, UUID observerId, UUID targetId, String mode, String usedCsv) {
        Session previous = sessions.put(observerId, new Session(serverId, observerId, targetId, mode, usedCsv));
        if (previous != null) {
            removeTargetIndex(previous.target(), observerId);
        }
        addTargetIndex(targetId, observerId);
        recordsByServerId.computeIfAbsent(serverId, k -> ConcurrentHashMap.newKeySet()).add(observerId);
    }

    private void addTargetIndex(UUID targetId, UUID observerId) {
        targetToObservers.computeIfAbsent(targetId, k -> ConcurrentHashMap.newKeySet()).add(observerId);
    }

    private void removeTargetIndex(UUID targetId, UUID observerId) {
        Set<UUID> observers = targetToObservers.get(targetId);
        if (observers != null) {
            observers.remove(observerId);
            if (observers.isEmpty()) {
                targetToObservers.remove(targetId, observers);
            }
        }
    }

    private void replyObserveFail(Player via, String serverId, UUID observer, String mode) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bos);
            out.writeUTF(SpectorProtocol.VERSION);
            out.writeUTF(SpectorProtocol.OBSERVE_FAIL);
            out.writeUTF(serverId);
            out.writeUTF(observer.toString());
            out.writeUTF(mode);
            out.writeUTF(secret);
            via.getCurrentServer().ifPresent(sc -> sc.sendPluginMessage(CHANNEL, bos.toByteArray()));
        } catch (IOException e) {
            logger.warn("Failed to send OBSERVE_FAIL", e);
        }
    }

    private static List<UUID> parseCsv(String csv) {
        List<UUID> ids = new ArrayList<>();
        for (String id : csv.split(",")) {
            if (!id.isBlank()) {
                ids.add(UUID.fromString(id));
            }
        }
        return ids;
    }

    private void sendHandoff(ServerConnection via, String serverId, UUID observer, String mode,
                             String usedCsv, UUID target) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bos);
            out.writeUTF(SpectorProtocol.VERSION);
            out.writeUTF(SpectorProtocol.HANDOFF);
            out.writeUTF(serverId);
            out.writeUTF(observer.toString());
            out.writeUTF(mode);
            out.writeUTF(usedCsv);
            out.writeUTF(target.toString());
            out.writeUTF(secret);
            via.sendPluginMessage(CHANNEL, bos.toByteArray());
            logger.info("[{}] 会话移交到后端: observer={} target={}", serverId, observer, target);
        } catch (IOException e) {
            logger.warn("Failed to send HANDOFF", e);
        }
    }

    /** 玩家切换服务器：目标是观察对象时把观察者送过去；观察者到达时补发 HANDOFF */
    @Subscribe
    public void onServerPostConnect(ServerPostConnectEvent event) {
        Player player = event.getPlayer();
        Optional<ServerConnection> current = player.getCurrentServer();
        if (current.isEmpty()) {
            return;
        }

        // 观察者到达新服务器：若其观察目标正在该服，向该后端补发 HANDOFF
        if (pendingHandoff.remove(player.getUniqueId()) != null) {
            Session session = sessions.get(player.getUniqueId());
            if (session != null) {
                UUID targetId = session.target();
                boolean targetHere = proxy.getPlayer(targetId)
                        .flatMap(Player::getCurrentServer)
                        .map(sc -> sc.getServer() == current.get().getServer())
                        .orElse(false);
                if (targetHere) {
                    sendHandoff(current.get(), session.serverId(), session.observer(),
                            session.mode(), session.used(), targetId);
                }
                return;
            }
        }

        UUID targetId = player.getUniqueId();
        Set<UUID> observers = targetToObservers.get(targetId);
        if (observers == null || observers.isEmpty()) {
            return;
        }

        // 观察对象切换服务器：所有观察者逐一跟随/移交（同服的直接移交，异服的送过去）
        for (UUID observerId : List.copyOf(observers)) {
            Session session = sessions.get(observerId);
            if (session == null) {
                continue;
            }
            proxy.getPlayer(observerId).ifPresent(observer -> {
                boolean observerHere = observer.getCurrentServer()
                        .map(sc -> sc.getServer() == current.get().getServer())
                        .orElse(false);
                if (observerHere) {
                    sendHandoff(current.get(), session.serverId(), observerId, session.mode(),
                            session.used(), targetId);
                } else {
                    pendingHandoff.put(observer.getUniqueId(), Boolean.TRUE);
                    logger.info("观察对象切服，跟随观察者: {} -> {}", observer.getUsername(),
                            current.get().getServerInfo().getName());
                    observer.createConnectionRequest(current.get().getServer())
                            .connectWithIndication()
                            .whenComplete((ok, error) -> {
                                if (!Boolean.TRUE.equals(ok) || error != null) {
                                    pendingHandoff.remove(observer.getUniqueId());
                                    // 观察者送不过去：旧后端的会话已被目标离开清理，保持 pair 等待目标下次切换
                                }
                            });
                }
            });
        }
    }

    /** 玩家退出网络：清会话；若是被观察者，通知其所有观察者所在后端结束观察 */
    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        pendingHandoff.remove(id);
        Session asObserver = sessions.get(id);
        if (asObserver != null) {
            removeSession(id);
            return;
        }
        Set<UUID> observers = targetToObservers.remove(id);
        if (observers == null || observers.isEmpty()) {
            return;
        }
        for (UUID observerId : List.copyOf(observers)) {
            Session session = sessions.remove(observerId);
            if (session == null) {
                continue;
            }
            removeFromRecords(session.serverId(), observerId);
            proxy.getPlayer(observerId).flatMap(Player::getCurrentServer).ifPresent(serverConnection -> {
                try {
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    DataOutputStream out = new DataOutputStream(bos);
                    out.writeUTF(SpectorProtocol.VERSION);
                    out.writeUTF(SpectorProtocol.TARGET_GONE);
                    out.writeUTF(session.serverId());
                    out.writeUTF(observerId.toString());
                    out.writeUTF(id.toString());
                    out.writeUTF(secret);
                    serverConnection.sendPluginMessage(CHANNEL, bos.toByteArray());
                    logger.info("[{}] 观察对象退出网络: target={}", session.serverId(), id);
                } catch (IOException e) {
                    logger.warn("Failed to send TARGET_GONE", e);
                }
            });
        }
    }

    private void removeSession(UUID observer) {
        Session session = sessions.remove(observer);
        if (session == null) {
            return;
        }
        removeTargetIndex(session.target(), observer);
        removeFromRecords(session.serverId(), observer);
        logger.info("[{}] 观察会话注销: observer={}", session.serverId(), observer);
    }

    private void removeFromRecords(String serverId, UUID observer) {
        Set<UUID> set = recordsByServerId.get(serverId);
        if (set != null) {
            set.remove(observer);
            if (set.isEmpty()) {
                recordsByServerId.remove(serverId, set);
            }
        }
    }
}
