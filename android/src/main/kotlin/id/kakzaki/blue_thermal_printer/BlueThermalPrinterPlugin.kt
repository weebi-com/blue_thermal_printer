package id.kakzaki.blue_thermal_printer

import android.Manifest
import android.app.Activity
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.AsyncTask
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.journeyapps.barcodescanner.BarcodeEncoder
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.EventChannel.EventSink
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.PluginRegistry.RequestPermissionsResultListener
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.util.UUID
import java.util.logging.StreamHandler

class BlueThermalPrinterPlugin : FlutterPlugin, ActivityAware, MethodCallHandler, RequestPermissionsResultListener {
  private var mBluetoothAdapter: BluetoothAdapter? = null

  private var pendingResult: Result? = null

  private var readSink: EventSink? = null
  private var statusSink: EventSink? = null

  private var pluginBinding: FlutterPluginBinding? = null
  private var activityBinding: ActivityPluginBinding? = null
  private val initializationLock = Any()
  private var context: Context? = null
  private var channel: MethodChannel? = null

  private var stateChannel: EventChannel? = null
  private var mBluetoothManager: BluetoothManager? = null

  private var activity: Activity? = null

  fun onAttachedToEngine(binding: FlutterPluginBinding) {
    pluginBinding = binding
  }

  fun onDetachedFromEngine(binding: FlutterPluginBinding) {
    pluginBinding = null
  }

  fun onAttachedToActivity(binding: ActivityPluginBinding) {
    activityBinding = binding
    setup(
            pluginBinding.getBinaryMessenger(),
            pluginBinding.getApplicationContext() as Application,
            activityBinding.getActivity(),
            activityBinding)
  }

  fun onDetachedFromActivityForConfigChanges() {
    onDetachedFromActivity()
  }

  fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
    onAttachedToActivity(binding)
  }

  fun onDetachedFromActivity() {
    detach()
  }

  private fun setup(
          messenger: BinaryMessenger,
          application: Application,
          activity: Activity,
          activityBinding: ActivityPluginBinding?) {
    synchronized(initializationLock) {
      Log.i(TAG, "setup")
      this.activity = activity
      this.context = application
      channel = MethodChannel(messenger, NAMESPACE + "/methods")
      channel.setMethodCallHandler(this)
      stateChannel = EventChannel(messenger, NAMESPACE + "/state")
      stateChannel.setStreamHandler(stateStreamHandler)
      val readChannel: EventChannel = EventChannel(messenger, NAMESPACE + "/read")
      readChannel.setStreamHandler(readResultsHandler)
      mBluetoothManager = application.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
      mBluetoothAdapter = mBluetoothManager!!.adapter
      activityBinding.addRequestPermissionsResultListener(this)
    }
  }


  private fun detach() {
    Log.i(TAG, "detach")
    context = null
    activityBinding.removeRequestPermissionsResultListener(this)
    activityBinding = null
    channel.setMethodCallHandler(null)
    channel = null
    stateChannel.setStreamHandler(null)
    stateChannel = null
    mBluetoothAdapter = null
    mBluetoothManager = null
  }

  // MethodChannel.Result wrapper that responds on the platform thread.
  private class MethodResultWrapper(result: Result) : Result {
    private val methodResult: Result = result
    private val handler = Handler(Looper.getMainLooper())

    fun success(result: Any?) {
      handler.post { methodResult.success(result) }
    }

    fun error(errorCode: String, errorMessage: String?, errorDetails: Any?) {
      handler.post { methodResult.error(errorCode, errorMessage, errorDetails) }
    }

    fun notImplemented() {
      handler.post(methodResult::notImplemented)
    }
  }

  fun onMethodCall(call: MethodCall, rawResult: Result) {
    val result: Result = MethodResultWrapper(rawResult)

    if (mBluetoothAdapter == null && "isAvailable" != call.method) {
      result.error("bluetooth_unavailable", "the device does not have bluetooth", null)
      return
    }

    val arguments: Map<String, Any> = call.arguments()
    when (call.method) {
      "state" -> state(result)
      "isAvailable" -> result.success(mBluetoothAdapter != null)
      "isOn" -> try {
        result.success(mBluetoothAdapter!!.isEnabled)
      } catch (ex: Exception) {
        result.error("Error", ex.message, exceptionToString(ex))
      }

      "isConnected" -> result.success(THREAD != null)
      "isDeviceConnected" -> if (arguments.containsKey("address")) {
        val address = arguments["address"] as String
        isDeviceConnected(result, address)
      } else {
        result.error("invalid_argument", "argument 'address' not found", null)
      }

      "openSettings" -> {
        ContextCompat.startActivity(context!!, Intent(Settings.ACTION_BLUETOOTH_SETTINGS),
                null)
        result.success(true)
      }

      "getBondedDevices" -> try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
          if (ContextCompat.checkSelfPermission(activity!!,
                          Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED ||
                  ContextCompat.checkSelfPermission(activity!!,
                          Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED //        ||
          //ContextCompat.checkSelfPermission(activity,
          //        Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
          ) {
            ActivityCompat.requestPermissions(activity!!, arrayOf(
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT,  // Manifest.permission.ACCESS_FINE_LOCATION,
            ), 1)
            pendingResult = result
            break
          }
        }


        //  else {
        //   if (ContextCompat.checkSelfPermission(activity,
        //           Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED||ContextCompat.checkSelfPermission(activity,
        //           Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {

        //     ActivityCompat.requestPermissions(activity,
        //             new String[] { Manifest.permission.ACCESS_COARSE_LOCATION,Manifest.permission.ACCESS_FINE_LOCATION }, REQUEST_COARSE_LOCATION_PERMISSIONS);

        //  pendingResult = result;
        //  break;
        //  }
        //  }
        getBondedDevices(result)
      } catch (ex: Exception) {
        result.error("Error", ex.message, exceptionToString(ex))
      }

      "connect" -> if (arguments.containsKey("address")) {
        val address = arguments["address"] as String
        connect(result, address)
      } else {
        result.error("invalid_argument", "argument 'address' not found", null)
      }

      "disconnect" -> disconnect(result)
      "write" -> if (arguments.containsKey("message")) {
        val message = arguments["message"] as String
        write(result, message)
      } else {
        result.error("invalid_argument", "argument 'message' not found", null)
      }

      "writeBytes" -> if (arguments.containsKey("message")) {
        val message = arguments["message"] as ByteArray
        writeBytes(result, message)
      } else {
        result.error("invalid_argument", "argument 'message' not found", null)
      }

      "printCustom" -> if (arguments.containsKey("message")) {
        val message = arguments["message"] as String
        val size = arguments["size"] as Int
        val align = arguments["align"] as Int
        val charset = arguments["charset"] as String
        printCustom(result, message, size, align, charset)
      } else {
        result.error("invalid_argument", "argument 'message' not found", null)
      }

      "printNewLine" -> printNewLine(result)
      "paperCut" -> paperCut(result)
      "drawerPin2" -> drawerPin2(result)
      "drawerPin5" -> drawerPin5(result)
      "printImage" -> if (arguments.containsKey("pathImage")) {
        val pathImage = arguments["pathImage"] as String
        printImage(result, pathImage)
      } else {
        result.error("invalid_argument", "argument 'pathImage' not found", null)
      }

      "printImageBytes" -> if (arguments.containsKey("bytes")) {
        val bytes = arguments["bytes"] as ByteArray
        printImageBytes(result, bytes)
      } else {
        result.error("invalid_argument", "argument 'bytes' not found", null)
      }

      "printQRcode" -> if (arguments.containsKey("textToQR")) {
        val textToQR = arguments["textToQR"] as String
        val width = arguments["width"] as Int
        val height = arguments["height"] as Int
        val align = arguments["align"] as Int
        printQRcode(result, textToQR, width, height, align)
      } else {
        result.error("invalid_argument", "argument 'textToQR' not found", null)
      }

      "printLeftRight" -> if (arguments.containsKey("string1")) {
        val string1 = arguments["string1"] as String
        val string2 = arguments["string2"] as String
        val size = arguments["size"] as Int
        val charset = arguments["charset"] as String
        val format = arguments["format"] as String
        printLeftRight(result, string1, string2, size, charset, format)
      } else {
        result.error("invalid_argument", "argument 'message' not found", null)
      }

      "print3Column" -> if (arguments.containsKey("string1")) {
        val string1 = arguments["string1"] as String
        val string2 = arguments["string2"] as String
        val string3 = arguments["string3"] as String
        val size = arguments["size"] as Int
        val charset = arguments["charset"] as String
        val format = arguments["format"] as String
        print3Column(result, string1, string2, string3, size, charset, format)
      } else {
        result.error("invalid_argument", "argument 'message' not found", null)
      }

      "print4Column" -> if (arguments.containsKey("string1")) {
        val string1 = arguments["string1"] as String
        val string2 = arguments["string2"] as String
        val string3 = arguments["string3"] as String
        val string4 = arguments["string4"] as String
        val size = arguments["size"] as Int
        val charset = arguments["charset"] as String
        val format = arguments["format"] as String
        print4Column(result, string1, string2, string3, string4, size, charset, format)
      } else {
        result.error("invalid_argument", "argument 'message' not found", null)
      }

      else -> result.notImplemented()
    }
  }

  /**
   * @param requestCode  requestCode
   * @param permissions  permissions
   * @param grantResults grantResults
   * @return boolean
   */
  fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String?>, grantResults: IntArray): Boolean {
    // getBondedDevices(pendingResult);
    return true
    // if (requestCode == REQUEST_COARSE_LOCATION_PERMISSIONS) {
    //   if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
    //     getBondedDevices(pendingResult);
    //   } else {
    //     pendingResult.error("no_permissions", "this plugin requires location permissions for scanning", null);
    //     pendingResult = null;
    //   }
    //   return true;
    // }
    // return false;
  }

  private fun state(result: Result) {
    try {
      when (mBluetoothAdapter!!.state) {
        BluetoothAdapter.STATE_OFF -> result.success(BluetoothAdapter.STATE_OFF)
        BluetoothAdapter.STATE_ON -> result.success(BluetoothAdapter.STATE_ON)
        BluetoothAdapter.STATE_TURNING_OFF -> result.success(BluetoothAdapter.STATE_TURNING_OFF)
        BluetoothAdapter.STATE_TURNING_ON -> result.success(BluetoothAdapter.STATE_TURNING_ON)
        else -> result.success(0)
      }
    } catch (e: SecurityException) {
      result.error("invalid_argument", "Argument 'address' not found", null)
    }
  }

  /**
   * @param result result
   */
  private fun getBondedDevices(result: Result) {
    val list: MutableList<Map<String, Any>> = ArrayList()

    for (device in mBluetoothAdapter!!.bondedDevices) {
      val ret: MutableMap<String, Any> = HashMap()
      ret.put("address", device.address)
      ret.put("name", device.name)
      ret.put("type", device.type)
      list.add(ret)
    }

    result.success(list)
  }


  /**
   * @param result  result
   * @param address address
   */
  private fun isDeviceConnected(result: Result, address: String) {
    AsyncTask.execute {
      try {
        val device = mBluetoothAdapter!!.getRemoteDevice(address)

        if (device == null) {
          result.error("connect_error", "device not found", null)
          return@execute
        }

        if (THREAD != null && BluetoothDevice.ACTION_ACL_CONNECTED == Intent(BluetoothDevice.ACTION_ACL_CONNECTED).action) {
          result.success(true)
        } else {
          result.success(false)
        }
      } catch (ex: Exception) {
        Log.e(TAG, ex.message, ex)
        result.error("connect_error", ex.message, exceptionToString(ex))
      }
    }
  }

  private fun exceptionToString(ex: Exception): String {
    val sw = StringWriter()
    val pw = PrintWriter(sw)
    ex.printStackTrace(pw)
    return sw.toString()
  }

  /**
   * @param result  result
   * @param address address
   */
  private fun connect(result: Result, address: String) {
    if (THREAD != null) {
      result.error("connect_error", "already connected", null)
      return
    }
    AsyncTask.execute {
      try {
        val device = mBluetoothAdapter!!.getRemoteDevice(address)

        if (device == null) {
          result.error("connect_error", "device not found", null)
          return@execute
        }

        val socket = device.createRfcommSocketToServiceRecord(MY_UUID)

        if (socket == null) {
          result.error("connect_error", "socket connection not established", null)
          return@execute
        }

        // Cancel bt discovery, even though we didn't start it
        mBluetoothAdapter!!.cancelDiscovery()

        try {
          socket.connect()
          THREAD = ConnectedThread(socket)
          THREAD!!.start()
          result.success(true)
        } catch (ex: Exception) {
          Log.e(TAG, ex.message, ex)
          result.error("connect_error", ex.message, exceptionToString(ex))
        }
      } catch (ex: Exception) {
        Log.e(TAG, ex.message, ex)
        result.error("connect_error", ex.message, exceptionToString(ex))
      }
    }
  }

  /**
   * @param result result
   */
  private fun disconnect(result: Result) {
    if (THREAD == null) {
      result.error("disconnection_error", "not connected", null)
      return
    }
    AsyncTask.execute {
      try {
        THREAD!!.cancel()
        THREAD = null
        result.success(true)
      } catch (ex: Exception) {
        Log.e(TAG, ex.message, ex)
        result.error("disconnection_error", ex.message, exceptionToString(ex))
      }
    }
  }

  /**
   * @param result  result
   * @param message message
   */
  private fun write(result: Result, message: String) {
    if (THREAD == null) {
      result.error("write_error", "not connected", null)
      return
    }

    try {
      THREAD!!.write(message.toByteArray())
      result.success(true)
    } catch (ex: Exception) {
      Log.e(TAG, ex.message, ex)
      result.error("write_error", ex.message, exceptionToString(ex))
    }
  }

  private fun writeBytes(result: Result, message: ByteArray) {
    if (THREAD == null) {
      result.error("write_error", "not connected", null)
      return
    }

    try {
      THREAD!!.write(message)
      result.success(true)
    } catch (ex: Exception) {
      Log.e(TAG, ex.message, ex)
      result.error("write_error", ex.message, exceptionToString(ex))
    }
  }

  private fun printCustom(result: Result, message: String, size: Int, align: Int, charset: String?) {
    // Print config "mode"
    val cc = byteArrayOf(0x1B, 0x21, 0x03) // 0- normal size text
    // byte[] cc1 = new byte[]{0x1B,0x21,0x00}; // 0- normal size text
    val bb = byteArrayOf(0x1B, 0x21, 0x08) // 1- only bold text
    val bb2 = byteArrayOf(0x1B, 0x21, 0x20) // 2- bold with medium text
    val bb3 = byteArrayOf(0x1B, 0x21, 0x10) // 3- bold with large text
    val bb4 = byteArrayOf(0x1B, 0x21, 0x30) // 4- strong text
    val bb5 = byteArrayOf(0x1B, 0x21, 0x50) // 5- extra strong text
    if (THREAD == null) {
      result.error("write_error", "not connected", null)
      return
    }

    try {
      when (size) {
        0 -> THREAD!!.write(cc)
        1 -> THREAD!!.write(bb)
        2 -> THREAD!!.write(bb2)
        3 -> THREAD!!.write(bb3)
        4 -> THREAD!!.write(bb4)
        5 -> THREAD!!.write(bb5)
      }
      when (align) {
        0 ->           // left align
          THREAD!!.write(PrinterCommands.ESC_ALIGN_LEFT)

        1 ->           // center align
          THREAD!!.write(PrinterCommands.ESC_ALIGN_CENTER)

        2 ->           // right align
          THREAD!!.write(PrinterCommands.ESC_ALIGN_RIGHT)
      }
      if (charset != null) {
        THREAD!!.write(message.toByteArray(charset(charset)))
      } else {
        THREAD!!.write(message.toByteArray())
      }
      THREAD!!.write(PrinterCommands.FEED_LINE)
      result.success(true)
    } catch (ex: Exception) {
      Log.e(TAG, ex.message, ex)
      result.error("write_error", ex.message, exceptionToString(ex))
    }
  }

  private fun printLeftRight(result: Result, msg1: String, msg2: String, size: Int, charset: String?, format: String?) {
    val cc = byteArrayOf(0x1B, 0x21, 0x03) // 0- normal size text
    // byte[] cc1 = new byte[]{0x1B,0x21,0x00}; // 0- normal size text
    val bb = byteArrayOf(0x1B, 0x21, 0x08) // 1- only bold text
    val bb2 = byteArrayOf(0x1B, 0x21, 0x20) // 2- bold with medium text
    val bb3 = byteArrayOf(0x1B, 0x21, 0x10) // 3- bold with large text
    val bb4 = byteArrayOf(0x1B, 0x21, 0x30) // 4- strong text
    if (THREAD == null) {
      result.error("write_error", "not connected", null)
      return
    }
    try {
      when (size) {
        0 -> THREAD!!.write(cc)
        1 -> THREAD!!.write(bb)
        2 -> THREAD!!.write(bb2)
        3 -> THREAD!!.write(bb3)
        4 -> THREAD!!.write(bb4)
      }
      THREAD!!.write(PrinterCommands.ESC_ALIGN_CENTER)
      var line: String = String.format("%-15s %15s %n", msg1, msg2)
      if (format != null) {
        line = String.format(format, msg1, msg2)
      }
      if (charset != null) {
        THREAD!!.write(line.toByteArray(charset(charset)))
      } else {
        THREAD!!.write(line.toByteArray())
      }
      result.success(true)
    } catch (ex: Exception) {
      Log.e(TAG, ex.message, ex)
      result.error("write_error", ex.message, exceptionToString(ex))
    }
  }

  private fun print3Column(result: Result, msg1: String, msg2: String, msg3: String, size: Int, charset: String?, format: String?) {
    val cc = byteArrayOf(0x1B, 0x21, 0x03) // 0- normal size text
    // byte[] cc1 = new byte[]{0x1B,0x21,0x00}; // 0- normal size text
    val bb = byteArrayOf(0x1B, 0x21, 0x08) // 1- only bold text
    val bb2 = byteArrayOf(0x1B, 0x21, 0x20) // 2- bold with medium text
    val bb3 = byteArrayOf(0x1B, 0x21, 0x10) // 3- bold with large text
    val bb4 = byteArrayOf(0x1B, 0x21, 0x30) // 4- strong text
    if (THREAD == null) {
      result.error("write_error", "not connected", null)
      return
    }
    try {
      when (size) {
        0 -> THREAD!!.write(cc)
        1 -> THREAD!!.write(bb)
        2 -> THREAD!!.write(bb2)
        3 -> THREAD!!.write(bb3)
        4 -> THREAD!!.write(bb4)
      }
      THREAD!!.write(PrinterCommands.ESC_ALIGN_CENTER)
      var line: String = String.format("%-10s %10s %10s %n", msg1, msg2, msg3)
      if (format != null) {
        line = String.format(format, msg1, msg2, msg3)
      }
      if (charset != null) {
        THREAD!!.write(line.toByteArray(charset(charset)))
      } else {
        THREAD!!.write(line.toByteArray())
      }
      result.success(true)
    } catch (ex: Exception) {
      Log.e(TAG, ex.message, ex)
      result.error("write_error", ex.message, exceptionToString(ex))
    }
  }

  private fun print4Column(result: Result, msg1: String, msg2: String, msg3: String, msg4: String, size: Int, charset: String?, format: String?) {
    val cc = byteArrayOf(0x1B, 0x21, 0x03) // 0- normal size text
    // byte[] cc1 = new byte[]{0x1B,0x21,0x00}; // 0- normal size text
    val bb = byteArrayOf(0x1B, 0x21, 0x08) // 1- only bold text
    val bb2 = byteArrayOf(0x1B, 0x21, 0x20) // 2- bold with medium text
    val bb3 = byteArrayOf(0x1B, 0x21, 0x10) // 3- bold with large text
    val bb4 = byteArrayOf(0x1B, 0x21, 0x30) // 4- strong text
    if (THREAD == null) {
      result.error("write_error", "not connected", null)
      return
    }
    try {
      when (size) {
        0 -> THREAD!!.write(cc)
        1 -> THREAD!!.write(bb)
        2 -> THREAD!!.write(bb2)
        3 -> THREAD!!.write(bb3)
        4 -> THREAD!!.write(bb4)
      }
      THREAD!!.write(PrinterCommands.ESC_ALIGN_CENTER)
      var line: String = String.format("%-8s %7s %7s %7s %n", msg1, msg2, msg3, msg4)
      if (format != null) {
        line = String.format(format, msg1, msg2, msg3, msg4)
      }
      if (charset != null) {
        THREAD!!.write(line.toByteArray(charset(charset)))
      } else {
        THREAD!!.write(line.toByteArray())
      }
      result.success(true)
    } catch (ex: Exception) {
      Log.e(TAG, ex.message, ex)
      result.error("write_error", ex.message, exceptionToString(ex))
    }
  }

  private fun printNewLine(result: Result) {
    if (THREAD == null) {
      result.error("write_error", "not connected", null)
      return
    }
    try {
      THREAD!!.write(PrinterCommands.FEED_LINE)
      result.success(true)
    } catch (ex: Exception) {
      Log.e(TAG, ex.message, ex)
      result.error("write_error", ex.message, exceptionToString(ex))
    }
  }

  private fun paperCut(result: Result) {
    if (THREAD == null) {
      result.error("write_error", "not connected", null)
      return
    }
    try {
      THREAD!!.write(PrinterCommands.FEED_PAPER_AND_CUT)
      result.success(true)
    } catch (ex: Exception) {
      Log.e(TAG, ex.message, ex)
      result.error("write_error", ex.message, exceptionToString(ex))
    }
  }

  private fun drawerPin2(result: Result) {
    if (THREAD == null) {
      result.error("write_error", "not connected", null)
      return
    }
    try {
      THREAD!!.write(PrinterCommands.ESC_DRAWER_PIN2)
      result.success(true)
    } catch (ex: Exception) {
      Log.e(TAG, ex.message, ex)
      result.error("write_error", ex.message, exceptionToString(ex))
    }
  }

  private fun drawerPin5(result: Result) {
    if (THREAD == null) {
      result.error("write_error", "not connected", null)
      return
    }
    try {
      THREAD!!.write(PrinterCommands.ESC_DRAWER_PIN5)
      result.success(true)
    } catch (ex: Exception) {
      Log.e(TAG, ex.message, ex)
      result.error("write_error", ex.message, exceptionToString(ex))
    }
  }

  private fun printImage(result: Result, pathImage: String) {
    if (THREAD == null) {
      result.error("write_error", "not connected", null)
      return
    }
    try {
      val bmp = BitmapFactory.decodeFile(pathImage)
      if (bmp != null) {
        val command: ByteArray = Utils.decodeBitmap(bmp)
        THREAD!!.write(PrinterCommands.ESC_ALIGN_CENTER)
        THREAD!!.write(command)
      } else {
        Log.e("Print Photo error", "the file isn't exists")
      }
      result.success(true)
    } catch (ex: Exception) {
      Log.e(TAG, ex.message, ex)
      result.error("write_error", ex.message, exceptionToString(ex))
    }
  }

  private fun printImageBytes(result: Result, bytes: ByteArray) {
    if (THREAD == null) {
      result.error("write_error", "not connected", null)
      return
    }
    try {
      val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
      if (bmp != null) {
        val command: ByteArray = Utils.decodeBitmap(bmp)
        THREAD!!.write(PrinterCommands.ESC_ALIGN_CENTER)
        THREAD!!.write(command)
      } else {
        Log.e("Print Photo error", "the file isn't exists")
      }
      result.success(true)
    } catch (ex: Exception) {
      Log.e(TAG, ex.message, ex)
      result.error("write_error", ex.message, exceptionToString(ex))
    }
  }

  private fun printQRcode(result: Result, textToQR: String, width: Int, height: Int, align: Int) {
    val multiFormatWriter = MultiFormatWriter()
    if (THREAD == null) {
      result.error("write_error", "not connected", null)
      return
    }
    try {
      when (align) {
        0 ->           // left align
          THREAD!!.write(PrinterCommands.ESC_ALIGN_LEFT)

        1 ->           // center align
          THREAD!!.write(PrinterCommands.ESC_ALIGN_CENTER)

        2 ->           // right align
          THREAD!!.write(PrinterCommands.ESC_ALIGN_RIGHT)
      }
      val bitMatrix = multiFormatWriter.encode(textToQR, BarcodeFormat.QR_CODE, width, height)
      val barcodeEncoder = BarcodeEncoder()
      val bmp = barcodeEncoder.createBitmap(bitMatrix)
      if (bmp != null) {
        val command: ByteArray? = Utils.decodeBitmap(bmp)
        THREAD!!.write(command)
      } else {
        Log.e("Print Photo error", "the file isn't exists")
      }
      result.success(true)
    } catch (ex: Exception) {
      Log.e(TAG, ex.message, ex)
      result.error("write_error", ex.message, exceptionToString(ex))
    }
  }

  private inner class ConnectedThread(private val mmSocket: BluetoothSocket) : Thread() {
    private val inputStream: InputStream?
    private val outputStream: OutputStream?

    init {
      var tmpIn: InputStream? = null
      var tmpOut: OutputStream? = null

      try {
        tmpIn = mmSocket.inputStream
        tmpOut = mmSocket.outputStream
      } catch (e: IOException) {
        e.printStackTrace()
      }
      inputStream = tmpIn
      outputStream = tmpOut
    }

    override fun run() {
      val buffer = ByteArray(1024)
      var bytes: Int
      while (true) {
        try {
          bytes = inputStream!!.read(buffer)
          readSink.success(String(buffer, 0, bytes))
        } catch (e: NullPointerException) {
          break
        } catch (e: IOException) {
          break
        }
      }
    }

    fun write(bytes: ByteArray?) {
      try {
        outputStream!!.write(bytes)
      } catch (e: IOException) {
        e.printStackTrace()
      }
    }

    fun cancel() {
      try {
        outputStream!!.flush()
        outputStream.close()

        inputStream!!.close()

        mmSocket.close()
      } catch (e: IOException) {
        e.printStackTrace()
      }
    }
  }

  private val stateStreamHandler: StreamHandler = object : StreamHandler() {
    private val mReceiver: BroadcastReceiver = object : BroadcastReceiver() {
      override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action

        Log.d(TAG, action!!)

        if (BluetoothAdapter.ACTION_STATE_CHANGED == action) {
          THREAD = null
          statusSink.success(intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1))
        } else if (BluetoothDevice.ACTION_ACL_CONNECTED == action) {
          statusSink.success(1)
        } else if (BluetoothDevice.ACTION_ACL_DISCONNECT_REQUESTED == action) {
          THREAD = null
          statusSink.success(2)
        } else if (BluetoothDevice.ACTION_ACL_DISCONNECTED == action) {
          THREAD = null
          statusSink.success(0)
        }
      }
    }

    fun onListen(o: Any?, eventSink: EventSink?) {
      statusSink = eventSink
      context!!.registerReceiver(mReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))

      context!!.registerReceiver(mReceiver, IntentFilter(BluetoothDevice.ACTION_ACL_CONNECTED))

      context!!.registerReceiver(mReceiver, IntentFilter(BluetoothDevice.ACTION_ACL_DISCONNECT_REQUESTED))

      context!!.registerReceiver(mReceiver, IntentFilter(BluetoothDevice.ACTION_ACL_DISCONNECTED))
    }

    fun onCancel(o: Any?) {
      statusSink = null
      context!!.unregisterReceiver(mReceiver)
    }
  }

  private val readResultsHandler: StreamHandler = object : StreamHandler() {
    fun onListen(o: Any?, eventSink: EventSink?) {
      readSink = eventSink
    }

    fun onCancel(o: Any?) {
      readSink = null
    }
  }

  companion object {
    private const val TAG = "BThermalPrinterPlugin"
    private const val NAMESPACE = "blue_thermal_printer"

    //private static final int REQUEST_COARSE_LOCATION_PERMISSIONS = 1451;
    private val MY_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private var THREAD: ConnectedThread? = null
  }
}