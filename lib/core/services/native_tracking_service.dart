import 'dart:async';

import 'package:flutter/services.dart';

import '../../data/local/audio_session_dao.dart';
import '../models/audio_session.dart';

class NativeTrackingService {
  NativeTrackingService(this._dao);

  static const _channel = MethodChannel('earbud_usage_tracker/native');

  final AudioSessionDao _dao;
  final StreamController<AudioSession> _sessionStreamController =
      StreamController<AudioSession>.broadcast();

  bool _initialized = false;

  Stream<AudioSession> get sessions => _sessionStreamController.stream;

  Future<void> initialize() async {
    if (_initialized) {
      return;
    }
    _channel.setMethodCallHandler(_handleMethodCall);
    _initialized = true;
  }

  Future<void> startTrackingService() {
    return _channel.invokeMethod('startService');
  }

  Future<void> stopTrackingService() {
    return _channel.invokeMethod('stopService');
  }

  Future<void> requestServiceStatus() {
    return _channel.invokeMethod('requestStatus');
  }

  Future<bool> isNotificationAccessGranted() async {
    final granted = await _channel.invokeMethod<dynamic>('isNotificationAccessGranted');
    return granted == true;
  }

  Future<bool> isBatteryOptimizationIgnored() async {
    final ignored = await _channel.invokeMethod<dynamic>('isBatteryOptimizationIgnored');
    return ignored == true;
  }

  Future<void> openNotificationAccessSettings() {
    return _channel.invokeMethod('openNotificationAccessSettings');
  }

  Future<void> openBatteryOptimizationSettings() {
    return _channel.invokeMethod('openBatteryOptimizationSettings');
  }

  Future<void> dispose() async {
    await _sessionStreamController.close();
  }

  Future<void> _handleMethodCall(MethodCall call) async {
    switch (call.method) {
      case 'sessionCompleted':
        final args = Map<String, dynamic>.from(call.arguments as Map);
        final session = AudioSession(
          startTime: DateTime.parse(args['startTime'] as String),
          endTime: DateTime.parse(args['endTime'] as String),
          duration: Duration(seconds: args['duration'] as int),
          avgVolume: (args['avgVolume'] as num).toDouble(),
          maxVolume: (args['maxVolume'] as num).toDouble(),
        );
        await _dao.insertSession(session);
        _sessionStreamController.add(session);
        break;
      default:
        throw PlatformException(
          code: 'UNIMPLEMENTED',
          message: 'Method ${call.method} not implemented on Flutter side',
        );
    }
  }
}
