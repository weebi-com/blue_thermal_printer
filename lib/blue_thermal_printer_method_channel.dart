import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import 'blue_thermal_printer_platform_interface.dart';

/// An implementation of [BlueThermalPrinterPlatform] that uses method channels.
class MethodChannelBlueThermalPrinter extends BlueThermalPrinterPlatform {
  /// The method channel used to interact with the native platform.
  @visibleForTesting
  final methodChannel = const MethodChannel('blue_thermal_printer');

  @override
  Future<String?> getPlatformVersion() async {
    final version = await methodChannel.invokeMethod<String>('getPlatformVersion');
    return version;
  }
}
