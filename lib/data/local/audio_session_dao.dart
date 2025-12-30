import 'package:path/path.dart' as p;
import 'package:sqflite/sqflite.dart';

import '../../core/models/audio_session.dart';

class DailySessionStats {
  final Duration totalDuration;
  final double averageVolume;
  final double maxVolume;

  const DailySessionStats({
    required this.totalDuration,
    required this.averageVolume,
    required this.maxVolume,
  });
}

class AudioSessionDao {
  static const _dbName = 'audio_sessions.db';
  static const _dbVersion = 1;
  static const _table = 'audio_sessions';

  Database? _db;

  Future<void> init() async {
    if (_db != null && _db!.isOpen) {
      return;
    }
    final databasePath = await getDatabasesPath();
    final path = p.join(databasePath, _dbName);
    _db = await openDatabase(
      path,
      version: _dbVersion,
      onCreate: (db, version) async {
        await db.execute('''
          CREATE TABLE $_table(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            start_time TEXT NOT NULL,
            end_time TEXT NOT NULL,
            duration_seconds INTEGER NOT NULL,
            avg_volume REAL NOT NULL,
            max_volume REAL NOT NULL
          )
        ''');
      },
    );
  }

  Future<int> insertSession(AudioSession session) async {
    final db = await _getDb();
    return db.insert(
      _table,
      {
        'start_time': session.startTime.toUtc().toIso8601String(),
        'end_time': session.endTime.toUtc().toIso8601String(),
        'duration_seconds': session.duration.inSeconds,
        'avg_volume': session.avgVolume,
        'max_volume': session.maxVolume,
      },
      conflictAlgorithm: ConflictAlgorithm.replace,
    );
  }

  Future<List<AudioSession>> getSessionsBetween(DateTime start, DateTime end) async {
    final db = await _getDb();
    // Convert local time boundaries to UTC for comparison with stored UTC timestamps
    final startUtc = start.toUtc().toIso8601String();
    final endUtc = end.toUtc().toIso8601String();
    
    final result = await db.query(
      _table,
      where: 'start_time >= ? AND start_time < ?',
      whereArgs: [startUtc, endUtc],
      orderBy: 'start_time ASC',
    );
    
    return result.map(_mapToSession).toList();
  }

  Future<List<AudioSession>> getAllSessions() async {
    final db = await _getDb();
    final result = await db.query(
      _table,
      orderBy: 'start_time DESC',
    );
    return result.map(_mapToSession).toList();
  }

  Future<DailySessionStats> getStatsForDate(DateTime date) async {
    // Create date boundaries in local time, then convert to UTC for comparison
    final localStart = DateTime(date.year, date.month, date.day);
    final localEnd = localStart.add(const Duration(days: 1));
    final startOfDay = localStart.toUtc();
    final endOfDay = localEnd.toUtc();
    final db = await _getDb();
    final result = await db.rawQuery(
      '''
      SELECT
        SUM(duration_seconds) as total_duration,
        AVG(avg_volume) as avg_volume,
        MAX(max_volume) as max_volume
      FROM $_table
      WHERE start_time >= ? AND start_time < ?
      ''',
      [
        startOfDay.toIso8601String(),
        endOfDay.toIso8601String(),
      ],
    );
    final row = result.first;
    final totalDurationSeconds = (row['total_duration'] as num?)?.toInt() ?? 0;
    final avgVolume = (row['avg_volume'] as num?)?.toDouble() ?? 0;
    final maxVolume = (row['max_volume'] as num?)?.toDouble() ?? 0;
    return DailySessionStats(
      totalDuration: Duration(seconds: totalDurationSeconds),
      averageVolume: avgVolume,
      maxVolume: maxVolume,
    );
  }

  Future<void> deleteAll() async {
    final db = await _getDb();
    await db.delete(_table);
  }

  Future<Database> _getDb() async {
    if (_db == null || !_db!.isOpen) {
      await init();
    }
    return _db!;
  }

  AudioSession _mapToSession(Map<String, dynamic> map) {
    return AudioSession(
      startTime: DateTime.parse(map['start_time'] as String).toLocal(),
      endTime: DateTime.parse(map['end_time'] as String).toLocal(),
      duration: Duration(seconds: map['duration_seconds'] as int),
      avgVolume: (map['avg_volume'] as num).toDouble(),
      maxVolume: (map['max_volume'] as num).toDouble(),
    );
  }
}
