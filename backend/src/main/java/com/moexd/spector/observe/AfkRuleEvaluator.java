package com.moexd.spector.observe;

/**
 * 挂机判定规则机（纯逻辑，不触碰 Bukkit API，可单测）。
 *
 * 每次采样传入目标当前位置/视角与时间戳，内部更新 {@link ActivityState}
 * 并返回判定结果；四条规则的优先级：
 *   2 容器（视角在动视为操作，重置计时）> 3 连点器 > 4 打字（降级信号） > 1 原地静止。
 */
final class AfkRuleEvaluator {

    enum Verdict { NONE, STATIONARY, CONTAINER, AUTOCLICKER, TYPING }

    /** 判定结果：verdict + lang 的 reason key + %seconds% 参数（仅时长类规则携带） */
    record Result(Verdict verdict, String reasonKey, String secondsArg) {
        static Result none() {
            return new Result(Verdict.NONE, null, null);
        }
    }

    /** @return 本次采样的判定；同时更新 state 的滑动窗口与计时 */
    Result evaluate(ActivityState state, double x, double y, double z, float yaw, float pitch, long now,
                    CheckSettings cfg) {
        boolean camMoved = Math.abs(state.yaw - yaw) > 0.01f || Math.abs(state.pitch - pitch) > 0.01f;
        boolean posMoved = state.hasPosition
                && (Math.abs(state.x - x) > 0.01 || Math.abs(state.y - y) > 0.01 || Math.abs(state.z - z) > 0.01);
        state.x = x;
        state.y = y;
        state.z = z;
        state.yaw = yaw;
        state.pitch = pitch;
        state.hasPosition = true;

        // 活跃判定：位置或视角任一移动即刷新计时（供规则 1）。
        // 连点器窗口只在视角移动时清零——规则 3 的前提就是"位置在动"，
        // 若位置移动也清零，streak 永远无法累积（历史上的预存 bug，单测捕获）。
        if (camMoved || posMoved) {
            state.lastActive = now;
        }
        if (camMoved) {
            state.autoclickerStreak = 0;
            state.windowActions = 0;
        }

        // 规则 2：容器/背包界面打开超过阈值（视角在动视为仍在操作，重置计时）
        if (state.inContainer) {
            if (camMoved) {
                state.containerSince = now;
            } else if (state.containerSince > 0 && now - state.containerSince > cfg.containerMillis()) {
                return new Result(Verdict.CONTAINER, "container", String.valueOf(cfg.containerSeconds()));
            }
            return Result.none();
        }

        // 规则 3：位置移动 + 视角完全冻结 + 持续同一动作（连点器）
        int actions = state.actionCounter.getAndSet(0);
        if (posMoved && !camMoved && actions > 0) {
            state.autoclickerStreak++;
            state.windowActions += actions;
            if (state.autoclickerStreak >= cfg.autoclickerStreak()
                    && state.windowActions >= cfg.autoclickerMinActions()) {
                return new Result(Verdict.AUTOCLICKER, "autoclicker", null);
            }
        }

        // 规则 4：打字挂机（降级信号）——原版客户端不发送键盘/聊天框状态，
        // 以最近一条聊天消息作为"打字中"信号；信号有效期内持续静止超过 typing 时限则切换。
        // 该规则优先于规则 1（其时限通常更短），聊天信号窗口内规则 1 不生效。
        if (cfg.chatEnabled() && cfg.chatWindowMillis() > 0
                && state.lastChatAt > 0 && now - state.lastChatAt < cfg.chatWindowMillis()) {
            if (now - state.lastActive > cfg.typingIdleMillis()) {
                return new Result(Verdict.TYPING, "typing", String.valueOf(cfg.typingIdleSeconds()));
            }
            return Result.none();
        }

        // 规则 1：原地静止（无位移、无视角变化、无容器）超过阈值
        if (now - state.lastActive > cfg.stationaryMillis()) {
            return new Result(Verdict.STATIONARY, "stationary", String.valueOf(cfg.stationaryMillis() / 1000));
        }
        return Result.none();
    }
}
