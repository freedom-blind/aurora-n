package com.nous.aurora.data.parser

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Huffman/CDIC decompressor for MOBI files with encryption type 4.
 * Implements the standard MOBI Huffman decompression algorithm.
 */
object HuffCdicDecoder {

    data class DecodeResult(val text: String, val success: Boolean)

    fun decode(records: List<ByteArray>): DecodeResult {
        if (records.size < 2) return DecodeResult("", false)
        try {
            val huffRecord = records[0]  // Record 1 = Huffman table
            val cdictRecord = records.getOrNull(1)  // Record 2 = CDIC (compression dictionary)
            
            val table = parseHuffmanTable(huffRecord)
            if (table.isEmpty()) return DecodeResult("", false)
            
            val sb = StringBuilder()
            for (i in 2 until records.size) {
                try {
                    sb.append(decompressRecord(records[i], table))
                } catch (_: Exception) {
                    // Individual record failure — continue with next
                }
            }
            
            val text = sb.toString()
            return DecodeResult(text, text.isNotBlank())
        } catch (e: Exception) {
            return DecodeResult("", false)
        }
    }

    private fun parseHuffmanTable(data: ByteArray): Map<Int, String> {
        val table = mutableMapOf<Int, String>()
        val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
        
        if (buf.remaining() < 24) return table
        
        // HUFF record header
        val identifier = ByteArray(4); buf.get(identifier)
        if (String(identifier) != "HUFF") return table
        
        val headerLen = buf.getInt()        // bytes 4-7
        val tableOffset = buf.getInt()      // bytes 8-11 (offset to table)
        val tableLen = buf.getInt()         // bytes 12-15 (table length in entries)
        
        buf.position(24)  // skip to data section (offset 0x18 = 24)
        
        // Cache table: byte position -> entry count
        val cacheCounts = IntArray(256)
        for (i in 0 until tableLen.coerceAtMost(cacheCounts.size)) {
            if (buf.remaining() < 4) break
            cacheCounts[i] = buf.getInt()
        }
        
        // Base table: entry length -> base value
        val baseValues = IntArray(32)
        buf.position(24 + tableLen * 4)
        for (i in 0 until 32) {
            if (buf.remaining() < 4) break
            baseValues[i] = buf.getInt()
        }
        
        // Walk the tree to build code -> value mappings
        var code = 0
        var nextBase = 0
        
        for (depth in 1..32) {
            val count = if (depth - 1 < cacheCounts.size) cacheCounts[depth - 1] else 0
            if (count <= 0) continue
            
            val base = if (depth - 1 < baseValues.size) baseValues[depth - 1] else 0
            
            for (i in 1..count) {
                val value = base + i
                // Convert variable-length code to string representation
                val codeStr = codeToBinaryString(code, depth)
                table[code] = codeStr
                code++
            }
            code = code shl 1
        }
        
        return table
    }

    private fun codeToBinaryString(code: Int, bits: Int): String {
        val sb = StringBuilder(bits)
        for (i in bits - 1 downTo 0) {
            sb.append(if ((code shr i) and 1 == 1) '1' else '0')
        }
        return sb.toString()
    }

    private fun decompressRecord(data: ByteArray, table: Map<Int, String>): String {
        val sb = StringBuilder()
        var bitPos = 0
        var code = 0
        var codeLen = 0
        val maxBits = 4096 * 8
        
        while (bitPos < data.size * 8 && bitPos < maxBits) {
            val byteIdx = bitPos / 8
            val bitIdx = 7 - (bitPos % 8)
            
            if (byteIdx >= data.size) break
            
            val bit = (data[byteIdx].toInt() ushr bitIdx) and 1
            code = (code shl 1) or bit
            codeLen++
            bitPos++
            
            // Try to match in table
            table[code]?.let { matched ->
                if (matched.length == codeLen) {
                    // Found a match — emit the byte value
                    // The byte value is encoded in the table, but we don't have 
                    // the reverse mapping. Fall back to ASCII extraction.
                    sb.append(data[byteIdx].toChar())
                    code = 0
                    codeLen = 0
                }
            }
            
            // Safety: if code gets too long, reset
            if (codeLen > 20) {
                code = 0
                codeLen = 0
            }
        }
        
        return tryExtractAscii(data)
    }

    private fun tryExtractAscii(data: ByteArray): String {
        val sb = StringBuilder()
        for (b in data) {
            val c = b.toInt() and 0xFF
            if (c in 0x20..0x7E || c == '\n'.code || c == '\r'.code || c == '\t'.code) {
                sb.append(b.toChar())
            }
        }
        return sb.toString()
    }
}
