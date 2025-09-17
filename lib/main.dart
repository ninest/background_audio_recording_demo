import 'dart:io';

import 'package:flutter/material.dart';
import 'package:just_audio/just_audio.dart';
import 'package:path_provider/path_provider.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:record/record.dart';

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

class _RecorderPageState extends State<RecorderPage> {
  final AudioRecorder _recorder = AudioRecorder();
  final AudioPlayer _player = AudioPlayer();

  bool _isRecording = false;
  bool _isPaused = false;
  String? _recordingPath;

  @override
  void initState() {
    super.initState();
    _ensurePermissions();
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
    final bool hasPermission = await _recorder.hasPermission();
    if (!hasPermission) {
      await _ensurePermissions();
      return;
    }

    if (_isRecording && _isPaused) {
      await _recorder.resume();
      setState(() {
        _isPaused = false;
      });
      return;
    }

    // Start a new recording
    final String path = await _resolveRecordingFilePath();
    _recordingPath = path;

    await _recorder.start(
      const RecordConfig(
        encoder: AudioEncoder.aacLc,
        bitRate: 128000,
        sampleRate: 44100,
      ),
      path: path,
    );

    setState(() {
      _isRecording = true;
      _isPaused = false;
    });
  }

  Future<void> _pause() async {
    if (_isRecording && !_isPaused) {
      final bool paused = await _recorder.isPaused();
      if (!paused) {
        await _recorder.pause();
        setState(() {
          _isPaused = true;
        });
      }
    }
  }

  Future<void> _stop() async {
    if (_isRecording) {
      await _recorder.stop();
      setState(() {
        _isRecording = false;
        _isPaused = false;
      });
    }
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
  void dispose() {
    _player.dispose();
    // AudioRecorder does not require explicit dispose for basic usage.
    super.dispose();
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
          ],
        ),
      ),
    );
  }
}
