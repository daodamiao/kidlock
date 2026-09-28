import 'dart:async';

import 'package:flutter/material.dart';

import 'bridge.dart';
import 'model.dart';
import 'settings_page.dart';
import 'tv_focus.dart';

/// 盒子端主控台：显示当前状态、局域网访问地址、常用操作入口。
class HomePage extends StatefulWidget {
  const HomePage({super.key});

  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage> {
  KidStatus? _status;
  String _error = '';
  Timer? _timer;

  @override
  void initState() {
    super.initState();
    _refresh();
    _timer = Timer.periodic(const Duration(seconds: 2), (_) => _refresh());
  }

  @override
  void dispose() {
    _timer?.cancel();
    super.dispose();
  }

  Future<void> _refresh() async {
    try {
      final s = await Bridge.getStatus();
      if (!mounted) return;
      setState(() {
        _status = s;
        _error = '';
      });
    } catch (e) {
      if (!mounted) return;
      setState(() => _error = '$e');
    }
  }

  void _snack(String text) {
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text(text), duration: const Duration(seconds: 2)),
    );
  }

  Future<void> _run(String label, Future<void> Function() f) async {
    try {
      await f();
      _snack('$label：完成');
    } catch (e) {
      _snack('$label：失败（$e）');
    }
    await _refresh();
  }

  @override
  Widget build(BuildContext context) {
    final s = _status;
    return Scaffold(
      body: SafeArea(
        child: Padding(
          padding: const EdgeInsets.all(28),
          child: s == null
              ? Center(
                  child: Text(
                    _error.isEmpty ? '正在连接守护服务…' : '读取状态失败：$_error',
                    style: const TextStyle(fontSize: 20, color: Color(0xFF93A1B5)),
                  ),
                )
              : Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Expanded(child: _statusCard(s)),
                    const SizedBox(width: 28),
                    SizedBox(width: 360, child: _actions(s)),
                  ],
                ),
        ),
      ),
    );
  }

  Widget _statusCard(KidStatus s) {
    final locked = s.locked;
    return Container(
      padding: const EdgeInsets.all(24),
      decoration: BoxDecoration(
        color: const Color(0xFF181D26),
        borderRadius: BorderRadius.circular(16),
        border: Border.all(color: const Color(0xFF2A3240)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        mainAxisSize: MainAxisSize.min,
        children: [
          Row(
            children: [
              const Text(
                '儿童限时管控',
                style: TextStyle(fontSize: 30, fontWeight: FontWeight.bold),
              ),
              const SizedBox(width: 16),
              _badge(locked ? '已锁定' : '未锁定', locked ? const Color(0xFFFF7A5A) : const Color(0xFF37C26B)),
              const SizedBox(width: 12),
              _badge(s.serviceRunning ? '守护中' : '未守护',
                  s.serviceRunning ? const Color(0xFF37C26B) : const Color(0xFFFF7A5A)),
            ],
          ),
          const SizedBox(height: 18),
          _infoRow('盒子时间', s.nowText),
          _infoRow('管控模式', '${s.mode == LockConfig.modeBlock ? '禁用时间段' : '开放时间段'}（${s.enabled ? '已启用' : '已停用'}）'),
          _infoRow('生效时间段', s.segmentsText),
          _infoRow('临时解锁至', s.unlockUntil > 0 ? s.unlockUntilText : '无'),
          _infoRow('下次状态变化', s.nextTransitionText),
          _infoRow('锁屏显示中', s.lockVisible ? '是' : '否'),
          _infoRow('当前前台应用', s.foreground.isEmpty ? '未知' : s.foreground),
          _infoRow('访问地址', s.urls.isEmpty ? '未获取到局域网 IP' : s.urls),
          _infoRow('配置备份', s.backupPath.isEmpty ? '未写入（点上方按钮或 Web 后台「立即备份配置」）' : s.backupPath),
          const SizedBox(height: 10),
          Text(
            '同一局域网的电脑 / 手机浏览器打开上面的地址即可配置（密码见设置页）',
            style: TextStyle(fontSize: 14, color: Colors.white.withValues(alpha: 0.55)),
          ),
        ],
      ),
    );
  }

  Widget _actions(KidStatus s) {
    return Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        _btn('启动 / 重启守护服务', const Color(0xFF4DA3FF), () => _run('守护服务', Bridge.startService)),
        _btn('打开设置', const Color(0xFF4DA3FF), () async {
          await Navigator.of(context).push(
            MaterialPageRoute(builder: (_) => const SettingsPage()),
          );
          await _refresh();
        }),
        _btn('立即锁定', const Color(0xFFC9523F), () => _run('立即锁定', Bridge.lockNow)),
        _btn('临时解锁 ${s.singleUnlockMinutes} 分钟', const Color(0xFF37C26B), () => _run('临时解锁', Bridge.unlockOnce)),
        _btn('清除临时解锁', const Color(0xFF263041), () => _run('清除解锁', Bridge.clearUnlock)),
        _btn('重启 Web 服务', const Color(0xFF263041), () => _run('Web 服务', Bridge.restartWeb)),
        _btn('立即备份配置（更新/重装不丢设置）', const Color(0xFF263041), () async {
          try {
            final p = await Bridge.backupConfig();
            _snack(p.isEmpty ? '备份失败：请在系统设置中授予存储权限' : '已备份到 $p');
          } catch (e) {
            _snack('备份失败：$e');
          }
          await _refresh();
        }),
        _btn(
          s.usageAccess ? '使用情况访问：已授权' : '授权使用情况访问',
          const Color(0xFF263041),
          () => _run('授权页', Bridge.openUsageAccess),
        ),
        _btn(
          s.deviceAdmin ? '设备管理器：已激活' : '激活设备管理器（防卸载）',
          const Color(0xFF263041),
          () => _run('设备管理器', Bridge.activateDeviceAdmin),
        ),
      ],
    );
  }

  Widget _btn(String text, Color color, Future<void> Function() onPressed) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 12),
      child: TvFocus(
        child: SizedBox(
          width: double.infinity,
          height: 56,
          child: ElevatedButton(
            style: ElevatedButton.styleFrom(
              backgroundColor: color,
              foregroundColor: Colors.white,
              shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
            ),
            onPressed: onPressed,
            child: Text(text, style: const TextStyle(fontSize: 18)),
          ),
        ),
      ),
    );
  }

  Widget _badge(String text, Color color) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 6),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.18),
        borderRadius: BorderRadius.circular(99),
      ),
      child: Text(text, style: TextStyle(color: color, fontSize: 16)),
    );
  }

  Widget _infoRow(String label, String value) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 5),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          SizedBox(
            width: 130,
            child: Text(
              label,
              style: TextStyle(fontSize: 16, color: Colors.white.withValues(alpha: 0.55)),
            ),
          ),
          Expanded(child: Text(value, style: const TextStyle(fontSize: 17))),
        ],
      ),
    );
  }
}
