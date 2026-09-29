package com.moexd.spector.observe;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 挂机规则机单测：四条规则的触发、重置与优先级。
 * 全程无 Bukkit 依赖——这正是把规则机抽成纯逻辑类的意义。
 */
class AfkRuleEvaluatorTest {

    private final AfkRuleEvaluator evaluator = new AfkRuleEvaluator();

    /** 固定阈值：静止 15s / 容器 30s / 连点 5 次×15 动作 / 打字 10s、信号窗口 60s */
    private static CheckSettings cfg() {
        return new CheckSettings(15_000, 30_000, 30, 5, 15,
                true, 60_000, 10_000, 10);
    }

    private static CheckSettings chatDisabled() {
        return new CheckSettings(15_000, 30_000, 30, 5, 15,
                false, 60_000, 10_000, 10);
    }

    // --------------------------------------------------------------- 规则 1

    @Test
    void stationaryTriggersAfterThreshold() {
        ActivityState state = new ActivityState();
        long t0 = System.currentTimeMillis();
        // 第一次采样建立基准
        evaluator.evaluate(state, 0, 64, 0, 0f, 0f, t0, cfg());
        // 静止 14.9s：未到阈值
        var r = evaluator.evaluate(state, 0, 64, 0, 0f, 0f, t0 + 14_900, cfg());
        assertEquals(AfkRuleEvaluator.Verdict.NONE, r.verdict());
        // 静止 15.1s：触发
        r = evaluator.evaluate(state, 0, 64, 0, 0f, 0f, t0 + 15_100, cfg());
        assertEquals(AfkRuleEvaluator.Verdict.STATIONARY, r.verdict());
        assertEquals("stationary", r.reasonKey());
        assertEquals("15", r.secondsArg());
    }

    @Test
    void movementResetsStationaryTimer() {
        ActivityState state = new ActivityState();
        long t0 = System.currentTimeMillis();
        evaluator.evaluate(state, 0, 64, 0, 0f, 0f, t0, cfg());
        // 位置移动重置计时
        evaluator.evaluate(state, 0.5, 64, 0, 0f, 0f, t0 + 14_000, cfg());
        var r = evaluator.evaluate(state, 0.5, 64, 0, 0f, 0f, t0 + 14_000 + 14_900, cfg());
        assertEquals(AfkRuleEvaluator.Verdict.NONE, r.verdict());
        // 视角移动同样重置
        evaluator.evaluate(state, 0.5, 64, 0, 5f, 0f, t0 + 30_000, cfg());
        r = evaluator.evaluate(state, 0.5, 64, 0, 5f, 0f, t0 + 30_000 + 14_900, cfg());
        assertEquals(AfkRuleEvaluator.Verdict.NONE, r.verdict());
    }

    // --------------------------------------------------------------- 规则 2

    @Test
    void containerTriggersOnlyWhenCameraFrozen() {
        ActivityState state = new ActivityState();
        long t0 = System.currentTimeMillis();
        evaluator.evaluate(state, 0, 64, 0, 0f, 0f, t0, cfg());
        state.inContainer = true;
        state.containerSince = t0;
        // 容器内视角移动：计时重置
        var r = evaluator.evaluate(state, 0, 64, 0, 10f, 0f, t0 + 40_000, cfg());
        assertEquals(AfkRuleEvaluator.Verdict.NONE, r.verdict());
        assertEquals(t0 + 40_000, state.containerSince);
        // 之后视角冻结 30+ 秒：触发
        r = evaluator.evaluate(state, 0, 64, 0, 10f, 0f, t0 + 40_000 + 30_100, cfg());
        assertEquals(AfkRuleEvaluator.Verdict.CONTAINER, r.verdict());
        assertEquals("30", r.secondsArg());
    }

    // --------------------------------------------------------------- 规则 3

    @Test
    void autoclickerNeedsStreakAndActions() {
        ActivityState state = new ActivityState();
        long t = System.currentTimeMillis();
        evaluator.evaluate(state, 0, 64, 0, 0f, 0f, t, cfg());
        t += 1000;
        // 连续 4 次采样满足"移动+视角冻结+动作"：streak 不足
        for (int i = 0; i < 4; i++) {
            state.actionCounter.set(5);
            var r = evaluator.evaluate(state, i + 1, 64, 0, 0f, 0f, t + i * 1000L, cfg());
            assertEquals(AfkRuleEvaluator.Verdict.NONE, r.verdict());
        }
        // 第 5 次采样：streak=5、窗口动作=25 ≥ 15 → 触发
        state.actionCounter.set(5);
        var r = evaluator.evaluate(state, 5, 64, 0, 0f, 0f, t + 4000, cfg());
        assertEquals(AfkRuleEvaluator.Verdict.AUTOCLICKER, r.verdict());
        assertEquals("autoclicker", r.reasonKey());
    }

    @Test
    void cameraMovementClearsAutoclickerStreak() {
        ActivityState state = new ActivityState();
        long t = System.currentTimeMillis();
        evaluator.evaluate(state, 0, 64, 0, 0f, 0f, t, cfg());
        // 累积 3 次 streak
        for (int i = 0; i < 3; i++) {
            state.actionCounter.set(10);
            evaluator.evaluate(state, i + 1, 64, 0, 0f, 0f, t + i * 1000L, cfg());
        }
        // 视角一动：streak 归零
        state.actionCounter.set(10);
        evaluator.evaluate(state, 3, 64, 0, 9f, 0f, t + 4000, cfg());
        assertEquals(0, state.autoclickerStreak);
        assertEquals(0, state.windowActions);
    }

    // --------------------------------------------------------------- 规则 4

    @Test
    void typingOverridesStationaryWithinChatWindow() {
        ActivityState state = new ActivityState();
        long t0 = System.currentTimeMillis();
        evaluator.evaluate(state, 0, 64, 0, 0f, 0f, t0, cfg());
        state.lastChatAt = t0; // 打字信号
        // 静止 11s（> typing 10s，< stationary 15s）：打字规则先触发
        var r = evaluator.evaluate(state, 0, 64, 0, 0f, 0f, t0 + 11_000, cfg());
        assertEquals(AfkRuleEvaluator.Verdict.TYPING, r.verdict());
        assertEquals("10", r.secondsArg());
    }

    @Test
    void typingRuleDisabledDoesNotBlockStationary() {
        ActivityState state = new ActivityState();
        long t0 = System.currentTimeMillis();
        evaluator.evaluate(state, 0, 64, 0, 0f, 0f, t0, cfg());
        state.lastChatAt = t0;
        // chat.enabled=false：打字规则关闭，走规则 1
        var r = evaluator.evaluate(state, 0, 64, 0, 0f, 0f, t0 + 11_000, chatDisabled());
        assertEquals(AfkRuleEvaluator.Verdict.NONE, r.verdict());
        r = evaluator.evaluate(state, 0, 64, 0, 0f, 0f, t0 + 15_100, chatDisabled());
        assertEquals(AfkRuleEvaluator.Verdict.STATIONARY, r.verdict());
    }

    // --------------------------------------------------------------- 优先级

    @Test
    void containerTakesPriorityOverOthers() {
        ActivityState state = new ActivityState();
        long t0 = System.currentTimeMillis();
        evaluator.evaluate(state, 0, 64, 0, 0f, 0f, t0, cfg());
        // 同时满足：容器打开 + 静止超阈值 + 打字信号
        state.inContainer = true;
        state.containerSince = t0;
        state.lastChatAt = t0;
        var r = evaluator.evaluate(state, 0, 64, 0, 0f, 0f, t0 + 31_000, cfg());
        assertEquals(AfkRuleEvaluator.Verdict.CONTAINER, r.verdict());
    }

    @Test
    void firstSampleEstablishesBaselineWithoutVerdict() {
        // 首次采样无基准（hasPosition=false），任何情况下不应判定
        ActivityState state = new ActivityState();
        var r = evaluator.evaluate(state, 100, 64, 100, 45f, 10f,
                System.currentTimeMillis() + 999_999, cfg());
        assertEquals(AfkRuleEvaluator.Verdict.NONE, r.verdict());
    }
}
