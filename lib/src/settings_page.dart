import 'package:flutter/material.dart';

import 'bridge.dart';
import 'model.dart';
import 'tv_focus.dart';

/// 盒子端设置页（遥控器可操作）：
/// 模式 / 多段时间 / 星期 / 单次解锁时长 / 管理密码 / Web 端口。
/// 解锁按键序列只读展示——修改请使用 Web 控制台（虚拟按键点击录入）。
class SettingsPage extends StatefulWidget {
  const SettingsPage({super.key});

  @override
  State<SettingsPage> createState() => _SettingsPageState();
}

class _SettingsPageState extends State<SettingsPage> {
  LockConfig? _cfg;
  bool _saving = false;
  String _error = '';
  int _port = 6666;
  final TextEditingController _pwdController = TextEditingController();

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void dispose() {
    _pwdController.dispose();
    super.dispose();
  }

  Future<void> _load() async {
    try {
      final c = await Bridge.getConfig();
      if (!mounted) return;
      setState(() {
        _cfg = c;
        _pwdController.text = c.password;
        _port = c.port;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() => _error = '$e');
    }
  }

  Future<void> _save() async {
    final cfg = _cfg;
    if (cfg == null) return;
    setState(() => _saving = true);
    try {
      cfg.password =
          _pwdController.text.trim().isEmpty ? '123456' : _pwdController.text.trim();
      cfg.port = _port;
      final saved = await Bridge.saveConfig(cfg);
      if (!mounted) return;
      setState(() => _cfg = saved);
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('已保存并立即生效')),
      );
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('保存失败：$e')));
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  /// 弹出电视端虚拟键盘修改管理密码（纯按钮，遥控器方向键可切换）
  Future<void> _editPassword() async {
    final edited = await showDialog<String>(
      context: context,
      builder: (_) => _PasswordDialog(initial: _pwdController.text),
    );
    if (edited != null && mounted) {
      setState(() => _pwdController.text = edited);
    }
  }

  @override
  Widget build(BuildContext context) {
    final cfg = _cfg;
    return Scaffold(
      appBar: AppBar(
        title: const Text('设置'),
        actions: [
          Padding(
            padding: const EdgeInsets.only(right: 16),
            child: Center(
              child: TvFocus(
                child: _saving
                    ? const SizedBox(
                        width: 20,
                        height: 20,
                        child: CircularProgressIndicator(strokeWidth: 2))
                    : TextButton(
                        onPressed: _save,
                        child: const Text('保存并立即生效',
                            style: TextStyle(fontSize: 18)),
                      ),
              ),
            ),
          ),
        ],
      ),
      body: cfg == null
          ? Center(
              child: Text(
                _error.isEmpty ? '读取配置中…' : '读取失败：$_error',
                style: const TextStyle(fontSize: 18, color: Color(0xFF93A1B5)),
              ),
            )
          : ListView(
              padding: const EdgeInsets.all(24),
              children: [
                _card(
                  '基本设置',
                  [
                    _switchTile('启用管控', cfg.enabled,
                        (v) => setState(() => cfg.enabled = v)),
                    const SizedBox(height: 12),
                    const Text('时间模式', style: TextStyle(fontSize: 17)),
                    const SizedBox(height: 8),
                    Row(
                      children: [
                        _choice('开放时间段（时段内可看）', cfg.mode == LockConfig.modeOpen,
                            () => setState(() => cfg.mode = LockConfig.modeOpen)),
                        const SizedBox(width: 12),
                        _choice('禁用时间段（仅时段内锁定）', cfg.mode == LockConfig.modeBlock,
                            () => setState(() => cfg.mode = LockConfig.modeBlock)),
                      ],
                    ),
                  ],
                ),
                _card(
                  '时间段（多段 · 按星期重复 · 支持跨零点）',
                  [
                    for (var i = 0; i < cfg.segments.length; i++)
                      _segmentEditor(cfg, i),
                    const SizedBox(height: 8),
                    Align(
                      alignment: Alignment.centerLeft,
                      child: TvFocus(
                        child: TextButton.icon(
                          onPressed: () => setState(
                            () => cfg.segments
                                .add(TimeSegment(start: 18 * 60, end: 20 * 60)),
                          ),
                          icon: const Icon(Icons.add),
                          label: const Text('添加时间段'),
                        ),
                      ),
                    ),
                  ],
                ),
                _card(
                  '家长解锁序列（只读）',
                  [
                    Wrap(
                      spacing: 8,
                      runSpacing: 8,
                      children: [
                        for (final k in cfg.unlockKeys)
                          Chip(
                            label: Text(keyName(k),
                                style: const TextStyle(fontSize: 16)),
                          ),
                        if (cfg.unlockKeys.isEmpty)
                          const Text('（未设置）',
                              style: TextStyle(color: Color(0xFF93A1B5))),
                      ],
                    ),
                    const SizedBox(height: 10),
                    const Text(
                      '为避免孩子看到解锁方式，此处不再展示与编辑序列。\n'
                      '如需修改：请在同一局域网的电脑/手机浏览器打开 Web 控制台'
                      '（地址见主界面"访问地址"），用页面上的虚拟按键点击录入。',
                      style: TextStyle(fontSize: 14, color: Color(0xFF93A1B5)),
                    ),
                  ],
                ),
                _card(
                  '单次解锁与安全',
                  [
                    _stepperRow(
                      '单次解锁时长（分钟）',
                      cfg.singleUnlockMinutes,
                      (v) => setState(
                          () => cfg.singleUnlockMinutes = v.clamp(1, 1440)),
                      step: 5,
                    ),
                    const SizedBox(height: 14),
                    // 管理密码（上下布局，点 OK 弹出虚拟键盘修改）
                    Container(
                      padding: const EdgeInsets.symmetric(
                          horizontal: 14, vertical: 6),
                      decoration: BoxDecoration(
                        color: const Color(0xFF0F141C),
                        borderRadius: BorderRadius.circular(12),
                        border:
                            Border.all(color: const Color(0xFF2A3240)),
                      ),
                      child: Row(
                        children: [
                          const Text('管理密码（Web 控制台）',
                              style: TextStyle(fontSize: 16)),
                          const Spacer(),
                          Text(
                            _pwdController.text.isEmpty
                                ? '（未设置）'
                                : '•' * _pwdController.text.length,
                            style: const TextStyle(
                                fontSize: 16, color: Color(0xFF93A1B5)),
                          ),
                          const SizedBox(width: 10),
                          TvFocus(
                            child: ElevatedButton(
                              onPressed: _editPassword,
                              child: const Text('修改'),
                            ),
                          ),
                        ],
                      ),
                    ),
                    const SizedBox(height: 12),
                    // Web 端口（上下布局，纯步进按钮，遥控器方向键可切换）
                    Container(
                      padding: const EdgeInsets.symmetric(
                          horizontal: 14, vertical: 6),
                      decoration: BoxDecoration(
                        color: const Color(0xFF0F141C),
                        borderRadius: BorderRadius.circular(12),
                        border:
                            Border.all(color: const Color(0xFF2A3240)),
                      ),
                      child: _stepperRow(
                        'Web 端口（1024-65535）',
                        _port,
                        (v) => setState(() => _port = v.clamp(1024, 65535)),
                        step: 1,
                        wideStep: 10,
                      ),
                    ),
                    const SizedBox(height: 12),
                    _switchTile('到点强制停止前台应用', cfg.forceStop,
                        (v) => setState(() => cfg.forceStop = v)),
                    _switchTile('到点同时黑屏锁屏（需设备管理器）', cfg.lockNow,
                        (v) => setState(() => cfg.lockNow = v)),
                  ],
                ),
                const SizedBox(height: 20),
                TvFocus(
                  child: SizedBox(
                    height: 56,
                    child: ElevatedButton(
                      onPressed: _saving ? null : _save,
                      child: const Text('保存并立即生效',
                          style: TextStyle(fontSize: 18)),
                    ),
                  ),
                ),
                const SizedBox(height: 40),
              ],
            ),
    );
  }

  Widget _segmentEditor(LockConfig cfg, int index) {
    final seg = cfg.segments[index];
    const names = ['日', '一', '二', '三', '四', '五', '六'];
    return Container(
      margin: const EdgeInsets.only(bottom: 12),
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: const Color(0xFF0F141C),
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: const Color(0xFF2A3240)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              const Text('开始 ', style: TextStyle(fontSize: 16)),
              _timeEditor(seg.start, (v) => setState(() => seg.start = v)),
              const SizedBox(width: 18),
              const Text('结束 ', style: TextStyle(fontSize: 16)),
              _timeEditor(seg.end, (v) => setState(() => seg.end = v)),
              const Spacer(),
              TvFocus(
                child: IconButton(
                  icon: const Icon(Icons.delete_outline,
                      color: Color(0xFFFF7A5A)),
                  onPressed: () => setState(() => cfg.segments.removeAt(index)),
                ),
              ),
            ],
          ),
          const SizedBox(height: 10),
          Wrap(
            spacing: 8,
            children: List.generate(7, (d) {
              final on = seg.days[d];
              return TvFocus(
                child: SizedBox(
                  width: 64,
                  child: TextButton(
                    style: TextButton.styleFrom(
                      backgroundColor:
                          on ? const Color(0xFF4DA3FF) : const Color(0xFF263041),
                      foregroundColor: Colors.white,
                      padding: const EdgeInsets.symmetric(vertical: 10),
                    ),
                    onPressed: () => setState(() => seg.days[d] = !on),
                    child: Text('周${names[d]}'),
                  ),
                ),
              );
            }),
          ),
        ],
      ),
    );
  }

  Widget _timeEditor(int minutes, ValueChanged<int> onChanged) {
    int norm(int v) => ((v % 1440) + 1440) % 1440;
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        _miniBtn('-1h', () => onChanged(norm(minutes - 60))),
        _miniBtn('-5m', () => onChanged(norm(minutes - 5))),
        const SizedBox(width: 10),
        Text(hhmm(minutes),
            style: const TextStyle(fontSize: 20, fontWeight: FontWeight.bold)),
        const SizedBox(width: 10),
        _miniBtn('+5m', () => onChanged(norm(minutes + 5))),
        _miniBtn('+1h', () => onChanged(norm(minutes + 60))),
      ],
    );
  }

  Widget _miniBtn(String text, VoidCallback onPressed) {
    return TvFocus(
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 3),
        child: SizedBox(
          height: 36,
          child: OutlinedButton(
            style: OutlinedButton.styleFrom(
                padding: const EdgeInsets.symmetric(horizontal: 10)),
            onPressed: onPressed,
            child: Text(text),
          ),
        ),
      ),
    );
  }

  Widget _stepperRow(
    String title,
    int value,
    ValueChanged<int> onChanged, {
    int step = 1,
    int? wideStep,
  }) {
    return Row(
      children: [
        Text(title, style: const TextStyle(fontSize: 16)),
        const Spacer(),
        if (wideStep != null) _miniBtn('-$wideStep', () => onChanged(value - wideStep)),
        _miniBtn('-', () => onChanged(value - step)),
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: 12),
          child: Text('$value',
              style: const TextStyle(fontSize: 20, fontWeight: FontWeight.bold)),
        ),
        _miniBtn('+', () => onChanged(value + step)),
        if (wideStep != null) _miniBtn('+$wideStep', () => onChanged(value + wideStep)),
      ],
    );
  }

  Widget _choice(String text, bool selected, VoidCallback onTap) {
    return Expanded(
      child: TvFocus(
        child: SizedBox(
          height: 48,
          child: ElevatedButton(
            style: ElevatedButton.styleFrom(
              backgroundColor:
                  selected ? const Color(0xFF4DA3FF) : const Color(0xFF263041),
              foregroundColor: Colors.white,
              // 选中项加亮边框，即使没有焦点也能看出当前选择
              side: BorderSide(
                color: selected ? const Color(0xFF8FC6FF) : Colors.transparent,
                width: 2,
              ),
            ),
            onPressed: onTap,
            child: Row(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                Icon(
                  selected ? Icons.radio_button_checked : Icons.radio_button_off,
                  size: 18,
                  color: selected ? Colors.white : const Color(0xFF93A1B5),
                ),
                const SizedBox(width: 8),
                Flexible(
                  child: Text(text,
                      style: const TextStyle(fontSize: 15),
                      overflow: TextOverflow.ellipsis),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }

  Widget _switchTile(String title, bool value, ValueChanged<bool> onChanged) {
    return TvFocus(
      child: SwitchListTile(
        contentPadding: EdgeInsets.zero,
        title: Text(title, style: const TextStyle(fontSize: 16)),
        value: value,
        onChanged: onChanged,
      ),
    );
  }

  Widget _card(String title, List<Widget> children) {
    return Container(
      margin: const EdgeInsets.only(bottom: 16),
      padding: const EdgeInsets.all(18),
      decoration: BoxDecoration(
        color: const Color(0xFF181D26),
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: const Color(0xFF2A3240)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(title,
              style:
                  const TextStyle(fontSize: 18, fontWeight: FontWeight.bold)),
          const SizedBox(height: 12),
          ...children,
        ],
      ),
    );
  }
}

/// 电视端虚拟键盘密码编辑弹窗：纯按钮，遥控器方向键 / OK 完整可操作。
class _PasswordDialog extends StatefulWidget {
  const _PasswordDialog({required this.initial});

  final String initial;

  @override
  State<_PasswordDialog> createState() => _PasswordDialogState();
}

class _PasswordDialogState extends State<_PasswordDialog> {
  late String _buf = widget.initial;
  bool _showPlain = false;

  static const _rows = <List<String>>[
    ['1', '2', '3', '4', '5', '6', '7', '8', '9', '0'],
    ['a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'i', 'j'],
    ['k', 'l', 'm', 'n', 'o', 'p', 'q', 'r', 's', 't'],
    ['u', 'v', 'w', 'x', 'y', 'z', '-', '_', '@', '.'],
  ];

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      backgroundColor: const Color(0xFF181D26),
      title: const Text('修改管理密码'),
      content: SizedBox(
        width: 560,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Container(
              width: double.infinity,
              padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
              decoration: BoxDecoration(
                color: const Color(0xFF0F141C),
                borderRadius: BorderRadius.circular(10),
                border: Border.all(color: const Color(0xFF2A3240)),
              ),
              child: Text(
                _buf.isEmpty
                    ? '（空）'
                    : (_showPlain ? _buf : '•' * _buf.length),
                style: const TextStyle(fontSize: 20, letterSpacing: 2),
              ),
            ),
            const SizedBox(height: 10),
            for (final row in _rows)
              Padding(
                padding: const EdgeInsets.only(bottom: 8),
                child: Row(
                  children: [
                    for (final ch in row)
                      Expanded(
                        child: TvFocus(
                          radius: 8,
                          child: Padding(
                            padding: const EdgeInsets.symmetric(horizontal: 2),
                            child: SizedBox(
                              height: 44,
                              child: OutlinedButton(
                                onPressed: () => setState(() => _buf += ch),
                                child: Text(ch,
                                    style: const TextStyle(fontSize: 16)),
                              ),
                            ),
                          ),
                        ),
                      ),
                  ],
                ),
              ),
            Row(
              children: [
                TvFocus(
                  child: OutlinedButton(
                    onPressed: () => setState(() {
                      if (_buf.isNotEmpty) _buf = _buf.substring(0, _buf.length - 1);
                    }),
                    child: const Text('退格'),
                  ),
                ),
                const SizedBox(width: 8),
                TvFocus(
                  child: OutlinedButton(
                    onPressed: () => setState(() => _buf = ''),
                    child: const Text('清空'),
                  ),
                ),
                const SizedBox(width: 8),
                TvFocus(
                  child: OutlinedButton(
                    onPressed: () => setState(() => _showPlain = !_showPlain),
                    child: Text(_showPlain ? '隐藏' : '明文'),
                  ),
                ),
                const Spacer(),
                TvFocus(
                  child: ElevatedButton(
                    onPressed: () => Navigator.of(context).pop(_buf),
                    child: const Text('确定'),
                  ),
                ),
                const SizedBox(width: 8),
                TvFocus(
                  child: OutlinedButton(
                    onPressed: () => Navigator.of(context).pop(),
                    child: const Text('取消'),
                  ),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}
