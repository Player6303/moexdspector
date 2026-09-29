package com.moexd.spector.observe;

import com.moexd.spector.MoexdSpectorPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.title.Title;
import org.bukkit.entity.Player;

import java.time.Duration;

/**
 * 切换画面反馈渲染（Title + ActionBar，MiniMessage）。
 * 文本与原因文案来自语言文件；发送调度到观察者线程执行，调用方不应持锁。
 */
final class FeedbackRenderer {

    private final MoexdSpectorPlugin plugin;

    FeedbackRenderer(MoexdSpectorPlugin plugin) {
        this.plugin = plugin;
    }

    /** 渲染并发送一次反馈（观察者的 EntityScheduler 上执行，观察者退出时自动作废） */
    void send(Player observer, String targetName, boolean randomMode, String reasonKey) {
        if (!plugin.feedbackEnabled()) {
            return;
        }
        final String mode = randomMode ? "随机轮换" : "手动";
        final String reasonText = plugin.feedbackReasonText(reasonKey);
        final boolean showTitle = plugin.feedbackTitleEnabled();
        final boolean showBar = plugin.feedbackActionBarEnabled();
        if (!showTitle && !showBar) {
            return;
        }
        observer.getScheduler().run(plugin, task -> {
            if (showTitle) {
                Component main = miniMessage(plugin.feedbackTitleText(), targetName, reasonText, mode);
                Component sub = miniMessage(plugin.feedbackTitleSubtitle(), targetName, reasonText, mode);
                long stayMs = plugin.feedbackTitleStayTicks() * 50L;
                observer.showTitle(Title.title(main, sub, Title.Times.times(
                        Duration.ofMillis(100), Duration.ofMillis(stayMs), Duration.ofMillis(200))));
            }
            if (showBar) {
                observer.sendActionBar(miniMessage(plugin.feedbackActionBarText(), targetName, reasonText, mode));
            }
        }, () -> { /* 观察者退出时任务自动结束 */ });
    }

    /** 持续 ActionBar（附身期间常驻显示）：直接发送，调用方须在观察者所属线程（定时任务内） */
    void holdActionBar(Player observer, String targetName, boolean randomMode) {
        if (!plugin.feedbackHoldActionBarEnabled()) {
            return;
        }
        observer.sendActionBar(miniMessage(plugin.feedbackHoldActionBarText(), targetName, "",
                randomMode ? "随机轮换" : "手动"));
    }

    static Component miniMessage(String template, String target, String reason, String mode) {
        String replaced = template
                .replace("%target%", target)
                .replace("%reason%", reason)
                .replace("%mode%", mode);
        return MiniMessage.miniMessage().deserialize(replaced);
    }
}
