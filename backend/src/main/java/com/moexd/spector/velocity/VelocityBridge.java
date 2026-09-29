package com.moexd.spector.velocity;

import com.moexd.spector.MoexdSpectorPlugin;
import com.moexd.spector.observe.ObservationManager;
import com.moexd.spector.protocol.SpectorProtocol;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Folia 后端与 Velocity 代理端附属（moexdspector-velocity）之间的插件消息桥。
 * 协议见 {@link SpectorProtocol}：VERSION, SUB, ...字段..., SECRET，全部 UTF。
 *
 * 后端 -> 代理：PAIR / UNPAIR / OBSERVE
 * 代理 -> 后端：HANDOFF / TARGET_GONE / OBSERVE_FAIL
 *
 * 安全：每条消息末尾附带共享密钥（velocity.yml 的 secret），客户端无法区分代理注入的消息，
 * 只能靠密钥防伪造；校验失败一律丢弃并告警。
 */
public final class VelocityBridge implements PluginMessageListener {

    private final MoexdSpectorPlugin plugin;
    private final ObservationManager manager;

    /** 全网玩家名缓存（代理周期性下发，Tab 补全用） */
    private final java.util.Set<String> networkNames = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public VelocityBridge(MoexdSpectorPlugin plugin, ObservationManager manager) {
        this.plugin = plugin;
        this.manager = manager;
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, SpectorProtocol.CHANNEL);
        plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, SpectorProtocol.CHANNEL, this);
        // 每 10 秒向代理请求一次全网玩家名（跨服 Tab 补全）；本服无人在线时跳过
        plugin.getServer().getGlobalRegionScheduler().runAtFixedRate(plugin,
                task -> requestNetworkPlayers(), 200L, 200L);
    }

    /** 全网玩家名快照（含本服） */
    public java.util.Set<String> networkPlayerNames() {
        return java.util.Set.copyOf(networkNames);
    }

    private void requestNetworkPlayers() {
        if (!enabled()) {
            return;
        }
        Player via = plugin.getServer().getOnlinePlayers().stream().findFirst().orElse(null);
        if (via == null) {
            return;
        }
        send(via, SpectorProtocol.REQ_PLAYERS, via.getUniqueId().toString());
    }

    public boolean enabled() {
        return plugin.velocityEnabled();
    }

    private String serverId() {
        return plugin.velocityServerId();
    }

    private String secret() {
        return plugin.velocitySecret();
    }

    /** 常量时间比较，防时序侧信道 */
    private boolean verifySecret(DataInputStream in) throws IOException {
        String provided = in.readUTF();
        return java.security.MessageDigest.isEqual(
                provided.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                secret().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * 向代理登记/更新观察会话，携带去重列表供跨服轮换使用。
     * observerId 必须显式传入：消息载体（via）可能不是观察者本人——
     * 此前用 via.getUniqueId() 当 observer，导致借道目标连接回报时
     * 代理登记了"目标观察自己"的幽灵会话，目标再换服即被误切旁观模式。
     */
    public void sendPair(Player via, UUID observerId, UUID target, String mode, String usedCsv) {
        if (!enabled() || via == null) {
            return;
        }
        send(via, SpectorProtocol.PAIR, observerId.toString(), target.toString(), mode, usedCsv);
    }

    /** 向代理注销观察会话 */
    public void sendUnpair(Player via, UUID observer) {
        if (!enabled() || via == null) {
            return;
        }
        send(via, SpectorProtocol.UNPAIR, observer.toString());
    }

    /** 跨服观察请求：目标在代理网络中，由代理把观察者送过去并移交会话 */
    public void sendObserveRequest(Player observer, String mode, String targetName, String usedCsv) {
        if (!enabled() || observer == null) {
            return;
        }
        send(observer, SpectorProtocol.OBSERVE, observer.getUniqueId().toString(), mode, targetName, usedCsv);
    }

    /** 强制断开某观察者的会话：代理注销登记并转发到其所在后端 */
    public void sendForceStop(Player via, UUID observer) {
        if (!enabled() || via == null) {
            return;
        }
        send(via, SpectorProtocol.FORCE_STOP, observer.toString());
    }

    /** 统一帧格式：VERSION, SUB, serverId, ...调用方字段..., SECRET（serverId 由本方法注入，两端解析按此顺序） */
    private void send(Player via, String sub, String... fields) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bos);
            out.writeUTF(SpectorProtocol.VERSION);
            out.writeUTF(sub);
            out.writeUTF(serverId());
            for (String field : fields) {
                out.writeUTF(field);
            }
            out.writeUTF(secret());
            via.sendPluginMessage(plugin, SpectorProtocol.CHANNEL, bos.toByteArray());
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "发送 Velocity 插件消息失败", e);
        }
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!SpectorProtocol.CHANNEL.equals(channel) || !enabled()) {
            return;
        }
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(message));
            if (!SpectorProtocol.VERSION.equals(in.readUTF())) {
                plugin.getLogger().warning("收到不兼容协议版本的插件消息，已忽略（请统一更新两端 jar）");
                return;
            }
            String sub = in.readUTF();
            switch (sub) {
                case SpectorProtocol.HANDOFF -> {
                    // 观察者已到达本服；目标以显式字段为准（消息可能经观察者的连接到达）
                    in.readUTF(); // server-id（本服记录，无需再校验）
                    UUID observer = UUID.fromString(in.readUTF());
                    String mode = in.readUTF();
                    List<UUID> used = parseCsv(in.readUTF());
                    UUID targetId = UUID.fromString(in.readUTF());
                    if (!verifySecret(in)) {
                        warnForgery("HANDOFF");
                        return;
                    }
                    manager.resumeHandoff(observer, targetId, mode, used);
                }
                case SpectorProtocol.TARGET_GONE -> {
                    in.readUTF(); // server-id
                    UUID observer = UUID.fromString(in.readUTF());
                    UUID target = UUID.fromString(in.readUTF());
                    if (!verifySecret(in)) {
                        warnForgery("TARGET_GONE");
                        return;
                    }
                    manager.stopIfPair(observer, target);
                }
                case SpectorProtocol.OBSERVE_FAIL -> {
                    in.readUTF(); // server-id
                    UUID observer = UUID.fromString(in.readUTF());
                    String mode = in.readUTF();
                    if (!verifySecret(in)) {
                        warnForgery("OBSERVE_FAIL");
                        return;
                    }
                    manager.onObserveFailed(observer, mode);
                }
                case SpectorProtocol.PLAYERS -> {
                    in.readUTF(); // server-id
                    UUID observer = UUID.fromString(in.readUTF());
                    String namesCsv = in.readUTF();
                    if (!verifySecret(in)) {
                        warnForgery("PLAYERS");
                        return;
                    }
                    networkNames.clear();
                    for (String name : namesCsv.split(",")) {
                        if (!name.isBlank()) {
                            networkNames.add(name);
                        }
                    }
                }
                case SpectorProtocol.STOP -> {
                    // 管理指令跨服转发：结束该观察者在本服的会话
                    in.readUTF(); // server-id
                    UUID observer = UUID.fromString(in.readUTF());
                    if (!verifySecret(in)) {
                        warnForgery("STOP");
                        return;
                    }
                    manager.stopSelf(observer);
                }
                default -> plugin.getLogger().warning("收到未知子通道的插件消息: " + sub);
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "解析 Velocity 插件消息失败", e);
        }
    }

    private void warnForgery(String sub) {
        plugin.getLogger().warning("收到未通过密钥校验的 " + sub + "，已忽略（可能存在伪造）");
    }

    public static List<UUID> parseCsv(String csv) {
        List<UUID> ids = new ArrayList<>();
        for (String id : csv.split(",")) {
            if (!id.isBlank()) {
                ids.add(UUID.fromString(id));
            }
        }
        return ids;
    }
}
