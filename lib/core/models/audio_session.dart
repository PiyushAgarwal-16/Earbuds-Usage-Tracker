class AudioSession {
  final DateTime startTime;
  final DateTime endTime;
  final Duration duration;
  final double avgVolume;
  final double maxVolume;

  const AudioSession({
    required this.startTime,
    required this.endTime,
    required this.duration,
    required this.avgVolume,
    required this.maxVolume,
  });

  AudioSession copyWith({
    DateTime? startTime,
    DateTime? endTime,
    Duration? duration,
    double? avgVolume,
    double? maxVolume,
  }) {
    return AudioSession(
      startTime: startTime ?? this.startTime,
      endTime: endTime ?? this.endTime,
      duration: duration ?? this.duration,
      avgVolume: avgVolume ?? this.avgVolume,
      maxVolume: maxVolume ?? this.maxVolume,
    );
  }

  Map<String, dynamic> toMap() {
    return {
      'startTime': startTime.toIso8601String(),
      'endTime': endTime.toIso8601String(),
      'duration': duration.inSeconds,
      'avgVolume': avgVolume,
      'maxVolume': maxVolume,
    };
  }

  factory AudioSession.fromMap(Map<String, dynamic> map) {
    return AudioSession(
      startTime: DateTime.parse(map['startTime'] as String),
      endTime: DateTime.parse(map['endTime'] as String),
      duration: Duration(seconds: map['duration'] as int),
      avgVolume: (map['avgVolume'] as num).toDouble(),
      maxVolume: (map['maxVolume'] as num).toDouble(),
    );
  }
}
