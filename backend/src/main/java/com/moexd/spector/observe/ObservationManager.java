package com.moexd.spector.observe;

import com.moexd.spector.MoexdSpectorPlugin;
import com.moexd.spector.protocol.SpectorProtocol;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 管理观察会话。支持**多观察者并存**：每个管理员拥有独立的会话
 * （模式、去重列表、挂机决策、看门狗、主动接管状态互不干扰），
 * 且允许多个观察者同时附身同一名玩家——目标监测任务按目标引用计数共享。
 *
 * 会话模式：MANUAL（/spector <玩家>，AFK 时自动换人）与
 * RANDOM（/spector random，按配置间隔轮换，全部轮完前不重复）。
 * 所有去重状态仅保存在内存中，不落库。
 *
 * 启用 Velocity 跨服时：指令可对其他服务器的玩家使用（由代理把观察者送过去并移交会话）；
 * 观察者意外脱离观察状态（改模式、分离视角等，跨服与死亡除外）超过 detach 时限后
 * 立即重新附身——优先本服可选玩家，本服无人则经代理跨服选人。
 * 被观察对象退出网络后在 relogin 宽限期内重连，会话自动恢复附身。
 *
 * 相机接管策略：观察者自己主动进入旁观状态（自行 /gamemode、旁观菜单选择其他玩家）
 * 时该会话不接管相机（voluntary），直到该观察者重新执行 /spector。
 *
 * 并发模型：会话状态被主线程（指令）、全局调度器（轮换）、目标区域线程（采样/AFK）、
 * 观察者区域线程（看门狗）同时触碰，所有"读状态→决策→写状态"的入口统一走 {@link #lock}。
 */
public final class ObservationManager {

    private final MoexdSpectorPlugin plugin;
    private final AfkRuleEvaluator afkEvaluator = new AfkRuleEvaluator();
    private final FeedbackRenderer feedbackRenderer;
    private final Object lock = new Object();

    /** 观察者 UUID -> 会话（多观察者并存） */
    private final Map<UUID, ObserverSession> sessions = new ConcurrentHashMap<>();
    /** 目标 UUID -> 正在观察该目标的观察者集合 */
    private final Map<UUID, Set<UUID>> watchersByTarget = new ConcurrentHashMap<>();
    /** 目标 UUID -> 引用计数的监测任务（多个观察者共享同一次采样） */
    private final Map<UUID, MonitorHandle> monitors = new ConcurrentHashMap<>();
    /** 目标 UUID -> 活动数据（与观察者无关，按目标共享） */
    private final Map<UUID, ActivityState> states = new ConcurrentHashMap<>();
    /** 观察者 UUID -> 会话纪元（每次建立/结束会话更新，用于使跨服超时回调失效） */
    private final Map<UUID, Long> observeEpochs = new ConcurrentHashMap<>();
    /** 近期挂机判定记录（/spector afk 报告用） */
    private final ArrayDeque<AfkRecord> recentAfk = new ArrayDeque<>();

    /**
     * 正在进行"插件发起的 setGameMode"的玩家集合。setGameMode 在调用线程同步派发
     * PlayerGameModeChangeEvent，因此以 UUID 键控（add → setGameMode → finally remove）
     * 可以正确区分插件切换与用户切换，且天然规避多观察者在不同区域线程并发切换时的串扰。
     */
    private final Set<UUID> pluginModeChanges = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Velocity 跨服桥（未启用时为 null） */
    private com.moexd.spector.velocity.VelocityBridge velocityBridge;

    public void setVelocityBridge(com.moexd.spector.velocity.VelocityBridge bridge) {
        this.velocityBridge = bridge;
    }

    public ObservationManager(MoexdSpectorPlugin plugin) {
        this.plugin = plugin;
        this.feedbackRenderer = new FeedbackRenderer(plugin);
    }

    private boolean crossServer() {
        return velocityBridge != null && velocityBridge.enabled();
    }

    /** 掉线不空窗（重连桥接）是否生效：总开关开启且宽限期大于 0 */
    private boolean reloginActive() {
        return plugin.reloginBridgeEnabled() && plugin.reloginGraceMillis() > 0;
    }

    public boolean isRunning() {
        return !sessions.isEmpty();
    }

    /** 供监听器安全获取活动状态（并发策略收口在 Manager 内） */
    Optional<ActivityState> stateFor(UUID playerId) {
        return Optional.ofNullable(states.get(playerId));
    }

    /** 按目标共享、引用计数的监测任务 */
    private static final class MonitorHandle {
        final ScheduledTask task;
        final AtomicInteger refs = new AtomicInteger(1);

        MonitorHandle(ScheduledTask task) {
            this.task = task;
        }
    }

    /** 一条近期挂机判定记录 */
    private record AfkRecord(String playerName, String reason, long at) { }

    // ------------------------------------------------------------------
    // 会话建立 / 结束
    // ------------------------------------------------------------------

    /** /spector <玩家ID>：为该观察者建立（或替换）会话 */
    public void startManual(Player observer, Player target) {
        synchronized (lock) {
            stopSessionLocked(observer.getUniqueId(), true);
            ObserverSession session = newSessionLocked(observer, ObserverSession.Mode.MANUAL);
            if (beginWatchingLocked(observer, session, target,
                    plugin.msg("observing", "%target%", target.getName()))) {
                sendFeedbackLocked(observer, session, target, "start");
            } else {
                // 目标在选中与附身之间退出：兜底提示（看门狗/切换路径会自行重选）
                observer.sendMessage(plugin.msg("target-not-found"));
                stopSessionLocked(observer.getUniqueId(), true);
            }
        }
    }

    /** /spector <玩家ID> 且目标在其他服务器：经代理移交会话并跟过去 */
    public void observeCrossServer(Player observer, String targetName, boolean random) {
        synchronized (lock) {
            stopSessionLocked(observer.getUniqueId(), true);
            if (velocityBridge != null) {
                // 全新会话，去重列表为空是有意为之；先清理后发送，保证超时回调的纪元校验有效
                sendObserveAndArmTimeout(observer, random ? SpectorProtocol.MODE_RANDOM : SpectorProtocol.MODE_MANUAL,
                        targetName, "");
            }
        }
    }

    /** /spector random */
    public void startRandom(Player observer) {
        synchronized (lock) {
            stopSessionLocked(observer.getUniqueId(), true);
            ObserverSession session = newSessionLocked(observer, ObserverSession.Mode.RANDOM);
            observer.sendMessage(plugin.msg("observing-random",
                    "%interval%", String.valueOf(plugin.getConfig().getInt("random.interval-seconds", 60))));
            startRotationTaskLocked(session);
            // 立即开始第一个对象（会话起点，反馈原因用 start）
            Player next = pickNextForRotation(session);
            if (next != null && beginWatchingLocked(observer, session, next,
                    plugin.msg("random-switch", "%target%", next.getName()))) {
                sendFeedbackLocked(observer, session, next, "start");
                return;
            }
            if (crossServer()) {
                String used = usedCsv(session);
                endSessionKeepPairLocked(session);
                sendObserveAndArmTimeout(observer, SpectorProtocol.MODE_RANDOM, "*", used);
            } else {
                observer.sendMessage(plugin.msg("no-candidates"));
                stopSessionLocked(observer.getUniqueId(), true);
            }
        }
    }

    /** 创建会话并登记纪元；voluntary/previousGamemode 只在会话起点确定一次 */
    private ObserverSession newSessionLocked(Player observer, ObserverSession.Mode mode) {
        ObserverSession session = new ObserverSession(observer.getUniqueId());
        session.mode = mode;
        session.previousGamemode = observer.getGameMode() == GameMode.SPECTATOR ? null : observer.getGameMode();
        session.voluntary = false;
        sessions.put(observer.getUniqueId(), session);
        observeEpochs.put(observer.getUniqueId(), System.nanoTime());
        // 进入观察者模式的语言提示（含退出指引）
        observer.sendMessage(plugin.msg("enter-observe"));
        return session;
    }

    /** /spector off：结束当前观察者的会话 */
    public void stopSelf(UUID observerId) {
        synchronized (lock) {
            stopSessionLocked(observerId, true);
        }
    }

    /** /spector off <玩家>（管理员/控制台）：强制结束指定观察者的会话，并经代理跨服转发 */
    public boolean stopOther(UUID observerId, Player via) {
        synchronized (lock) {
            boolean existed = sessions.containsKey(observerId);
            stopSessionLocked(observerId, true);
            if (velocityBridge != null && velocityBridge.enabled()) {
                Player connection = via != null ? via : anyOnlinePlayer();
                if (connection != null) {
                    velocityBridge.sendForceStop(connection, observerId);
                }
            }
            return existed;
        }
    }

    /** 插件卸载：结束全部会话 */
    public void stopAll() {
        synchronized (lock) {
            for (UUID observer : List.copyOf(sessions.keySet())) {
                stopSessionLocked(observer, true);
            }
        }
    }

    /**
     * 结束某观察者的会话：清理本地、还原 gamemode、通知代理注销。
     * restoreGamemode=false 用于观察者已离开本服的场景。
     */
    private void stopSessionLocked(UUID observerId, boolean restoreGamemode) {
        ObserverSession session = sessions.remove(observerId);
        if (session == null) {
            return;
        }
        observeEpochs.remove(observerId);
        teardownSessionTasksLocked(session);
        releaseWatchLocked(session);
        plugin.getLogger().info("观察会话结束: observer=" + observerId);

        if (velocityBridge != null && velocityBridge.enabled()) {
            Player via = Bukkit.getPlayer(observerId);
            if (via == null) {
                via = anyOnlinePlayer();
            }
            if (via != null) {
                velocityBridge.sendUnpair(via, observerId);
            }
        }
        // 还原观察者原本的 gamemode（主动接管者自己管理自己的模式；已离服者跳过）
        if (restoreGamemode && !session.voluntary && session.previousGamemode != null) {
            Player player = Bukkit.getPlayer(observerId);
            if (player != null && player.getGameMode() == GameMode.SPECTATOR) {
                pluginModeChanges.add(observerId);
                try {
                    player.setGameMode(session.previousGamemode);
                } finally {
                    pluginModeChanges.remove(observerId);
                }
            }
        }
    }

    /** 仅脱离本地监测，不注销代理会话（跨服移交给其他后端时使用） */
    private void endSessionKeepPairLocked(ObserverSession session) {
        sessions.remove(session.observerId);
        teardownSessionTasksLocked(session);
        releaseWatchLocked(session);
    }

    private void teardownSessionTasksLocked(ObserverSession session) {
        cancelTask(session.victimExpiryTask);
        cancelTask(session.rotationTask);
        cancelTask(session.watchdogTask);
        cancelTask(session.reloginExpiryTask);
        cancelTask(session.pendingVerdictTask);
        session.victimExpiryTask = null;
        session.rotationTask = null;
        session.watchdogTask = null;
        session.reloginExpiryTask = null;
        session.pendingVerdictTask = null;
        session.detachedSince = -1;
        session.attachGraceUntil = 0;
        session.lastVictim = null;
        session.pendingReloginTarget = null;
        session.pendingVerdictTarget = null;
        // 注意：states 按目标共享且可能仍被其他会话引用，只能由 releaseMonitor 引用归零时清理
    }

    private static void cancelTask(ScheduledTask task) {
        if (task != null) {
            task.cancel();
        }
    }

    private Player anyOnlinePlayer() {
        for (Player online : Bukkit.getOnlinePlayers()) {
            return online;
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 目标监测（引用计数共享）与 watcher 索引
    // ------------------------------------------------------------------

    /** @return false 表示目标已不在线，无法建立监测 */
    private boolean acquireMonitorLocked(UUID targetId) {
        MonitorHandle result = monitors.compute(targetId, (id, handle) -> {
            if (handle != null) {
                handle.refs.incrementAndGet();
                return handle;
            }
            Player target = Bukkit.getPlayer(id);
            if (target == null) {
                return null;
            }
            return new MonitorHandle(target.getScheduler().runAtFixedRate(plugin,
                    task -> sampleTarget(target),
                    () -> releaseMonitorIfIdle(id),
                    1L, plugin.checkIntervalTicks()));
        });
        return result != null;
    }

    private void releaseMonitorLocked(UUID targetId) {
        monitors.computeIfPresent(targetId, (id, handle) -> {
            if (handle.refs.decrementAndGet() <= 0) {
                handle.task.cancel();
                states.remove(id);
                return null;
            }
            return handle;
        });
    }

    /** 实体调度器 retired 回调（目标退出）触发：无引用则清索引 */
    private void releaseMonitorIfIdle(UUID targetId) {
        synchronized (lock) {
            monitors.computeIfPresent(targetId, (id, handle) -> {
                if (handle.refs.get() <= 0) {
                    states.remove(id);
                    return null;
                }
                return handle;
            });
        }
    }

    private void addWatcherLocked(UUID targetId, UUID observerId) {
        watchersByTarget.computeIfAbsent(targetId, k -> ConcurrentHashMap.newKeySet()).add(observerId);
    }

    private void releaseWatchLocked(ObserverSession session) {
        UUID targetId = session.currentTargetId;
        if (targetId == null) {
            return;
        }
        Set<UUID> watchers = watchersByTarget.get(targetId);
        if (watchers != null) {
            watchers.remove(session.observerId);
            if (watchers.isEmpty()) {
                watchersByTarget.remove(targetId, watchers);
                releaseMonitorLocked(targetId);
            }
        }
        session.currentTargetId = null;
    }

    // ------------------------------------------------------------------
    // 附身 / 看门狗
    // ------------------------------------------------------------------

    /** @return false 表示目标已退出，未能建立监测（调用方应自行重选或停止） */
    private boolean beginWatchingLocked(Player observer, ObserverSession session, Player target, String message) {
        observer.sendMessage(message);
        session.currentTargetId = target.getUniqueId();
        addWatcherLocked(session.currentTargetId, session.observerId);
        if (!acquireMonitorLocked(session.currentTargetId)) {
            // 目标在选中与建立监测之间退出
            releaseWatchLocked(session);
            return false;
        }
        if (!session.voluntary) {
            makeSpectating(observer);
            attachCamera(observer, target);
        }
        startWatchdogLocked(session, observer);
        session.detachedSince = -1;
        // teleportAsync 完成需要时间，给看门狗留出宽限期
        session.attachGraceUntil = System.currentTimeMillis() + attachGraceMillis();
        sendPair(observer, session);
        return true;
    }

    private long attachGraceMillis() {
        return Math.max(3000L, plugin.detachReattachMillis() * 3);
    }

    /** 观察者进入旁观模式并贴近目标 */
    private void attachCamera(Player observer, Player target) {
        observer.teleportAsync(target.getLocation());
    }

    private void makeSpectating(Player observer) {
        if (observer.getGameMode() != GameMode.SPECTATOR) {
            pluginModeChanges.add(observer.getUniqueId());
            try {
                observer.setGameMode(GameMode.SPECTATOR);
            } finally {
                pluginModeChanges.remove(observer.getUniqueId());
            }
        }
    }

    /** 观察者看门狗：检测意外脱离观察状态（跨服移交与死亡路径除外） */
    private void startWatchdogLocked(ObserverSession session, Player observer) {
        cancelTask(session.watchdogTask);
        session.watchdogTask = observer.getScheduler().runAtFixedRate(plugin,
                task -> watchObserver(observer, session.observerId),
                () -> { /* 观察者退出时任务自动结束 */ },
                20L, 20L);
    }

    private void watchObserver(Player observer, UUID observerId) {
        synchronized (lock) {
            ObserverSession session = sessions.get(observerId);
            if (session == null || session.currentTargetId == null) {
                return;
            }
            // 观察者主动接管相机后插件不再干预
            if (session.voluntary) {
                return;
            }
            long now = System.currentTimeMillis();
            // 附身宽限期内只观察不动手
            if (now < session.attachGraceUntil) {
                return;
            }
            Player target = Bukkit.getPlayer(session.currentTargetId);
            if (target != null && observer.getGameMode() != GameMode.SPECTATOR) {
                // 兜底：gamemode 事件应已自动退出；此处防御事件被其他插件取消的情况
                observer.sendMessage(plugin.msg("mode-exit"));
                endSessionOnUserExitLocked(session);
                return;
            }
            boolean attached = false;
            if (target != null && observer.getGameMode() == GameMode.SPECTATOR) {
                Entity spectating = observer.getSpectatorTarget();
                if (spectating != null && !spectating.getUniqueId().equals(target.getUniqueId())) {
                    // 观察者通过旁观菜单主动选择其他玩家：视为主动接管
                    markVoluntaryLocked(observer, session);
                    return;
                }
                attached = spectating != null && spectating.getUniqueId().equals(target.getUniqueId());
            }
            // 脱离状态：超过 detach 时限仍未恢复则重新附身
            if (attached) {
                session.detachedSince = -1;
                return;
            }
            if (session.detachedSince < 0) {
                session.detachedSince = now;
                return;
            }
            if (now - session.detachedSince < plugin.detachReattachMillis()) {
                return;
            }
            session.detachedSince = -1;
            if (target != null) {
                // 目标还在本服：直接重新附身回同一目标
                makeSpectating(observer);
                attachCamera(observer, target);
                session.attachGraceUntil = now + attachGraceMillis();
                observer.sendMessage(plugin.msg("lock-reattached"));
                return;
            }
            Player next = pickCandidate(session, Set.of(observerId));
            if (next != null && switchToLocked(session, next,
                    plugin.msg("detach-reattach", "%target%", next.getName()), "detach")) {
                return;
            }
            if (crossServer()) {
                // 本服没有可选玩家：经代理跨服选人（模式保持，随机模式沿用去重列表）
                String modeStr = session.mode == ObserverSession.Mode.RANDOM ? SpectorProtocol.MODE_RANDOM : SpectorProtocol.MODE_MANUAL;
                String used = usedCsv(session);
                endSessionKeepPairLocked(session);
                sendObserveAndArmTimeout(observer, modeStr, "*", used);
                return;
            }
            observer.sendMessage(plugin.msg("no-candidates"));
            stopSessionLocked(observerId, true);
        }
    }

    /** 观察者主动接管相机：该会话暂停相机控制直到重新执行 /spector */
    private void markVoluntaryLocked(Player observer, ObserverSession session) {
        if (session.voluntary) {
            return;
        }
        session.voluntary = true;
        observer.sendMessage(plugin.msg("voluntary-pause"));
        plugin.getLogger().info("观察者 " + observer.getName() + " 主动接管相机，插件暂停相机控制");
    }

    /**
     * PlayerGameModeChangeEvent 回调：观察者的游戏模式切换语义。
     * - 切到非旁观模式（生存/创造等）：视为主动退出，自动结束观察会话（保留用户选择的新模式）
     * - 切到旁观模式：仍在观察中，提示正常退出方式是 /spector off
     * - 插件自身发起的切换（pluginModeChanges 集合）不触发任何处理
     */
    public void onObserverGameModeChanging(UUID playerId, GameMode newMode) {
        if (pluginModeChanges.contains(playerId)) {
            return;
        }
        synchronized (lock) {
            ObserverSession session = sessions.get(playerId);
            if (session == null) {
                return;
            }
            Player observer = Bukkit.getPlayer(playerId);
            if (newMode == GameMode.SPECTATOR) {
                // voluntary 会话已是插件让出相机的状态，重复提示只会打扰
                if (observer != null && !session.voluntary) {
                    // 节流：10 秒内不重复提示，避免反复切换时刷屏
                    long now = System.currentTimeMillis();
                    if (now - session.lastSpectateHintAt > 10_000L) {
                        session.lastSpectateHintAt = now;
                        observer.sendMessage(plugin.msg("spectate-hint"));
                    }
                }
                return;
            }
            // 切到非旁观：自动退出观察（不还原 gamemode——用户已主动选择了新模式）
            plugin.getLogger().info("观察者切换游戏模式，自动退出观察: observer=" + observerIdName(playerId)
                    + " -> " + newMode);
            if (observer != null) {
                observer.sendMessage(plugin.msg("mode-exit"));
            }
            endSessionOnUserExitLocked(session);
        }
    }

    /** 用户主动退出路径：清理会话并注销代理，但不还原 gamemode（用户自己管理） */
    private void endSessionOnUserExitLocked(ObserverSession session) {
        session.previousGamemode = null;
        UUID observerId = session.observerId;
        stopSessionLocked(observerId, false);
    }

    private String observerIdName(UUID observerId) {
        Player player = Bukkit.getPlayer(observerId);
        return player != null ? player.getName() : observerId.toString();
    }

    /**
     * PlayerTeleportEvent(SPECTATE) 回调：锁定观察者输入。
     * Shift 退出附身、左键点击其他生物换绑视角都会触发 SPECTATE 传送——
     * 插件发起的会话（非主动接管）中，只允许"绑定到当前观察目标"的传送，其余一律取消。
     */
    public void onSpectateSwitch(org.bukkit.event.player.PlayerTeleportEvent event) {
        if (!plugin.lockSpectateEnabled()) {
            return;
        }
        synchronized (lock) {
            ObserverSession session = sessions.get(event.getPlayer().getUniqueId());
            if (session == null || session.voluntary || session.currentTargetId == null) {
                return;
            }
            Player target = Bukkit.getPlayer(session.currentTargetId);
            if (target == null) {
                return;
            }
            org.bukkit.Location to = event.getTo();
            if (to != null && to.getWorld() != null
                    && to.getWorld().equals(target.getWorld())
                    && to.toVector().distanceSquared(target.getLocation().toVector()) < 1.0) {
                return; // 绑定到当前观察目标，放行
            }
            event.setCancelled(true);
            event.getPlayer().sendMessage(plugin.msg("lock-cancelled"));
        }
    }

    /**
     * /spector next|pre：按玩家名字序在候选池中步进切换（无会话时从第一位开始观察）。
     * 显式指令 = 回到插件控制的意图：重置主动接管状态；
     * 若正处于掉线等待/裁决状态，则放弃该掉线玩家——本会话内不再自动切回、不再进候选池。
     */
    public void stepObservation(Player observer, boolean forward) {
        synchronized (lock) {
            ObserverSession session = sessions.get(observer.getUniqueId());
            if (session != null) {
                // 显式步进 = 回到插件控制的意图
                session.voluntary = false;
                // 放弃挂起的掉线等待/裁决：取消计时并加入本会话忽略名单
                abandonPendingLocked(observer, session, session.pendingReloginTarget, true);
                abandonPendingLocked(observer, session, session.pendingVerdictTarget, false);
            }
            List<Player> ordered = orderedCandidatesLocked(observer.getUniqueId(),
                    session != null ? session.ignoredTargets : Set.of());
            if (ordered.isEmpty()) {
                observer.sendMessage(plugin.msg("no-candidates"));
                return;
            }
            if (session == null) {
                // 无会话：从候选池第一位开始手动观察
                Player first = ordered.get(0);
                ObserverSession created = newSessionLocked(observer, ObserverSession.Mode.MANUAL);
                if (beginWatchingLocked(observer, created, first,
                        plugin.msg("observing", "%target%", first.getName()))) {
                    sendFeedbackLocked(observer, created, first, "start");
                } else {
                    observer.sendMessage(plugin.msg("no-candidates"));
                    stopSessionLocked(observer.getUniqueId(), true);
                }
                return;
            }
            int idx = -1;
            for (int i = 0; i < ordered.size(); i++) {
                if (ordered.get(i).getUniqueId().equals(session.currentTargetId)) {
                    idx = i;
                    break;
                }
            }
            if (idx < 0) {
                // 当前无目标（掉线等待等状态）：next 从第一位、pre 从最后一位开始
                idx = forward ? -1 : 1;
            }
            Player next = ordered.get(Math.floorMod(idx + (forward ? 1 : -1), ordered.size()));
            if (next.getUniqueId().equals(session.currentTargetId)) {
                // 候选池步进一圈仍回到自己：没有其他可观察玩家
                observer.sendMessage(plugin.msg("no-candidates"));
                return;
            }
            switchToLocked(session, next, plugin.msg("random-switch", "%target%", next.getName()), "manual");
        }
    }

    /** 显式步进时放弃挂起等待的玩家：取消计时、加入忽略名单（随机模式同时计入去重） */
    private void abandonPendingLocked(Player observer, ObserverSession session, UUID pending, boolean relogin) {
        if (pending == null) {
            return;
        }
        if (relogin) {
            cancelTask(session.reloginExpiryTask);
            session.reloginExpiryTask = null;
            session.pendingReloginTarget = null;
        } else {
            cancelTask(session.pendingVerdictTask);
            session.pendingVerdictTask = null;
            session.pendingVerdictTarget = null;
        }
        session.ignoredTargets.add(pending);
        // 双保险：RANDOM 模式同时计入去重名单（pickCandidate 已检查 ignoredTargets，
        // 冗余记录让 status 的"本轮已观察"计数与实际语义保持一致）
        if (session.mode == ObserverSession.Mode.RANDOM) {
            session.usedTargets.add(pending);
        }
        observer.sendMessage(plugin.msg("relogin-abandoned", "%target%", targetNameOf(pending)));
        plugin.getLogger().info("观察者手动切换，放弃等待重连的玩家: observer=" + session.observerId
                + " target=" + pending);
    }

    /** 候选池的确定性排序视图（含当前目标以便步进），排除豁免/观察者/本人/会话忽略名单 */
    private List<Player> orderedCandidatesLocked(UUID observerId, Set<UUID> ignored) {
        List<Player> candidates = new ArrayList<>();
        for (Player online : Bukkit.getOnlinePlayers()) {
            UUID id = online.getUniqueId();
            if (!id.equals(observerId) && !sessions.containsKey(id) && !plugin.isExcluded(online)
                    && !ignored.contains(id)) {
                candidates.add(online);
            }
        }
        candidates.sort(java.util.Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER));
        return candidates;
    }

    // ------------------------------------------------------------------
    // 切换 / 轮换 / 挂机
    // ------------------------------------------------------------------

    /** 切换某会话的观察对象；目标在切换瞬间退出时自动改选一次（须持有 lock） */
    private boolean switchToLocked(ObserverSession session, Player newTarget, String reasonMessage, String feedbackReasonKey) {
        Player observer = Bukkit.getPlayer(session.observerId);
        if (observer == null) {
            stopSessionLocked(session.observerId, false);
            return true;
        }
        if (session.mode == ObserverSession.Mode.RANDOM) {
            session.usedTargets.add(newTarget.getUniqueId());
        }
        releaseWatchLocked(session);
        if (beginWatchingLocked(observer, session, newTarget, reasonMessage)) {
            sendFeedbackLocked(observer, session, newTarget, feedbackReasonKey);
            return true;
        }
        // 目标在切换瞬间退出：改选一次
        Player alt = pickCandidate(session, Set.of(session.observerId, newTarget.getUniqueId()));
        if (alt != null && beginWatchingLocked(observer, session, alt,
                plugin.msg("observing", "%target%", alt.getName()))) {
            sendFeedbackLocked(observer, session, alt, feedbackReasonKey);
            return true;
        }
        observer.sendMessage(plugin.msg("no-candidates"));
        stopSessionLocked(session.observerId, true);
        return true;
    }

    /**
     * 切换画面反馈：Title + ActionBar（MiniMessage，占位符 %target%/%reason%/%mode%）。
     * 发送涉及网络 I/O，先在锁内取快照，再调度到观察者线程执行，避免持锁发包。
     */
    /** 切换画面反馈：渲染细节见 FeedbackRenderer（锁内只取快照，发送调度到观察者线程） */
    private void sendFeedbackLocked(Player observer, ObserverSession session, Player target, String reasonKey) {
        feedbackRenderer.send(observer, target.getName(), session.mode == ObserverSession.Mode.RANDOM, reasonKey);
    }

    /** 发送跨服观察请求并武装超时提示（代理端未响应时给用户明确反馈） */
    private void sendObserveAndArmTimeout(Player observer, String mode, String targetName, String usedCsv) {
        plugin.getLogger().info("发送跨服观察请求: observer=" + observer.getName()
                + " mode=" + mode + " target=" + targetName);
        // 全网随机选人时同时排除所有观察者（旁观中的管理员没有观察价值）
        String excludedCsv = composeExcludedCsv(usedCsv, sessions.keySet());
        velocityBridge.sendObserveRequest(observer, mode, targetName, excludedCsv);
        // 纪元：请求发出时该观察者必无会话；若 5 秒后仍无会话且纪元未变，则提示超时
        Long epoch = observeEpochs.get(observer.getUniqueId());
        Bukkit.getGlobalRegionScheduler().runDelayed(plugin, task -> {
            if (observeEpochs.get(observer.getUniqueId()) == epoch
                    && !sessions.containsKey(observer.getUniqueId()) && observer.isOnline()) {
                observer.sendMessage(plugin.msg("observing-timeout"));
            }
        }, 100L);
    }

    /** 目标挂机：通知所有正在观察该目标的会话，各自独立决策（目标区域线程） */
    private void afkTriggered(UUID targetId, String reasonKey, String... reasonArgs) {
        synchronized (lock) {
            Set<UUID> watchers = watchersByTarget.get(targetId);
            if (watchers == null || watchers.isEmpty()) {
                return;
            }
            String reason = plugin.reason(reasonKey, reasonArgs);
            recordAfkLocked(targetNameOf(targetId), reason);

            for (UUID observerId : List.copyOf(watchers)) {
                ObserverSession session = sessions.get(observerId);
                if (session == null || !targetId.equals(session.currentTargetId)) {
                    continue;
                }
                Player observer = Bukkit.getPlayer(observerId);
                if (observer == null) {
                    if (crossServer()) {
                        endSessionKeepPairLocked(session);
                    } else {
                        stopSessionLocked(observerId, false);
                    }
                    continue;
                }
                String noCandidateMsg = plugin.msg("afk-no-candidate",
                        "%target%", targetNameOf(targetId), "%reason%", reason);
                plugin.getLogger().info("观察对象 " + targetNameOf(targetId) + " 疑似挂机(" + reason + ")");

                if (session.mode == ObserverSession.Mode.MANUAL) {
                    Player next = pickCandidate(session, Set.of(targetId, observerId));
                    if (next == null && crossServer()) {
                        String used = usedCsv(session);
                        endSessionKeepPairLocked(session);
                        sendObserveAndArmTimeout(observer, SpectorProtocol.MODE_MANUAL, "*", used);
                        continue;
                    }
                    if (next == null) {
                        observer.sendMessage(noCandidateMsg);
                        continue;
                    }
                    switchToLocked(session, next, plugin.msg("afk-switch",
                            "%target%", targetNameOf(targetId), "%reason%", reason, "%newtarget%", next.getName()), "afk");
                } else {
                    session.usedTargets.add(targetId);
                    Player next = pickNextForRotation(session);
                    if (next == null && crossServer()) {
                        String used = usedCsv(session);
                        endSessionKeepPairLocked(session);
                        sendObserveAndArmTimeout(observer, SpectorProtocol.MODE_RANDOM, "*", used);
                        continue;
                    }
                    if (next == null) {
                        observer.sendMessage(noCandidateMsg);
                        continue;
                    }
                    switchToLocked(session, next, plugin.msg("random-switch-afk",
                            "%target%", targetNameOf(targetId), "%reason%", reason, "%newtarget%", next.getName()), "afk");
                }
            }
        }
    }

    private void recordAfkLocked(String playerName, String reason) {
        recentAfk.addLast(new AfkRecord(playerName, reason, System.currentTimeMillis()));
        while (recentAfk.size() > 20) {
            recentAfk.removeFirst();
        }
    }

    /** /spector afk：近期挂机判定报告 */
    public List<String> afkReportLines() {
        synchronized (lock) {
            if (recentAfk.isEmpty()) {
                return List.of(plugin.msg("afk-list-empty"));
            }
            List<String> lines = new ArrayList<>();
            lines.add(plugin.msg("afk-list-header"));
            long now = System.currentTimeMillis();
            for (AfkRecord record : recentAfk) {
                lines.add(plugin.msg("afk-list-entry",
                        "%player%", record.playerName(),
                        "%reason%", record.reason(),
                        "%time%", String.valueOf(Math.max(0, (now - record.at()) / 1000))));
            }
            return lines;
        }
    }

    /** RANDOM 轮换任务回调（全局线程，按会话独立轮换） */
    private void rotate(UUID observerId) {
        synchronized (lock) {
            ObserverSession session = sessions.get(observerId);
            if (session == null || session.mode != ObserverSession.Mode.RANDOM) {
                return;
            }
            if (session.currentTargetId == null) {
                // 等待重连等休眠状态：本轮跳过
                return;
            }
            Player observer = Bukkit.getPlayer(observerId);
            if (observer == null) {
                if (crossServer()) {
                    endSessionKeepPairLocked(session);
                } else {
                    stopSessionLocked(observerId, false);
                }
                return;
            }
            session.usedTargets.add(session.currentTargetId);
            Player next = pickNextForRotation(session);
            if (next == null && crossServer()) {
                // 本服已无可选玩家：经代理跨服选人
                String used = usedCsv(session);
                endSessionKeepPairLocked(session);
                sendObserveAndArmTimeout(observer, SpectorProtocol.MODE_RANDOM, "*", used);
                return;
            }
            if (next == null) {
                return;
            }
            switchToLocked(session, next, plugin.msg("random-switch", "%target%", next.getName()), "rotate");
        }
    }

    private void startRotationTaskLocked(ObserverSession session) {
        cancelTask(session.rotationTask);
        session.rotationTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin,
                task -> rotate(session.observerId), plugin.randomIntervalTicks(), plugin.randomIntervalTicks());
    }

    private Player pickNextForRotation(ObserverSession session) {
        Player next = pickCandidate(session, session.usedTargets);
        if (next == null && !session.usedTargets.isEmpty()) {
            // 所有本服在线玩家都观察过一轮，清空记录重新开始（满足“轮完之前不重复”）
            session.usedTargets.clear();
            next = pickCandidate(session, Set.of());
        }
        return next;
    }

    /**
     * 候选玩家：排除观察者本人、排除集合、会话内忽略名单（被显式放弃的掉线玩家）、
     * 豁免玩家（NPC/名单/权限）及其他观察者。
     * 注：PermissibleBase 底层为并发结构，权限检查在全局调度线程执行是主流权限插件
     * （LuckPerms 等）明确支持的场景。
     */
    private Player pickCandidate(ObserverSession session, Set<UUID> excluded) {
        UUID observerId = session.observerId;
        List<Player> candidates = new ArrayList<>();
        for (Player online : Bukkit.getOnlinePlayers()) {
            UUID id = online.getUniqueId();
            if (!id.equals(observerId) && !excluded.contains(id) && !session.ignoredTargets.contains(id)
                    && !sessions.containsKey(id) && !plugin.isExcluded(online)) {
                candidates.add(online);
            }
        }
        if (candidates.isEmpty()) {
            return null;
        }
        return candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
    }

    /** 组合跨服选人的排除列表：本轮去重名单 + 所有观察者（public 以便单测） */
    public static String composeExcludedCsv(String usedCsv, Set<UUID> observerIds) {
        StringBuilder csv = new StringBuilder(usedCsv == null ? "" : usedCsv);
        for (UUID id : observerIds) {
            if (id == null) {
                continue;
            }
            if (csv.length() > 0) {
                csv.append(',');
            }
            csv.append(id);
        }
        return csv.toString();
    }

    private String usedCsv(ObserverSession session) {
        StringBuilder csv = new StringBuilder();
        for (UUID id : session.usedTargets) {
            if (csv.length() > 0) {
                csv.append(',');
            }
            csv.append(id);
        }
        return csv.toString();
    }

    private String targetNameOf(UUID targetId) {
        Player target = Bukkit.getPlayer(targetId);
        return target != null ? target.getName() : targetId.toString();
    }

    private void sendPair(Player via, ObserverSession session) {
        if (velocityBridge != null && session.currentTargetId != null) {
            velocityBridge.sendPair(via, session.currentTargetId,
                    session.mode == ObserverSession.Mode.RANDOM ? SpectorProtocol.MODE_RANDOM : SpectorProtocol.MODE_MANUAL,
                    usedCsv(session));
        }
    }

    // ------------------------------------------------------------------
    // 采样（按目标共享，与观察者数量无关）
    // ------------------------------------------------------------------

    /** 周期采样：读取位置/视角/容器状态并判定挂机（目标区域线程） */
    /** 周期采样：读取位置/视角，交给规则机判定（目标区域线程） */
    private void sampleTarget(Player target) {
        UUID targetId = target.getUniqueId();
        ActivityState state = states.computeIfAbsent(targetId, id -> new ActivityState());
        Location loc = target.getLocation();
        AfkRuleEvaluator.Result result = afkEvaluator.evaluate(state,
                loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), loc.getPitch(),
                System.currentTimeMillis(), CheckSettings.from(plugin));
        if (result.verdict() != AfkRuleEvaluator.Verdict.NONE) {
            afkTriggered(targetId, result.reasonKey(), "%seconds%", result.secondsArg());
        }
    }

    // ------------------------------------------------------------------
    // 进出服 / 死亡 / 复活 / 移交 / 重连
    // ------------------------------------------------------------------

    /** 玩家退出时清理其状态 */
    public void handleQuit(UUID playerId) {
        synchronized (lock) {
            states.remove(playerId);
            ObserverSession asObserver = sessions.get(playerId);
            if (asObserver != null) {
                if (crossServer()) {
                    // 可能是观察者跨服离开；若其真正退出网络，代理会移除会话
                    endSessionKeepPairLocked(asObserver);
                } else {
                    stopSessionLocked(playerId, false);
                }
                return;
            }
            Set<UUID> watchers = watchersByTarget.get(playerId);
            if (watchers == null || watchers.isEmpty()) {
                return;
            }
            for (UUID observerId : List.copyOf(watchers)) {
                ObserverSession session = sessions.get(observerId);
                if (session == null) {
                    continue;
                }
                if (crossServer()) {
                    // 跨服退出不能立即桥接（否则 PAIR 会覆盖代理侧映射、破坏跟随）：
                    // 进入"等待裁决"状态，HANDOFF=跟随重建，TARGET_GONE=按掉线处理，
                    // 重连桥接开启时宽限期内未裁决则兜底桥接，否则结束会话
                    markVerdictAwaitLocked(session, playerId);
                } else if (reloginActive()) {
                    markReloginAwaitLocked(session, playerId);
                } else {
                    stopSessionLocked(observerId, false);
                }
            }
        }
    }

    /**
     * 跨服退出：进入"等待裁决"状态。只本地脱离、不更新代理映射；
     * HANDOFF 到来则重建跟随，TARGET_GONE 到来则按掉线处理，
     * 宽限期内代理无任何裁决（附属缺失/掉线）则兜底按掉线处理。
     */
    private void markVerdictAwaitLocked(ObserverSession session, UUID targetId) {
        releaseWatchLocked(session);
        cancelTask(session.watchdogTask);
        cancelTask(session.victimExpiryTask);
        session.watchdogTask = null;
        session.victimExpiryTask = null;
        session.detachedSince = -1;
        session.lastVictim = null;
        session.pendingVerdictTarget = targetId;
        long grace = plugin.reloginGraceMillis();
        UUID observerId = session.observerId;
        session.pendingVerdictTask = Bukkit.getGlobalRegionScheduler().runDelayed(plugin, task -> {
            synchronized (lock) {
                ObserverSession current = sessions.get(observerId);
                if (current != null && targetId.equals(current.pendingVerdictTarget)) {
                    if (reloginActive()) {
                        plugin.getLogger().info("代理未在宽限期内裁决目标去向，按掉线处理: observer=" + observerId);
                        markReloginAwaitLocked(current, targetId);
                    } else {
                        plugin.getLogger().info("代理未在宽限期内裁决目标去向且重连桥接未开启，结束会话: observer=" + observerId);
                        stopSessionLocked(observerId, true);
                    }
                }
            }
        }, Math.max(1, grace / 50));
        plugin.getLogger().info("观察对象离开本服，等待代理裁决去向: observer=" + observerId
                + " target=" + targetNameOf(targetId));
    }

    /**
     * 目标退出：会话休眠等待其重连（宽限期内），但直播不空窗——
     * 立即跳到下一个玩家继续观察；掉线者在宽限期内重连则切回，
     * 桥接期间临时观察的玩家会从本轮去重名单移除、重新可被随机选中。
     * 跨服场景由 HANDOFF/TARGET_GONE 进一步裁决。
     */
    private void markReloginAwaitLocked(ObserverSession session, UUID targetId) {
        // 若此前处于"等待裁决"状态，先清除
        cancelTask(session.pendingVerdictTask);
        session.pendingVerdictTask = null;
        session.pendingVerdictTarget = null;
        releaseWatchLocked(session);
        cancelTask(session.watchdogTask);
        cancelTask(session.victimExpiryTask);
        session.watchdogTask = null;
        session.victimExpiryTask = null;
        session.detachedSince = -1;
        session.lastVictim = null;
        session.pendingReloginTarget = targetId;
        long grace = plugin.reloginGraceMillis();
        UUID observerId = session.observerId;
        session.reloginExpiryTask = Bukkit.getGlobalRegionScheduler().runDelayed(plugin, task -> {
            synchronized (lock) {
                ObserverSession current = sessions.get(observerId);
                if (current != null && targetId.equals(current.pendingReloginTarget)) {
                    // 约定：pendingReloginTarget 仅在本回调与显式 next/pre 的 abandon 路径清空；
                    // abandon 会先取消本任务再清标记，因此此处不会与手动切换竞争
                    current.pendingReloginTarget = null;
                    current.reloginExpiryTask = null;
                    if (current.currentTargetId != null) {
                        // 宽限到期不掐直播：掉线者未归，继续观察当前桥接目标
                        plugin.getLogger().info("观察对象未在宽限期内重连，继续观察当前目标: observer=" + observerId);
                        Player obs = Bukkit.getPlayer(observerId);
                        if (obs != null) {
                            obs.sendMessage(plugin.msg("relogin-expired", "%target%", targetNameOf(targetId)));
                        }
                    } else {
                        plugin.getLogger().info("观察对象未在宽限期内重连且无桥接目标，结束会话: observer=" + observerId);
                        stopSessionLocked(observerId, true);
                    }
                }
            }
        }, Math.max(1, grace / 50));
        plugin.getLogger().info("观察对象退出，进入重连宽限期: observer=" + observerId
                + " target=" + targetNameOf(targetId));

        // 直播不空窗：立即跳到下一个玩家（RANDOM 模式沿用去重列表；轮换任务保持运行）
        Player observer = Bukkit.getPlayer(observerId);
        if (observer == null) {
            // 观察者也不在本服：跨服场景由代理维持会话，否则结束
            if (!crossServer()) {
                stopSessionLocked(observerId, false);
            }
            return;
        }
        Player next = session.mode == ObserverSession.Mode.RANDOM
                ? pickNextForRotation(session)
                : pickCandidate(session, Set.of(targetId, observerId));
        if (next != null) {
            switchToLocked(session, next, plugin.msg("relogin-switch",
                    "%target%", targetNameOf(targetId), "%newtarget%", next.getName()), "bridge");
        } else {
            // 无人可跳：保持等待重连（宽限期短，不做跨服请求）
            observer.sendMessage(plugin.msg("no-candidates"));
        }
    }

    /** 观察者跨服到达本服：恢复旁观模式并附身到目标 */
    public void handleJoin(UUID playerId) {
        synchronized (lock) {
            // 1) 目标在重连宽限期内回来：恢复所有正在等待该目标的会话（多观察者多播）
            boolean resumed = false;
            for (ObserverSession session : List.copyOf(sessions.values())) {
                if (playerId.equals(session.pendingReloginTarget)) {
                    resumeReloginLocked(session, playerId);
                    resumed = true;
                }
            }
            if (resumed) {
                // 该玩家同时自身也是观察者（被别人观察的管理员）时，继续走下方附身恢复
            }
            // 2) 观察者跨服到达本服：恢复旁观模式并附身到目标
            ObserverSession session = sessions.get(playerId);
            if (session == null || session.currentTargetId == null || session.voluntary) {
                return;
            }
            scheduleAttachLocked(session);
        }
    }

    /** HANDOFF：观察者到达本服，观察对象为本服玩家，重建该观察者的会话 */
    public void resumeHandoff(UUID observerId, Player target, String modeStr, List<UUID> used) {
        synchronized (lock) {
            // 该观察者已有本地会话则替换（跨服移交以代理侧为准）
            ObserverSession old = sessions.remove(observerId);
            if (old != null) {
                teardownSessionTasksLocked(old);
                releaseWatchLocked(old);
            }
            ObserverSession session = new ObserverSession(observerId);
            session.mode = SpectorProtocol.MODE_RANDOM.equals(modeStr) ? ObserverSession.Mode.RANDOM : ObserverSession.Mode.MANUAL;
            // 观察者刚跨服到达，原 gamemode 未知，结束时不再还原
            session.previousGamemode = null;
            session.voluntary = false;
            session.currentTargetId = target.getUniqueId();
            sessions.put(observerId, session);
            observeEpochs.put(observerId, System.nanoTime());
            session.usedTargets.addAll(used);
            addWatcherLocked(session.currentTargetId, observerId);
            acquireMonitorLocked(session.currentTargetId);
            if (session.mode == ObserverSession.Mode.RANDOM) {
                startRotationTaskLocked(session);
            }
            // 用目标玩家连接向代理回报本服 server-id 与最新去重列表
            sendPair(target, session);
            plugin.getLogger().info("跨服会话已移交到本服: observer=" + observerId + " target=" + target.getName());
            // 消息经本服连接到达时观察者必然已在本服（join 事件早于插件消息），
            // 因此附身由本方法自行调度，handleJoin 仅作兜底
            scheduleAttachLocked(session);
        }
    }

    /** 延迟 2 tick 执行旁观切换与附身（join/HANDOFF 场景共用），并复核会话归属 */
    private void scheduleAttachLocked(ObserverSession session) {
        UUID observerId = session.observerId;
        UUID expectedTarget = session.currentTargetId;
        if (expectedTarget == null) {
            return;
        }
        Player observer = Bukkit.getPlayer(observerId);
        if (observer == null) {
            return;
        }
        observer.getScheduler().runDelayed(plugin, task -> {
            synchronized (lock) {
                ObserverSession current = sessions.get(observerId);
                if (current == null || current.voluntary
                        || !observerId.equals(current.observerId)
                        || current.currentTargetId == null
                        || !current.currentTargetId.equals(expectedTarget)) {
                    return;
                }
                Player obs = Bukkit.getPlayer(observerId);
                Player tgt = Bukkit.getPlayer(expectedTarget);
                if (obs == null || tgt == null) {
                    return;
                }
                makeSpectating(obs);
                attachCamera(obs, tgt);
                current.attachGraceUntil = System.currentTimeMillis() + attachGraceMillis();
                startWatchdogLocked(current, obs);
                obs.sendMessage(plugin.msg("handoff-followed", "%target%", tgt.getName()));
                sendFeedbackLocked(obs, current, tgt, "handoff");
            }
        }, () -> { /* 观察者退出时任务自动结束 */ }, 2L);
    }

    /** 目标在重连宽限期内回来：切回其视角，桥接目标重新回到随机候选池 */
    private void resumeReloginLocked(ObserverSession session, UUID targetId) {
        cancelTask(session.reloginExpiryTask);
        session.reloginExpiryTask = null;
        session.pendingReloginTarget = null;
        Player target = Bukkit.getPlayer(targetId);
        Player observer = Bukkit.getPlayer(session.observerId);
        if (target == null) {
            return;
        }
        if (observer == null) {
            // 观察者不在本服：非跨服直接结束（避免僵尸会话）；跨服场景其退出时已本地脱离
            if (!crossServer()) {
                stopSessionLocked(session.observerId, false);
            } else {
                endSessionKeepPairLocked(session);
            }
            return;
        }
        // 桥接期间临时观察的玩家不占用本轮去重名额，重新可被随机选中
        UUID bridged = session.currentTargetId;
        if (bridged != null) {
            session.usedTargets.remove(bridged);
        }
        releaseWatchLocked(session);
        plugin.getLogger().info("观察对象在宽限期内重连，恢复会话: observer=" + session.observerId);
        beginWatchingLocked(observer, session, target,
                plugin.msg("relogin-reattach", "%target%", target.getName()));
    }

    /** TARGET_GONE：观察对象退出 Velocity 网络；等待裁决/宽限期内保留会话等待重连 */
    public void stopIfPair(UUID observer, UUID target) {
        synchronized (lock) {
            ObserverSession session = sessions.get(observer);
            if (session == null) {
                return;
            }
            boolean verdictMatch = target.equals(session.pendingVerdictTarget);
            boolean watching = target.equals(session.currentTargetId);
            if (!verdictMatch && !watching) {
                return;
            }
            if (verdictMatch) {
                // 跨服裁决结果：目标确实退出了网络，按掉线处理
                cancelTask(session.pendingVerdictTask);
                session.pendingVerdictTask = null;
                session.pendingVerdictTarget = null;
            }
            if (reloginActive()) {
                markReloginAwaitLocked(session, target);
            } else {
                plugin.getLogger().info("观察对象已退出网络，结束观察会话");
                stopSessionLocked(observer, true);
            }
        }
    }

    /** OBSERVE_FAIL：代理找不到可观察/可跨服选出的玩家 */
    public void onObserveFailed(UUID observer, String modeStr) {
        Player player = Bukkit.getPlayer(observer);
        if (player == null) {
            return;
        }
        player.sendMessage(plugin.msg(SpectorProtocol.MODE_RANDOM.equals(modeStr) ? "no-candidates" : "target-not-found"));
    }

    /** /spector status：会话快照 */
    public List<String> statusLines(UUID observerId) {
        synchronized (lock) {
            ObserverSession session = sessions.get(observerId);
            if (session == null) {
                return List.of(plugin.msg("status-idle"));
            }
            String modeText = session.mode == ObserverSession.Mode.RANDOM ? "随机轮换" : "手动";
            String targetName = targetNameOf(session.currentTargetId);
            int coWatchers = 0;
            if (session.currentTargetId != null) {
                Set<UUID> watchers = watchersByTarget.get(session.currentTargetId);
                coWatchers = watchers != null ? watchers.size() - 1 : 0;
            }
            List<String> lines = new ArrayList<>();
            lines.add(plugin.msg("status",
                    "%mode%", modeText,
                    "%target%", targetName,
                    "%used%", String.valueOf(session.usedTargets.size()),
                    "%cowatchers%", String.valueOf(coWatchers),
                    "%voluntary%", session.voluntary ? "是（插件已暂停相机控制）" : "否"));
            if (session.pendingReloginTarget != null) {
                lines.add(plugin.msg("status-relogin", "%target%", targetNameOf(session.pendingReloginTarget)));
            }
            if (session.pendingVerdictTarget != null) {
                lines.add(plugin.msg("status-verdict", "%target%", targetNameOf(session.pendingVerdictTarget)));
            }
            return lines;
        }
    }

    /** 被观察玩家死亡：通知所有观察该目标的会话，各自独立切换/记录复活附身 */
    public void handleDeath(Player target) {
        synchronized (lock) {
            UUID targetId = target.getUniqueId();
            Set<UUID> watchers = watchersByTarget.get(targetId);
            if (watchers == null || watchers.isEmpty()) {
                return;
            }
            for (UUID observerId : List.copyOf(watchers)) {
                ObserverSession session = sessions.get(observerId);
                if (session == null || !targetId.equals(session.currentTargetId)) {
                    continue;
                }
                Player observer = Bukkit.getPlayer(observerId);
                if (observer == null) {
                    if (!crossServer()) {
                        stopSessionLocked(observerId, false);
                    }
                    continue;
                }
                if (plugin.respawnReattach()) {
                    session.lastVictim = targetId;
                    session.victimDeathTime = System.currentTimeMillis();
                    cancelTask(session.victimExpiryTask);
                    // 超过复活窗口后不再附身回去
                    session.victimExpiryTask = Bukkit.getGlobalRegionScheduler().runDelayed(plugin,
                            task -> {
                                synchronized (lock) {
                                    if (targetId.equals(session.lastVictim)) {
                                        session.lastVictim = null;
                                    }
                                }
                            }, Math.max(1, plugin.respawnWindowMillis() / 50));
                }

                Player next;
                if (session.mode == ObserverSession.Mode.MANUAL) {
                    next = pickCandidate(session, Set.of(targetId, observerId));
                } else {
                    session.usedTargets.add(targetId);
                    next = pickNextForRotation(session);
                }
                if (next == null) {
                    observer.sendMessage(plugin.msg("no-candidates"));
                    continue;
                }
                switchToLocked(session, next, plugin.msg("victim-switch",
                        "%target%", target.getName(), "%newtarget%", next.getName()), "victim");
            }
        }
    }

    /** 死亡的观察对象在复活窗口内复活：立即附身回去 */
    public void handleRespawn(UUID playerId) {
        synchronized (lock) {
            for (ObserverSession session : List.copyOf(sessions.values())) {
                if (!playerId.equals(session.lastVictim)) {
                    continue;
                }
                session.lastVictim = null;
                cancelTask(session.victimExpiryTask);
                session.victimExpiryTask = null;
                if (System.currentTimeMillis() - session.victimDeathTime > plugin.respawnWindowMillis()) {
                    continue;
                }
                Player back = Bukkit.getPlayer(playerId);
                Player observer = Bukkit.getPlayer(session.observerId);
                if (back == null) {
                    continue;
                }
                if (observer == null) {
                    if (!crossServer()) {
                        stopSessionLocked(session.observerId, false);
                    }
                    continue;
                }
                switchToLocked(session, back, plugin.msg("victim-reattach", "%target%", back.getName()), "relogin");
            }
        }
    }
}
