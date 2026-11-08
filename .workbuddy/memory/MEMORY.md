# KidLock 项目长期约定

## 项目定位
儿童限时管控 App，目标 Android 7.1.2 (API 25) / Amlogic arm64-v8a 电视盒子。
**本仓库（Flutter + Kotlin）是唯一真实项目**。曾另有一份纯 Java 零依赖的独立实现（在别的工作区），功能精简，已删除；勿再基于它改动，也不要照搬它的代码（权限模型、序列、端口都不同）。

## 技术栈（适用性优先）
- UI：Flutter (Dart) `lib/`；原生能力：Kotlin `android/app/src/main/kotlin/com/kidlock/app/`
- 零第三方运行时依赖；Web 控制台为自写 ServerSocket HTTP 服务 + 单文件内联 HTML/ES5 JS
- minSdk 24 / targetSdk 25 / 仅 arm64-v8a；版本由 `pubspec.yaml` 驱动（versionCode/versionName 自动传给 Gradle，勿手改 build.gradle.kts）
- 当前版本：**v1.4.4**（`1.4.4+9`，用户手动升版；发布包 `app-release-v1.4.4.apk`；v1.4.4 为无障碍配置编译修复版，功能同 v1.4.3）
- **工作区另有未定版本号的遥控器修订**：新增 `N1KeyBridge.kt`，按键优先复用盒子 8080 系统遥控服务（见下）。版本号由用户手动管理，勿擅自改 `pubspec.yaml` / `build_task.bat`

## Web 权限模型（重要）
- 所有 JSON 响应恒为 **HTTP 200**，成功与否一律看 body 的 `ok` 字段；密码错误为 `ok:false, error:"密码错误"`
- 读免密：`GET /api/config`（不下发 password 与两组 unlockKeys，只给 keyCount1/2）、`/api/status`、`/api/screen`
- 写需密码：`POST /api/config`、`/api/action`、`/api/auth`、`/api/key`
- 前端无常驻密码输入框，写操作统一走 `askPassword()` 弹窗；`pwdCache` 只用于弹窗预填

## 版本线
v1.2 → v1.3（家长门禁 GateActivity + 双序列：遥控器方向键 / 手机音量键）→ v1.3.1（端口 6666→9090，避开浏览器不安全端口黑名单）→ v1.4.0（IPTV 锁屏静音三重兜底）→ v1.4.1（Web 移动端适配 + MediaProjection 免密截图 + 配置外置备份）→ v1.4.2（全屏 Web 遥控器 + 配置页 ? 浮窗 / 长卡片折叠）→ v1.4.3（返回键失效修复：root `su` 注入 / 无障碍全局返回；新增音量 ± 与逐路径报错）→ v1.4.4（编译修复：删除无障碍配置里非法的 `android:flags`）→ **未定版本号修订**（参考 Jimmuy/N1_controler，遥控器改为优先复用盒子 8080 系统遥控服务，N1/YYF 固件零配置可用）

## 关键约定
- **改完代码不编译**，用户自行在 VSCode 终端跑 `build_task.bat` 或 `flutter build apk --release --target-platform android-arm64`
- 校验手段：node 抽 `<script>` 用 `new Function` 查 JS 语法、DOM id 引用、静态标签配对；Kotlin 括号必须用**状态机**剥离注释/字符串后配对（正则剥离会误伤字符串里的 `//` 导致全文件误报）
- 浏览器禁用端口含 6666 / 6665-6669，Web 默认端口 **9090**；**8080 是盒子系统遥控服务的端口，不要占用，要复用**
- **盒子系统遥控服务桥接（遥控器首选路径，重要）**：参考 https://github.com/Jimmuy/N1_controler —— 手机端「N1 遥控器」App 自己不注入按键，只 POST `http://<盒子IP>:8080/v1/keyevent`，body `{"keycode":4,"longclick":false}`；真正执行 `input keyevent` 的是盒子 YYF 系统里跑在 system/root 身份的常驻服务。KidLock 装在同一台盒子上，因此 `N1KeyBridge.kt` 从 `127.0.0.1:8080` 转发（Kotlin 侧转发而非网页 JS 直连：绕开 CORS、保留 `/api/key` 密码校验、能读 HTTP 状态码）。探测只做 TCP connect、失败缓存 30s；非 YYF 固件自动回退，不受影响。Manifest 需 `android:usesCleartextTraffic="true"`
- **Web 遥控器返回键的权限前提**：普通应用无 `INJECT_EVENTS`（signature 级），应用进程内任何注入方式都会被拒。**首选 8080 系统服务，此时无需 root / 无障碍 / 任何配置**；盒子上没有该服务时才回退到 root（`su -c "input keyevent 4"`，等价 adb shell）、无障碍 `performGlobalAction(GLOBAL_ACTION_BACK)`、自家界面 `dispatchKeyEvent`。音量键走 `AudioManager`，不受此限制
- `/api/status` 新增 `bridge` / `bridgeDetail`；`/api/action` 新增 `reprobeBridge`（返回 `{bridge, detail}`）；成功 `path` 为 `盒子系统遥控服务 :8080/v1/keyevent`
- **无障碍服务配置 XML 的坑**：`<accessibility-service>` 的合法属性是 `android:accessibilityFlags`，**不存在** `android:flags`；写错会让 AAPT2 link 直接报错、Release 包无法构建。不需要自定义 flag 时整项省略即可（默认就是 `flagDefault`）。属性合法性对照 `platforms/android-36/data/res/values/attrs.xml` 的 `AccessibilityService` styleable
- 资源编译自检：用 `build-tools/36.0.0/aapt2.exe compile --dir <res> -o <zip>` + `aapt2 link -I <android.jar> --manifest <manifest>`；源码 Manifest 缺 `package` 是 Flutter/AGP 常态（namespace 由 Gradle 注入），单独跑 link 时需在临时副本里补 `package="com.kidlock.app"`
- 发布前清理：build/、.dart_tool/、.gradle/、.idea/、.git/ 等；隐私检查 grep 本机用户名与绝对路径
- **发布包需排除 `.workbuddy/`**（本记忆目录，非源码）
- 用户偏好：结构化表格输出；改动要给理由；不主动编译
