package app.hondadash

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/** A byte pipe to an ELM327-style adapter. */
interface Link {
    fun open()
    fun write(s: String)
    /** Reads until the '>' prompt or the timeout. Returns the text without the prompt. */
    fun readUntilPrompt(timeoutMs: Long): String
    fun close()
    val label: String
}

@SuppressLint("MissingPermission")
class BluetoothLink(private val device: BluetoothDevice) : Link {
    private var socket: BluetoothSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    override val label: String get() = (device.name ?: "Adapter") + " " + device.address

    override fun open() {
        try { BluetoothAdapter.getDefaultAdapter()?.cancelDiscovery() } catch (_: Exception) {}
        val spp = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        val attempts = listOf<() -> BluetoothSocket>(
            { device.createInsecureRfcommSocketToServiceRecord(spp) },
            { device.createRfcommSocketToServiceRecord(spp) },
            {
                val m = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                m.invoke(device, 1) as BluetoothSocket
            },
        )
        var last: Exception? = null
        for (make in attempts) {
            try {
                val s = make()
                s.connect()
                socket = s
                input = s.inputStream
                output = s.outputStream
                return
            } catch (e: Exception) {
                last = e
                try { socket?.close() } catch (_: Exception) {}
                socket = null
            }
        }
        throw IOException("Bluetooth connect failed: ${last?.message}")
    }

    override fun write(s: String) {
        val o = output ?: throw IOException("Not connected")
        o.write((s + "\r").toByteArray(Charsets.US_ASCII))
        o.flush()
    }

    override fun readUntilPrompt(timeoutMs: Long): String {
        val i = input ?: throw IOException("Not connected")
        val sb = StringBuilder()
        val deadline = System.currentTimeMillis() + timeoutMs
        val buf = ByteArray(256)
        while (System.currentTimeMillis() < deadline) {
            if (i.available() > 0) {
                val n = i.read(buf, 0, Math.min(buf.size, i.available()))
                for (k in 0 until n) {
                    val c = (buf[k].toInt() and 0xFF).toChar()
                    if (c == '>') return sb.toString()
                    sb.append(c)
                }
            } else {
                Thread.sleep(2)
            }
        }
        return sb.toString() + "\u0000TIMEOUT"
    }

    override fun close() {
        try { input?.close() } catch (_: Exception) {}
        try { output?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        socket = null
    }
}

/**
 * A fake adapter and ECU for testing the screens without the car. It answers AT commands, mode 01,
 * mode 22 RAM reads (from a simulated RAM map) and mode 23 commands like the v5.2 file.
 */
class DemoLink : Link {
    override val label = "Demo (no car)"
    private var pending: String = ""
    private val ram = HashMap<Int, Int>()
    private val t0 = System.currentTimeMillis()

    override fun open() {}
    override fun close() {}

    override fun write(s: String) {
        pending = s.trim().uppercase().replace(" ", "")
    }

    private fun w16(a: Int, v: Int) { ram[a] = (v shr 8) and 0xFF; ram[a + 1] = v and 0xFF }

    private fun simulate() {
        val t = (System.currentTimeMillis() - t0) / 1000.0
        val rpm = (1800 + 1500 * Math.sin(t / 3.0) + 700 * Math.sin(t * 1.7)).toInt().coerceIn(700, 6500)
        val gear = 1 + ((t / 8).toInt() % 5)
        val ratios = doubleArrayOf(2.656, 1.516, 1.082, 0.772, 0.566)
        val main = (rpm * 0.97).toInt()
        val cs = (main / ratios[gear - 1]).toInt()
        w16(0xBC9A, rpm); w16(0xC98C, rpm); w16(0xC990, cs); w16(0xC992, main)
        ram[0xC84D] = gear
        ram[0xC419] = if (rpm < 2500) 2 else 1
        w16(0xBC46, 880 + (20 * Math.sin(t / 20)).toInt())
        w16(0xB6C8, 280); w16(0xD742, if (rpm > 3000) 110 else 280)
        w16(0xCF40, (rpm / 3).coerceAtMost(65535))
    }

    override fun readUntilPrompt(timeoutMs: Long): String {
        Thread.sleep(25)
        val c = pending
        if (c.startsWith("AT")) return when {
            c == "ATZ" -> "ELM327 v1.5 (demo)"
            c == "ATRV" -> "13.9V"
            c == "ATI" -> "ELM327 v1.5"
            else -> "OK"
        }
        simulate()
        val hex = if (c.length % 2 == 1) c.substring(0, c.length - 1) else c
        if (hex.length < 4) return "?"
        val mode = hex.substring(0, 2).toInt(16)
        return when (mode) {
            0x01 -> {
                val pid = hex.substring(2, 4).toInt(16)
                val rpm = ((ram[0xBC9A] ?: 0) shl 8) or (ram[0xBC9B] ?: 0)
                val data = when (pid) {
                    0x00 -> "BE1FA813"
                    0x0C -> String.format("%04X", rpm * 4)
                    0x0D -> String.format("%02X", (rpm / 40).coerceAtMost(255))
                    0x05 -> "7A"
                    0x0F -> "46"
                    0x0B -> "28"
                    0x11 -> "1A"
                    0x0E -> "9C"
                    0x04 -> "50"
                    0x06, 0x07 -> "88"
                    0x24 -> "80008000"
                    else -> return "NO DATA"
                }
                "41" + hex.substring(2, 4) + data
            }
            0x22 -> {
                val base = hex.substring(2, 6).toInt(16)
                val sb = StringBuilder("62" + hex.substring(2, 6))
                for (k in 0 until 4) sb.append(String.format("%02X", ram[base + k] ?: 0))
                sb.toString()
            }
            0x23 -> {
                val cmd = hex.substring(2, 4).toInt(16)
                val v = if (hex.length >= 6) hex.substring(4, 6).toInt(16) else 0
                when (cmd) {
                    0x03 -> { ram[0x6012] = if (v == 1 || v == 2) v else 0 }
                    0x07 -> { ram[0x6023] = v }
                    0x08 -> { ram[0x6024] = v }
                    0x09 -> { ram[0x6025] = v }
                    0x0A -> { ram[0x6038] = v }
                    else -> {}
                }
                "63" + hex.substring(2)
            }
            0x03 -> "4300000000000"
            0x04 -> "44"
            else -> "NO DATA"
        }
    }
}
