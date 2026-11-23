package dev.mixcloudtv.data.stream

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** The base64 + repeating-key XOR obfuscation Mixcloud applies to stream URLs. */
@OptIn(ExperimentalEncodingApi::class)
object XorCipher {
    private const val KEY = "IFYOUWANTTHEARTISTSTOGETPAIDDONOTDOWNLOADFROMMIXCLOUD"
    private val keyBytes = KEY.encodeToByteArray()

    fun decode(encoded: String): String = xor(Base64.decode(encoded)).decodeToString()

    fun encode(plain: String): String = Base64.encode(xor(plain.encodeToByteArray()))

    private fun xor(input: ByteArray): ByteArray =
        ByteArray(input.size) { i -> (input[i].toInt() xor keyBytes[i % keyBytes.size].toInt()).toByte() }
}
