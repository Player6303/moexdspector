package com.moexd.spector.observe;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.GameMode;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 单个观察者的会话数据。字段由 {@link ObservationManager} 在其锁内读写。
 */
final class ObserverSession {

    enum Mode { MANUAL, RANDOM }

    final UUID observerId;
    Mode mode;
    UUID currentTargetId;
    /** 会话建立时观察者的 gamemode，结束时还原；原本就是旁观则为 null */
    GameMode previousGamemode;
    /** 观察者主动接管相机（自行进旁观/旁观菜单选人），插件暂停相机控制 */
    boolean voluntary;
    long detachedSince = -1;
    long attachGraceUntil;
    /** RANDOM 模式本轮已观察过的玩家，轮完全部后清空重开 */
    final Set<UUID> usedTargets = new HashSet<>();
    /** 死亡切走后等待复活的观察对象 */
    UUID lastVictim;
    long victimDeathTime;
    ScheduledTask victimExpiryTask;
    ScheduledTask rotationTask;
    ScheduledTask watchdogTask;
    /** 目标退出网络后等待其重连的宽限状态（非 null 表示正在等待） */
    UUID pendingReloginTarget;
    ScheduledTask reloginExpiryTask;
    /** 跨服退出后等待代理裁决（HANDOFF=跟随 / TARGET_GONE=掉线）的目标；宽限期内未裁决则按掉线处理 */
    UUID pendingVerdictTarget;
    ScheduledTask pendingVerdictTask;
    /** 本会话内被管理员显式放弃的玩家（如掉线等待期间执行 next/pre）：不再自动切回、不再进候选池 */
    final Set<UUID> ignoredTargets = new HashSet<>();
    /** spectate-hint 上次提示时刻（节流用） */
    long lastSpectateHintAt;

    ObserverSession(UUID observerId) {
        this.observerId = observerId;
    }
}
