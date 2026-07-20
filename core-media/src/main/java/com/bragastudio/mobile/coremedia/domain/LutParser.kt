package com.bragastudio.mobile.coremedia.domain

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 3D LUT Parser for .cube files
 */
class LutParser {
    
    data class LutData(
        val size: Int,
        val floatData: FloatArray
    )
    
    companion object {
        private const val TAG = "LutParser"
        
        fun parseFromUri(context: Context, uri: android.net.Uri): LutData? {
            try {
                val inputStream = context.contentResolver.openInputStream(uri) ?: return null
                return parseFromStream(inputStream)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse LUT from URI: ${e.message}")
                return null
            }
        }

        fun parseFromFile(file: java.io.File): LutData? {
            try {
                val inputStream = java.io.FileInputStream(file)
                return parseFromStream(inputStream)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse LUT from file: ${e.message}")
                return null
            }
        }

        fun parseFromAssets(context: Context, fileName: String): LutData? {
            try {
                val inputStream = context.assets.open(fileName)
                return parseFromStream(inputStream)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse LUT from assets: ${e.message}")
                return null
            }
        }

        private fun parseFromStream(inputStream: InputStream): LutData? {
            try {
                val reader = BufferedReader(InputStreamReader(inputStream))
                
                var size = 0
                val dataValues = mutableListOf<Float>()
                
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val l = line?.trim() ?: continue
                    if (l.isEmpty() || l.startsWith("#") || l.startsWith("TITLE") || l.startsWith("DOMAIN_MIN") || l.startsWith("DOMAIN_MAX")) {
                        continue
                    }
                    
                    if (l.startsWith("LUT_3D_SIZE")) {
                        val parts = l.split(" ")
                        if (parts.size >= 2) {
                            size = parts[1].toInt()
                        }
                        continue
                    }
                    
                    // Se não é metadata, são os valores RGB
                    val rgb = l.split("\\s+".toRegex())
                    if (rgb.size >= 3) {
                        dataValues.add(rgb[0].toFloat())
                        dataValues.add(rgb[1].toFloat())
                        dataValues.add(rgb[2].toFloat())
                        // Vulkan expects 4 channels for R32G32B32A32_SFLOAT usually, or R16G16B16A16
                        dataValues.add(1.0f) 
                    }
                }
                
                reader.close()
                inputStream.close()
                
                if (size > 0 && dataValues.size == size * size * size * 4) {
                    return LutData(size, dataValues.toFloatArray())
                }
                
                Log.e(TAG, "Invalid LUT data: size=$size, values=${dataValues.size}, expected=${size * size * size * 4}")
                return null
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse LUT stream: ${e.message}")
                return null
            }
        }
        
        fun getLutByteArray(lutData: LutData): ByteArray {
            val floatArray = lutData.floatData
            val byteArray = ByteArray(floatArray.size)
            for (i in floatArray.indices) {
                // Clamp entre 0.0 e 1.0, depois multiplica por 255
                val clamped = floatArray[i].coerceIn(0.0f, 1.0f)
                byteArray[i] = (clamped * 255.0f).toInt().toByte()
            }
            return byteArray
        }
    }
}


