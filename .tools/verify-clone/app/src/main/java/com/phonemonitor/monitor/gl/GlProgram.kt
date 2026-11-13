package com.phonemonitor.monitor.gl

import android.opengl.GLES20

/** GLSL 程序封装：编译、链接、uniform/attribute 定位 */
class GlProgram(vertexSource: String, fragmentSource: String) {

    val id: Int

    init {
        val vs = compile(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fs = compile(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        id = GLES20.glCreateProgram().also { program ->
            GLES20.glAttachShader(program, vs)
            GLES20.glAttachShader(program, fs)
            GLES20.glLinkProgram(program)
        }
        val status = IntArray(1)
        GLES20.glGetProgramiv(id, GLES20.GL_LINK_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) {
            "着色器链接失败: ${GLES20.glGetProgramInfoLog(id)}"
        }
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
    }

    fun use() = GLES20.glUseProgram(id)

    fun uniform(name: String): Int = GLES20.glGetUniformLocation(id, name)

    fun attrib(name: String): Int = GLES20.glGetAttribLocation(id, name)

    fun release() = GLES20.glDeleteProgram(id)

    private fun compile(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) {
            "着色器编译失败(${if (type == GLES20.GL_VERTEX_SHADER) "VS" else "FS"}): " +
                GLES20.glGetShaderInfoLog(shader)
        }
        return shader
    }
}
