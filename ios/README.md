# TesNav iOS

独立 iOS 15+ 客户端，不修改 Android 源码。Bundle ID 固定为
`com.garan.tesnav.ios`，便于高德 iOS Key 与签名配置保持稳定。

## 功能

- CLLocation 当前定位与高德逆地理编码地址；
- 300 ms 防抖的高德 Input Tips 模糊搜索，缺少坐标时回退到 POI 搜索；
- 最多三条驾车路线及时间、距离、收费、红绿灯信息；
- 高德实时导航视图、内置系统 TTS、静音/恢复和后台定位/语音；
- 车道建议、当前路段、转向、出口和匝道信息映射；
- 高德 GPS 弱标记仅用于诊断；路线仍由位置精度、数据新鲜度和匹配状态约束；
- Keychain 持久化 P-256 App 身份；
- UDP 7765 自动发现并认证 C3XL，HTTP 7766 `/v3/snapshot` 签名发送；
- 无共享 Token，App 内显示 App Key ID、C3XL Device ID、地址和连接状态。

## 本地配置

创建 `Config.local.xcconfig`（该文件已被 Git 忽略）：

```xcconfig
AMAP_IOS_API_KEY = 绑定 com.garan.tesnav.ios 的高德 iOS Key
TESNAV_DEVELOPMENT_TEAM = Apple Developer Team ID
```

Android 高德 Key 不能作为 iOS Key 使用。请在高德控制台创建“iOS 平台 SDK”
Key，并把安全码 Bundle ID 设置为 `com.garan.tesnav.ios`。

## 生成与验证

```bash
./build.sh
```

脚本会运行 XcodeGen、CocoaPods 和无签名模拟器构建。真机无线安装要求：

1. Xcode 已登录 Apple ID，并存在 Apple Development 签名身份；
2. iPhone 曾通过 USB 信任并启用 Developer Mode；
3. Xcode 的 Devices and Simulators 中勾选 Connect via network；
4. Mac 与 iPhone 位于同一局域网；
5. `Config.local.xcconfig` 已填写 Team ID 和高德 iOS Key。

满足条件后：

```bash
TESNAV_IOS_DEVICE_ID=<Xcode device UDID> ./install-wireless.sh
```

应用首次启动会显示隐私说明；同意后才初始化高德 SDK、请求定位和启动 C3XL
自动发现。高德导航 SDK 使用 `isUseInternalTTS` 进行系统语音播报。
