package com.kooo.evcam.camera;

import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.Matrix;
import android.view.Surface;

import com.kooo.evcam.AppLog;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * EGL/OpenGL 渲染桥接类
 * 用于将 SurfaceTexture（来自 Camera）的内容渲染到 MediaCodec 的输入 Surface
 * 
 * 工作流程：
 * 1. Camera 输出到 SurfaceTexture（用于 TextureView 预览）
 * 2. 本类监听 SurfaceTexture 的 onFrameAvailable 回调
 * 3. 使用 OpenGL 将 SurfaceTexture 的内容渲染到 MediaCodec 的输入 Surface
 * 4. MediaCodec 编码后通过 MediaMuxer 写入文件
 */
public class EglSurfaceEncoder {
    private static final String TAG = "EglSurfaceEncoder";

    // Vertex shader - 简单的顶点变换
    private static final String VERTEX_SHADER =
            "uniform mat4 uMVPMatrix;\n" +
            "uniform mat4 uTexMatrix;\n" +
            "attribute vec4 aPosition;\n" +
            "attribute vec4 aTextureCoord;\n" +
            "varying vec2 vTextureCoord;\n" +
            "void main() {\n" +
            "    gl_Position = uMVPMatrix * aPosition;\n" +
            "    vTextureCoord = (uTexMatrix * aTextureCoord).xy;\n" +
            "}\n";

    // Fragment shader - 使用外部纹理（OES）采样
    private static final String FRAGMENT_SHADER =
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;\n" +
            "varying vec2 vTextureCoord;\n" +
            "uniform samplerExternalOES sTexture;\n" +
            "void main() {\n" +
            "    gl_FragColor = texture2D(sTexture, vTextureCoord);\n" +
            "}\n";

    // 顶点坐标（全屏四边形）
    private static final float[] VERTICES = {
            -1.0f, -1.0f,  // 左下
             1.0f, -1.0f,  // 右下
            -1.0f,  1.0f,  // 左上
             1.0f,  1.0f,  // 右上
    };

    // 纹理坐标
    private static final float[] TEXTURE_COORDS = {
            0.0f, 0.0f,  // 左下
            1.0f, 0.0f,  // 右下
            0.0f, 1.0f,  // 左上
            1.0f, 1.0f,  // 右上
    };

    private final String cameraId;
    private final int width;
    private final int height;

    // EGL 相关
    private EGLDisplay eglDisplay = EGL14.EGL_NO_DISPLAY;
    private EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
    private EGLSurface eglSurface = EGL14.EGL_NO_SURFACE;
    private EGLConfig eglConfig;

    // OpenGL 相关
    private int program;
    private int textureId;
    private FloatBuffer vertexBuffer;
    private FloatBuffer texCoordBuffer;

    // Shader 变量位置
    private int positionHandle;
    private int texCoordHandle;
    private int mvpMatrixHandle;
    private int texMatrixHandle;
    private int textureHandle;

    // 变换矩阵
    private final float[] mvpMatrix = new float[16];
    private final float[] texMatrix = new float[16];

    // 输入 SurfaceTexture（来自 Camera）
    private SurfaceTexture inputSurfaceTexture;

    // 状态
    private boolean isInitialized = false;
    private boolean isReleased = false;

    public EglSurfaceEncoder(String cameraId, int width, int height) {
        this.cameraId = cameraId;
        this.width = width;
        this.height = height;

        // 初始化 MVP 矩阵为单位矩阵
        Matrix.setIdentityM(mvpMatrix, 0);
    }

    /**
     * 初始化 EGL 和 OpenGL
     * @param outputSurface MediaCodec 的输入 Surface
     * @return 创建的 OES 纹理 ID（用于创建 SurfaceTexture 供 Camera 输出）
     */
    public int initialize(Surface outputSurface) {
        if (isInitialized) {
            AppLog.w(TAG, "Camera " + cameraId + " EglSurfaceEncoder already initialized");
            return textureId;
        }

        AppLog.d(TAG, "Camera " + cameraId + " Initializing EglSurfaceEncoder " + width + "x" + height);

        try {
            // 初始化 EGL
            initEgl(outputSurface);

            // 初始化 OpenGL
            initGl();

            isInitialized = true;
            AppLog.d(TAG, "Camera " + cameraId + " EglSurfaceEncoder initialized, textureId=" + textureId);

            return textureId;

        } catch (Exception e) {
            AppLog.e(TAG, "Camera " + cameraId + " Failed to initialize EglSurfaceEncoder", e);
            release();
            throw new RuntimeException("Failed to initialize EglSurfaceEncoder", e);
        }
    }

    /**
     * 设置输入 SurfaceTexture
     */
    public void setInputSurfaceTexture(SurfaceTexture surfaceTexture) {
        this.inputSurfaceTexture = surfaceTexture;
        AppLog.d(TAG, "Camera " + cameraId + " Input SurfaceTexture set");
    }

    /**
     * 渲染一帧到输出 Surface
     * 应该在 SurfaceTexture.onFrameAvailable 回调中调用
     * @param presentationTimeNs 帧的呈现时间（纳秒）
     */
    public void drawFrame(long presentationTimeNs) {
        if (!isInitialized || isReleased) {
            return;
        }

        if (inputSurfaceTexture == null) {
            AppLog.w(TAG, "Camera " + cameraId + " No input SurfaceTexture set");
            return;
        }

        try {
            // 首先绑定 EGL context（必须在 updateTexImage 之前）
            makeCurrent();

            // 更新纹理（需要在正确的 EGL context 中）
            inputSurfaceTexture.updateTexImage();
            inputSurfaceTexture.getTransformMatrix(texMatrix);

            // 设置视口
            GLES20.glViewport(0, 0, width, height);

            // 清除颜色缓冲
            GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

            // 使用着色器程序
            GLES20.glUseProgram(program);
            checkGlError("glUseProgram");

            // 绑定纹理
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);

            // 设置 uniform 变量
            GLES20.glUniformMatrix4fv(mvpMatrixHandle, 1, false, mvpMatrix, 0);
            GLES20.glUniformMatrix4fv(texMatrixHandle, 1, false, texMatrix, 0);
            GLES20.glUniform1i(textureHandle, 0);

            // 设置顶点属性
            GLES20.glEnableVertexAttribArray(positionHandle);
            GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer);

            GLES20.glEnableVertexAttribArray(texCoordHandle);
            GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer);

            // 绘制
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            checkGlError("glDrawArrays");

            // 设置呈现时间戳并交换缓冲区
            EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, presentationTimeNs);
            EGL14.eglSwapBuffers(eglDisplay, eglSurface);

            // 禁用顶点属性
            GLES20.glDisableVertexAttribArray(positionHandle);
            GLES20.glDisableVertexAttribArray(texCoordHandle);

        } catch (Exception e) {
            AppLog.e(TAG, "Camera " + cameraId + " Error drawing frame", e);
        }
    }

    /**
     * 更新输出 Surface（用于分段切换时）
     * 销毁旧的 EGL Surface，创建新的绑定到新的 MediaCodec 输入 Surface
     * @param newOutputSurface 新的 MediaCodec 输入 Surface
     */
    public void updateOutputSurface(Surface newOutputSurface) {
        if (!isInitialized || isReleased) {
            AppLog.w(TAG, "Camera " + cameraId + " Cannot update output surface: not initialized or released");
            return;
        }

        AppLog.d(TAG, "Camera " + cameraId + " Updating output surface");

        try {
            // 销毁旧的 EGL Surface
            if (eglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, eglContext);
                EGL14.eglDestroySurface(eglDisplay, eglSurface);
                eglSurface = EGL14.EGL_NO_SURFACE;
            }

            // 创建新的 EGL Surface
            int[] surfaceAttribList = {
                    EGL14.EGL_NONE
            };
            eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, newOutputSurface, surfaceAttribList, 0);
            if (eglSurface == EGL14.EGL_NO_SURFACE) {
                throw new RuntimeException("Unable to create new EGL window surface");
            }

            // 设置为当前上下文
            makeCurrent();

            AppLog.d(TAG, "Camera " + cameraId + " Output surface updated successfully");

        } catch (Exception e) {
            AppLog.e(TAG, "Camera " + cameraId + " Failed to update output surface", e);
            throw new RuntimeException("Failed to update output surface", e);
        }
    }

    /**
     * 仅消费帧而不渲染（用于非录制状态时保持 SurfaceTexture 正常工作）
     * 关键：必须调用 updateTexImage() 来消费帧，否则 SurfaceTexture 会保持 pending 状态，
     * 不再触发后续的 onFrameAvailable 回调
     */
    public void consumeFrame() {
        if (!isInitialized || isReleased) {
            return;
        }

        if (inputSurfaceTexture == null) {
            return;
        }

        try {
            // 绑定 EGL context（必须在 updateTexImage 之前）
            makeCurrent();
            // 只消费帧，不渲染
            inputSurfaceTexture.updateTexImage();
        } catch (Exception e) {
            // 非录制状态下的错误不需要记录
        }
    }

    /**
     * 释放资源
     */
    public void release() {
        if (isReleased) {
            return;
        }

        AppLog.d(TAG, "Camera " + cameraId + " Releasing EglSurfaceEncoder");

        isReleased = true;
        isInitialized = false;

        // 释放 OpenGL 资源
        if (program != 0) {
            GLES20.glDeleteProgram(program);
            program = 0;
        }

        if (textureId != 0) {
            int[] textures = {textureId};
            GLES20.glDeleteTextures(1, textures, 0);
            textureId = 0;
        }

        // 释放 EGL 资源
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);

            if (eglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, eglSurface);
                eglSurface = EGL14.EGL_NO_SURFACE;
            }

            if (eglContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(eglDisplay, eglContext);
                eglContext = EGL14.EGL_NO_CONTEXT;
            }

            EGL14.eglTerminate(eglDisplay);
            eglDisplay = EGL14.EGL_NO_DISPLAY;
        }

        inputSurfaceTexture = null;

        AppLog.d(TAG, "Camera " + cameraId + " EglSurfaceEncoder released");
    }

    /**
     * 获取纹理 ID
     */
    public int getTextureId() {
        return textureId;
    }

    /**
     * 检查是否已初始化
     */
    public boolean isInitialized() {
        return isInitialized && !isReleased;
    }

    // ===== 私有方法 =====

    /**
     * 初始化 EGL
     */
    private void initEgl(Surface outputSurface) {
        // 获取 EGL Display
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            throw new RuntimeException("Unable to get EGL14 display");
        }

        // 初始化 EGL
        int[] version = new int[2];
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            throw new RuntimeException("Unable to initialize EGL14");
        }
        AppLog.d(TAG, "Camera " + cameraId + " EGL initialized: " + version[0] + "." + version[1]);

        // 选择 EGL 配置
        int[] attribList = {
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT | EGL14.EGL_WINDOW_BIT,
                EGLExt.EGL_RECORDABLE_ANDROID, 1,  // 重要：支持录制
                EGL14.EGL_NONE
        };

        EGLConfig[] configs = new EGLConfig[1];
        int[] numConfigs = new int[1];
        if (!EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, 1, numConfigs, 0)) {
            throw new RuntimeException("Unable to find suitable EGL config");
        }
        eglConfig = configs[0];

        // 创建 EGL Context
        int[] contextAttribList = {
                EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                EGL14.EGL_NONE
        };
        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, contextAttribList, 0);
        if (eglContext == EGL14.EGL_NO_CONTEXT) {
            throw new RuntimeException("Unable to create EGL context");
        }

        // 创建 EGL Surface（绑定到 MediaCodec 的输入 Surface）
        int[] surfaceAttribList = {
                EGL14.EGL_NONE
        };
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, outputSurface, surfaceAttribList, 0);
        if (eglSurface == EGL14.EGL_NO_SURFACE) {
            throw new RuntimeException("Unable to create EGL window surface");
        }

        // 设置为当前上下文
        makeCurrent();

        AppLog.d(TAG, "Camera " + cameraId + " EGL setup complete");
    }

    /**
     * 初始化 OpenGL
     */
    private void initGl() {
        // 创建着色器程序
        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER);
        if (program == 0) {
            throw new RuntimeException("Unable to create shader program");
        }

        // 获取属性位置
        positionHandle = GLES20.glGetAttribLocation(program, "aPosition");
        texCoordHandle = GLES20.glGetAttribLocation(program, "aTextureCoord");
        mvpMatrixHandle = GLES20.glGetUniformLocation(program, "uMVPMatrix");
        texMatrixHandle = GLES20.glGetUniformLocation(program, "uTexMatrix");
        textureHandle = GLES20.glGetUniformLocation(program, "sTexture");

        // 创建 OES 纹理
        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        textureId = textures[0];

        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        // 创建顶点缓冲
        vertexBuffer = createFloatBuffer(VERTICES);
        texCoordBuffer = createFloatBuffer(TEXTURE_COORDS);

        AppLog.d(TAG, "Camera " + cameraId + " OpenGL setup complete, textureId=" + textureId);
    }

    /**
     * 设置为当前 EGL 上下文
     */
    private void makeCurrent() {
        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            throw new RuntimeException("eglMakeCurrent failed");
        }
    }

    /**
     * 创建着色器程序
     */
    private int createProgram(String vertexSource, String fragmentSource) {
        int vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource);
        if (vertexShader == 0) {
            return 0;
        }

        int fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource);
        if (fragmentShader == 0) {
            GLES20.glDeleteShader(vertexShader);
            return 0;
        }

        int program = GLES20.glCreateProgram();
        if (program == 0) {
            AppLog.e(TAG, "Could not create program");
            return 0;
        }

        GLES20.glAttachShader(program, vertexShader);
        GLES20.glAttachShader(program, fragmentShader);
        GLES20.glLinkProgram(program);

        int[] linkStatus = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0);
        if (linkStatus[0] != GLES20.GL_TRUE) {
            AppLog.e(TAG, "Could not link program: " + GLES20.glGetProgramInfoLog(program));
            GLES20.glDeleteProgram(program);
            return 0;
        }

        // 删除着色器（已链接到程序）
        GLES20.glDeleteShader(vertexShader);
        GLES20.glDeleteShader(fragmentShader);

        return program;
    }

    /**
     * 加载着色器
     */
    private int loadShader(int shaderType, String source) {
        int shader = GLES20.glCreateShader(shaderType);
        if (shader == 0) {
            AppLog.e(TAG, "Could not create shader type " + shaderType);
            return 0;
        }

        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);

        int[] compiled = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            AppLog.e(TAG, "Could not compile shader " + shaderType + ": " + GLES20.glGetShaderInfoLog(shader));
            GLES20.glDeleteShader(shader);
            return 0;
        }

        return shader;
    }

    /**
     * 创建 FloatBuffer
     */
    private FloatBuffer createFloatBuffer(float[] data) {
        ByteBuffer bb = ByteBuffer.allocateDirect(data.length * 4);
        bb.order(ByteOrder.nativeOrder());
        FloatBuffer fb = bb.asFloatBuffer();
        fb.put(data);
        fb.position(0);
        return fb;
    }

    /**
     * 检查 OpenGL 错误
     */
    private void checkGlError(String op) {
        int error;
        while ((error = GLES20.glGetError()) != GLES20.GL_NO_ERROR) {
            AppLog.e(TAG, "Camera " + cameraId + " " + op + ": glError " + error);
        }
    }
}
