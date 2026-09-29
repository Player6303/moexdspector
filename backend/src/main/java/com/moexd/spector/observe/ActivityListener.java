package com.moexd.spector.observe;

import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EquipmentSlot;

/**
 * 活动事件监听：为容器计时、连点器特征与活跃重置提供信号。
 * 事件可能来自不同区域线程（聊天为异步线程），因此只做无锁的原子更新。
 */
public final class ActivityListener implements Listener {

    private final ObservationManager manager;

    public ActivityListener(ObservationManager manager) {
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!manager.isRunning()) {
            return;
        }
        manager.stateFor(event.getPlayer().getUniqueId()).ifPresent(state -> {
            state.inContainer = true;
            state.containerSince = System.currentTimeMillis();
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!manager.isRunning()) {
            return;
        }
        manager.stateFor(event.getPlayer().getUniqueId()).ifPresent(state -> {
            state.inContainer = false;
            state.containerSince = -1;
            state.lastActive = System.currentTimeMillis();
        });
    }

    /** 容器或自身背包内点击均视为活跃 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!manager.isRunning()) {
            return;
        }
        manager.stateFor(event.getWhoClicked().getUniqueId())
                .ifPresent(state -> state.lastActive = System.currentTimeMillis());
    }

    /** 聊天视为活跃并记录“打字中”降级信号（异步线程，仅原子更新时间戳） */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        if (!manager.isRunning()) {
            return;
        }
        manager.stateFor(event.getPlayer().getUniqueId()).ifPresent(state -> {
            state.lastActive = System.currentTimeMillis();
            state.lastChatAt = state.lastActive;
        });
    }

    /** 执行指令视为活跃 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!manager.isRunning()) {
            return;
        }
        manager.stateFor(event.getPlayer().getUniqueId())
                .ifPresent(state -> state.lastActive = System.currentTimeMillis());
    }

    /** 挥手：连点器的主要信号 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAnimation(PlayerAnimationEvent event) {
        if (!manager.isRunning()) {
            return;
        }
        manager.stateFor(event.getPlayer().getUniqueId()).ifPresent(state -> {
            if (event.getAnimationType() == PlayerAnimationType.ARM_SWING) {
                state.actionCounter.incrementAndGet();
            }
        });
    }

    /** 主手交互：同样计入动作（副手事件跳过，避免双计） */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (!manager.isRunning() || event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        manager.stateFor(event.getPlayer().getUniqueId())
                .ifPresent(state -> state.actionCounter.incrementAndGet());
    }

    /** 观察者切换游戏模式：切非旁观=自动退出观察，切旁观=提示用 /spector off 退出 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        manager.onObserverGameModeChanging(event.getPlayer().getUniqueId(), event.getNewGameMode());
    }

    /** Shift 退出附身/左键切换附身对象 = SPECTATE 传送；插件会话中锁定（可配置） */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpectateTeleport(PlayerTeleportEvent event) {
        if (event.getCause() == PlayerTeleportEvent.TeleportCause.SPECTATE) {
            manager.onSpectateSwitch(event);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        manager.handleQuit(event.getPlayer().getUniqueId());
    }

    /** 观察者经 Velocity 跨服到达本服时恢复旁观与附身 */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        manager.handleJoin(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        manager.handleDeath(event.getEntity());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        manager.handleRespawn(event.getPlayer().getUniqueId());
    }
}
