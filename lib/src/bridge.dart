import 'package:flutter/services.dart';

import 'model.dart';

/// 与原生 Kotlin 侧通信的桥接层。
/// 原生负责：常驻服务 / 锁屏 / 杀进程 / 设备管理器 / Web 服务；
/// Dart 负责：TV 端界面与配置编辑。
class Bridge {
  static const MethodChannel _c = MethodChannel('com.kidlock.app/bridge');

  static Future<LockConfig> getConfig() async {
    final res = await _c.invokeMethod('getConfig');
    return LockConfig.fromMap(Map<dynamic, dynamic>.from(res as Map));
  }

  static Future<LockConfig> saveConfig(LockConfig cfg) async {
    final res = await _c.invokeMethod('saveConfig', cfg.toMap());
    return LockConfig.fromMap(Map<dynamic, dynamic>.from(res as Map));
  }

  static Future<KidStatus> getStatus() async {
    final res = await _c.invokeMethod('getStatus');
    return KidStatus.fromMap(Map<dynamic, dynamic>.from(res as Map));
  }

  static Future<void> startService() => _c.invokeMethod('startService');
  static Future<void> unlockOnce() => _c.invokeMethod('unlockOnce');
  static Future<void> clearUnlock() => _c.invokeMethod('clearUnlock');
  static Future<void> lockNow() => _c.invokeMethod('lockNow');
  static Future<void> restartWeb() => _c.invokeMethod('restartWeb');

  /// 立即把配置备份到外部存储，返回备份文件路径（失败返回空串）
  static Future<String> backupConfig() async {
    final res = await _c.invokeMethod('backupConfig');
    return (res ?? '').toString();
  }

  static Future<void> openUsageAccess() => _c.invokeMethod('openUsageAccess');
  static Future<void> activateDeviceAdmin() => _c.invokeMethod('activateDeviceAdmin');
}

/// Android KeyCode -> 显示名（与原生 KeySequenceMatcher.name 保持一致）
String keyName(int code) {
  switch (code) {
    case 19:
      return '↑';
    case 20:
      return '↓';
    case 21:
      return '←';
    case 22:
      return '→';
    case 23:
      return 'OK';
    case 66:
      return '确定';
    case 4:
      return '返回';
    case 82:
      return '菜单';
    case 62:
      return '空格';
    default:
      if (code >= 7 && code <= 16) return '${code - 7}';
      return 'K$code';
  }
}
