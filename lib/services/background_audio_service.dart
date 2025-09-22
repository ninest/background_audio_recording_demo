import 'dart:io';
import 'package:flutter/services.dart';
import 'package:flutter/foundation.dart';

class BackgroundAudioService {
  static const MethodChannel _channel = MethodChannel('com.example.background_audio_demo/audio_service');
  
  static BackgroundAudioService? _instance;
  static BackgroundAudioService get instance => _instance ??= BackgroundAudioService._();
  
  BackgroundAudioService._();

  /// Start background audio recording
  /// 
  /// On Android: Uses foreground service for persistent recording
  /// On iOS: Relies on background audio capability
  Future<bool> startRecording(String outputPath) async {
    try {
      if (Platform.isAndroid) {
        final result = await _channel.invokeMethod('startRecording', {
          'outputPath': outputPath,
        });
        return result == true;
      } else {
        // For iOS, we'll use the existing record package directly
        // as it already supports background recording with proper configuration
        return true;
      }
    } on PlatformException catch (e) {
      debugPrint('Failed to start background recording: ${e.message}');
      return false;
    }
  }

  /// Stop background audio recording
  Future<bool> stopRecording() async {
    try {
      if (Platform.isAndroid) {
        final result = await _channel.invokeMethod('stopRecording');
        return result == true;
      } else {
        return true;
      }
    } on PlatformException catch (e) {
      debugPrint('Failed to stop background recording: ${e.message}');
      return false;
    }
  }

  /// Pause background audio recording
  Future<bool> pauseRecording() async {
    try {
      if (Platform.isAndroid) {
        final result = await _channel.invokeMethod('pauseRecording');
        return result == true;
      } else {
        return true;
      }
    } on PlatformException catch (e) {
      debugPrint('Failed to pause background recording: ${e.message}');
      return false;
    }
  }

  /// Resume background audio recording
  Future<bool> resumeRecording() async {
    try {
      if (Platform.isAndroid) {
        final result = await _channel.invokeMethod('resumeRecording');
        return result == true;
      } else {
        return true;
      }
    } on PlatformException catch (e) {
      debugPrint('Failed to resume background recording: ${e.message}');
      return false;
    }
  }

  /// Get current recording state from the background service
  Future<RecordingState> getRecordingState() async {
    try {
      if (Platform.isAndroid) {
        final result = await _channel.invokeMethod('getRecordingState');
        if (result is Map) {
          return RecordingState(
            isRecording: result['isRecording'] ?? false,
            isPaused: result['isPaused'] ?? false,
            outputPath: result['outputPath'],
          );
        }
      }
      return RecordingState(isRecording: false, isPaused: false, outputPath: null);
    } on PlatformException catch (e) {
      debugPrint('Failed to get recording state: ${e.message}');
      return RecordingState(isRecording: false, isPaused: false, outputPath: null);
    }
  }

  /// Request battery optimization exemption (Android only)
  /// This helps prevent the system from killing the background service
  Future<void> requestBatteryOptimizationExemption() async {
    try {
      if (Platform.isAndroid) {
        await _channel.invokeMethod('requestBatteryOptimization');
      }
    } on PlatformException catch (e) {
      debugPrint('Failed to request battery optimization exemption: ${e.message}');
    }
  }

  /// Check if the device supports background recording
  bool get supportsBackgroundRecording {
    return Platform.isIOS || Platform.isAndroid;
  }

  /// Check if the current platform uses foreground service
  bool get usesForegroundService {
    return Platform.isAndroid;
  }
}

class RecordingState {
  final bool isRecording;
  final bool isPaused;
  final String? outputPath;

  RecordingState({
    required this.isRecording,
    required this.isPaused,
    this.outputPath,
  });

  @override
  String toString() {
    return 'RecordingState(isRecording: $isRecording, isPaused: $isPaused, outputPath: $outputPath)';
  }
}
