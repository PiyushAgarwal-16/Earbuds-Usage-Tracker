import 'dart:async';

import 'package:flutter/material.dart';

import 'core/models/audio_session.dart';
import 'core/services/native_tracking_service.dart';
import 'data/local/audio_session_dao.dart';
import 'features/dashboard/dashboard_screen.dart';
import 'features/onboarding/onboarding_screen.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  
  final dao = AudioSessionDao();
  await dao.init();

  // Simple platform check without FFI dependencies
  final trackingService = NativeTrackingService(dao);      
  await trackingService.initialize();
  
  runApp(EarbudUsageTrackerApp(
    dao: dao,
    trackingService: trackingService,
  ));
}

class EarbudUsageTrackerApp extends StatefulWidget {
  const EarbudUsageTrackerApp({
    super.key,
    required this.dao,
    required this.trackingService,
  });

  final AudioSessionDao dao;
  final NativeTrackingService trackingService;

  @override
  State<EarbudUsageTrackerApp> createState() => _EarbudUsageTrackerAppState();
}

class _EarbudUsageTrackerAppState extends State<EarbudUsageTrackerApp>
    with WidgetsBindingObserver {
  bool _loading = true;
  bool _notificationGranted = false;
  bool _batteryIgnored = false;
  DailySessionStats? _dailyStats;
  List<AudioSession> _todaySessions = const [];
  StreamSubscription<AudioSession>? _sessionSubscription;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _initialize();
  }

  Future<void> _initialize() async {
    await _refreshPermissions();
    await _loadTodayData();
    _sessionSubscription = widget.trackingService.sessions.listen((session) {
      print('🟢 New session received in main app: ${session.duration}');
      _loadTodayData();
    });
    if (!mounted) {
      return;
    }
    setState(() {
      _loading = false;
    });
    if (_notificationGranted && _batteryIgnored) {
      await widget.trackingService.startTrackingService();
    }
  }

  Future<void> _refreshPermissions() async {
    final notificationGranted =
        await widget.trackingService.isNotificationAccessGranted();
    final batteryIgnored =
        await widget.trackingService.isBatteryOptimizationIgnored();
    if (!mounted) {
      return;
    }
    setState(() {
      _notificationGranted = notificationGranted;
      _batteryIgnored = batteryIgnored;
    });
  }

  Future<void> _loadTodayData() async {
    print('🟡 Loading today\'s data...');
    final stats = await widget.dao.getStatsForDate(DateTime.now());
    final now = DateTime.now();
    final rangeStart = DateTime(now.year, now.month, now.day);
    final rangeEnd = rangeStart.add(const Duration(days: 1));
    final sessions = await widget.dao.getSessionsBetween(rangeStart, rangeEnd);
    print('🟡 Loaded ${sessions.length} sessions for today');
    if (!mounted) {
      return;
    }
    setState(() {
      _dailyStats = stats;
      _todaySessions = sessions;
    });
  }

  Future<void> _handleOnboardingComplete() async {
    await _refreshPermissions();
    if (_notificationGranted && _batteryIgnored) {
      await widget.trackingService.startTrackingService();
    }
    await _loadTodayData();
  }

  Future<void> _openNotificationSettings() {
    return widget.trackingService.openNotificationAccessSettings();
  }

  Future<void> _openBatterySettings() {
    return widget.trackingService.openBatteryOptimizationSettings();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      _refreshPermissions();
    }
  }

  @override
  void dispose() {
    _sessionSubscription?.cancel();
    WidgetsBinding.instance.removeObserver(this);
    widget.trackingService.dispose();
    super.dispose();
  }

  bool get _isOnboarded => _notificationGranted && _batteryIgnored;

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Earbud Usage Tracker',
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(seedColor: Colors.blueGrey),
        useMaterial3: true,
      ),
      home: _loading
          ? const _SplashScreen()
          : _isOnboarded
              ? DashboardScreen(
                  stats: _dailyStats,
                  sessions: _todaySessions,
                  onRefresh: _loadTodayData,
                )
              : OnboardingScreen(
                  notificationGranted: _notificationGranted,
                  batteryIgnored: _batteryIgnored,
                  onRequestNotificationAccess: _openNotificationSettings,
                  onRequestBatteryOptimization: _openBatterySettings,
                  onRefreshStatus: _refreshPermissions,
                  onComplete: _handleOnboardingComplete,
                ),
    );
  }
}

class _SplashScreen extends StatelessWidget {
  const _SplashScreen();

  @override
  Widget build(BuildContext context) {
    return const Scaffold(
      body: Center(
        child: CircularProgressIndicator(),
      ),
    );
  }
}
