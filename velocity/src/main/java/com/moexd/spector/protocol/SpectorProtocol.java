package com.moexd.spector.protocol;

/**
 * 后端 ⇄ 代理 的插件消息协议契约。
 * 两端（backend / velocity 模块）各持有一份完全相同的副本，改动必须同步。
 *
 * 帧格式：VERSION, SUB, ...字段..., SECRET（全部 writeUTF）
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

    public static final String MODE_MANUAL = "MANUAL";
    public static final String MODE_RANDOM = "RANDOM";

    private SpectorProtocol() {
    }
}
