package com.ai.companion.core.avatar

import android.content.Context
import android.opengl.GLSurfaceView
import android.util.Log
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * 3D虚拟形象引擎 - 简化版，使用GLSurfaceView直接渲染
 * 实际3D渲染在后续版本中完善
 */
class Avatar3DEngine(private val context: Context) {

    companion object {
        private const val TAG = "Avatar3DEngine"
    }

    private var glSurfaceView: GLSurfaceView? = null
    private var currentEmotion = "NEUTRAL"
    private var isCurrentlySpeaking = false

    fun init(surfaceView: GLSurfaceView) {
        glSurfaceView = surfaceView

        surfaceView.setEGLContextClientVersion(2)
        surfaceView.setRenderer(object : GLSurfaceView.Renderer {
            override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
                gl?.glClearColor(0.95f, 0.90f, 0.95f, 1.0f)
            }

            override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
                gl?.glViewport(0, 0, width, height)
                gl?.glMatrixMode(GL10.GL_PROJECTION)
                gl?.glLoadIdentity()
                val ratio = width.toFloat() / height.toFloat()
                gl?.glFrustumf(-ratio, ratio, -1f, 1f, 1.5f, 10f)
                gl?.glMatrixMode(GL10.GL_MODELVIEW)
            }

            override fun onDrawFrame(gl: GL10?) {
                gl?.glClear(GL10.GL_COLOR_BUFFER_BIT or GL10.GL_DEPTH_BUFFER_BIT)
                gl?.glLoadIdentity()
                gl?.glTranslatef(0f, 0f, -3f)

                // 绘制简单卡通形象（头部 - 球体近似）
                drawAvatar(gl)
            }
        })
        surfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
    }

    private fun drawAvatar(gl: GL10?) {
        // 根据情绪选择颜色
        val color = when (currentEmotion) {
            "HAPPY" -> floatArrayOf(1.0f, 0.85f, 0.0f)  // 金色
            "SAD" -> floatArrayOf(0.4f, 0.6f, 1.0f)     // 蓝色
            "ANGRY" -> floatArrayOf(1.0f, 0.3f, 0.3f)   // 红色
            "TIRED" -> floatArrayOf(0.6f, 0.6f, 0.8f)   // 灰蓝
            "ANXIOUS" -> floatArrayOf(1.0f, 0.7f, 0.3f) // 橙色
            "LOVING" -> floatArrayOf(1.0f, 0.4f, 0.7f)  // 粉色
            else -> floatArrayOf(0.8f, 0.7f, 0.9f)       // 紫色
        }

        gl?.glColor4f(color[0], color[1], color[2], 1.0f)

        // 绘制球体（头部）
        drawSphere(gl, 0.0f, 0.3f, 0.0f, 0.25f, 16)

        // 绘制身体（圆锥）
        gl?.glColor4f(color[0] * 0.7f, color[1] * 0.7f, color[2] * 0.7f, 1.0f)
        drawCone(gl, 0.0f, -0.3f, 0.0f, 0.2f, 0.3f, 12)

        // 眼睛
        val eyeY = 0.35f
        val eyeOffset = 0.08f
        gl?.glColor4f(1.0f, 1.0f, 1.0f, 1.0f)
        drawSphere(gl, -eyeOffset, eyeY, 0.22f, 0.04f, 8)
        drawSphere(gl, eyeOffset, eyeY, 0.22f, 0.04f, 8)

        // 瞳孔
        gl?.glColor4f(0.1f, 0.1f, 0.1f, 1.0f)
        drawSphere(gl, -eyeOffset, eyeY, 0.24f, 0.02f, 8)
        drawSphere(gl, eyeOffset, eyeY, 0.24f, 0.02f, 8)

        // 嘴巴 - 根据情绪
        gl?.glColor4f(0.6f, 0.3f, 0.3f, 1.0f)
        val mouthY = 0.22f
        if (currentEmotion == "HAPPY" || currentEmotion == "LOVING") {
            // 微笑
            drawArc(gl, 0.0f, mouthY, 0.23f, 0.06f, 0.04f, 8)
        } else if (currentEmotion == "SAD") {
            // 悲伤
            drawArc(gl, 0.0f, mouthY - 0.03f, 0.23f, -0.04f, 0.06f, 8)
        } else {
            // 中性
            drawLine(gl, -0.06f, mouthY, 0.0f, 0.06f, mouthY, 0.0f)
        }

        // 说话动画
        if (isCurrentlySpeaking) {
            val mouthOpen = (Math.sin(System.currentTimeMillis() / 100.0) * 0.02 + 0.02).toFloat()
            gl?.glColor4f(0.6f, 0.3f, 0.3f, 1.0f)
            drawSphere(gl, 0.0f, mouthY - 0.02f, 0.24f, 0.015f + mouthOpen, 6)
        }
    }

    private fun drawSphere(gl: GL10?, cx: Float, cy: Float, cz: Float, r: Float, stacks: Int) {
        val slices = stacks * 2
        for (i in 0 until stacks) {
            val lat0 = Math.PI * (-0.5 + (i.toDouble() / stacks))
            val z0 = Math.sin(lat0).toFloat()
            val zr0 = Math.cos(lat0).toFloat()

            val lat1 = Math.PI * (-0.5 + ((i + 1).toDouble() / stacks))
            val z1 = Math.sin(lat1).toFloat()
            val zr1 = Math.cos(lat1).toFloat()

            gl?.glBegin(GL10.GL_TRIANGLE_STRIP)
            for (j in 0..slices) {
                val lng = 2 * Math.PI * (j.toDouble() / slices)
                val x = Math.cos(lng).toFloat()
                val y = Math.sin(lng).toFloat()

                gl?.glVertex3f(cx + x * zr0 * r, cy + z0 * r, cz + y * zr0 * r)
                gl?.glVertex3f(cx + x * zr1 * r, cy + z1 * r, cz + y * zr1 * r)
            }
            gl?.glEnd()
        }
    }

    private fun drawCone(gl: GL10?, cx: Float, cy: Float, cz: Float, radius: Float, height: Float, sides: Int) {
        gl?.glBegin(GL10.GL_TRIANGLE_FAN)
        gl?.glVertex3f(cx, cy + height, cz) // 顶点
        for (i in 0..sides) {
            val angle = 2 * Math.PI * (i.toDouble() / sides)
            val x = Math.cos(angle).toFloat() * radius
            val z = Math.sin(angle).toFloat() * radius
            gl?.glVertex3f(cx + x, cy, cz + z)
        }
        gl?.glEnd()
    }

    private fun drawArc(gl: GL10?, cx: Float, cy: Float, cz: Float, width: Float, height: Float, segments: Int) {
        gl?.glBegin(GL10.GL_LINE_STRIP)
        for (i in 0..segments) {
            val t = Math.PI * (i.toDouble() / segments)
            val x = Math.cos(t).toFloat() * width
            val y = Math.sin(t).toFloat() * height
            gl?.glVertex3f(cx + x, cy - y, cz)
        }
        gl?.glEnd()
    }

    private fun drawLine(gl: GL10?, x1: Float, y1: Float, z1: Float, x2: Float, y2: Float, z2: Float) {
        gl?.glBegin(GL10.GL_LINES)
        gl?.glVertex3f(x1, y1, z1)
        gl?.glVertex3f(x2, y2, z2)
        gl?.glEnd()
    }

    fun loadAvatarModel(modelPath: String) {
        Log.d(TAG, "Avatar model loading skipped (using GL fallback): $modelPath")
    }

    fun setEmotion(emotion: String) {
        currentEmotion = emotion
    }

    fun setSpeaking(speaking: Boolean) {
        isCurrentlySpeaking = speaking
    }

    fun onResume() {
        glSurfaceView?.onResume()
    }

    fun onPause() {
        glSurfaceView?.onPause()
    }

    fun release() {
        // no-op
    }
}

enum class AvatarAnimation {
    IDLE, TALKING, HAPPY, SAD, ANGRY, TIRED, ANXIOUS, LOVING, SURPRISED, BLINK
}