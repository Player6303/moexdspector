package com.moexd.spector;

import com.moexd.spector.command.SpectorCommand;
import com.moexd.spector.observe.ActivityListener;
import com.moexd.spector.observe.ObservationManager;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Set;
import java.util.UUID;

public final class MoexdSpectorPlugin extends JavaPlugin {

    private ObservationManager observationManager;
    private com.moexd.spector.velocity.VelocityBridge velocityBridge;
    private FileConfiguration langConfig;
    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadLang();
        loadPassList();
        this.observationManager = new ObservationManager(this);

        // bridge 必须先于指令装配创建，且不依赖指令注册成功
        this.velocityBridge = new com.moexd.spector.velocity.VelocityBridge(this, observationManager);
        observationManager.setVelocityBridge(velocityBridge);

        PluginCommand command = getCommand("spector");
        if (command != null) {
            SpectorCommand executor = new SpectorCommand(this, observationManager, velocityBridge);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        } else {
            getLogger().severe("plugin.yml 中缺少 spector 指令注册，指令将不可用！");
        }

        getServer().getPluginManager().registerEvents(new ActivityListener(observationManager), this);

        getLogger().info("moexdspector enabled (Folia " + getServer().getVersion()
                + ", velocity=" + velocityBridge.enabled() + ")");
    }

    @Override
    public void onDisable() {
        if (observationManager != null) {
            observationManager.stopAll();
        }
    }

    public int checkIntervalTicks() {
        return Math.max(1, getConfig().getInt("check.interval-ticks", 20));
    }

    public long stationaryMillis() {
        return getConfig().getLong("check.stationary-seconds", 15) * 1000L;
    }

    public long containerMillis() {
        return getConfig().getLong("check.container-seconds", 30) * 1000L;
    }

    public boolean respawnReattach() {
        return getConfig().getBoolean("check.respawn-reattach", true);
    }

    public long respawnWindowMillis() {
        return Math.max(1, getConfig().getLong("check.respawn-reattach-seconds", 3)) * 1000L;
    }

    public long detachReattachMillis() {
        return Math.max(1, getConfig().getLong("check.detach-reattach-seconds", 1)) * 1000L;
    }

    /** 目标退出网络后的重连宽限（秒），0 = 关闭（立即结束会话） */
    public long reloginGraceMillis() {
        return Math.max(0, getConfig().getLong("check.relogin-grace-seconds", 30)) * 1000L;
    }

    /** 掉线不空窗（重连附身）总开关：关闭时目标掉线立即结束会话；开启时按宽限期桥接等待重连 */
    public boolean reloginBridgeEnabled() {
        return getConfig().getBoolean("check.relogin-reattach", false);
    }

    /** 是否锁定观察者输入（禁止 Shift 退出附身/左键切换附身对象等意外脱离） */
    public boolean lockSpectateEnabled() {
        return getConfig().getBoolean("check.lock-spectate", true);
    }

    // ------------------------------------------------------------------
    // 画面反馈（Title / ActionBar，MiniMessage）
    // ------------------------------------------------------------------

    public boolean feedbackEnabled() {
        return getConfig().getBoolean("feedback.enabled", true);
    }

    public boolean feedbackTitleEnabled() {
        return feedbackEnabled() && getConfig().getBoolean("feedback.title-enabled", true);
    }

    public boolean feedbackActionBarEnabled() {
        return feedbackEnabled() && getConfig().getBoolean("feedback.actionbar-enabled", true);
    }

    public int feedbackTitleStayTicks() {
        return Math.max(10, getConfig().getInt("feedback.title-stay-ticks", 40));
    }

    public String feedbackTitleText() {
        return langConfig.getString("feedback.title-text",
                "<gradient:#00e0ff:#ffffff>正在观察</gradient> <yellow><bold>%target%</bold>");
    }

    public String feedbackTitleSubtitle() {
        return langConfig.getString("feedback.title-subtitle", "<gray>%reason%");
    }

    public String feedbackActionBarText() {
        return langConfig.getString("feedback.actionbar-text",
                "<gray>观察中: <white>%target% <dark_gray>· %reason% · %mode%");
    }

    /** 各切换事件的副标题/原因文案（%reason% 来源），在 lang 文件 feedback.feedback-reasons 中自定义 */
    public String feedbackReasonText(String key) {
        return langConfig.getString("feedback.feedback-reasons." + key,
                DEFAULT_FEEDBACK_REASONS.getOrDefault(key, key));
    }

    private static final java.util.Map<String, String> DEFAULT_FEEDBACK_REASONS = java.util.Map.of(
            "start", "开始观察",
            "afk", "挂机",
            "rotate", "轮换",
            "manual", "手动切换",
            "victim", "目标死亡",
            "relogin", "重连恢复",
            "detach", "脱离重附身",
            "bridge", "掉线桥接",
            "handoff", "跨服跟随"
    );

    /** /spector reload：重载 config.yml、语言文件与 pass_player.yml */
    public void reloadConfigs() {
        reloadConfig();
        loadLang();
        loadPassList();
    }

    /** 加载语言文件（config.yml 的 lang 指定，位于 lang/ 目录） */
    private void loadLang() {
        String lang = getConfig().getString("lang", "zh_cn");
        String resource = "lang/" + lang + ".yml";
        java.io.File file = new java.io.File(new java.io.File(getDataFolder(), "lang"), lang + ".yml");
        if (!file.exists()) {
            try {
                saveResource(resource, false);
            } catch (IllegalArgumentException e) {
                getLogger().warning("语言文件 " + resource + " 不存在，回退 zh_cn");
                lang = "zh_cn";
                file = new java.io.File(new java.io.File(getDataFolder(), "lang"), "zh_cn.yml");
                if (!file.exists()) {
                    saveResource("lang/zh_cn.yml", false);
                }
            }
        }
        org.bukkit.configuration.file.YamlConfiguration yaml =
                org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file);
        // 内置默认值兜底：jar 内资源作为二级默认
        try (java.io.InputStream in = getResource("lang/" + lang + ".yml")) {
            if (in != null) {
                yaml.setDefaults(org.bukkit.configuration.file.YamlConfiguration
                        .loadConfiguration(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8)));
            }
        } catch (java.io.IOException ignored) {
        }
        this.langConfig = yaml;
        getLogger().info("语言文件已加载: lang/" + lang + ".yml");
    }

    /** 打字挂机：静止超过该时长切换（打字信号有效期内） */
    public long typingIdleMillis() {
        return Math.max(1, getConfig().getLong("chat.typing-idle-seconds", 10)) * 1000L;
    }

    /** 打字信号的降级窗口：最近发过聊天消息的多少秒内视为“打字中”；<=0 关闭该检测 */
    public long chatRecentWindowMillis() {
        if (!getConfig().getBoolean("chat.enabled", true)) {
            return 0L;
        }
        return Math.max(0, getConfig().getLong("chat.recent-window-seconds", 60)) * 1000L;
    }

    // ------------------------------------------------------------------
    // 豁免（pass_player.yml / 权限节点 / NPC）
    // ------------------------------------------------------------------

    private Set<String> passNames = Set.of();
    private Set<UUID> passIds = Set.of();

    /** 加载 pass_player.yml（玩家名不区分大小写，亦可写 UUID） */
    private void loadPassList() {
        java.io.File file = new java.io.File(getDataFolder(), "pass_player.yml");
        if (!file.exists()) {
            saveResource("pass_player.yml", false);
        }
        org.bukkit.configuration.file.YamlConfiguration yaml =
                org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file);
        Set<String> names = new java.util.HashSet<>();
        Set<UUID> ids = new java.util.HashSet<>();
        for (String entry : yaml.getStringList("pass")) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            try {
                ids.add(UUID.fromString(entry.trim()));
            } catch (IllegalArgumentException e) {
                names.add(entry.trim().toLowerCase(java.util.Locale.ROOT));
            }
        }
        this.passNames = Set.copyOf(names);
        this.passIds = Set.copyOf(ids);
        getLogger().info("豁免名单已加载: " + (names.size() + ids.size()) + " 名玩家");
    }

    /** 该玩家是否被豁免（不可被观察、不进入候选池） */
    public boolean isExcluded(Player player) {
        if (getConfig().getBoolean("exclude.skip-npc", true) && player.hasMetadata("NPC")) {
            return true;
        }
        // Essentials 系 /vanish 惯例：隐身玩家不作为观察对象
        if (getConfig().getBoolean("exclude.skip-vanished", true) && player.hasMetadata("vanished")) {
            return true;
        }
        String permission = getConfig().getString("exclude.permission", "");
        if (permission != null && !permission.isBlank() && player.hasPermission(permission)) {
            return true;
        }
        return passNames.contains(player.getName().toLowerCase(java.util.Locale.ROOT))
                || passIds.contains(player.getUniqueId());
    }

    public int autoclickerStreak() {
        return Math.max(1, getConfig().getInt("check.autoclicker-streak", 5));
    }

    public int autoclickerMinActions() {
        return Math.max(1, getConfig().getInt("check.autoclicker-min-actions", 15));
    }

    public int randomIntervalTicks() {
        return Math.max(20, getConfig().getInt("random.interval-seconds", 60) * 20);
    }

    public String msg(String key, String... placeholders) {
        String raw = langConfig.getString("messages." + key, key);
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            raw = raw.replace(placeholders[i], placeholders[i + 1]);
        }
        String prefix = langConfig.getString("prefix", "");
        return color(prefix + raw);
    }

    public String reason(String key, String... placeholders) {
        String raw = langConfig.getString("reasons." + key, key);
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            raw = raw.replace(placeholders[i], placeholders[i + 1]);
        }
        return color(raw);
    }

    private static String color(String text) {
        return text.replace('&', '\u00A7');
    }
}
