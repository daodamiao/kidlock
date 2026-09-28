import 'package:flutter/material.dart';

/// 电视端焦点高亮组件：
/// 包裹任意可聚焦控件，获得焦点时显示金色描边 + 光晕，
/// 让遥控器用户清楚当前选中的是哪个选项。
/// 注意：本组件自身不参与焦点竞争（canRequestFocus=false），
/// 仅监听子控件焦点变化。
class TvFocus extends StatefulWidget {
  const TvFocus({
    super.key,
    required this.child,
    this.radius = 10,
    this.borderColor = const Color(0xFFFFC24D),
  });

  final Widget child;
  final double radius;
  final Color borderColor;

  @override
  State<TvFocus> createState() => _TvFocusState();
}

class _TvFocusState extends State<TvFocus> {
  bool _focused = false;

  @override
  Widget build(BuildContext context) {
    return Focus(
      canRequestFocus: false,
      skipTraversal: true,
      onFocusChange: (v) {
        if (mounted) setState(() => _focused = v);
      },
      child: Container(
        decoration: BoxDecoration(
          borderRadius: BorderRadius.circular(widget.radius),
          border: Border.all(
            color: _focused ? widget.borderColor : Colors.transparent,
            width: 3,
          ),
          boxShadow: _focused
              ? [
                  BoxShadow(
                    color: widget.borderColor.withValues(alpha: 0.35),
                    blurRadius: 14,
                    spreadRadius: 1,
                  ),
                ]
              : null,
        ),
        child: widget.child,
      ),
    );
  }
}
