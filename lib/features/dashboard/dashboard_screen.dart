import 'package:flutter/material.dart';

import '../../core/models/audio_session.dart';
import '../../data/local/audio_session_dao.dart';

class DashboardScreen extends StatelessWidget {
  const DashboardScreen({
    super.key,
    required this.stats,
    required this.sessions,
    required this.onRefresh,
  });

  final DailySessionStats? stats;
  final List<AudioSession> sessions;
  final Future<void> Function() onRefresh;

  @override
  Widget build(BuildContext context) {
    final totalDuration = stats?.totalDuration ?? Duration.zero;
    final averageVolume = stats?.averageVolume ?? 0;
    final maxVolume = stats?.maxVolume ?? 0;

    return Scaffold(
      appBar: AppBar(
        title: const Text('Dashboard'),
      ),
      body: RefreshIndicator(
        onRefresh: onRefresh,
        child: ListView(
          padding: const EdgeInsets.all(24),
          children: [
            Text(
              "Today's listening",
              style: Theme.of(context).textTheme.headlineSmall,
            ),
            const SizedBox(height: 16),
            Row(
              children: [
                Expanded(
                  child: _MetricCard(
                    title: 'Duration',
                    value: _formatDuration(totalDuration),
                    icon: Icons.timer,
                    color: Colors.blueGrey,
                  ),
                ),
                const SizedBox(width: 16),
                Expanded(
                  child: _MetricCard(
                    title: 'Average volume',
                    value: '${averageVolume.toStringAsFixed(1)}%',
                    icon: Icons.graphic_eq,
                    color: Colors.deepPurple,
                  ),
                ),
              ],
            ),
            const SizedBox(height: 16),
            _MetricCard(
              title: 'Peak volume',
              value: '${maxVolume.toStringAsFixed(1)}%',
              icon: Icons.volume_up,
              color: Colors.teal,
            ),
            const SizedBox(height: 24),
            Card(
              elevation: 1,
              child: Padding(
                padding: const EdgeInsets.all(20),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      'Weekly summary',
                      style: Theme.of(context).textTheme.titleMedium,
                    ),
                    const SizedBox(height: 8),
                    const Text('Charts and advanced insights are coming soon.'),
                  ],
                ),
              ),
            ),
            const SizedBox(height: 24),
            Text(
              'Today\'s sessions',
              style: Theme.of(context).textTheme.titleMedium,
            ),
            const SizedBox(height: 12),
            if (sessions.isEmpty)
              const Text('No listening sessions recorded yet.')
            else
              ...sessions.map(
                (session) => Card(
                  elevation: 0,
                  margin: const EdgeInsets.only(bottom: 12),
                  child: ListTile(
                    leading: const Icon(Icons.headset),
                    title: Text(
                      _formatTimeRange(context, session.startTime, session.endTime),
                    ),
                    subtitle: Text(
                      'Duration ${_formatDuration(session.duration)} · Avg ${session.avgVolume.toStringAsFixed(1)}% · Max ${session.maxVolume.toStringAsFixed(1)}%',
                    ),
                  ),
                ),
              ),
          ],
        ),
      ),
    );
  }

  static String _formatDuration(Duration duration) {
    final hours = duration.inHours;
    final minutes = duration.inMinutes.remainder(60);
    final seconds = duration.inSeconds.remainder(60);
    if (hours > 0) {
      return '${hours}h ${minutes}m';
    }
    if (minutes > 0) {
      return '${minutes}m ${seconds}s';
    }
    return '${seconds}s';
  }

  static String _formatTimeRange(
    BuildContext context,
    DateTime start,
    DateTime end,
  ) {
    final localization = MaterialLocalizations.of(context);
    final startLabel = localization.formatTimeOfDay(
      TimeOfDay.fromDateTime(start),
      alwaysUse24HourFormat: false,
    );
    final endLabel = localization.formatTimeOfDay(
      TimeOfDay.fromDateTime(end),
      alwaysUse24HourFormat: false,
    );
    return '$startLabel - $endLabel';
  }
}

class _MetricCard extends StatelessWidget {
  const _MetricCard({
    required this.title,
    required this.value,
    required this.icon,
    required this.color,
  });

  final String title;
  final String value;
  final IconData icon;
  final Color color;

  @override
  Widget build(BuildContext context) {
    return Card(
      elevation: 1,
      child: Padding(
        padding: const EdgeInsets.all(20),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Icon(icon, color: color),
            const SizedBox(height: 12),
            Text(
              value,
              style: Theme.of(context).textTheme.headlineSmall?.copyWith(color: color),
            ),
            const SizedBox(height: 8),
            Text(title, style: Theme.of(context).textTheme.bodyMedium),
          ],
        ),
      ),
    );
  }
}
