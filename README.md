# moexdspector

基于 **Folia 26.2** 的旁观观察插件，附带 **Velocity 代理端附属插件**，实现跨服跟随观察。

核心能力：

- **多管理员同时观察**：每个管理员拥有独立会话，且允许多人同时附身同一名玩家，互不干扰；
- 自动检测被观察玩家是否挂机，挂机则立刻切换观察对象（含打字挂机检测）；
- 定时随机轮换观察对象，本轮内绝不重复（含跨服场景）；
- 被观察玩家跨服时，观察者自动跟随传送，并完整移交观察会话；
- 被观察玩家死亡立即切换，若其快速复活则自动附身回去；
- 被观察玩家掉线不空窗：立即切到下一位玩家，宽限期内重连自动切回（为直播展示设计，**默认关闭**，`check.relogin-reattach` 开启）；
- 观察者意外脱离观察状态时，在限定时间内自动重新附身；
- **输入锁定**：`/spector` 会话中禁止 Shift 退出附身、左键换绑附身对象等意外脱离（`check.lock-spectate`，默认开）；
- **`/spector next|pre`** 手动步进切换候选池，锁定状态下游览玩家的推荐方式；掉线等待期间使用则放弃该掉线玩家；
- **画面反馈**：每次切换目标显示 Title + ActionBar（MiniMessage 格式，文本/开关/时长可配）；
- 观察者切到非旁观模式自动退出观察、切到旁观则提示退出方式；关闭 lock-spectate 时旁观菜单换绑视为主动接管；
- 观察结束后自动还原观察者原本的 gamemode；
- NPC / 隐身玩家 / 豁免名单 / 自定义权限节点四级排除体系；
- 全部状态保存在内存中，**不使用任何数据库**，重启即重置。

---

## 目录

- [项目结构](#项目结构)
- [环境要求](#环境要求)
- [构建](#构建)
- [安装与部署](#安装与部署)
- [指令](#指令)
- [挂机检测规则](#挂机检测规则)
- [豁免体系](#豁免体系)
- [配置文件详解](#配置文件详解)
  - [后端 config.yml](#后端-configyml)
  - [语言文件 lang/zh_cn.yml](#语言文件-lang-zh_cnyml)
  - [pass_player.yml](#pass_playeryml)
  - [Velocity 端 moexdspector.properties](#velocity-端-moexdspectorproperties)
- [跨服机制原理](#跨服机制原理)
  - [架构分工](#架构分工)
  - [通信协议](#通信协议)
  - [会话生命周期](#会话生命周期)
- [相机接管策略](#相机接管策略)
- [多观察者语义](#多观察者语义)
- [掉线不空窗（重连附身）](#掉线不空窗重连附身)
- [安全性](#安全性)
- [已知限制](#已知限制)
- [常见问题](#常见问题)
- [版本记录](#版本记录)

---

## 项目结构

Maven 多模块工程：

```
spector/
├── pom.xml                          # 父工程（聚合 backend 与 velocity 两个模块）
├── backend/                         # Folia 后端插件
│   ├── pom.xml                      # 依赖 dev.folia:folia-api:26.2.build.7-beta（含 JUnit 5 测试）
│   └── src/
│       ├── main/resources/
│       │   ├── plugin.yml           # folia-supported: true, api-version 26.2
│       │   ├── config.yml           # 逻辑配置（阈值 / Velocity / 豁免 / 反馈开关 / lang）
│       │   ├── lang/zh_cn.yml       # 语言文件（全部可读文本，前缀 MoeXDSpector）
│       │   └── pass_player.yml      # 豁免名单（玩家名或 UUID）
│       ├── main/java/com/moexd/spector/
│       │   ├── MoexdSpectorPlugin.java       # 主类：装配、配置、豁免判定、消息渲染
│       │   ├── command/SpectorCommand.java   # /spector 指令与 Tab 补全（含管理侧）
│       │   ├── protocol/SpectorProtocol.java # 与代理端共享的协议常量（两端各一份副本）
│       │   ├── observe/
│       │   │   ├── ObservationManager.java   # 多会话状态机：按观察者建会话、目标监测引用计数
│       │   │   ├── ActivityState.java        # 单个被观察玩家的活动数据（纯内存）
│       │   │   └── ActivityListener.java     # 容器/挥手/交互/聊天/死亡/复活/进出服/模式事件
│       │   └── velocity/
│       │       └── VelocityBridge.java       # 与代理端的插件消息通道（收发 + 密钥校验）
│       └── test/java/com/moexd/spector/protocol/
│           └── ProtocolConsistencyTest.java  # 协议契约一致性测试（两端逐字节比对）
└── velocity/                        # Velocity 代理端附属插件
    ├── pom.xml                      # 依赖 com.velocitypowered:velocity-api:4.2.0
    └── src/main/java/com/moexd/spector/
        ├── protocol/SpectorProtocol.java  # 协议常量副本（与 backend 同步维护）
        └── velocity/VelocitySpectorPlugin.java
                                        # 会话登记、跨服跟随、HANDOFF 移交、密钥校验
```

---

## 环境要求

| 组件 | 要求 |
| --- | --- |
| Minecraft / Folia | **26.2**（Folia 26.2.build.x） |
| Velocity | 3.x/4.x（仅跨服功能需要，建议最新版） |
| 转发模式 | Velocity **modern forwarding**（跨服功能必需） |
| Java（运行） | 26.2 后端所需版本（API 字节码为 Java 25 / class 69，需 Java 25+） |
| Java（编译） | JDK 25 |

---

## 构建

```bash
# Git Bash / Linux
JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-25.0.3.9-hotspot" mvn package
```

产物：

- `backend/target/moexdspector-1.0.1.jar` —— Folia 后端插件
- `velocity/target/moexdspector-velocity-1.0.1.jar` —— Velocity 附属插件

> **两端必须同时更新**：消息帧带协议版本号校验，新旧版本混搭时跨服消息会被拒绝（日志有明确提示，不会静默错乱）。构建时自动运行协议契约一致性测试，防止两端 `SpectorProtocol.java` 漂移。

---

## 安装与部署

### 单服务器（不跨服）

1. 将 `moexdspector-1.0.1.jar` 放入 Folia 服务端 `plugins/` 目录；
2. 启动一次生成 `plugins/moexdspector/config.yml`、`lang/zh_cn.yml` 与 `pass_player.yml`，按需调整后重启；
3. `velocity.enabled` 保持 `false` 即可，其余功能全部可用。

### Velocity 跨服网络

1. **每个** Folia 后端都安装后端插件，并为每个后端配置**唯一**的 `velocity.server-id`（如 `lobby`、`survival`、`creative`）；
2. 所有后端将 `velocity.enabled` 设为 `true`；
3. 所有后端与 Velocity 端的**共享密钥保持一致**（见[安全性](#安全性)）；
4. Velocity 端安装 `moexdspector-velocity-1.0.1.jar`，首次运行会在
   `plugins/config/moexdspector/moexdspector.properties` 生成默认配置，修改 `secret` 后重启；
5. 确认 `velocity.toml` 使用 modern 转发模式。

---

## 指令

主指令：`/spector`。观察权限 `moexdspector.use`（默认 OP），管理权限 `moexdspector.admin`（默认 OP，控制台可用）。

| 指令 | 权限 | 说明 |
| --- | --- | --- |
| `/spector <玩家ID>` | use | 附身到目标玩家并开启挂机检测；检测到挂机时自动随机切换到另一名在线玩家并继续检测。**开启跨服后**：目标在其他服务器时，观察者会被自动传送过去并附身。目标在豁免名单中则拒绝。 |
| `/spector random` | use | 开启随机轮换模式：按配置间隔切换观察对象；本轮内已观察过的玩家不会重复，全部轮完一遍后清空记录重新开始。**开启跨服后**：本服玩家轮完会自动跨服全网选人，去重列表跨服连续。 |
| `/spector next` / `/spector pre` | use | 手动步进：按玩家名字序切换到下一位/上一位可观察玩家（循环）。无会话时 `next` 从第一位开始观察。**显式意图**：会同时重置主动接管状态（恢复锁定与镜头控制）；若正处于掉线等待/裁决状态，则放弃该掉线玩家——本会话内不再自动切回、不再进候选池与步进列表。 |
| `/spector status` | use | 查看当前会话状态：模式 / 当前观察目标 / 本轮已观察人数 / 同行观察者数量 / 相机接管状态 / 是否在等待掉线目标重连。 |
| `/spector afk` | use | 查看近期挂机判定报告（最近 20 条：玩家、原因、多少秒前）。 |
| `/spector off` | use | 停止**自己的**观察会话（不影响其他管理员），注销跨服会话，并**自动还原自己开始观察前的 gamemode**。 |
| `/spector reload` | admin | 重载 `config.yml`、语言文件（`lang/`）与 `pass_player.yml`（改豁免名单/阈值/文案无需重启）。**不影响进行中的会话**：已在观察某玩家的会话继续，新候选池从下次选择起生效。 |
| `/spector off <玩家>` | admin | 强制断开指定观察者的会话（可由控制台或其他管理员执行），并还原其 gamemode。**跨服生效**：通过代理转发到观察者所在的后端。 |

Tab 补全：`random` / `status` / `afk` / `off` / 本服在线玩家名（管理员额外可见 `reload`）。

---

## 挂机检测规则

采样任务按**目标**共享（多个观察者看同一个人时只有一份采样），运行在被观察玩家的 EntityScheduler 上（Folia 区域线程模型），默认每 20 tick（1 秒）采样一次。四条判定规则：

### 规则 1：原地静止

同一点位（位置无变化）+ 视角无变化 + 未打开任何容器界面，持续超过 `check.stationary-seconds`（默认 15 秒）→ 判定挂机。

### 规则 2：容器/界面挂机

打开箱子等容器界面（以 `InventoryOpenEvent` 计时）后，若视角完全不动，持续超过 `check.container-seconds`（默认 30 秒）→ 判定挂机。
**容器内视角发生移动会重置该计时**（视为仍在操作）；关闭容器、容器内点击同样重置。

### 规则 3：连点器特征

位置持续移动 + 视角完全冻结 + 持续重复同一动作（连续挥手/交互），连续满足 `check.autoclicker-streak`（默认 5）次采样、窗口内累计动作数达到 `check.autoclicker-min-actions`（默认 15）→ 判定挂机。

### 规则 4：打字挂机

> 原版协议没有键盘输入/聊天框开关的 API（客户端不发包），无法直接感知"正在打字"，因此采用降级信号。

以**最近一条聊天消息**作为"打字中"信号（有效期 `chat.recent-window-seconds`，默认 60 秒）。信号有效期内，若观察对象持续静止（无位移、无视角变化）超过 `chat.typing-idle-seconds`（默认 10 秒）→ 判定挂机并切换。

- 规则 4 优先于规则 1，且聊天信号窗口内规则 1 不生效（聊天者由通常更短的打字时限单独管辖）；
- 对方继续回话会不断刷新信号与活跃计时；聊完安静超过时限才切换；
- 边走边聊视为活跃；
- `chat.enabled: false` 可整体关闭该检测。聊天较活跃的服务器建议把 `typing-idle-seconds` 调到 20–30 秒，避免句间思考被误切。

### 计时重置

任何以下行为都会重置活跃计时：视角移动、位置移动（无冻结视角+连点特征）、容器内点击、背包内点击、发送聊天消息、执行指令。

### 额外的观察者侧检测

- **死亡切换**：被观察玩家死亡时，每个观察该目标的会话独立随机切换到另一名玩家；若原目标在 `check.respawn-reattach-seconds`（默认 3 秒）内复活，各会话独立附身回去（可通过 `check.respawn-reattach: false` 关闭）。
- **脱离重附身**：除跨服离开、死亡、掉线等待三种正常路径外，观察者因任何原因脱离观察状态（被改回生存模式、旁观视角分离等），持续超过 `check.detach-reattach-seconds`（默认 1 秒）后自动重新附身：
  1. 目标还在本服 → 直接附身回同一目标；
  2. 本服没有可选玩家 → 跨服开启时经代理切换到其他服务器选人（随机模式沿用去重列表）；
  3. 未开跨服且无候选人 → 提示并停止。
  4. 附身后有 ≥3 秒的宽限期（teleportAsync 完成前看门狗只观察不动手，避免卡顿时传送循环）。

掉线属于独立路径，见[掉线不空窗（重连附身）](#掉线不空窗重连附身)。

---

## 豁免体系

四个豁免来源，命中任一即生效：该玩家**不可被观察**（显式指令会被拒绝），也**不会进入任何候选池**（随机/AFK/死亡/脱离切换）。

| 来源 | 开关/配置 | 说明 |
| --- | --- | --- |
| NPC | `exclude.skip-npc: true`（默认开） | 命中 `"NPC"` metadata（Citizens 等插件的惯例）即豁免 |
| 隐身玩家 | `exclude.skip-vanished: true`（默认开） | 命中 `"vanished"` metadata（Essentials 系 `/vanish` 惯例）即豁免 |
| 豁免名单 | `pass_player.yml` | 玩家名（不区分大小写）或 UUID，启动时加载（`/spector reload` 可热重载） |
| 自定义权限 | `exclude.permission: ""` | **默认空=关闭**；配置后拥有该权限的玩家豁免 |

> 边界：豁免的权限与 NPC 检测只能由后端完成。跨服**随机**选人（全网选人）发生在 Velocity 侧，无法感知各后端的权限/名单/NPC，因此跨服随机模式下其他服务器的豁免玩家仍可能被选中；单服使用与定向观察不受影响。

---

## 配置文件详解

### 后端 config.yml（逻辑配置）

```yaml
lang: "zh_cn"                     # 语言文件（plugins/moexdspector/lang/ 目录，可自行翻译新语言）
check:
  interval-ticks: 20              # 采样间隔（tick）
  stationary-seconds: 15          # 规则1：原地静止阈值
  container-seconds: 30           # 规则2：容器界面挂机阈值（视角移动重置）
  respawn-reattach: true          # 死亡后复活是否自动附身回去
  respawn-reattach-seconds: 3     # 复活附身回去的窗口
  detach-reattach-seconds: 1      # 观察者意外脱离后重附身的时限
  relogin-reattach: false         # 掉线不空窗总开关（默认关闭），开启后按下行宽限期桥接等待重连
  relogin-grace-seconds: 30       # 掉线重连宽限（秒），仅 relogin-reattach: true 时生效
  lock-spectate: true             # 锁定观察者输入（禁止 Shift 退出/左键换绑附身对象）
  autoclicker-streak: 5           # 规则3：连续满足的采样次数
  autoclicker-min-actions: 15     # 规则3：窗口内累计动作数

feedback:                          # 切换画面反馈开关；文本与原因文案在 lang/zh_cn.yml 的 feedback 段
  enabled: true                    # 总开关
  title-enabled: true              # 是否显示 Title
  title-stay-ticks: 40             # Title 停留时长（tick）
  actionbar-enabled: true          # 是否显示 ActionBar

random:
  interval-seconds: 60            # /spector random 轮换间隔

chat:
  enabled: true                   # 是否启用规则4（打字挂机）
  typing-idle-seconds: 10         # 打字状态下持续静止超过该秒数切换
  recent-window-seconds: 60       # 最近发过聊天消息的多少秒内视为"打字中"

exclude:
  skip-npc: true                  # 跳过 NPC（"NPC" metadata）
  skip-vanished: true             # 跳过隐身玩家（"vanished" metadata）
  permission: ""                  # 自定义豁免权限节点，留空关闭

velocity:
  enabled: false                  # 是否启用跨服跟随
  server-id: "lobby"              # 本服务器在 Velocity 网络中的唯一 id（各后端不得重复）
  secret: "change-me"             # 共享密钥，与 Velocity 端 secret 一致，防伪造消息

# 提示消息与挂机原因文案见 lang/zh_cn.yml（messages / reasons 段）
```

> **升级提示**：新版本新增的配置项不会自动合并进已有的 config.yml，删除旧文件重新生成或手动补齐（或用 `/spector reload` 前先补齐文件）。**文本已迁移至 lang/ 目录**：旧版 config.yml 中的 `messages`/`reasons` 段不再生效，请删除后让插件释放 `lang/zh_cn.yml` 并在其中修改文案。

### 语言文件 lang/zh_cn.yml

全部可读文本集中于此（config.yml 只保留逻辑配置）：prefix（默认 &8[&bMoeXDSpector&8] &r）、messages（全部提示）、reasons（挂机原因）、feedback（Title/ActionBar 文本与各切换事件的原因文案，MiniMessage）。由 config.yml 的 lang 键指定，复制翻译新语言文件后改 lang 即可切换；/spector reload 会一并重载。

### pass_player.yml

位于 `plugins/moexdspector/pass_player.yml`，首次运行自动生成：

```yaml
# 名单内的玩家不会被观察/检测，也不会出现在随机观察的候选池中。
# 支持玩家名（不区分大小写）或 UUID：
pass: []
#   - "Steve"
#   - "069a79f4-44e9-4726-a5be-fca90e38aaf5"
```

> 建议用 UUID：正版玩家改名后玩家名条目会失效。修改后执行 `/spector reload` 生效。

### Velocity 端 moexdspector.properties

位于 `plugins/config/moexdspector/moexdspector.properties`，首次运行自动生成：

```properties
secret=change-me
```

`secret` 必须与所有后端 `config.yml` 的 `velocity.secret` 完全一致。

---

## 跨服机制原理

### 架构分工

```
┌──────────┐  插件消息通道 moexd:spector  ┌──────────────┐   插件消息通道   ┌──────────┐
│ Velocity │◄───────────────────────────►│  Folia 后端 A  │◄──────────────►│ Folia 后端B│
│  附属插件  │   PAIR/UNPAIR/OBSERVE/HANDOFF/ │  moexdspector  │                │moexdspector│
│          │   TARGET_GONE/OBSERVE_FAIL   │  (检测+指令)    │                │ (检测+指令) │
└──────────┘                              └──────────────┘                └──────────┘
```

- **后端插件**：负责一切本地事务——挂机检测、死亡/复活、脱离重附身、轮换决策、旁观附身。
- **Velocity 附属**：负责路由——登记会话、跟随目标切服、跨服选人、移交会话、目标掉线通知。
- 通信使用自定义插件消息通道 `moexd:spector`，数据为 UTF 字符串序列，全部状态保存在内存，**不落库、不依赖 Redis**。

### 通信协议

帧格式：`VERSION, SUB, ...字段..., SECRET`（全部 UTF 字符串）。协议常量定义在两端的 `SpectorProtocol` 类中（`com.moexd.spector.protocol`），改动必须两端同步（构建期有一致性测试兜底）。

后端 → 代理（经观察者客户端连接发出，被代理拦截）：

| 子通道 | 载荷 | 用途 |
| --- | --- | --- |
| `PAIR` | serverId, observer, target, mode, usedCsv | 登记/更新会话（含去重列表） |
| `UNPAIR` | serverId, observer | 注销会话 |
| `OBSERVE` | serverId, observer, mode, targetName, usedCsv | 跨服观察请求；targetName 为 `*` 时由代理全网随机选人 |

代理 → 后端（经 `ServerConnection#sendPluginMessage` 直达目标后端）：

| 子通道 | 载荷 | 用途 |
| --- | --- | --- |
| `HANDOFF` | serverId, observer, mode, usedCsv, targetUuid | 观察者到达目标服后移交会话 |
| `TARGET_GONE` | serverId, observer, target | 观察对象退出网络，触发重连宽限/结束会话 |
| `OBSERVE_FAIL` | serverId, observer, mode | 代理找不到可观察玩家 |
| `FORCE_STOP` | serverId, observer | 管理指令跨服转发：注销代理侧会话 |
| `STOP` | serverId, observer | 代理转发到观察者所在后端，结束其本地会话 |

每条消息末尾附带共享密钥用于防伪造（见[安全性](#安全性)）。

### 会话生命周期

1. **建立**：执行 `/spector` 指令 → 该观察者本地建会话（旁观模式 + 附身 + 监测任务 + 看门狗 + 轮换任务）→ 向代理发 `PAIR`。多名管理员可各自建立会话，也可同时附身同一目标。
2. **目标跨服**：目标退出当前后端 → 所有观察该目标的会话进入重连等待并立即切换到下一位玩家（不掉线不空窗）→ 代理检测到目标进入新服务器 → 把**所有**观察者传送过去 → 各观察者到达后向新后端发 `HANDOFF` → 新后端逐个重建会话（模式、去重列表随消息移交）→ 各观察者恢复旁观并附身。
3. **目标掉线**：代理 `DisconnectEvent` 逐个回发 `TARGET_GONE` → 各后端进入[掉线不空窗](#掉线不空窗重连附身)流程。
4. **观察者退出网络**：代理直接移除该观察者的会话。
5. **正常结束**：`/spector off`、`/spector off <玩家>` 或后端卸载 → 清理、还原观察者 gamemode 并回发 `UNPAIR`。
6. **代理无响应**：跨服请求发出后 5 秒内无 `HANDOFF`/`OBSERVE_FAIL` 回执 → 提示"代理端未响应"（检查附属插件与密钥）。

---

## 相机接管策略

插件对观察者相机的控制遵循"用户优先"原则（**按会话隔离**，只影响自己的会话）：

- **插件接管**：执行 `/spector` 时自动切旁观并附身；观察者意外脱离时限时重附身；目标切服/死亡/掉线时自动跟随切换。
- **游戏模式语义**：通过 `/spector` 进入观察后——切到**非旁观模式**（生存/创造等）= 自动退出观察（提示并保留用户选择的新模式）；切到**旁观模式** = 提示"如需退出请输入 /spector off"。不再"拽回"。
- **用户优先**（主动接管）：关闭 `lock-spectate` 后，观察者通过旁观菜单换绑到其他玩家时视为"主动接管相机"——插件完全让出控制权：不再强制旁观、不再重附身、不再锁定输入、切换观察对象时只换监测目标不动镜头。会话监测与消息提示照常运行。
- **输入锁定**（`check.lock-spectate: true`，默认开）：通过 `/spector` 进入的观察会话中，Shift 退出附身、左键点击其他生物换绑视角等"意外脱离"操作会被直接取消（伴随提示）——这些操作本质是客户端发 SPECTATE 传送包，插件在服务端拦截，取消瞬间客户端可能有一帧轻微闪烁。被拽回时会提示"正常退出请使用 /spector off"。需要旁观菜单自由切换时关闭该开关，或走上面的"用户优先"路径。
- **手动步进**：`/spector next|pre` 在锁定状态下也能随意切换观察对象，是游览候选池的推荐方式。
- **画面反馈**：进入观察与每次切换目标都会显示 Title 与 ActionBar（MiniMessage 格式、文本与开关可配，见 `feedback` 配置段），直播观众能直观看到"正在观察谁"。
- **恢复**：该观察者重新执行 `/spector ...` 重置接管状态；`/spector off` 结束会话并还原 gamemode（主动接管期间结束的，不还原——用户自己管理自己的模式）。
- `/spector status` 可查看当前是否处于主动接管状态。

---

## 多观察者语义

- 每个管理员的会话完全独立：模式、去重列表、挂机决策、主动接管状态互不干扰；
- 多人可**同时附身同一名玩家**：采样任务按目标引用计数共享（只采样一份），挂机触发时各会话独立决策——A 可能切到 X，B 同时切到 Y；
- A 执行 `/spector off` 只结束 A 的会话，不影响 B；
- 候选玩家池自动排除：豁免玩家（见[豁免体系](#豁免体系)）、所有正在观察他人的观察者（旁观者无观察价值）、观察者本人。

---

## 掉线不空窗（重连附身）

> **默认关闭**：`check.relogin-reattach: false`。开启后（配合 `relogin-grace-seconds`，默认 30 秒）行为如下；关闭时目标掉线立即结束观察会话（跨服跟随不受影响）。

为直播展示设计：被观察玩家掉线时，观察画面绝不空转。以"正在观察 A，A 掉线"为例：

1. **A 掉线瞬间**：会话不结束——立即随机跳到下一位玩家 B 继续观察（提示"观察对象 A 掉线，暂时切换到 B"），同时启动重连宽限倒计时（`check.relogin-grace-seconds`，默认 30 秒）。
2. **等待期间一切照常**：挂机检测、随机轮换都作用在 B 身上，直播无缝。
3. **A 在宽限期内重连**：自动切回 A 的视角，提示"已重新上线，恢复观察"；同时 **B 从本轮去重名单中移除**——B 只是临时桥接，不占用本轮名额，之后的轮换/切换可以再次随机到他。
4. **A 超时未归**：宽限到期**不掐断直播**——清掉等待状态，继续观察当前的桥接玩家 B；A 之后回来按普通玩家处理（可再次被 `/spector` 指定或随机选中）。若连桥接目标都没有（无人可跳），会话才正常收尾。
5. **等待期间手动 `next`/`pre`**：视为放弃 A——取消倒计时，A 加入本会话忽略名单（不再自动切回、不再进候选池与步进列表，随机模式同时计入去重），直播继续。

边界行为：

- **无人可跳**（本服只有 A 和管理员）：保持等待状态并提示，不发跨服请求；
- **等待期间 B 挂机**：正常按挂机规则继续切换，宽限倒计时不受影响；
- **跨服切换与掉线的裁决**：A 离开本服时先进入"等待裁决"状态（不立即桥接、不更新代理映射）——代理判定为切服则走 `HANDOFF` 跟随移交；判定为掉线（`TARGET_GONE`）才进入重连桥接。宽限期内代理无任何裁决（附属缺失等）则兜底按掉线处理；
- **A 回来时管理员不在本服**（跨服场景）：无法本地附身，按超时逻辑收尾（协议边界：重连请求需要通过观察者的连接发给代理）；
- 多观察者各玩各的：每个会话独立"跳走/切回"，A 重连会**同时切回所有**等待他的观察者；
- `/spector status` 会同时显示当前实际观察对象与"等待重连/等待裁决"状态行。

---

## 安全性

- 后端无法在 API 层面区分"代理注入的插件消息"与"客户端伪造的插件消息"，因此协议要求**每条消息末尾附带共享密钥**，接收端以常量时间比较校验，不通过则丢弃并记录警告。
- Velocity 侧只接受来自 `ServerConnection` 方向（后端→代理）的消息，客户端直接发往代理的同通道消息一律忽略。
- 消息帧首字段为协议版本号，两端版本不一致时拒绝处理并提示统一更新。
- 密钥默认值为 `change-me`，**部署时务必修改**；不一致时跨服功能不可用，日志中会出现明确的"未通过密钥校验"警告。

---

## 已知限制

- **自身背包**：原版客户端打开自身背包不会通知服务端（协议层面限制），因此对"看背包"的处理是背包内点击视为活跃；完整计时检测只覆盖容器界面（箱子等）。
- **打字检测为降级信号**：无法感知"聊天框已打开但未发送"，纯发呆开聊天框仍由规则 1（15 秒）接管；只有实际发出消息后才开始按打字规则计时。
- **载具挂机**：玩家乘坐移动的载具（船等）时位置持续变化，会被视为活跃，暂不判定挂机。
- **内存态**：所有去重列表与会话记录不做持久化，后端/代理重启后从零开始。
- **跨服移交间隙**：目标切服到观察者附身完成的瞬间存在短暂空档（通常数百毫秒），此期间不会产生新的切换决策。
- **跨服随机选人的豁免盲区**：全网随机选人在代理侧进行，无法感知各后端的权限/名单/NPC 状态（详见[豁免体系](#豁免体系)边界说明）；观察者本人已在排除列表中。
- **掉线切回要求观察者在本服**：目标在宽限期内重连，但观察者此前已不在该服时无法本地附身，按超时收尾。
- **被观察者可感知**：旁观模式会在目标客户端显示观察者视角，玩家能察觉自己被观察——是否告知玩家属于运营决策，建议在服务器规则中说明。

---

## 常见问题

**Q：跨服功能不生效，日志提示"未通过密钥校验"？**
A：检查所有后端 `config.yml` 的 `velocity.secret` 与 Velocity 端 `moexdspector.properties` 的 `secret` 是否完全一致。

**Q：`/spector 玩家名` 提示"代理端未响应"？**
A：Velocity 端未安装 `moexdspector-velocity` 附属、附属版本与后端不一致（协议版本被拒）、或密钥不匹配。三者逐一核对。

**Q：`/spector 玩家名` 提示"该玩家在豁免名单中"？**
A：目标命中了 `pass_player.yml`、`exclude.permission` 权限节点、NPC 检测或隐身检测四者之一，按设计拒绝观察。

**Q：`/spector 玩家名` 提示找不到玩家，但对方明明在线？**
A：对方在另一个服务器且 `velocity.enabled` 未开启。开启跨服并核对密钥后即可自动跟过去。

**Q：观察者被反复传送？**
A：插件已内置附身宽限期（≥3 秒）；若仍出现，检查是否有其他插件在竞争修改观察者的 gamemode。

**Q：我输入 /gamemode survival 想退出，会发生什么？**
A：会**自动退出观察**（提示"检测到你切换了游戏模式，已自动退出观察"），你选的新模式被保留。观察期间切到旁观模式则只是提示"如需退出请输入 /spector off"。正常退出推荐 `/spector off`（会还原你观察前的 gamemode）。

**Q：拦截视角切换时画面会闪一下？**
A：取消 SPECTATE 传送是服务端行为，客户端可能在取消生效前渲染一帧再被拉回，属于方案固有的轻微闪烁，无法在服务端完全消除。

**Q：另一个管理员执行 /spector 会把我踢出观察吗？**
A：不会。会话按观察者隔离，多管理员可同时观察，甚至同时附身同一名玩家；`/spector off` 只结束自己的会话。需要强制断开别人时用 `/spector off <玩家>`（admin 权限）。

**Q：改了 pass_player.yml 不生效？**
A：执行 `/spector reload`（admin 权限）。建议名单里使用 UUID，避免玩家改名后失效。

**Q：为什么不用 Redis / 数据库？**
A：设计约束如此。所有记录（去重列表、会话映射、server-id 索引）均为内存态，代理与后端之间通过插件消息即时同步，重启即重置。

---

## 版本记录
### 1.0.1

- **修复**：连点器检测（规则 3）从未真正触发的预存缺陷——streak 被位置移动误清零，现仅在视角移动时清零（重构为纯规则机时由单元测试捕获）。
- **重构**：挂机规则机（AfkRuleEvaluator）、配置快照（CheckSettings）、画面反馈渲染（FeedbackRenderer）、会话数据（ObserverSession）自 ObservationManager 拆分为独立类；Manager 从约 1500 行收缩至约 1350 行。
- **测试**：新增规则机 9 个单元测试（四条规则触发/重置/优先级/打字开关/首采样基准），全套 16 个用例。
- 行为变化提示：此前规则 3 形同虚设，本版起连点器特征（移动+视角冻结+持续动作）将按配置正常判定切换。


### 1.0.0

- 首个版本，目标 Folia 26.2（folia-api 26.2.build.7-beta），Velocity 附属基于 velocity-api 4.2.0。
- `/spector <玩家|random|status|afk|off|reload>` 指令与 Tab 补全，管理侧（reload / off <玩家>）支持控制台。
- 挂机检测：原地静止 15s / 容器挂机 30s（视角移动重置）/ 连点器特征（移动+视角冻结+重复动作）/ 打字挂机（聊天降级信号，默认 10s，可关）。
- 挂机自动切换观察对象；AFK 无候选人时明确提示而非误报切换；`/spector afk` 近期判定报告。
- 随机轮换模式，本轮内不重复；跨服轮换去重列表连续。
- 死亡立即切换；复活窗口（默认 3s，可关）内自动附身回去。
- **掉线不空窗**（`check.relogin-reattach`，**默认关闭**）：目标掉线立即切到下一位玩家，宽限期内（默认 30s）重连自动切回（多观察者多播），桥接目标从本轮去重名单移除、重新可被随机选中；超时不掐断直播，继续观察桥接目标。跨服退出先等待代理裁决（HANDOFF=跟随 / TARGET_GONE=掉线），避免与跟随机制冲突。
- 观察者脱离观察状态限时重附身（默认 1s，优先本服、可跨服选人，附身宽限期防传送循环）。
- 观察者主动接管相机（关闭 lock-spectate 后旁观菜单换绑）时插件不接管；切非旁观模式自动退出观察。
- **输入锁定**（`check.lock-spectate`，默认开）：/spector 会话中禁止 Shift 退出附身、左键换绑附身对象等意外脱离。
- **`/spector next|pre`**：按名字序步进切换候选池；掉线等待期间使用则放弃该掉线玩家（本会话内不再切回/选中）。
- **画面反馈**：切换时 Title + ActionBar（MiniMessage，`feedback` 段可配开关与文本）。
- **多语言系统**：全部可读文本独立于 lang/ 目录（默认 zh_cn），前缀 MoeXDSpector，/spector reload 一并重载。
- **游戏模式语义**：通过 /spector 进入观察后，切换到非旁观模式=自动退出观察（保留新模式）；切到旁观=提示用 /spector off 退出。
- 观察结束自动还原观察者 gamemode（/spector off 场景；切模式退出则保留用户选择）。
- **多观察者支持**：会话按观察者隔离，多人可同时附身同一目标，采样按目标共享，跨服跟随/移交/掉线全部多播。
- **豁免体系**：NPC metadata 检测、隐身玩家检测、`pass_player.yml` 豁免名单（玩家名/UUID，支持热重载）、自定义豁免权限节点（默认关闭）。
- Velocity 跨服：跟随目标切服、指令跨服使用、全网随机选人（排除观察者）、去重列表跨服连续、HANDOFF 到达确认、传送失败回执、5 秒无响应提示、跨服裁决状态、管理指令跨服转发（FORCE_STOP/STOP）。
- 安全：共享密钥常量时间校验、代理方向过滤、协议版本号校验。
- 工程质量：协议契约一致性测试（JUnit 5，构建期比对两端协议文件），关键动作 INFO 日志；核心挂机规则机为纯逻辑类（`AfkRuleEvaluator`），四条规则的触发/重置/优先级由 16 个单元测试覆盖（拆分过程中发现并修复了连点器 streak 被位置移动误清零、导致规则 3 从未触发的预存 bug）。
