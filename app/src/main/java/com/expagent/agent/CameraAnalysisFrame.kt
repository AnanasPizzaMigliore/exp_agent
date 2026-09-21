/*
* Copyright (C) 2026 exp agent contributors
*
* This program is free software: you can redistribute it and/or modify
* it under the terms of the GNU General Public License as published by
* the Free Software Foundation, version 3.
*
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
* GNU General Public License for more details.
*
* You should have received a copy of the GNU General Public License
* along with this program. If not, see <https://www.gnu.org/licenses/>.
*/

package com.expagent.agent

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream
import kotlin.math.ceil

/** Copies camera-owned buffers on the analysis executor; never retains ImageProxy. */
data class CameraAnalysisFrame(
    val luma: ByteArray,
    val width: Int,
    val height: Int,
    val rotation: Int,
    val jpeg: ByteArray?,
    ) {
    companion object {
        fun copy(image: ImageProxy, includeSnapshot: Boolean): CameraAnalysisFrame {
            val y=image.planes[0]
            val yBuffer=y.buffer.duplicate()
            val yStart=yBuffer.position()
            val step=ceil(maxOf(image.width/320.0, image.height/240.0)).toInt().coerceAtLeast(1)
            val width=image.width/step
            val height=image.height/step
            val luma=ByteArray(width*height) { index ->
                yBuffer.get(yStart+(index/width)*step*y.rowStride+(index%width)*step*y.pixelStride)
                }
            val jpeg=if (includeSnapshot) runCatching { snapshot(image) }.getOrNull() else null
            return CameraAnalysisFrame(luma, width, height, image.imageInfo.rotationDegrees, jpeg)
            }

        private fun snapshot(image: ImageProxy): ByteArray {
            require(image.format==ImageFormat.YUV_420_888&&image.planes.size==3)
            val step=ceil(maxOf(image.width, image.height)/640.0).toInt().coerceAtLeast(1)
            val width=(image.width/step)/2*2
            val height=(image.height/step)/2*2
            require(width>0&&height>0)
            val planes=image.planes
            val buffers=planes.map { it.buffer.duplicate() }
            val starts=buffers.map { it.position() }
            val nv21=ByteArray(width*height*3/2)
            for (row in 0 until height) for (col in 0 until width)
            nv21[row*width+col]=buffers[0].get(starts[0]+row*step*planes[0].rowStride+col*step*planes[0].pixelStride)
            var offset=width*height
            for (row in 0 until height/2) for (col in 0 until width/2) {
                nv21[offset++]=buffers[2].get(starts[2]+row*step*planes[2].rowStride+col*step*planes[2].pixelStride)
                nv21[offset++]=buffers[1].get(starts[1]+row*step*planes[1].rowStride+col*step*planes[1].pixelStride)
                }
            val output=ByteArrayOutputStream()
            check(YuvImage(nv21, ImageFormat.NV21, width, height, null)
                .compressToJpeg(Rect(0, 0, width, height), 75, output))
            val bytes=output.toByteArray()
            val rotation=image.imageInfo.rotationDegrees
            if (rotation==0) return bytes
            val decoded=BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return bytes
            try {
                val matrix=Matrix().apply { postRotate(rotation.toFloat()) }
                val rotated=Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
                try {
                    val rotatedOutput=ByteArrayOutputStream()
                    check(rotated.compress(Bitmap.CompressFormat.JPEG, 75, rotatedOutput))
                    return rotatedOutput.toByteArray()
                    }
                finally { if (rotated!==decoded) rotated.recycle() }
                }
            finally { decoded.recycle() }
            }
        }
    }
