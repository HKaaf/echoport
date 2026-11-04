package io.github.hkaaf.echoport.engine

import android.os.SystemClock
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Echoport protocol v1. Keep in sync with PROTOCOL.md in the repository root. */
object Proto {
    const val VERSION = 1
    const val DEFAULT_PORT = 47210
    /** NsdManager wants the type without the trailing ".local.". */
    const val NSD_TYPE = "_echoport._tcp"

    const val SAMPLE_RATE = 48_000
    const val CHANNELS = 2
    const val FRAME_MS = 5
    const val FRAME_SAMPLES = SAMPLE_RATE * FRAME_MS / 1000 // per channel

    const val HEADER_LEN = 12
    const val KIND_AUDIO: Byte = 0
    const val KIND_HI: Byte = 1
    private const val MAX_MSG = 64 * 1024

    fun readMsg(input: DataInputStream): JSONObject {
        val len = input.readInt()
        if (len < 0 || len > MAX_MSG) throw IOException("control message too large: $len")
        val bytes = ByteArray(len)
        input.readFully(bytes)
        return JSONObject(String(bytes, Charsets.UTF_8))
    }

    fun writeMsg(out: DataOutputStream, msg: JSONObject) {
        val bytes = msg.toString().toByteArray(Charsets.UTF_8)
        synchronized(out) {
            out.writeInt(bytes.size)
            out.write(bytes)
            out.flush()
        }
    }

    fun msg(type: String, vararg fields: Pair<String, Any?>): JSONObject =
        JSONObject().apply {
            put("type", type)
            for ((k, v) in fields) if (v != null) put(k, v)
        }

    fun writeHeader(buf: ByteArray, kind: Byte, token: Int, seq: Int) {
        val bb = ByteBuffer.wrap(buf).order(ByteOrder.BIG_ENDIAN)
        bb.put('E'.code.toByte()).put('P'.code.toByte()).put(VERSION.toByte()).put(kind)
        bb.putInt(token).putInt(seq)
    }

    data class Header(val kind: Byte, val token: Int, val seq: Int)

    fun parseHeader(buf: ByteArray, len: Int): Header? {
        if (len < HEADER_LEN || buf[0] != 'E'.code.toByte() || buf[1] != 'P'.code.toByte() || buf[2] != VERSION.toByte()) return null
        val bb = ByteBuffer.wrap(buf, 4, 8).order(ByteOrder.BIG_ENDIAN)
        return Header(buf[3], bb.int, bb.int)
    }

    fun nowMs(): Long = SystemClock.elapsedRealtime()
}

/** The server turned us away; [code] is one of the protocol error codes. */
class RefusedException(val code: String, message: String) : IOException(message)
