package id.kakzaki.blue_thermal_printer

/**
 * Created by https://goo.gl/UAfmBd on 2/6/2017.
 */
object PrinterCommands {
    const val HT: Byte = 0x9
    const val LF: Byte = 0x0A
    const val CR: Byte = 0x0D
    const val ESC: Byte = 0x1B
    const val DLE: Byte = 0x10
    const val GS: Byte = 0x1D
    const val FS: Byte = 0x1C
    const val STX: Byte = 0x02
    const val US: Byte = 0x1F
    const val CAN: Byte = 0x18
    const val CLR: Byte = 0x0C
    const val EOT: Byte = 0x04

    val INIT: ByteArray = byteArrayOf(27, 64)
    var FEED_LINE: ByteArray = byteArrayOf(10)

    var SELECT_FONT_A: ByteArray = byteArrayOf(20, 33, 0)

    var SET_BAR_CODE_HEIGHT: ByteArray = byteArrayOf(29, 104, 100)
    var PRINT_BAR_CODE_1: ByteArray = byteArrayOf(29, 107, 2)
    var SEND_NULL_BYTE: ByteArray = byteArrayOf(0x00)

    var SELECT_PRINT_SHEET: ByteArray = byteArrayOf(0x1B, 0x63, 0x30, 0x02)
    var FEED_PAPER_AND_CUT: ByteArray = byteArrayOf(0x1D, 0x56, 66, 0x00)

    var SELECT_CYRILLIC_CHARACTER_CODE_TABLE: ByteArray = byteArrayOf(0x1B, 0x74, 0x11)

    var SELECT_BIT_IMAGE_MODE: ByteArray = byteArrayOf(0x1B, 0x2A, 33, -128, 0)
    var SET_LINE_SPACING_24: ByteArray = byteArrayOf(0x1B, 0x33, 24)
    var SET_LINE_SPACING_30: ByteArray = byteArrayOf(0x1B, 0x33, 30)

    var TRANSMIT_DLE_PRINTER_STATUS: ByteArray = byteArrayOf(0x10, 0x04, 0x01)
    var TRANSMIT_DLE_OFFLINE_PRINTER_STATUS: ByteArray = byteArrayOf(0x10, 0x04, 0x02)
    var TRANSMIT_DLE_ERROR_STATUS: ByteArray = byteArrayOf(0x10, 0x04, 0x03)
    var TRANSMIT_DLE_ROLL_PAPER_SENSOR_STATUS: ByteArray = byteArrayOf(0x10, 0x04, 0x04)

    val ESC_FONT_COLOR_DEFAULT: ByteArray = byteArrayOf(0x1B, 'r'.code.toByte(), 0x00)
    val FS_FONT_ALIGN: ByteArray = byteArrayOf(0x1C, 0x21, 1, 0x1B,
            0x21, 1)
    val ESC_ALIGN_LEFT: ByteArray = byteArrayOf(0x1b, 'a'.code.toByte(), 0x00)
    val ESC_ALIGN_RIGHT: ByteArray = byteArrayOf(0x1b, 'a'.code.toByte(), 0x02)
    val ESC_ALIGN_CENTER: ByteArray = byteArrayOf(0x1b, 'a'.code.toByte(), 0x01)
    val ESC_CANCEL_BOLD: ByteArray = byteArrayOf(0x1B, 0x45, 0)


    /** */
    val ESC_HORIZONTAL_CENTERS: ByteArray = byteArrayOf(0x1B, 0x44, 20, 28, 0)
    val ESC_CANCLE_HORIZONTAL_CENTERS: ByteArray = byteArrayOf(0x1B, 0x44, 0)

    /** */
    /*********** Open Cash Drawer  */
    val ESC_DRAWER_PIN2: ByteArray = byteArrayOf(0x1B, 'p'.code.toByte(), 0x30)
    val ESC_DRAWER_PIN5: ByteArray = byteArrayOf(0x1B, 'p'.code.toByte(), 0x31)

    /** */
    val ESC_ENTER: ByteArray = byteArrayOf(0x1B, 0x4A, 0x40)
    val PRINTE_TEST: ByteArray = byteArrayOf(0x1D, 0x28, 0x41)
}