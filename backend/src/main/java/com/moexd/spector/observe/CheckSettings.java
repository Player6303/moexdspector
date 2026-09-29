package com.moexd.spector.observe;

import com.moexd.spector.MoexdSpectorPlugin;

/**
 * 检测相关配置的不可变快照。规则机每次评估时从插件读取，
 * 保证 /spector reload 后立即生效且无共享可变状态。
 */
record CheckSettings(
        long stationaryMillis,
        long containerMillis,
        int containerSeconds,
        int autoclickerStreak,
        int autoclickerMinActions,
        boolean chatEnabled,
        long chatWindowMillis,
        long typingIdleMillis,
        int typingIdleSeconds
) {
    static CheckSettings from(MoexdSpectorPlugin plugin) {
        return new CheckSettings(
                plugin.stationaryMillis(),
                plugin.containerMillis(),
                plugin.getConfig().getInt("check.container-seconds", 30),
                plugin.autoclickerStreak(),
                plugin.autoclickerMinActions(),
                plugin.getConfig().getBoolean("chat.enabled", true),
                plugin.chatRecentWindowMillis(),
                plugin.typingIdleMillis(),
                plugin.getConfig().getInt("chat.typing-idle-seconds", 10)
        );
    }
}
