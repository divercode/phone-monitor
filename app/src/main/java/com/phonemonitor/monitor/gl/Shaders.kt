package com.phonemonitor.monitor.gl

/**
 * GLSL 着色器源码。
 *
 * 管线：OES 纹理采样（采集卡画面）→ 3D LUT（2D 图集三线性插值）
 *      → 斑马纹 / 峰值对焦 / 构图网格 → 输出
 *
 * 无采集卡时由 uTestPattern = 1 走内置测试图案，方便先验证 LUT 管线。
 */
object Shaders {

    const val VERTEX = """
        attribute vec4 aPosition;
        attribute vec2 aTexCoord;
        varying vec2 vTexCoord;

        void main() {
            gl_Position = aPosition;
            vTexCoord = aTexCoord;
        }
    """

    const val FRAGMENT = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;

        varying vec2 vTexCoord;

        uniform samplerExternalOES uSource;
        uniform mat4 uTexMatrix;

        uniform sampler2D uLut;
        uniform float uLutEnabled;
        uniform float uLutSize;
        uniform float uLutStride;
        uniform float uLutDim;
        uniform float uLutTiles;

        uniform float uPeaking;
        uniform float uPeakingThreshold;
        uniform float uZebra;
        uniform float uZebraThreshold;
        uniform float uGrid;
        uniform float uSafeArea;
        uniform float uDesqueeze;
        uniform vec2 uTexel;
        uniform vec2 uVideoPixels;
        uniform vec2 uVideoOrigin;
        uniform float uTestPattern;
        uniform float uQuarterTurns;
        uniform float uMirror;

        // ---------- 工具函数 ----------
        float luma(vec3 c) {
            return dot(c, vec3(0.299, 0.587, 0.114));
        }

        vec3 testPattern(vec2 uv) {
            float bars = 8.0;
            float i = floor(uv.x * bars);
            vec3 c = vec3(0.05);
            if (i < 1.0) c = vec3(1.0);
            else if (i < 2.0) c = vec3(1.0, 1.0, 0.0);
            else if (i < 3.0) c = vec3(0.0, 1.0, 1.0);
            else if (i < 4.0) c = vec3(0.0, 1.0, 0.0);
            else if (i < 5.0) c = vec3(1.0, 0.0, 1.0);
            else if (i < 6.0) c = vec3(1.0, 0.0, 0.0);
            else if (i < 7.0) c = vec3(0.0, 0.0, 1.0);
            float ramp = mix(0.12, 1.0, 1.0 - uv.y);
            return c * ramp;
        }

        vec3 sampleScene(vec2 uv) {
            vec2 t = (uTexMatrix * vec4(uv, 0.0, 1.0)).xy;
            return texture2D(uSource, t).rgb;
        }

        vec3 scene(vec2 uv) {
            if (uTestPattern > 0.5) return testPattern(uv);
            return sampleScene(uv);
        }

        // ---------- 3D LUT（2D 图集 + 三线性插值） ----------
        vec3 sampleSlice(float z, vec2 xy, float n, float tiles) {
            float tx = mod(z, tiles);
            float ty = floor(z / tiles);
            vec2 origin = vec2(tx, ty) * uLutStride;
            vec2 texel = origin + 1.0 + xy;
            vec2 uv = (texel + 0.5) / uLutDim;
            return texture2D(uLut, uv).rgb;
        }

        vec3 applyLut(vec3 c) {
            float n = uLutSize;
            vec3 idx = clamp(c, 0.0, 1.0) * (n - 1.0);
            float z0 = floor(idx.b);
            float z1 = min(z0 + 1.0, n - 1.0);
            float fz = idx.b - z0;
            vec3 c0 = sampleSlice(z0, idx.rg, n, uLutTiles);
            vec3 c1 = sampleSlice(z1, idx.rg, n, uLutTiles);
            return mix(c0, c1, fz);
        }

        // ---------- 构图辅助 ----------
        float gridAlpha(vec2 p, float W, float H) {
            float lineW = 0.6;
            float a = 0.0;
            // 三分线
            a = max(a, 1.0 - smoothstep(lineW, lineW + 1.0, abs(p.x - W / 3.0)));
            a = max(a, 1.0 - smoothstep(lineW, lineW + 1.0, abs(p.x - 2.0 * W / 3.0)));
            a = max(a, 1.0 - smoothstep(lineW, lineW + 1.0, abs(p.y - H / 3.0)));
            a = max(a, 1.0 - smoothstep(lineW, lineW + 1.0, abs(p.y - 2.0 * H / 3.0)));
            // 中心十字
            float dCx = abs(p.x - W * 0.5);
            float dCy = abs(p.y - H * 0.5);
            a = max(a, (1.0 - smoothstep(lineW, lineW + 1.0, dCx)) * step(abs(p.y - H * 0.5), 36.0));
            a = max(a, (1.0 - smoothstep(lineW, lineW + 1.0, dCy)) * step(abs(p.x - W * 0.5), 36.0));
            return a;
        }

        float safeAreaAlpha(vec2 p, float W, float H) {
            vec2 margin = vec2(W, H) * 0.1;
            float l = 1.0 - smoothstep(0.6, 1.6, abs(p.x - margin.x));
            float r = 1.0 - smoothstep(0.6, 1.6, abs(p.x - (W - margin.x)));
            float t = 1.0 - smoothstep(0.6, 1.6, abs(p.y - margin.y));
            float b = 1.0 - smoothstep(0.6, 1.6, abs(p.y - (H - margin.y)));
            float inX = step(margin.x - 1.0, p.x) * step(p.x, W - margin.x + 1.0);
            float inY = step(margin.y - 1.0, p.y) * step(p.y, H - margin.y + 1.0);
            return max(max(l, r) * inY, max(t, b) * inX);
        }

        // ---------- 主函数 ----------
        void main() {
            vec2 uv = vTexCoord;

            // 系统相机预览需要按传感器方向旋转（UVC 采集卡为 0）
            if (uQuarterTurns > 0.5) {
                if (uQuarterTurns < 1.5) {
                    uv = vec2(uv.y, 1.0 - uv.x);
                } else if (uQuarterTurns < 2.5) {
                    uv = vec2(1.0 - uv.x, 1.0 - uv.y);
                } else {
                    uv = vec2(1.0 - uv.y, uv.x);
                }
            }

            // 系统相机预览左右镜像修正（UVC 为 0）
            if (uMirror > 0.5) {
                uv.x = 1.0 - uv.x;
            }

            if (uDesqueeze > 1.001) {
                uv.x = 0.5 + (uv.x - 0.5) / uDesqueeze;
            }

            vec3 c = scene(uv);
            vec3 graded = (uLutEnabled > 0.5) ? applyLut(c) : c;
            float l = luma(graded);

            // 斑马纹：显示亮度超过阈值的区域
            if (uZebra > 0.5 && l > uZebraThreshold) {
                float stripe = step(0.5, fract((gl_FragCoord.x + gl_FragCoord.y) / 14.0));
                graded = mix(graded, vec3(1.0, 0.15, 0.55), 0.45 + 0.3 * stripe);
            }

            // 峰值对焦：梯度幅值超过阈值的边缘染红
            if (uPeaking > 0.5) {
                float lx0 = luma(scene(uv - vec2(uTexel.x, 0.0)));
                float lx1 = luma(scene(uv + vec2(uTexel.x, 0.0)));
                float ly0 = luma(scene(uv - vec2(0.0, uTexel.y)));
                float ly1 = luma(scene(uv + vec2(0.0, uTexel.y)));
                float g = abs(lx1 - lx0) + abs(ly1 - ly0);
                if (g > uPeakingThreshold) {
                    graded = mix(graded, vec3(1.0, 0.12, 0.12), clamp(0.4 + g * 2.5, 0.4, 0.95));
                }
            }

            // 构图网格 / 安全框
            vec2 p = gl_FragCoord.xy - uVideoOrigin;
            float W = uVideoPixels.x;
            float H = uVideoPixels.y;
            float a = 0.0;
            if (uGrid > 0.5) {
                a = max(a, gridAlpha(p, W, H));
            }
            if (uSafeArea > 0.5) {
                a = max(a, safeAreaAlpha(p, W, H) * 0.9);
            }
            if (a > 0.001) {
                graded = mix(graded, vec3(0.88, 0.88, 0.88), a * 0.55);
            }

            gl_FragColor = vec4(graded, 1.0);
        }
    """
}
