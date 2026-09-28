import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import 'src/home_page.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  // TV 遥控器用方向键移动焦点，这里保证焦点/按键系统就绪
  SystemChrome.setEnabledSystemUIMode(SystemUiMode.edgeToEdge);
  runApp(const KidLockApp());
}

class KidLockApp extends StatelessWidget {
  const KidLockApp({super.key});

  @override
  Widget build(BuildContext context) {
    const bg = Color(0xFF0F1319);
    return MaterialApp(
      title: '儿童限时管控',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        useMaterial3: true,
        brightness: Brightness.dark,
        scaffoldBackgroundColor: bg,
        colorScheme: ColorScheme.dark(
          primary: const Color(0xFF4DA3FF),
          surface: const Color(0xFF181D26),
          onSurface: const Color(0xFFE9EEF5),
        ),
      ),
      home: const HomePage(),
    );
  }
}
