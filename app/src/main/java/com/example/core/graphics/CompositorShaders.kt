package com.example.core.graphics

object CompositorShaders {

    const val LAYER_VERTEX_SHADER = """
        attribute vec4 aPosition;
        attribute vec2 aTexCoord;
        uniform mat4 uMvpMatrix;
        uniform mat4 uTexMatrix;
        varying vec2 vTexCoord;
        varying vec2 vCanvasUv;
        void main() {
            gl_Position = uMvpMatrix * aPosition;
            vCanvasUv = gl_Position.xy * 0.5 + 0.5;
            vTexCoord = (uTexMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
        }
    """

    const val BLIT_VERTEX_SHADER = """
        attribute vec4 aPosition;
        attribute vec2 aTexCoord;
        varying vec2 vTexCoord;
        void main() {
            gl_Position = aPosition;
            vTexCoord = aTexCoord;
        }
    """

    private const val LAYER_FRAGMENT_BODY = """
        precision mediump float;
        varying vec2 vTexCoord;
        varying vec2 vCanvasUv;

        uniform mat4 uColorMatrix;
        uniform vec4 uColorOffset;
        uniform float uOpacity;
        uniform float uVignette;

        uniform int uShaderType;
        uniform float uShaderIntensity;
        uniform float uBlurAmount;
        uniform float uSharpenAmount;
        uniform float uTimeSec;
        uniform vec2 uTexelSize;

        uniform int uChromaEnabled;
        uniform vec3 uChromaRgb;
        uniform float uChromaSimilarity;
        uniform float uChromaSmoothness;
        uniform float uChromaSpill;

        uniform int uLutEnabled;
        uniform float uLutIntensity;
        uniform sampler2D uLutTexture;

        uniform int uMaskShape;
        uniform vec2 uMaskCenter;
        uniform vec2 uMaskSize;
        uniform float uMaskRotationRad;
        uniform float uMaskFeather;
        uniform float uMaskCornerRadius;
        uniform int uMaskInverted;

        float rand2d(vec2 co) {
            return fract(sin(dot(co.xy, vec2(12.9898, 78.233))) * 43758.5453);
        }

        vec2 rgbToCbCr(vec3 rgb) {
            float cb = -0.168736 * rgb.r - 0.331264 * rgb.g + 0.5 * rgb.b;
            float cr = 0.5 * rgb.r - 0.418688 * rgb.g - 0.081312 * rgb.b;
            return vec2(cb, cr);
        }

        vec4 applyChromaKey(vec4 col) {
            if (uChromaEnabled == 0 || col.a <= 0.001) return col;
            vec2 keyCbCr = rgbToCbCr(uChromaRgb);
            vec2 pxCbCr = rgbToCbCr(col.rgb);
            float dist = distance(pxCbCr, keyCbCr);
            float edge0 = clamp(uChromaSimilarity, 0.02, 0.90);
            float edge1 = clamp(edge0 + max(uChromaSmoothness, 0.01), edge0 + 0.01, 1.0);
            float maskA = smoothstep(edge0, edge1, dist);
            vec3 outRgb = col.rgb;
            if (uChromaSpill > 0.01 && dist < edge1 * 1.45) {
                float spillWeight = (1.0 - smoothstep(edge0, edge1 * 1.45, dist)) * uChromaSpill;
                float luma = dot(outRgb, vec3(0.2126, 0.7152, 0.0722));
                outRgb = mix(outRgb, vec3(luma), clamp(spillWeight, 0.0, 0.92));
            }
            return vec4(outRgb, col.a * maskA);
        }

        vec3 applyLut2dStrip(vec3 color) {
            if (uLutEnabled == 0 || uLutIntensity <= 0.005) return color;
            vec3 c = clamp(color, 0.0, 1.0);
            float blue = c.b * 15.0;
            float slice0 = floor(blue);
            float slice1 = min(15.0, slice0 + 1.0);
            float bFrac = blue - slice0;

            float rCoord = c.r * 15.0;
            float gCoord = c.g * 15.0;
            float v = (gCoord + 0.5) / 16.0;

            float u0 = (slice0 * 16.0 + rCoord + 0.5) / 256.0;
            float u1 = (slice1 * 16.0 + rCoord + 0.5) / 256.0;

            vec3 lut0 = texture2D(uLutTexture, vec2(u0, v)).rgb;
            vec3 lut1 = texture2D(uLutTexture, vec2(u1, v)).rgb;
            vec3 mapped = mix(lut0, lut1, bFrac);
            return mix(color, mapped, clamp(uLutIntensity, 0.0, 1.0));
        }

        float evalMaskAlpha(vec2 uv) {
            if (uMaskShape == 0) return 1.0;
            vec2 d = uv - uMaskCenter;
            float c = cos(-uMaskRotationRad);
            float s = sin(-uMaskRotationRad);
            vec2 r = vec2(d.x * c - d.y * s, d.x * s + d.y * c);
            vec2 halfSize = max(uMaskSize * 0.5, vec2(0.01));
            float feather = clamp(uMaskFeather, 0.002, 0.5);
            float dist = -1.0;
            if (uMaskShape == 1) {
                vec2 q = abs(r) - (halfSize - vec2(uMaskCornerRadius));
                dist = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - uMaskCornerRadius;
            } else if (uMaskShape == 2) {
                vec2 n = r / halfSize;
                dist = (length(n) - 1.0) * min(halfSize.x, halfSize.y);
            } else if (uMaskShape == 3) {
                dist = r.x;
            }
            float a = 1.0 - smoothstep(-feather, feather, dist);
            return (uMaskInverted == 1) ? (1.0 - a) : a;
        }

        vec4 sampleWithShader(vec2 uv) {
            if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) {
                return vec4(0.0);
            }
            vec4 baseColor = sampleLayerTexture(uv);
            if (uShaderType == 0 || uShaderIntensity <= 0.005) {
                return baseColor;
            }
            float amt = uShaderIntensity;
            if (uShaderType == 1) {
                // Chromatic Aberration (radial)
                vec2 dir = (uv - vec2(0.5)) * 0.025 * amt;
                float r = sampleLayerTexture(clamp(uv + dir, 0.0, 1.0)).r;
                float b = sampleLayerTexture(clamp(uv - dir, 0.0, 1.0)).b;
                return vec4(r, baseColor.g, b, baseColor.a);
            } else if (uShaderType == 2) {
                // RGB Split (horizontal)
                float shift = 0.022 * amt;
                float r = sampleLayerTexture(clamp(uv + vec2(shift, 0.0), 0.0, 1.0)).r;
                float b = sampleLayerTexture(clamp(uv - vec2(shift, 0.0), 0.0, 1.0)).b;
                return vec4(r, baseColor.g, b, baseColor.a);
            } else if (uShaderType == 3) {
                // Film Grain
                float noise = (rand2d(uv * 320.0 + vec2(uTimeSec * 17.0, uTimeSec * 31.0)) - 0.5) * 0.28 * amt;
                return vec4(clamp(baseColor.rgb + noise, 0.0, 1.0), baseColor.a);
            } else if (uShaderType == 4) {
                // Bloom Glow
                vec2 off = uTexelSize * (4.0 + 10.0 * amt);
                vec3 blur = (
                    sampleLayerTexture(clamp(uv + vec2(off.x, 0.0), 0.0, 1.0)).rgb +
                    sampleLayerTexture(clamp(uv - vec2(off.x, 0.0), 0.0, 1.0)).rgb +
                    sampleLayerTexture(clamp(uv + vec2(0.0, off.y), 0.0, 1.0)).rgb +
                    sampleLayerTexture(clamp(uv - vec2(0.0, off.y), 0.0, 1.0)).rgb
                ) * 0.25;
                vec3 bright = max(blur - vec3(0.45), vec3(0.0)) * (1.8 * amt);
                return vec4(clamp(baseColor.rgb + bright, 0.0, 1.0), baseColor.a);
            } else if (uShaderType == 5) {
                // VHS Scanlines
                float wave = sin(uv.y * 45.0 + uTimeSec * 6.0) * 0.004 * amt;
                vec2 suv = clamp(uv + vec2(wave, 0.0), 0.0, 1.0);
                vec4 c = sampleLayerTexture(suv);
                float scan = 1.0 - (0.24 * amt) * (0.5 + 0.5 * sin(uv.y * 280.0));
                return vec4(c.rgb * scan, c.a);
            } else if (uShaderType == 6) {
                // Pixelate
                float grid = mix(180.0, 22.0, amt);
                vec2 puv = floor(uv * grid) / grid;
                return sampleLayerTexture(clamp(puv, 0.0, 1.0));
            } else if (uShaderType == 7) {
                // Sharpen Edge
                vec2 t = uTexelSize * 1.5;
                vec3 neighbors = (
                    sampleLayerTexture(clamp(uv + vec2(t.x, 0.0), 0.0, 1.0)).rgb +
                    sampleLayerTexture(clamp(uv - vec2(t.x, 0.0), 0.0, 1.0)).rgb +
                    sampleLayerTexture(clamp(uv + vec2(0.0, t.y), 0.0, 1.0)).rgb +
                    sampleLayerTexture(clamp(uv - vec2(0.0, t.y), 0.0, 1.0)).rgb
                ) * 0.25;
                vec3 sharp = baseColor.rgb + (baseColor.rgb - neighbors) * (1.8 * amt);
                return vec4(clamp(sharp, 0.0, 1.0), baseColor.a);
            } else if (uShaderType == 8) {
                // Radial Zoom Blur
                vec2 dir = (vec2(0.5) - uv) * 0.065 * amt;
                vec4 sum = baseColor;
                sum += sampleLayerTexture(clamp(uv + dir * 0.25, 0.0, 1.0));
                sum += sampleLayerTexture(clamp(uv + dir * 0.50, 0.0, 1.0));
                sum += sampleLayerTexture(clamp(uv + dir * 0.75, 0.0, 1.0));
                sum += sampleLayerTexture(clamp(uv + dir * 1.00, 0.0, 1.0));
                return sum * 0.2;
            } else if (uShaderType == 9) {
                // Fisheye Lens Warp
                vec2 d = uv - vec2(0.5);
                float r2 = dot(d, d);
                float factor = mix(1.0, 0.65 + 1.45 * r2, amt);
                vec2 fuv = vec2(0.5) + d * factor;
                if (fuv.x < 0.0 || fuv.x > 1.0 || fuv.y < 0.0 || fuv.y > 1.0) {
                    return vec4(0.0, 0.0, 0.0, baseColor.a);
                }
                return sampleLayerTexture(fuv);
            } else if (uShaderType == 10) {
                // Film Halation
                vec2 off = uTexelSize * (5.0 + 12.0 * amt);
                vec3 s1 = sampleLayerTexture(clamp(uv + vec2(off.x, 0.0), 0.0, 1.0)).rgb;
                vec3 s2 = sampleLayerTexture(clamp(uv - vec2(off.x, 0.0), 0.0, 1.0)).rgb;
                vec3 s3 = sampleLayerTexture(clamp(uv + vec2(0.0, off.y), 0.0, 1.0)).rgb;
                vec3 s4 = sampleLayerTexture(clamp(uv - vec2(0.0, off.y), 0.0, 1.0)).rgb;
                vec3 avg = (s1 + s2 + s3 + s4) * 0.25;
                float hi = max(dot(avg, vec3(0.299, 0.587, 0.114)) - 0.58, 0.0);
                vec3 halo = vec3(1.0, 0.28, 0.10) * hi * (2.6 * amt);
                return vec4(clamp(baseColor.rgb + halo, 0.0, 1.0), baseColor.a);
            } else if (uShaderType == 11) {
                // Neon Edge
                vec2 t = uTexelSize * 1.5;
                vec3 n = sampleLayerTexture(clamp(uv + vec2(0.0, t.y), 0.0, 1.0)).rgb;
                vec3 s = sampleLayerTexture(clamp(uv - vec2(0.0, t.y), 0.0, 1.0)).rgb;
                vec3 e = sampleLayerTexture(clamp(uv + vec2(t.x, 0.0), 0.0, 1.0)).rgb;
                vec3 w = sampleLayerTexture(clamp(uv - vec2(t.x, 0.0), 0.0, 1.0)).rgb;
                float edge = clamp(length(e - w) + length(n - s), 0.0, 1.0);
                vec3 neon = mix(vec3(0.08, 0.85, 0.98), vec3(0.95, 0.22, 0.82), uv.x) * pow(edge, 0.85) * 2.2;
                vec3 finalRgb = mix(baseColor.rgb, baseColor.rgb * 0.25 + neon, amt);
                return vec4(clamp(finalRgb, 0.0, 1.0), baseColor.a);
            }
            return baseColor;
        }

        vec4 applyBlurAndSharpen(vec4 color, vec2 uv) {
            vec4 outCol = color;
            if (uBlurAmount > 0.05) {
                vec2 stepUv = uTexelSize * clamp(uBlurAmount * 0.9, 0.5, 14.0);
                vec4 sum = outCol * 0.24;
                sum += sampleLayerTexture(clamp(uv + vec2(stepUv.x, 0.0), 0.0, 1.0)) * 0.12;
                sum += sampleLayerTexture(clamp(uv - vec2(stepUv.x, 0.0), 0.0, 1.0)) * 0.12;
                sum += sampleLayerTexture(clamp(uv + vec2(0.0, stepUv.y), 0.0, 1.0)) * 0.12;
                sum += sampleLayerTexture(clamp(uv - vec2(0.0, stepUv.y), 0.0, 1.0)) * 0.12;
                sum += sampleLayerTexture(clamp(uv + vec2(stepUv.x, stepUv.y), 0.0, 1.0)) * 0.07;
                sum += sampleLayerTexture(clamp(uv + vec2(-stepUv.x, stepUv.y), 0.0, 1.0)) * 0.07;
                sum += sampleLayerTexture(clamp(uv + vec2(stepUv.x, -stepUv.y), 0.0, 1.0)) * 0.07;
                sum += sampleLayerTexture(clamp(uv + vec2(-stepUv.x, -stepUv.y), 0.0, 1.0)) * 0.07;
                float blendBlur = clamp(uBlurAmount / 2.5, 0.0, 1.0);
                outCol = mix(outCol, sum, blendBlur);
            }
            if (uSharpenAmount > 0.01) {
                vec2 t = uTexelSize * 1.35;
                vec3 neighbors = (
                    sampleLayerTexture(clamp(uv + vec2(t.x, 0.0), 0.0, 1.0)).rgb +
                    sampleLayerTexture(clamp(uv - vec2(t.x, 0.0), 0.0, 1.0)).rgb +
                    sampleLayerTexture(clamp(uv + vec2(0.0, t.y), 0.0, 1.0)).rgb +
                    sampleLayerTexture(clamp(uv - vec2(0.0, t.y), 0.0, 1.0)).rgb
                ) * 0.25;
                vec3 sharpened = outCol.rgb + (outCol.rgb - neighbors) * (1.2 * clamp(uSharpenAmount, 0.0, 1.5));
                outCol = vec4(clamp(sharpened, 0.0, 1.0), outCol.a);
            }
            return outCol;
        }

        void main() {
            vec4 sampled = applyBlurAndSharpen(sampleWithShader(vTexCoord), vTexCoord);
            vec4 tex = applyChromaKey(sampled);
            vec4 graded = clamp(uColorMatrix * vec4(tex.rgb, 1.0) + uColorOffset, 0.0, 1.0);
            graded.rgb = applyLut2dStrip(graded.rgb);
            if (uVignette > 0.01) {
                float d = distance(vCanvasUv, vec2(0.5));
                float vig = smoothstep(0.32, 0.85, d) * uVignette * 0.82;
                graded.rgb *= (1.0 - vig);
            }
            float maskA = evalMaskAlpha(vCanvasUv);
            float outA = tex.a * uOpacity * maskA;
            gl_FragColor = vec4(graded.rgb, clamp(outA, 0.0, 1.0));
        }
    """

    const val LAYER_FRAGMENT_SHADER_2D = """
        precision mediump float;
        uniform sampler2D uTexture;
        vec4 sampleLayerTexture(vec2 uv) {
            return texture2D(uTexture, uv);
        }
    """ + LAYER_FRAGMENT_BODY

    const val LAYER_FRAGMENT_SHADER_OES = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        uniform samplerExternalOES uTexture;
        vec4 sampleLayerTexture(vec2 uv) {
            return texture2D(uTexture, uv);
        }
    """ + LAYER_FRAGMENT_BODY

    const val BLEND_FRAGMENT_SHADER = """
        precision mediump float;
        varying vec2 vTexCoord;
        uniform sampler2D uBaseTex;
        uniform sampler2D uTopTex;
        uniform int uBlendMode;

        vec3 applyBlend(vec3 base, vec3 top, int mode) {
            if (mode == 1) {
                // Add
                return min(base + top, vec3(1.0));
            } else if (mode == 2) {
                // Multiply
                return base * top;
            } else if (mode == 3) {
                // Screen
                return 1.0 - (1.0 - base) * (1.0 - top);
            } else if (mode == 4) {
                // Overlay
                vec3 lo = 2.0 * base * top;
                vec3 hi = 1.0 - 2.0 * (1.0 - base) * (1.0 - top);
                return mix(lo, hi, step(vec3(0.5), base));
            } else if (mode == 5) {
                // Darken
                return min(base, top);
            } else if (mode == 6) {
                // Lighten
                return max(base, top);
            }
            return top;
        }

        void main() {
            vec4 base = texture2D(uBaseTex, vTexCoord);
            vec4 top = texture2D(uTopTex, vTexCoord);
            vec3 blended = applyBlend(base.rgb, top.rgb, uBlendMode);
            vec3 outRgb = mix(base.rgb, blended, top.a);
            float outA = clamp(base.a + top.a * (1.0 - base.a), 0.0, 1.0);
            gl_FragColor = vec4(outRgb, outA);
        }
    """

    const val PASSTHROUGH_FRAGMENT_SHADER = """
        precision mediump float;
        varying vec2 vTexCoord;
        uniform sampler2D uTexture;
        void main() {
            gl_FragColor = texture2D(uTexture, vTexCoord);
        }
    """

    /**
     * GPU Invert Lyric fragment shader.
     * Samples the composited video framebuffer via vCanvasUv and the transparent glyph mask via
     * vTexCoord, inverting underlying RGB pixels strictly proportionally to mask coverage and uInvertStrength.
     */
    const val INVERT_TEXT_FRAGMENT_SHADER = """
        precision mediump float;
        varying vec2 vTexCoord;
        varying vec2 vCanvasUv;

        uniform sampler2D uBaseTex;
        uniform sampler2D uMaskTex;
        uniform float uInvertStrength;
        uniform float uEdgeSoftness;
        uniform float uOpacity;
        uniform vec2 uMaskTexelSize;

        void main() {
            vec4 base = texture2D(uBaseTex, clamp(vCanvasUv, 0.0, 1.0));
            if (vTexCoord.x < 0.0 || vTexCoord.x > 1.0 || vTexCoord.y < 0.0 || vTexCoord.y > 1.0) {
                gl_FragColor = base;
                return;
            }
            float centerA = texture2D(uMaskTex, vTexCoord).a;
            float maskA = centerA;
            if (uEdgeSoftness > 0.005) {
                vec2 stepUv = uMaskTexelSize * (1.0 + uEdgeSoftness * 8.0);
                float n = texture2D(uMaskTex, clamp(vTexCoord + vec2(0.0, stepUv.y), 0.0, 1.0)).a;
                float s = texture2D(uMaskTex, clamp(vTexCoord - vec2(0.0, stepUv.y), 0.0, 1.0)).a;
                float e = texture2D(uMaskTex, clamp(vTexCoord + vec2(stepUv.x, 0.0), 0.0, 1.0)).a;
                float w = texture2D(uMaskTex, clamp(vTexCoord - vec2(stepUv.x, 0.0), 0.0, 1.0)).a;
                float softBlur = centerA * 0.40 + (n + s + e + w) * 0.15;
                maskA = mix(centerA, softBlur, clamp(uEdgeSoftness * 2.0, 0.0, 1.0));
            }
            float amount = clamp(maskA * clamp(uInvertStrength, 0.0, 1.0) * clamp(uOpacity, 0.0, 1.0), 0.0, 1.0);
            vec3 inverted = vec3(1.0) - base.rgb;
            vec3 outRgb = mix(base.rgb, inverted, amount);
            gl_FragColor = vec4(outRgb, base.a);
        }
    """
}
