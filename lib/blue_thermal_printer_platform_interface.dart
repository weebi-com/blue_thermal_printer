import 'package:plugin_platform_interface/plugin_platform_interface.dart';

import 'blue_thermal_printer_method_channel.dart';

abstract class BlueThermalPrinterPlatform extends PlatformInterface {
  /// Constructs a BlueThermalPrinterPlatform.
  BlueThermalPrinterPlatform() : super(token: _token);

  static final Object _token = Object();

  static BlueThermalPrinterPlatform _instance = MethodChannelBlueThermalPrinter();

  /// The default instance of [BlueThermalPrinterPlatform] to use.
  ///
  /// Defaults to [MethodChannelBlueThermalPrinter].
  static BlueThermalPrinterPlatform get instance => _instance;

  /// Platform-specific implementations should set this with their own
  /// platform-specific class that extends [BlueThermalPrinterPlatform] when
  /// they register themselves.
  static set instance(BlueThermalPrinterPlatform instance) {
    PlatformInterface.verifyToken(instance, _token);
    _instance = instance;
  }

  Future<String?> getPlatformVersion() {
    throw UnimplementedError('platformVersion() has not been implemented.');
  }
}
