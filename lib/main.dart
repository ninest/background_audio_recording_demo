import 'dart:io';
import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_local_notifications/flutter_local_notifications.dart';
import 'package:just_audio/just_audio.dart';
import 'package:path_provider/path_provider.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:record/record.dart';

import 'services/background_audio_service.dart';

void main() {
  runApp(const MyApp());
}

class MyApp extends StatelessWidget {
  const MyApp({super.key});

  // This widget is the root of your application.
  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Background Recorder',
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(seedColor: Colors.deepPurple),
      ),
      home: const RecorderPage(),
    );
  }
}

class RecorderPage extends StatefulWidget {
  const RecorderPage({super.key});

  @override
  State<RecorderPage> createState() => _RecorderPageState();
}

class _RecorderPageState extends State<RecorderPage> with WidgetsBindingObserver {
  final AudioRecorder _recorder = AudioRecorder();
  final AudioPlayer _player = AudioPlayer();
  final FlutterLocalNotificationsPlugin _localNotifications = FlutterLocalNotificationsPlugin();
  final BackgroundAudioService _backgroundService = BackgroundAudioService.instance;

  bool _isRecording = false;
  bool _isPaused = false;
  String? _recordingPath;
  static const int _recordingNotificationId = 1001;
  bool _useBackgroundService = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _useBackgroundService = Platform.isAndroid;
    _ensurePermissions();
    _initNotifications();
    _checkBackgroundServiceState();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _streamTimer?.cancel();
    _stateCheckTimer?.cancel();
    _player.dispose();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    super.didChangeAppLifecycleState(state);
    if (state == AppLifecycleState.resumed) {
      // Check service state when app comes back to foreground
      _checkBackgroundServiceState();
    }
  }

  Future<void> _checkBackgroundServiceState() async {
    if (_useBackgroundService) {
      final state = await _backgroundService.getRecordingState();
      if (mounted) {
        setState(() {
          _isRecording = state.isRecording;
          _isPaused = state.isPaused;
          if (state.outputPath != null) {
            _recordingPath = state.outputPath;
          }
        });

        if (_isRecording) {
          await _showRecordingNotification(paused: _isPaused);
          if (!_isPaused) {
            _startStreamingMock();
          }
        }
      }
    }
  }

  Future<void> _ensurePermissions() async {
    final mic = await Permission.microphone.status;
    if (!mic.isGranted) {
      await Permission.microphone.request();
    }
    if (Platform.isAndroid) {
      final notif = await Permission.notification.status;
      if (!notif.isGranted) {
        await Permission.notification.request();
      }

      // Request battery optimization exemption for better background performance
      await _backgroundService.requestBatteryOptimizationExemption();
    }
  }

  Future<void> _initNotifications() async {
    const DarwinInitializationSettings iosInit = DarwinInitializationSettings(
      requestAlertPermission: true,
      requestBadgePermission: false,
      requestSoundPermission: false,
      defaultPresentAlert: true,
      defaultPresentBadge: false,
      defaultPresentSound: false,
    );

    const AndroidInitializationSettings androidInit = AndroidInitializationSettings('@mipmap/ic_launcher');

    const InitializationSettings initSettings = InitializationSettings(
      iOS: iosInit,
      android: androidInit,
    );

    await _localNotifications.initialize(
      initSettings,
      onDidReceiveNotificationResponse: (NotificationResponse response) async {
        // Tapping the notification brings the app to foreground automatically.
        // No-op here, but could navigate if needed.
      },
    );

    // Create notification channel for Android
    if (Platform.isAndroid) {
      const AndroidNotificationChannel channel = AndroidNotificationChannel(
        'recording_channel',
        'Audio Recording',
        description: 'Notifications for audio recording status',
        importance: Importance.low,
      );

      final AndroidFlutterLocalNotificationsPlugin? androidPlugin =
          _localNotifications.resolvePlatformSpecificImplementation<AndroidFlutterLocalNotificationsPlugin>();
      await androidPlugin?.createNotificationChannel(channel);
    }

    // Explicitly request iOS permissions via the plugin implementation
    final IOSFlutterLocalNotificationsPlugin? iosPlugin =
        _localNotifications.resolvePlatformSpecificImplementation<IOSFlutterLocalNotificationsPlugin>();
    await iosPlugin?.requestPermissions(alert: true, badge: false, sound: false);
  }

  Future<void> _showRecordingNotification({required bool paused}) async {
    // On Android, the foreground service handles notifications
    // On iOS, we show local notifications
    if (Platform.isIOS || !_useBackgroundService) {
      const DarwinNotificationDetails iosDetails = DarwinNotificationDetails(
        presentAlert: true,
        presentBadge: false,
        presentSound: false,
        threadIdentifier: 'recording',
      );

      const AndroidNotificationDetails androidDetails = AndroidNotificationDetails(
        'recording_channel',
        'Audio Recording',
        channelDescription: 'Notifications for audio recording status',
        importance: Importance.low,
        priority: Priority.low,
        ongoing: true,
        autoCancel: false,
        silent: true,
      );

      await _localNotifications.show(
        _recordingNotificationId,
        paused ? 'Recording paused' : 'Recording in progress',
        'Tap to return to the app',
        const NotificationDetails(
          iOS: iosDetails,
          android: androidDetails,
        ),
      );
    }
  }

  Future<void> _cancelRecordingNotification() async {
    // Only cancel local notifications, foreground service handles its own
    if (Platform.isIOS || !_useBackgroundService) {
      await _localNotifications.cancel(_recordingNotificationId);
    }
  }

  Future<String> _resolveRecordingFilePath() async {
    final Directory dir = Platform.isIOS
        ? await getApplicationDocumentsDirectory()
        : await getExternalStorageDirectory() ?? await getApplicationDocumentsDirectory();
    final String fileName = 'recording_${DateTime.now().millisecondsSinceEpoch}.m4a';
    return '${dir.path}/$fileName';
  }

  Future<void> _startOrResume() async {
    // Check permissions - use background service permission check if available
    bool hasPermission = false;
    if (_useBackgroundService) {
      // For background service, we'll rely on the service to handle permissions
      // The service will fail gracefully if permissions aren't granted
      hasPermission = true;
    } else {
      hasPermission = await _recorder.hasPermission();
    }

    if (!hasPermission) {
      await _ensurePermissions();
      return;
    }

    if (_isRecording && _isPaused) {
      if (_useBackgroundService) {
        final success = await _backgroundService.resumeRecording();
        if (!success) {
          debugPrint('Failed to resume background service recording');
          return;
        }
      } else {
        await _recorder.resume();
      }
      setState(() {
        _isPaused = false;
      });
      await _showRecordingNotification(paused: false);
      _startStreamingMock();
      return;
    }

    // Start a new recording
    final String path = await _resolveRecordingFilePath();
    _recordingPath = path;

    bool recordingStarted = false;

    if (_useBackgroundService) {
      // Use background service for Android - service handles MediaRecorder directly
      debugPrint('Starting background service recording to: $path');
      final success = await _backgroundService.startRecording(path);
      if (success) {
        recordingStarted = true;
        debugPrint('Background service recording started successfully');
      } else {
        debugPrint('Background service failed, falling back to regular recording');
        _useBackgroundService = false;
      }
    }

    if (!_useBackgroundService) {
      // Use regular recording for iOS or as fallback
      debugPrint('Starting regular recording to: $path');
      await _recorder.start(
        const RecordConfig(
          encoder: AudioEncoder.aacLc,
          bitRate: 128000,
          sampleRate: 44100,
        ),
        path: path,
      );
      recordingStarted = true;
      debugPrint('Regular recording started successfully');
    }

    if (recordingStarted) {
      setState(() {
        _isRecording = true;
        _isPaused = false;
      });
      await _showRecordingNotification(paused: false);
      _startStreamingMock();
    }
  }

  Future<void> _pause() async {
    if (_isRecording && !_isPaused) {
      if (_useBackgroundService) {
        await _backgroundService.pauseRecording();
      } else {
        final bool paused = await _recorder.isPaused();
        if (!paused) {
          await _recorder.pause();
        }
      }
      setState(() {
        _isPaused = true;
      });
      await _showRecordingNotification(paused: true);
      _pauseStreamingMock();
    }
  }

  Future<void> _stop() async {
    if (_isRecording) {
      if (_useBackgroundService) {
        await _backgroundService.stopRecording();
      } else {
        await _recorder.stop();
      }
      setState(() {
        _isRecording = false;
        _isPaused = false;
      });
      await _cancelRecordingNotification();
      _stopStreamingMock();
    }
  }

  Timer? _streamTimer;
  Timer? _stateCheckTimer;
  int _sentChunks = 0;

  void _startStreamingMock() {
    _streamTimer?.cancel();
    _streamTimer = Timer.periodic(const Duration(milliseconds: 800), (_) {
      _sentChunks += 1;
      debugPrint('Mock sent chunk #$_sentChunks');
      if (mounted) {
        setState(() {});
      }
    });

    // Start periodic state checking for background service
    if (_useBackgroundService) {
      _startStateChecking();
    }
  }

  void _startStateChecking() {
    _stateCheckTimer?.cancel();
    _stateCheckTimer = Timer.periodic(const Duration(seconds: 5), (_) async {
      if (_useBackgroundService && mounted) {
        final state = await _backgroundService.getRecordingState();
        debugPrint('Service state check - isRecording: ${state.isRecording}, isPaused: ${state.isPaused}');

        // Update UI if service state differs from our state
        if (state.isRecording != _isRecording || state.isPaused != _isPaused) {
          debugPrint('State mismatch detected, updating UI');
          setState(() {
            _isRecording = state.isRecording;
            _isPaused = state.isPaused;
            if (state.outputPath != null) {
              _recordingPath = state.outputPath;
            }
          });
        }
      }
    });
  }

  void _pauseStreamingMock() {
    _streamTimer?.cancel();
    _streamTimer = null;
  }

  void _stopStreamingMock() {
    _streamTimer?.cancel();
    _streamTimer = null;
    _stateCheckTimer?.cancel();
    _stateCheckTimer = null;
  }

  Future<void> _play() async {
    if (_recordingPath == null) return;
    if (_player.playing) {
      await _player.stop();
    }
    await _player.setFilePath(_recordingPath!);
    await _player.play();
  }



  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        backgroundColor: Theme.of(context).colorScheme.inversePrimary,
        title: const Text('Background Recorder POC'),
      ),
      body: Padding(
        padding: const EdgeInsets.all(16.0),
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Row(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                ElevatedButton.icon(
                  onPressed: _startOrResume,
                  icon: Icon(_isRecording && _isPaused ? Icons.play_arrow : Icons.fiber_manual_record),
                  label: Text(_isRecording && _isPaused ? 'Resume' : 'Record'),
                ),
                const SizedBox(width: 12),
                ElevatedButton.icon(
                  onPressed: _isRecording && !_isPaused ? _pause : null,
                  icon: const Icon(Icons.pause),
                  label: const Text('Pause'),
                ),
              ],
            ),
            const SizedBox(height: 12),
            Row(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                ElevatedButton.icon(
                  onPressed: _isRecording ? _stop : null,
                  icon: const Icon(Icons.stop),
                  label: const Text('Stop'),
                ),
                const SizedBox(width: 12),
                ElevatedButton.icon(
                  onPressed: _recordingPath != null && !_isRecording ? _play : null,
                  icon: const Icon(Icons.play_arrow),
                  label: const Text('Play'),
                ),
              ],
            ),
            const SizedBox(height: 24),
            Text('Sent chunks: $_sentChunks'),
            const SizedBox(height: 12),
            if (_recordingPath != null)
              Text(
                'Saved to:\n$_recordingPath',
                textAlign: TextAlign.center,
              ),
            const SizedBox(height: 12),
            Text(
              _isRecording
                  ? (_isPaused ? 'Recording paused' : 'Recording... app can be minimized')
                  : 'Idle',
            ),
            const SizedBox(height: 12),
            if (Platform.isAndroid)
              Text(
                _useBackgroundService
                    ? 'Using background service (persistent recording)'
                    : 'Using standard recording (may stop in background)',
                style: Theme.of(context).textTheme.bodySmall,
                textAlign: TextAlign.center,
              ),
          ],
        ),
      ),
    );
  }
}
