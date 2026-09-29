package com.moexd.spector.observe;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 单个被观察玩家的活动状态。
 * 采样字段由目标区域线程读写；inContainer/containerSince/lastActive 会被事件线程触碰，
 * 因此标记 volatile；计数器供事件线程原子累加。
 */
final class ActivityState {

    volatile long lastActive = System.currentTimeMillis();

    double x, y, z;
    float yaw, pitch;
    boolean hasPosition;

    volatile boolean inContainer;
    volatile long containerSince = -1;

    /** 最近一次发出聊天消息的时刻；-1 表示从未发出（作为“打字中”的降级信号） */
    volatile long lastChatAt = -1;

    /** 连点器特征：连续“位移+视角冻结+重复动作”的采样次数与窗口内动作累计 */
    int autoclickerStreak;
    int windowActions;

    /** 挥手/交互计数，由事件监听线程累加，采样线程取走并清零 */
    final AtomicInteger actionCounter = new AtomicInteger();
}
