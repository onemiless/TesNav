# NavAssist v2（Android P0）

NavAssist v2 是独立于现有 WebSocket v1 的、默认关闭的单向导航数据出口。它只发送手机端的导航观测，不接收也不生成车辆控制命令。

共享 JSON Schema 位于 [`protocol/navassist-v2.schema.json`](../protocol/navassist-v2.schema.json)。接收端应启用严格 schema 校验并拒绝未知字段。

## 启用方式

在本机 Gradle properties 中同时配置以下两项才会启动 v2：

```properties
NAV_ASSIST_V2_URL=http://192.168.53.232:7766
NAV_ASSIST_V2_TOKEN=replace-with-a-random-shared-secret

# 可选；小于 200 ms 仍会被钳制为 200 ms（最高 5 Hz）
NAV_ASSIST_V2_INTERVAL_MS=200
```

`NAV_ASSIST_V2_URL` 是 C3XL 服务的 base URL。客户端固定 POST 到其根路径 `/v2/snapshot`。URL 为空或不是有效的 HTTP/HTTPS 地址、token 的 UTF-8 key material 少于 16 bytes，或 `validForMs` 不在 100–2000 ms 时，exporter 保持 `STOPPED`，不会创建网络请求。token 只作为 HMAC 密钥，不会出现在请求 body 或 header 中；仓库中没有默认密钥。

当 v2 配置完整且通过上述 gate 时，v2 优先接管手机到 C3XL 的出口，legacy v1 WebSocket 不启动，避免默认旧地址持续重连。配置缺失或不满足 gate 时仍完全沿用原 v1 的 `EXPORT_ENABLED` 行为。

HTTP 与 HTTPS 都受支持，封闭场地若使用 HTTP，必须依靠隔离测试网络。更高信任边界应使用 HTTPS。
当前测试 token 会编译进 APK，不能视为硬件保护的秘密；测试完成后应轮换 token，并且不要分发该 APK。

## 请求与认证

- Method：`POST`
- Path：`/v2/snapshot`
- Content-Type：`application/json; charset=utf-8`
- Header：`X-NavAssist-Signature: <64-char lowercase hex>`
- 最大频率：5 Hz；请求不并发，慢请求会自然降低频率
- 任意 2xx 响应视为成功

body 是无空白的 canonical JSON：所有 object key 递归按字典序排列，数组顺序保留，null 字段省略。签名算法：

```text
lower_hex(HMAC-SHA256(UTF8(raw_http_body), UTF8(NAV_ASSIST_V2_TOKEN)))
```

接收端必须对收到的原始 body bytes 验签，不能 parse 后重新序列化再验签。比较签名时应使用 constant-time comparison。

## 会话、顺序与有效期

- `sessionId`：Android 前台导航服务每次创建 v2 exporter 时生成新的 UUID；最长 64 字符，只允许 ASCII 字母、数字、`.`、`_`、`:`、`-`。
- `sequence`：同一 session 内从 1 开始严格递增；发送失败也不会复用序号。
- `routeRevision`：路线成功发布、清除或失败失效时递增。路线重算期间 `navigationMode=recalculating` 且 `routeActive=false`。
- `maneuverEventId`：对稳定 key `sessionId:routeRevision:stepIndex:maneuver` 做 SHA-256，取前 8 bytes、清除最高符号位，得到稳定的正 int64。同一 maneuver 的 5 Hz 快照复用同一个 ID；没有有效事件时固定为 `0`。
- `validForMs`：当前 Android 发送 500 ms。C3XL 以自身 monotonic 接收时间执行控制 TTL；另外用 `sourceWallTimeMs` 限制跨进程重放窗口，因此静态检查必须确认手机与 C3XL 系统时间相差不超过约 1 秒。

接收端还应拒绝重复或倒退的 sequence、已经关闭的 session、旧 routeRevision，以及验签失败的消息。丢包只会形成 sequence gap，不应导致接收端等待补包。

`routeActive=true` 只会在实时或模拟导航运行中、路线已规划、非重算/结束状态、GPS 未报告弱信号、`routeMatched=true`，且完整的 `location` 与 `guidance` 对象都存在时产生。任一前置观测缺失时发送 `routeActive=false`，并将 `maneuverEventId` 归零。location 对象存在时，坐标、精度、方向、速度和观测时间均为必填；无效或非有限数值会使整个 location 缺省。

C3XL 的实车控制 gate 只接受 Android/iOS 的 `realtime` 快照；`simulation` 即使 `routeActive=true` 也只可用于协议诊断，不能形成纵向目标。

## 字段新鲜度

不同高德回调的观测时间彼此独立：

| 字段 | 唯一更新时间来源 |
| --- | --- |
| `location.observedAtMs` | `onLocationChange` |
| `guidance.observedAtMs` | `onNaviInfoUpdate` |
| `lanes.observedAtMs` | `showLaneInfo` / `hideLaneInfo` |

5 Hz exporter 只重复发送最新快照，不会刷新上述观测时间。这样位置高频更新不会让旧 maneuver 或旧车道推荐看起来仍然新鲜。

`sourceWallTimeMs` 是本次快照生成时间，不等同于任何字段的观测时间。

## Android 高德字段映射

| NavAssist 字段 | Android SDK 来源 | 处理规则 |
| --- | --- | --- |
| `routeMatched` | `AMapNaviLocation.isMatchNaviPath()` | 定位回调前省略 |
| step/link/point index | `AMapNaviLocation.curStepIndex/curLinkIndex/curPointIndex` | 负值省略 |
| `guidance.maneuver` | `NaviInfo.iconType` | 映射为共享的小写 snake_case enum；未识别值为 `unknown` |
| `guidance.maneuverDistanceM` | `NaviInfo.curStepRetainDistance` | 负值省略 |
| `roadClass/roadType` | `naviPath.steps[curStep].links[curLink]` | 仅索引有效且值在高德文档范围内时发送，否则省略 |
| lane actions | `AMapLaneInfo` | 沿用现有 `LaneAction` 大写 enum；推荐车道不代表自车当前所在车道 |

每个快照最多发送 16 条 lane item，lane index 限制为 0–31，每个 action 数组最多 16 项；mapper 与 schema 同时执行这些上限。

坐标系固定标注为 `gcj02`。当前 SDK 没有可靠的结构化“下一个 maneuver”或建议弯道速度来源，因此 `nextManeuver`、`nextManeuverDistanceM`、`advisorySpeedMps` 目前省略。摄像头限速不会被误当作建议弯道速度。

`NaviInfo.exitDirectionInfo` 提供出口名称与文字方向，但没有可安全映射为左右出口的结构化方向 enum。本版本不会解析文字猜测 `exit_left/exit_right`。当前 link 的 `roadType` 若为 6/9，可分别表示已经处于匝道/出口 link，但不能被解释成“前方应立即向该方向变道”。

字段和常量以高德官方 Android Navi Javadoc 为准：[`AMapNaviLocation`](https://a.amap.com/lbs/static/unzip/Android_Navi_Doc/com/amap/api/navi/model/AMapNaviLocation.html)、[`NaviInfo`](https://a.amap.com/lbs/static/unzip/Android_Navi_Doc/com/amap/api/navi/model/NaviInfo.html)、[`AMapNaviLink`](https://a.amap.com/lbs/static/unzip/Android_Navi_Doc/com/amap/api/navi/model/AMapNaviLink.html)、[`IconType`](https://a.amap.com/lbs/static/unzip/Android_Navi_Doc/com/amap/api/navi/enums/IconType.html) 和 [`LaneAction`](https://a.amap.com/lbs/static/unzip/Android_Navi_Doc/com/amap/api/navi/enums/LaneAction.html)。v2 从原始 lane code 独立映射；legacy v1 的既有 mapper 语义保持不变。

## 与 v1 的隔离

- 原 `EXPORT_ENABLED`、`WEBSOCKET_URL`、`API_TOKEN`、`EXPORT_INTERVAL_MS` 默认值与运行路径不变；仅在 v2 完整配置时抑制 v1 启动。
- 新增的 `NavigationState` v2 字段均为 JVM `transient`，不会进入 legacy Gson v1 payload。
- v2 使用独立 OkHttp client、coroutine 和连接状态；未配置时不会启动。
- v2 没有 route geometry POST，也不接收 C3XL 回包。
- v2 配置生效后 legacy v1 不启动，因此原 v1 回传的 `is_tesla_nav_active` 也不可用；依赖该回传 gate 的 Tesla/Home Assistant 自动同步不会自动进入 ready。v2 P0 不伪造该入站状态。
- v2 没有方向盘角度、曲率、加速度、CAN 或其他车辆控制字段。
