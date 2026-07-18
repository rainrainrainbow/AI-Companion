package com.ai.companion.core.avatar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.GLSurfaceView
import android.util.Log
import com.google.android.filament.*
import com.google.android.filament.gltfio.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 3D虚拟形象引擎 - 使用Google Filament渲染
 * 支持GLTF模型加载、表情动画、动作过渡
 */
class Avatar3DEngine(private val context: Context) {

    companion object {
        private const val TAG = "Avatar3DEngine"
    }

    private var engine: Engine? = null
    private var scene: Scene? = null
    private var view: View? = null
    private var renderer: Renderer? = null
    private var camera: Camera? = null
    private var materialProvider: MaterialProvider? = null
    private var assetLoader: AssetLoader? = null
    private var asset: FilamentAsset? = null

    // 表情混合参数
    private val blendShapeWeights = FloatArray(50)

    // 动画状态
    private var currentAnimation = AvatarAnimation.IDLE
    private var animationProgress = 0f
    private var isBlinking = false
    private var blinkTimer = 0f

    // 渲染表面
    private var glSurfaceView: GLSurfaceView? = null

    fun init(surfaceView: GLSurfaceView) {
        glSurfaceView = surfaceView

        engine = Engine.create()
        scene = engine?.createScene()
        renderer = engine?.createRenderer()
        view = engine?.createView()

        // 设置相机
        camera = engine?.createCamera()
        camera?.setProjection(
            Camera.Projection.PERSPECTIVE,
            45.0,           // FOV
            1.0f,           // aspect (will be set properly later)
            0.1,            // near
            100.0           // far
        )
        camera?.lookAt(
            0.0, 1.5, 3.0,   // 相机位置
            0.0, 1.2, 0.0,   // 看向目标
            0.0, 1.0, 0.0    // 上方向
        )
        view?.setCamera(camera)

        // 设置场景背景
        scene?.setSkybox {
            // 创建柔和渐变背景
        }

        // 初始化光照
        setupLighting()

        // 设置渲染表面
        surfaceView.setRenderer(object : GLSurfaceView.Renderer {
            override fun onSurfaceCreated(gl: javax.microedition.khronos.opengles.GL10?, config: javax.microedition.khronos.egl.EGLConfig?) {
                // 引擎初始化已在前面完成
            }

            override fun onSurfaceChanged(gl: javax.microedition.khronos.opengles.GL10?, width: Int, height: Int) {
                view?.setViewport(Rect(0, 0, width, height))
                camera?.setProjection(
                    Camera.Projection.PERSPECTIVE,
                    45.0,
                    width.toFloat() / height.toFloat(),
                    0.1,
                    100.0
                )
            }

            override fun onDrawFrame(gl: javax.microedition.khronos.opengles.GL10?) {
                updateAnimation()
                renderer?.beginFrame(glSurfaceView)
                renderer?.render(view)
                renderer?.endFrame()
            }
        })
        surfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
    }

    private fun setupLighting() {
        // 环境光
        val indirectLight = IndirectLight.Builder()
            .build(engine!!)
        scene?.setIndirectLight(indirectLight)

        // 主光源
        val light = EntityManager.get().create()
        LightManager.Builder(LightManager.Type.DIRECTIONAL)
            .color(1.0f, 0.98f, 0.95f)
            .intensity(100000.0f)
            .direction(1.0f, -1.0f, -1.0f)
            .build(engine!!, light)
        scene?.addEntity(light)
    }

    fun loadAvatarModel(modelPath: String) {
        try {
            materialProvider = MaterialProvider(engine!!)
            assetLoader = AssetLoader(engine!!, materialProvider!!, null)

            val modelFile = File(modelPath)
            if (!modelFile.exists()) {
                Log.w(TAG, "Avatar model not found: $modelPath")
                // 使用程序化生成的默认形象
                createDefaultAvatar()
                return
            }

            val buffer = modelFile.readBytes()
            asset = assetLoader?.createAssetFromBinary(buffer)
            asset?.root?.let { scene?.addEntity(it) }

            // 获取骨骼动画
            val animator = asset?.animator
            if (animator != null && animator.animationCount > 0) {
                Log.d(TAG, "Avatar loaded with ${animator.animationCount} animations")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load avatar model", e)
            createDefaultAvatar()
        }
    }

    private fun createDefaultAvatar() {
        // 使用Filament程序化生成一个简单的卡通形象
        // 由基本几何体组合：球体(头)、圆柱体(身体)、球体(眼睛)
        val renderableManager = engine?.renderableManager
        val entityManager = EntityManager.get()

        // 创建头部（球体）
        val headEntity = entityManager.create()
        RenderableManager.Builder(1)
            .material(0, createDefaultMaterial())
            .geometry(0, RenderableManager.PrimitiveType.TRIANGLES, createSphereMesh(0.3f, 32))
            .build(engine!!, headEntity)
        scene?.addEntity(headEntity)

        // 创建身体（圆柱体）
        val bodyEntity = entityManager.create()
        RenderableManager.Builder(1)
            .material(0, createDefaultMaterial())
            .geometry(0, RenderableManager.PrimitiveType.TRIANGLES, createCylinderMesh(0.25f, 0.5f))
            .build(engine!!, bodyEntity)
        scene?.addEntity(bodyEntity)

        // 转换控制
        val headTransform = TransformManager().getInstance(headEntity)
        // 设置头在身体上方
        val bodyTransform = TransformManager().getInstance(bodyEntity)
        bodyTransform?.let {
            // 设置位置
        }
    }

    private fun createDefaultMaterial(): MaterialInstance? {
        // 创建默认材质（粉色/肤色）
        return null
    }

    private fun createSphereMesh(radius: Float, segments: Int): VertexBuffer? {
        // 生成球体顶点数据
        return null
    }

    private fun createCylinderMesh(radius: Float, height: Float): VertexBuffer? {
        // 生成圆柱体顶点数据
        return null
    }

    /**
     * 设置表情 - 基于AI情绪状态
     */
    fun setEmotion(emotion: String) {
        val animator = asset?.animator ?: return

        // 根据情绪选择对应的动画/表情混合
        val targetAnim = when (emotion) {
            "HAPPY" -> AvatarAnimation.HAPPY
            "SAD" -> AvatarAnimation.SAD
            "ANGRY" -> AvatarAnimation.ANGRY
            "TIRED" -> AvatarAnimation.TIRED
            "ANXIOUS" -> AvatarAnimation.ANXIOUS
            "LOVING" -> AvatarAnimation.LOVING
            else -> AvatarAnimation.IDLE
        }

        // 平滑过渡到目标动画
        currentAnimation = targetAnim
        animationProgress = 0f

        // 设置blend shape权重
        resetBlendShapes()
        when (emotion) {
            "HAPPY" -> {
                blendShapeWeights[0] = 0.8f  // smile
                blendShapeWeights[1] = 0.3f  // brow_up
            }
            "SAD" -> {
                blendShapeWeights[2] = 0.7f  // brow_down
                blendShapeWeights[3] = 0.5f  // mouth_frown
            }
            "ANGRY" -> {
                blendShapeWeights[2] = 0.9f  // brow_down
                blendShapeWeights[4] = 0.6f  // mouth_press
            }
            "LOVING" -> {
                blendShapeWeights[0] = 0.6f  // smile
                blendShapeWeights[5] = 0.4f  // eye_soft
            }
        }
    }

    /**
     * 触发说话动捕
     */
    fun setSpeaking(speaking: Boolean) {
        if (speaking) {
            currentAnimation = AvatarAnimation.TALKING
        } else {
            currentAnimation = AvatarAnimation.IDLE
        }
    }

    private fun resetBlendShapes() {
        for (i in blendShapeWeights.indices) {
            blendShapeWeights[i] = 0f
        }
    }

    private fun updateAnimation() {
        // 更新眨眼
        blinkTimer += 0.016f // ~60fps
        if (blinkTimer > 3.0f + Math.random() * 2.0f) {
            isBlinking = true
            blinkTimer = 0f
        }
        if (isBlinking) {
            // 眨眼动画（快速闭合再张开）
            if (blinkTimer > 0.1f) {
                isBlinking = false
                blinkTimer = 0f
            }
        }

        // 空闲呼吸动画
        if (currentAnimation == AvatarAnimation.IDLE) {
            val breath = Math.sin(System.currentTimeMillis() / 1000.0 * 2.0) * 0.02
            // 轻微上下浮动身体
        }

        // 动画过渡
        if (animationProgress < 1.0f) {
            animationProgress += 0.03f
            if (animationProgress > 1.0f) animationProgress = 1.0f
        }

        // 更新blend shape
        val animator = asset?.animator
        if (animator != null) {
            for (i in blendShapeWeights.indices) {
                // animator.setBlendShapeWeight(...)
            }
        }
    }

    fun onResume() {
        engine?.execute()
    }

    fun onPause() {
        // 暂停渲染
    }

    fun release() {
        try {
            asset?.release()
            assetLoader?.release()
            materialProvider?.release()
            engine?.destroy()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing engine", e)
        }
    }
}

enum class AvatarAnimation {
    IDLE,
    TALKING,
    HAPPY,
    SAD,
    ANGRY,
    TIRED,
    ANXIOUS,
    LOVING,
    SURPRISED,
    BLINK
}