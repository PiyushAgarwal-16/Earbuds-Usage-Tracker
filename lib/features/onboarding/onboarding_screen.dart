import 'package:flutter/material.dart';

class OnboardingScreen extends StatelessWidget {
  const OnboardingScreen({
    super.key,
    required this.notificationGranted,
    required this.batteryIgnored,
    required this.onRequestNotificationAccess,
    required this.onRequestBatteryOptimization,
    required this.onRefreshStatus,
    required this.onComplete,
  });

  final bool notificationGranted;
  final bool batteryIgnored;
  final Future<void> Function() onRequestNotificationAccess;
  final Future<void> Function() onRequestBatteryOptimization;
  final Future<void> Function() onRefreshStatus;
  final Future<void> Function() onComplete;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final ready = notificationGranted && batteryIgnored;
    return Scaffold(
      appBar: AppBar(
        title: const Text('Welcome'),
      ),
      body: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              'Enable automatic earbud tracking',
              style: theme.textTheme.headlineSmall,
            ),
            const SizedBox(height: 12),
            Text(
              'Follow these quick steps so the tracker can run reliably in the background.',
              style: theme.textTheme.bodyMedium,
            ),
            const SizedBox(height: 24),
            _OnboardingStep(
              title: 'Grant notification access',
              description: 'Allows the app to detect when media playback is active.',
              completed: notificationGranted,
              actionLabel: notificationGranted
                  ? 'View notification settings'
                  : 'Open notification settings',
              onAction: onRequestNotificationAccess,
            ),
            const SizedBox(height: 16),
            _OnboardingStep(
              title: 'Disable battery optimizations',
              description:
                  'Prevents the system from stopping the foreground tracking service.',
              completed: batteryIgnored,
              actionLabel:
                  batteryIgnored ? 'View battery settings' : 'Adjust battery settings',
              onAction: onRequestBatteryOptimization,
            ),
            const SizedBox(height: 16),
            Text(
              'On the next screen, select Earbud Usage Tracker and disable optimizations so tracking stays active.',
              style: theme.textTheme.bodyMedium,
            ),
            const Spacer(),
            TextButton(
              onPressed: () async {
                await onRefreshStatus();
                if (context.mounted) {
                  ScaffoldMessenger.of(context).showSnackBar(
                    const SnackBar(content: Text('Status refreshed.')),
                  );
                }
              },
              child: const Text('Check status'),
            ),
            const SizedBox(height: 12),
            FilledButton(
              onPressed: ready
                  ? () async {
                      await onComplete();
                    }
                  : null,
              child: const Text('Start tracking'),
            ),
          ],
        ),
      ),
    );
  }
}

class _OnboardingStep extends StatelessWidget {
  const _OnboardingStep({
    required this.title,
    required this.description,
    required this.completed,
    required this.actionLabel,
    required this.onAction,
  });

  final String title;
  final String description;
  final bool completed;
  final String actionLabel;
  final Future<void> Function() onAction;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Card(
      elevation: 1,
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 20),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Icon(
              completed ? Icons.check_circle : Icons.headset,
              size: 28,
              color: completed ? Colors.green : theme.colorScheme.primary,
            ),
            const SizedBox(width: 16),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    title,
                    style: theme.textTheme.titleMedium,
                  ),
                  const SizedBox(height: 6),
                  Text(
                    description,
                    style: theme.textTheme.bodyMedium,
                  ),
                  const SizedBox(height: 12),
                  FilledButton.tonal(
                    onPressed: () async {
                      await onAction();
                    },
                    child: Text(actionLabel),
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}
