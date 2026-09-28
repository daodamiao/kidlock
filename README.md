# KidLock · 儿童限时管控（Android 7.1.2 / Amlogic arm64-v8a 电视盒子）

面向 **Android 7.1.2（API 25）+ Amlogic 四核 arm64-v8a 电视盒子** 的儿童限时管控 App。
TV 端界面用 **Flutter（Dart）**，系统级能力（常驻服务 / 全屏锁屏 / 按键拦截 / 杀进程 / 设备管理器 / 内嵌 Web 服务）用 **Kotlin 原生**实现，二者通过 MethodChannel 通信。

---
This project source code is available for non-commercial use only. Commercial use requires written authorization. 
本项目源码仅允许非商业使用，商业使用需要作者书面授权

## 一、技术选型

| 项目 | 选择 | 理由 |
|---|---|---|
| 项目类型 | Android TV 端常驻管控工具（系统级能力 + 轻量配置 UI） | 需要前台服务、Activity 抢占前台、按键拦截、设备管理器 |
| 语言 / 框架 | **Flutter 3.47（Dart 3.13）+ Kotlin 原生** | Flutter 负责 TV 大屏 UI（D-pad 焦点体系完善、开发快）；系统能力无法用 Dart 实现（服务保活、锁屏 Activity、`dispatchKeyEvent`、`DevicePolicyManager`），必须走原生 |
| 原生侧 UI | Kotlin 原生 View（不引入 Compose） | 锁屏页要求“到点秒弹 + 极低内存”，原生 View 首帧 <100ms，省下 Compose 运行时的 10~20MB 内存与冷启动开销 |
| 网络 | 自写 `ServerSocket` HTTP 服务 | 盒子内存有限，不引入 Netty/Ktor；零第三方依赖 |
| JSON | 系统内置 `org.json` | 同上 |
| 持久化 | SharedPreferences + JSON | 断网 / 重启都不依赖任何外部服务 |

**备选方案对比**

| 方案 | 优点 | 为什么不选 |
|---|---|---|
| 纯 Flutter（Dart 侧跑 Web 服务 / 定时器） | 代码单一 | Dart isolate 随进程销毁；锁屏到点必须靠原生服务，后台 isolate 在 TV 上保活不可靠 |
| Kotlin + Jetpack Compose | 全原生一致性 | 锁屏冷启动慢 100~300ms、内存多 10~20MB，低端 SoC 不划算 |
| 引入第三方 HTTP / JSON 库 | 开发省事 | 增加包体与方法数，本项目协议极简，标准库足够 |

---

## 二、目录结构

```
KidLock/
├── pubspec.yaml
├── lib/                                  # Flutter（Dart）端
│   ├── main.dart                         # 入口 + 主题
│   └── src/
│       ├── bridge.dart                   # MethodChannel 桥接 + 按键名映射
│       ├── model.dart                    # LockConfig / TimeSegment / KidStatus
│       ├── home_page.dart                # 盒子端主控台（状态 + 操作）
│       └── settings_page.dart            # 盒子端设置（时段 / 序列 / 密码 / 端口）
└── android/app/src/main/
    ├── AndroidManifest.xml               # 权限 + 服务 + 广播 + 设备管理器
    ├── kotlin/com/kidlock/app/
    │   ├── KidLockApp.kt                 # Application：进程启动即拉起服务
    │   ├── MainActivity.kt               # FlutterActivity + MethodChannel
    │   ├── MonitorService.kt             # 常驻服务：定时 / 抢前台 / 杀进程 / 闹钟 / 心跳 / Web
    │   ├── LockScreenActivity.kt         # 全屏锁屏页：吞掉所有遥控器按键
    │   ├── GateActivity.kt               # 家长验证门禁页：打开 App 前必须输入序列
    │   ├── TimeRule.kt                   # 规则引擎：两种模式 / 多段 / 星期 / 跨零点
    │   ├── KeySequenceMatcher.kt         # 多组解锁序列并行匹配（遥控器方向键 / 手机音量键）
    │   ├── ForegroundHelper.kt           # 前台应用识别（UsageStats + 降级）
    │   ├── ConfigStore.kt / Config.kt    # 配置模型 + SharedPreferences 持久化
    │   ├── ConfigWebServer.kt            # 内嵌 HTTP 服务（配置页 + JSON API）
    │   ├── BootReceiver.kt               # 开机 / 升级 / 时间变更自启
    │   └── KidDeviceAdmin.kt             # 设备管理器（防卸载 + lockNow）
    └── res/
        ├── raw/config_page.html          # Web 配置页面（单文件，内联 CSS/JS）
        ├── xml/device_admin.xml
        ├── drawable-xxhdpi/ic_stat.png   # 通知小图标
        └── values/{strings,styles}.xml   # LaunchTheme / NormalTheme / LockTheme
```

---

## 三、构建配置（关键）

| 项 | 值 | 说明 |
|---|---|---|
| Flutter | 3.47.5（Dart 3.13.4）稳定版 | |
| `compileSdk` | 36（跟随 Flutter 默认） | 仅工具链使用，不影响运行时 |
| `minSdk` | **24** | Flutter 3.x 引擎下限；目标设备为 25，满足 |
| `targetSdk` | **25** | **核心**：以 API 25 兼容行为运行，绕开 API 26+ 的后台启动限制、通知渠道、隐式广播限制 |
| `applicationId` | `com.kidlock.app` | |
| ABI | `arm64-v8a`（splits 只打 arm64） | 无 32 位负担，包体最小 |
| 工具链 | JDK 17+ / Kotlin 2.4 / AGP 9.1 / Gradle 9.3 | 模板默认 |

> 代码里用到的最高运行时 API 是 23（`AlarmManager.setExactAndAllowWhileIdle`），已做 `Build.VERSION` 判断；
> `AudioManager.dispatchMediaKeyEvent` 用反射调用并 try-catch，避免不同固件差异导致崩溃。

---

## 四、所需权限

| 权限 | 危险权限 | 用途 |
|---|---|---|
| `RECEIVE_BOOT_COMPLETED` | 否 | 开机自启、重启后规则继续生效 |
| `INTERNET` / `ACCESS_NETWORK_STATE` / `ACCESS_WIFI_STATE` | 否 | 局域网内嵌 Web 服务、展示盒子 IP |
| `WAKE_LOCK` | 否 | 到点唤醒设备执行锁定 |
| `KILL_BACKGROUND_PROCESSES` | 否 | 到点停止正在播放的应用 |
| `DISABLE_KEYGUARD` / `REORDER_TASKS` | 否 | 锁屏页覆盖显示、置前 |
| `PACKAGE_USAGE_STATS` | **是（需手动授权）** | 精确识别前台应用；不授权自动降级为进程重要性推断，功能不失效 |

授权 `PACKAGE_USAGE_STATS`（三选一）：

```bash
adb shell appops set com.kidlock.app GET_USAGE_STATS allow     # 推荐
# 或：设置 → 安全 → 有权查看使用情况的应用 → 勾选「儿童限时管控」
# 或：App 首页点「授权使用情况访问」跳转授权页
```

---

## 五、功能实现要点

### 1. 定时锁定（强制停止播放 + 全屏锁屏）
到点时 `MonitorService` 依次执行：
1. `AudioManager` 媒体暂停键（反射）+ 通用暂停广播 → 先暂停播放；
2. 启动 `LockScreenActivity` **抢占前台**（原播放 App 退到后台并 onPause）；
3. 延迟 300ms 后 `ActivityManager.killBackgroundProcesses(pkg)` → 彻底停掉进程；
4. 可选：`DevicePolicyManager.lockNow()` 直接黑屏（需已激活设备管理器）。

> 这样无需 `FORCE_STOP_PACKAGES`（系统签名权限）也能达到“强制停止”效果。

### 2. 屏蔽遥控器
- `LockScreenActivity.dispatchKeyEvent()` **对所有按键返回 true**（方向键 / 确认 / 返回 / 数字 / 音量全部吞掉），`onBackPressed()` 空实现。
- HOME 键无法被普通应用拦截，用三条措施兜底：① 服务 10s 轮询 + 3s 重试，锁屏被盖住就重新弹出；② 锁屏已显示时若仍有第三方应用抢前台，直接杀掉；③ 设备管理器固化。
- 锁屏页是**独立 task**（`taskAffinity` + `excludeFromRecents`），不污染主界面返回栈。

### 3. 解锁序列（两组并行，任一组命中即可）
- **序列 1 · 电视遥控器**：默认 `上 上 下 下 左 左 右 右`（KeyCode 19,19,20,20,21,21,22,22）
- **序列 2 · 手机音量键**：默认 `音量+ 音量+ 音量- 音量-`（KeyCode 24,24,25,25）
- 不做设备类型判断：**两种序列同时生效**，遥控器盒子与手机都能用同一套解锁方式。
- 3 秒内未按完则重新匹配，允许“重叠开头”（如 `↑↑↓` 后再按 `↑↑↓↓←←→→` 仍可命中）。
- 三处入口共用同一匹配器：**锁屏页**、**打开 App 的家长验证页**、解锁后的放行判定。
- 可自定义，但只能在 **Web 控制台**修改（虚拟按键点击录入，不录制真实按键）。

### 3.1 打开 App 也要验证（家长门禁 GateActivity）
- 冷启动进入 App 前先弹出「家长验证」页，必须输入任一序列才能看到主界面，**防止孩子自己打开 App 改配置**。
- 验证页吞掉所有按键（含返回键），验证不通过直接退到后台，不展示任何界面。
- 短暂切到后台再回来（如去系统设置授权）不会重复要求；离开 App 超过 **3 分钟**再回来需重新验证。
- HOME 键由系统处理，家长可随时按 HOME 退出。

### 3.2 单次解锁时长
- 命中后：`解锁截止 = min(单次解锁时长, 本次锁定的自然结束时刻)` → **保证下一个禁用时间点仍然会锁**，也保证解锁时长不会溢出到下一段开放时间。

### 4. 两种时间模式 + 多段 + 星期 + 跨零点
- `OPEN`：时段内可看、时段外锁定；`BLOCK`：仅时段内锁定。
- 每段独立勾选星期；`start > end` 自动按跨零点处理，判定时同时回看“昨天生效且跨零点”的段（如周日 22:00→01:00，周一 00:30 仍命中）。

### 5. 稳定性 / 保活
- **双通道定时**：10s 轮询 + 下一翻转点的精确闹钟（`RTC_WAKEUP`）。
- **心跳 10 分钟** + `onDestroy` / `onTaskRemoved` 注册 2~3s 的一次性重启闹钟 + `START_STICKY`。
- **断网 / 重启不受影响**：规则只依赖本地 SharedPreferences 与系统时间，Web 服务只是配置入口。
- 所有系统调用均 try-catch，单点异常不会让服务崩溃。

---

## 六、编译与安装部署

### 编译
```bash
# 方式 A：Android Studio 打开本目录 → flutter pub get → Build APK
# 方式 B：命令行（Flutter 3.47+ / JDK 17+）
cd KidLock
flutter pub get
flutter build apk --release --target-platform android-arm64
# 产物：build/app/outputs/flutter-apk/app-arm64-v8a-release.apk
```

### 安装（adb 连盒子）
```bash
adb connect 192.168.1.50:5555          # 盒子 IP，或用 USB
adb install -r build/app/outputs/flutter-apk/app-arm64-v8a-release.apk
adb shell am start -n com.kidlock.app/.MainActivity
```

### 一次性初始化（强烈建议）
```bash
# 1) 授权前台应用识别（提高“杀进程”精度）
adb shell appops set com.kidlock.app GET_USAGE_STATS allow

# 2) 激活设备管理器（防止被卸载）—— 也可在 App 内点按钮激活
adb shell dpm set-active-admin com.kidlock.app/.KidDeviceAdmin

# 3)（可选加固）设为 Device Owner，卸载需先移除，能力最强
adb shell dpm set-device-owner com.kidlock.app/.KidDeviceAdmin
# 注意：set-device-owner 只能在“未设置任何账号”的设备上执行一次。
```

### 配置（局域网 Web）
1. 打开盒子上的「儿童限时管控」（需先输入家长解锁序列），屏幕显示 `http://192.168.x.x:6666`；
2. 同一 WiFi 下的电脑 / 手机浏览器打开该地址；
3. 页面**自动读取**当前配置（无需密码）；修改后填入「当前密码」（默认 **`123456`**）→ 「保存并立即生效」；
4. 解锁序列默认**隐藏**：点「显示序列」并输入当前密码后才可见/可编辑，用虚拟按键点击录入；
5. 建议第一时间改掉管理密码。

盒子端「设置」页也支持遥控器操作，但**解锁序列只做只读展示**，修改请走 Web 控制台。

### 默认配置
- 模式：开放时间段（18:00–20:00，全星期）
- 解锁序列 1（遥控器）：上 上 下 下 左 左 右 右
- 解锁序列 2（手机音量键）：音量+ 音量+ 音量- 音量-
- 单次解锁：30 分钟；端口：6666；密码：123456
- 配置双保险：内部 SharedPreferences（升级/覆盖安装保留）+ 外部备份 `/sdcard/KidLock/kidlock_config.json`（卸载重装自动恢复）

---

## 七、Web API

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/status` | 状态快照（无需密码） |
| GET | `/api/config` | 读取配置（无需密码；`password` 与两组 `unlockKeys*` 均不下发，只返回步数 `keyCount1/keyCount2`） |
| POST | `/api/config` | JSON（含 `pwd` 校验）保存并立即生效；`password` 留空表示不修改；未提交 `unlockKeys*` 则保持原序列不变 |
| POST | `/api/action` | `{pwd, action}`，action ∈ `unlock` / `lock` / `clearUnlock` / `restartWeb` / `backup` / `eval` / `showKeys`（校验密码后返回两组真实序列） |

配置 JSON 示例：

```json
{
  "enabled": true,
  "mode": "OPEN",
  "segments": [{ "start": 1080, "end": 1200, "days": [1,1,1,1,1,1,1] }],
  "unlockKeys": [19,19,20,20,21,21,22,22],
  "unlockKeys2": [24,24,25,25],
  "singleUnlockMinutes": 30,
  "password": "123456",
  "port": 6666,
  "forceStop": true,
  "lockNow": false
}
```
`days` 顺序为 **周日→周六**；`start/end` 为自 00:00 起的分钟数，`start > end` 表示跨零点。

---

## 八、MethodChannel（Dart ↔ 原生）

通道名：`com.kidlock.app/bridge`

| Dart 调用 | 作用 |
|---|---|
| `getConfig` / `saveConfig` | 读写配置（端口变化会自动重启 Web 服务，其余改动立即重算规则） |
| `getStatus` | 状态快照：锁定状态、下次翻转时间、前台应用、IP、服务 / Web 是否运行等 |
| `startService` | 拉起守护服务 |
| `unlockOnce` / `clearUnlock` / `lockNow` | 临时解锁 / 清除解锁 / 立即锁定 |
| `restartWeb` | 重启内嵌 Web 服务 |
| `openUsageAccess` / `activateDeviceAdmin` | 跳转系统授权页 |

---

## 九、常见问题

| 现象 | 排查 |
|---|---|
| 浏览器打不开 | 确认电脑 / 手机与盒子同网段；端口被占用可改成 8081 后保存 |
| 到点没锁住 | 看 App 首页「守护中」徽标；`adb shell dumpsys activity services com.kidlock.app` |
| 杀不掉播放器 | 授权 `GET_USAGE_STATS`；部分系统级播放器可改为开启「到点黑屏锁屏」 |
| 按序列没解锁 | 需 3 秒内按完；确认 Web 控制台「显示序列」里的两组序列与实际按键一致（遥控器方向键 / 手机音量键都可） |
| 打开 App 就要求验证 | 正常：冷启动必须验证家长序列；离开超过 3 分钟再回来也会要求，避免孩子自己打开改配置 |
| 卸载不了 | 先「设置 → 安全 → 设备管理器」取消激活，或 `adb shell dpm remove-active-admin com.kidlock.app/.KidDeviceAdmin` |
| 遥控器在主界面不听使唤 | 用方向键 + 确认键导航；密码 / 端口输入框需要外接键盘，或改用 Web 配置 |
