package com.moexd.spector.protocol;

/**
 * 后端 ⇄ 代理 的插件消息协议契约。
 * 两端（backend / velocity 模块）各持有一份完全相同的副本，改动必须同步。
 *
 * 帧格式：VERSION, SUB, serverId, ...字段..., SECRET（全部 writeUTF），逐消息字段如下：
 * <pre>
 * 后端 -> 代理（代理端统一先读 serverId、observer）：
 *   PAIR        serverId observer target mode usedCsv
 *   UNPAIR      serverId observer
 *   OBSERVE     serverId observer mode targetName usedCsv
 *   FORCE_STOP  serverId observer
 * 代理 -> 后端（后端统一先读 serverId；HANDOFF 的目标以显式字段传输）：
 *   HANDOFF     serverId observer mode usedCsv target
 *   TARGET_GONE serverId observer target
 *   OBSERVE_FAIL serverId observer mode
 *   STOP        serverId observer
 *   REQ_PLAYERS serverId observer
 *
 * 代理 -> 后端补充：
 *   PLAYERS     serverId observer namesCsv
 * </pre>
 * 修改任何消息的字段时必须同时更新两端的写入与解析，并同步本表。
 */
public final class SpectorProtocol {

    public static final String CHANNEL = "moexd:spector";
    public static final String VERSION = "1";

    public static final String PAIR = "PAIR";
    public static final String UNPAIR = "UNPAIR";
    public static final String OBSERVE = "OBSERVE";
    public static final String HANDOFF = "HANDOFF";
    public static final String TARGET_GONE = "TARGET_GONE";
    public static final String OBSERVE_FAIL = "OBSERVE_FAIL";
    /** 后端 -> 代理：强制断开某观察者的会话（管理指令跨服转发） */
    public static final String FORCE_STOP = "FORCE_STOP";
    /** 代理 -> 后端：转发到观察者所在后端，结束其本地会话 */
    public static final String STOP = "STOP";
    /** 后端 -> 代理：请求全网玩家名列表（Tab 补全用） */
    public static final String REQ_PLAYERS = "REQ_PLAYERS";
    /** 代理 -> 后端：全网玩家名列表（CSV） */
    public static final String PLAYERS = "PLAYERS";

    public static final String MODE_MANUAL = "MANUAL";
    public static final String MODE_RANDOM = "RANDOM";

    private SpectorProtocol() {
    }
}
