/// 数据模型：与原生 Kotlin 侧 LockConfig / TimeRule 一一对应。
/// 时间段用「自 00:00 起的分钟数」表示，start > end 即跨零点。

String hhmm(int minute) {
  final m = minute % 1440;
  final h = m ~/ 60;
  final mm = m % 60;
  return '${h.toString().padLeft(2, '0')}:${mm.toString().padLeft(2, '0')}';
}

class TimeSegment {
  int start;
  int end;
  /// 下标 0 = 周日，1 = 周一 ... 6 = 周六
  List<bool> days;

  TimeSegment({required this.start, required this.end, List<bool>? days})
      : days = days ?? List<bool>.filled(7, true);

  factory TimeSegment.fromMap(Map<dynamic, dynamic> m) {
    final raw = m['days'];
    List<bool> d = List<bool>.filled(7, true);
    if (raw is List) {
      d = List<bool>.generate(
        7,
        (i) => i < raw.length ? (raw[i] == true || raw[i] == 1) : false,
      );
    }
    return TimeSegment(
      start: _asInt(m['start']),
      end: _asInt(m['end']),
      days: d,
    );
  }

  Map<String, dynamic> toMap() => {
        'start': start,
        'end': end,
        'days': days.map((e) => e ? 1 : 0).toList(),
      };

  String get text => '${hhmm(start)} - ${hhmm(end)}';

  String get daysText {
    const names = ['日', '一', '二', '三', '四', '五', '六'];
    final sb = StringBuffer();
    for (var i = 0; i < 7; i++) {
      if (days[i]) sb.write('周${names[i]} ');
    }
    final s = sb.toString().trim();
    return s.isEmpty ? '未选星期' : s;
  }

  TimeSegment copy() => TimeSegment(start: start, end: end, days: List<bool>.from(days));
}

class LockConfig {
  static const String modeOpen = 'OPEN';
  static const String modeBlock = 'BLOCK';
  static const List<int> defaultKeys = [19, 19, 20, 20, 21, 21, 22, 22];

  bool enabled;
  String mode;
  List<TimeSegment> segments;
  List<int> unlockKeys;
  int singleUnlockMinutes;
  String password;
  int port;
  bool forceStop;
  bool lockNow;

  LockConfig({
    this.enabled = true,
    this.mode = modeOpen,
    List<TimeSegment>? segments,
    List<int>? unlockKeys,
    this.singleUnlockMinutes = 30,
    this.password = '123456',
    this.port = 6666,
    this.forceStop = true,
    this.lockNow = false,
  })  : segments = segments ?? [TimeSegment(start: 18 * 60, end: 20 * 60)],
        unlockKeys = unlockKeys ?? List<int>.from(defaultKeys);

  factory LockConfig.fromMap(Map<dynamic, dynamic> m) {
    final segs = <TimeSegment>[];
    final rawSegs = m['segments'];
    if (rawSegs is List) {
      for (final item in rawSegs) {
        if (item is Map) segs.add(TimeSegment.fromMap(item));
      }
    }
    final keys = <int>[];
    final rawKeys = m['unlockKeys'];
    if (rawKeys is List) {
      for (final k in rawKeys) {
        final v = _asInt(k);
        if (v > 0) keys.add(v);
      }
    }
    return LockConfig(
      enabled: m['enabled'] == true,
      mode: (m['mode'] ?? modeOpen).toString() == modeBlock ? modeBlock : modeOpen,
      segments: segs.isEmpty ? [TimeSegment(start: 18 * 60, end: 20 * 60)] : segs,
      unlockKeys: keys.isEmpty ? List<int>.from(defaultKeys) : keys,
      singleUnlockMinutes: _asInt(m['singleUnlockMinutes'], def: 30),
      password: (m['password'] ?? '123456').toString(),
      port: _asInt(m['port'], def: 6666),
      forceStop: m['forceStop'] == true,
      lockNow: m['lockNow'] == true,
    );
  }

  Map<String, dynamic> toMap() => {
        'enabled': enabled,
        'mode': mode,
        'segments': segments.map((s) => s.toMap()).toList(),
        'unlockKeys': unlockKeys,
        'singleUnlockMinutes': singleUnlockMinutes,
        'password': password,
        'port': port,
        'forceStop': forceStop,
        'lockNow': lockNow,
      };

  LockConfig copy() => LockConfig.fromMap(toMap());

  String get modeText => mode == modeBlock ? '禁用时间段' : '开放时间段';
}

/// 状态快照（原生 MonitorService.statusMap）
class KidStatus {
  final bool locked;
  final bool enabled;
  final String mode;
  final String nowText;
  final int unlockUntil;
  final String unlockUntilText;
  final bool manualLock;
  final String nextTransitionText;
  final bool lockVisible;
  final String foreground;
  final bool usageAccess;
  final bool deviceAdmin;
  final bool serviceRunning;
  final bool webRunning;
  final List<String> ips;
  final int port;
  final int singleUnlockMinutes;
  final String segmentsText;
  final String backupPath;

  KidStatus({
    required this.locked,
    required this.enabled,
    required this.mode,
    required this.nowText,
    required this.unlockUntil,
    required this.unlockUntilText,
    required this.manualLock,
    required this.nextTransitionText,
    required this.lockVisible,
    required this.foreground,
    required this.usageAccess,
    required this.deviceAdmin,
    required this.serviceRunning,
    required this.webRunning,
    required this.ips,
    required this.port,
    required this.singleUnlockMinutes,
    required this.segmentsText,
    required this.backupPath,
  });

  factory KidStatus.fromMap(Map<dynamic, dynamic> m) => KidStatus(
        locked: m['locked'] == true,
        enabled: m['enabled'] == true,
        mode: (m['mode'] ?? 'OPEN').toString(),
        nowText: (m['nowText'] ?? '--').toString(),
        unlockUntil: _asInt(m['unlockUntil']),
        unlockUntilText: (m['unlockUntilText'] ?? '--').toString(),
        manualLock: m['manualLock'] == true,
        nextTransitionText: (m['nextTransitionText'] ?? '--').toString(),
        lockVisible: m['lockVisible'] == true,
        foreground: (m['foreground'] ?? '').toString(),
        usageAccess: m['usageAccess'] == true,
        deviceAdmin: m['deviceAdmin'] == true,
        serviceRunning: m['serviceRunning'] == true,
        webRunning: m['webRunning'] == true,
        ips: (m['ips'] as List? ?? const []).map((e) => e.toString()).toList(),
        port: _asInt(m['port'], def: 6666),
        singleUnlockMinutes: _asInt(m['singleUnlockMinutes'], def: 30),
        segmentsText: (m['segmentsText'] ?? '--').toString(),
        backupPath: (m['backup'] ?? '').toString(),
      );

  String get urls => ips.map((ip) => 'http://$ip:$port').join('   ');
}

int _asInt(dynamic v, {int def = 0}) {
  if (v is num) return v.toInt();
  if (v is String) return int.tryParse(v) ?? def;
  return def;
}
