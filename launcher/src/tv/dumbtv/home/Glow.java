package tv.dumbtv.home;

import android.content.Context;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.opengl.GLES11Ext;
import android.view.Surface;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.os.SystemClock;
import android.util.Log;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.Random;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * react_style=glowroom: six particle rooms (dune glide, tunnel rush, planet, light rain, galaxy, beam hall)
 * drawn as additive GL points whose positions all come from one vertex shader, so the CPU only uploads a
 * handful of uniforms per frame. A song plays 3-5 of them, switching on section changes (never sooner than
 * 20 s) with a 1 s crossfade; the same room or palette never plays twice in a row.
 * Comfort: colours are capped at saturation 0.55 / value 0.8, each point is faint, kick pulses are smoothed
 * and the drop bloom happens at most once every 8 s and only swells the points, never the whole screen.
 */
final class Glow implements GLSurfaceView.Renderer {
    private static final String TAG = "dumbglow";
    static final String[] NAMES = {"dunes", "tunnel", "planet", "rain", "galaxy", "beam"};
    private static final int MAXN = 16000;
    private static final float TAN_H = 0.62f;
    private static final float MIN_DWELL = 20f, MAX_DWELL = 45f, FADE = 1.5f;
    private static final int[] SCENE_HARM = {0, 1, 0, 1, 2, 1}; // dunes, tunnel, planet, rain, galaxy, beam

    private static final String VS =
        "attribute vec4 aS;\n"
        + "uniform float uT, uDist, uKick, uBass, uMid, uHigh, uEner, uAspect, uScene, uAlpha, uSize, uBloom, uPx, uDrift, uSpin, uPunch, uKickB, uBurst, uMrot;\n"
        + "uniform vec2 uVar, uOff;\n"
        + "uniform vec4 uSig;\n"
        + "uniform vec4 uAge, uRipX, uRipZ;\n"
        + "uniform vec3 uC0, uC1, uC2;\n"
        + "varying vec3 vCol;\nvarying float vA;\n"
        + "float h1(float x) { return fract(sin(x * 127.1) * 43758.5453); }\n"
        + "float rip(float rx, float rz, float age, float x, float z) {\n"
        + "  if (age < 0.0 || age > 2.0) return 0.0;\n"
        + "  float d = length(vec2(x - rx, z - rz)) - age * 4.2;\n"
        + "  return exp(-d * d * 1.6) * (1.0 - age * 0.5);\n"
        + "}\n"
        + "float shock(float d, float age, float sp, float w) {\n"
        + "  if (age < 0.0 || age > 1.4) return 0.0;\n"
        + "  float x = (d - age * sp) / w;\n"
        + "  return exp(-x * x) * (1.0 - age / 1.4);\n"
        + "}\n"
        + "float shocks(float d, float sp, float w) {\n"
        + "  return shock(d, uAge.x, sp, w) + shock(d, uAge.y, sp, w) + shock(d, uAge.z, sp, w) + shock(d, uAge.w, sp, w);\n"
        + "}\n"
        + "void main() {\n"
        + "  vec3 p = vec3(0.0, 0.0, 1.0);\n"
        + "  float b = 0.0, sz = 0.1, hue = 0.5, acc = 0.0, gold = 0.0;\n"
        + "  float cat = aS.x;\n"
        + "  if (uScene < 0.5) {\n" // dunes
        + "    float tw = pow(0.5 + 0.5 * sin(uT * 3.0 + aS.w * 60.0), 4.0);\n"
        + "    if (cat < 0.80) {\n"
        + "      float x = (aS.y - 0.5) * 24.0;\n"
        + "      float z = mod(aS.z * 22.0 - uDist, 22.0) + 1.0;\n"
        + "      float amp = (0.55 + uBass * 1.3) * (0.8 + uVar.x * 0.6);\n"
        + "      float hg = amp * (0.7 * sin(x * 0.33 + z * 0.21 + uT * 0.15) + 0.5 * sin(x * 0.17 - z * 0.37 + uT * 0.1) + 0.3 * sin(z * 0.9 + x * 0.5));\n"
        + "      float wv = shocks(length(vec2(x, z - 1.0)), 9.0, 1.3);\n"
        + "      hg += wv * (0.45 + uBass);\n"
        + "      p = vec3(x, hg - 1.7, z);\n"
        + "      float sp = step(0.55, aS.w);\n"
        + "      b = (0.11 + max(hg, 0.0) * 0.14 + wv * 0.22 + (0.34 + uHigh * 1.4) * tw * sp) * smoothstep(23.0, 15.0, z) * smoothstep(1.0, 3.0, z);\n"
        + "      sz = 0.10 + tw * sp * (0.10 + uHigh * 0.2);\n"
        + "      hue = 0.15 + 0.55 * z / 26.0; acc = sp * tw;\n"
        + "    } else if (cat < 0.95) {\n"
        + "      float az = (aS.y - 0.5) * 2.8; float el = 0.08 + aS.z * 1.2;\n"
        + "      p = 45.0 * vec3(sin(az) * cos(el), sin(el), cos(az) * cos(el));\n"
        + "      b = 0.12 + 0.35 * tw; sz = 0.30; hue = 0.8; acc = tw;\n"
        + "    } else {\n"
        + "      p = vec3((aS.y - 0.5) * 80.0, -1.7 + aS.z * 6.0, 30.0 + aS.w * 20.0);\n"
        + "      b = 0.0; sz = 0.1; hue = 0.9;\n"
        + "    }\n"
        + "    b *= 1.0 + uKickB * 0.25; sz *= 1.0 + uKick * 0.3;\n"
        + "  } else if (uScene < 1.5) {\n" // tunnel
        + "    if (cat < 0.76) {\n"
        + "      float ang = aS.y * 6.2832 + uSpin * 0.06;\n"
        + "      float rad = (1.4 + aS.z * 2.4 + 0.25 * sin(aS.w * 30.0 + uT * 0.5)) * (1.0 + uBass * 0.18 + uKick * 0.12);\n"
        + "      float k = floor(aS.w * 3.0);\n"
        + "      float z = mod(aS.z * 37.0 + aS.y * 11.0 - uDist, 34.0) + 0.5;\n"
        + "      ang += (uVar.x - 0.5) * z * 0.09;\n"
        + "      p = vec3(cos(ang) * rad, sin(ang) * rad, z + k * 0.4 * uSig.y * (1.0 + uKick));\n"
        + "      b = (0.42 - 0.10 * k) * smoothstep(34.0, 22.0, z) * smoothstep(0.4, 2.0, z);\n"
        + "      float zr = 0.0;\n"
        + "      sz = 0.13 - 0.025 * k; hue = ang / 6.2832 * 0.5 + 0.2 + 0.4 * z / 34.0;\n"
        + "      acc = step(0.93, aS.w);\n"
        + "    } else if (cat < 0.94) {\n"
        + "      float ri = floor(aS.w * 4.0);\n"
        + "      float age = ri < 0.5 ? uAge.x : (ri < 1.5 ? uAge.y : (ri < 2.5 ? uAge.z : uAge.w));\n"
        + "      float ang = aS.y * 6.2832;\n"
        + "      p = vec3(cos(ang) * 3.2, sin(ang) * 3.2, 30.0 - age * 22.0);\n"
        + "      b = (age >= 0.0 && age < 1.4) ? 0.7 * (1.0 - age / 1.4) : 0.0;\n"
        + "      sz = 0.2; hue = 0.9; acc = 1.0;\n"
        + "    } else {\n"
        + "      float s = aS.y * 6.2832; float r = aS.z * 2.4 * (1.0 + uKick * 0.4);\n"
        + "      p = vec3(cos(s) * r, sin(s) * r, 34.0 + aS.w * 4.0);\n"
        + "      b = 0.16 + uKick * 0.12; sz = 0.45; hue = 0.85; acc = 0.6;\n"
        + "    }\n"
        + "  } else if (uScene < 2.5) {\n" // planet
        + "    vec3 C = vec3(0.0, 0.0, 6.5 + 0.4 * sin(uT * 0.2));\n"
        + "    float R = 1.6 * (1.0 + uBass * 0.25 + uKick * 0.07);\n"
        + "    float tl = 0.2 + uVar.x * 0.55; float ct = cos(tl), st = sin(tl);\n"
        + "    float occl = 0.0;\n"
        + "    if (cat < 0.62) {\n"
        + "      float u = aS.y * 6.2832 + uSpin * 0.3; float v = aS.z * 2.0 - 1.0; float sr = sqrt(1.0 - v * v);\n"
        + "      vec3 n = vec3(cos(u) * sr, v, sin(u) * sr);\n"
        + "      float wv = shocks((v + 1.0) * 2.0, 5.0, 0.6);\n"
        + "      n = vec3(n.x * ct - n.y * st, n.x * st + n.y * ct, n.z);\n"
        + "      p = C + n * R;\n"
        + "      float face = -n.z; float rim = pow(1.0 - abs(face), 2.5);\n"
        + "      float bands = 0.5 + 0.5 * sin(v * 9.0 + sin(u * 3.0) * 0.3);\n"
        + "      b = (0.26 * (0.6 + 0.8 * bands) + wv * 0.28) * smoothstep(-0.08, 0.12, face) + rim * 0.42 * smoothstep(-0.25, 0.0, face);\n"
        + "      sz = 0.075; hue = bands * 0.8 + 0.1; acc = rim;\n"
        + "    } else if (cat < 0.90) {\n"
        + "      float a = aS.y * 6.2832 + uT * 0.08 + uSpin * 0.05; float rr = (2.3 + aS.z * 1.3) * (1.0 + uKick * 0.05);\n"
        + "      vec3 q = vec3(cos(a) * rr, 0.0, sin(a) * rr);\n"
        + "      q = vec3(q.x, -q.z * sin(0.3), q.z * cos(0.3));\n"
        + "      q = vec3(q.x * ct - q.y * st, q.x * st + q.y * ct, q.z);\n"
        + "      p = C + q;\n"
        + "      b = (0.15 + uMid * 0.2) * (0.6 + 0.6 * sin(rr * 18.0)); sz = 0.055; hue = 0.55 + 0.3 * sin(rr * 5.0);\n"
        + "      occl = 1.0;\n"
        + "    } else if (cat < 0.97) {\n"
        + "      float mk = floor(aS.w * 2.0); float an = uT * (0.3 + 0.2 * mk) + mk * 2.7;\n"
        + "      vec3 mc = C + vec3(cos(an) * (4.2 + mk * 1.3), sin(an * 0.7) * 0.9 * (1.0 - mk * 0.5) + 0.3, sin(an) * (2.5 + mk * 0.8));\n"
        + "      float mr = 0.32 + 0.1 * mk;\n"
        + "      float v = aS.z * 2.0 - 1.0; float u = aS.y * 6.2832; float sr = sqrt(1.0 - v * v);\n"
        + "      vec3 n = vec3(cos(u) * sr, v, sin(u) * sr);\n"
        + "      p = mc + n * mr;\n"
        + "      b = n.z < 0.1 ? 0.30 : 0.0; sz = 0.07; hue = 0.3 + 0.4 * mk; acc = 0.3;\n"
        + "      occl = 1.0;\n"
        + "    } else {\n"
        + "      float az = (aS.y - 0.5) * 2.8; float el = (aS.z - 0.5) * 1.8;\n"
        + "      p = 48.0 * vec3(sin(az) * cos(el), sin(el), cos(az) * cos(el));\n"
        + "      b = 0.10 + 0.30 * pow(0.5 + 0.5 * sin(uT * 2.0 + aS.w * 50.0), 4.0); sz = 0.3; hue = 0.8; acc = 1.0; occl = 1.0;\n"
        + "    }\n"
        + "    if (occl > 0.5 && p.z > C.z) {\n"
        + "      if (length(p.xy / p.z - C.xy / C.z) < R / sqrt(C.z * C.z - R * R) * 1.01) b = 0.0;\n"
        + "    }\n"
        + "    b *= 1.0 + uKickB * 0.2;\n"
        + "  } else if (uScene < 3.5) {\n" // light rain
        + "    if (cat < 0.62) {\n"
        + "      float did = floor(aS.y * 1200.0); float k = floor(fract(aS.y * 1200.0) * 5.0);\n"
        + "      float layer = floor(h1(did + 3.1) * 3.0);\n"
        + "      float sp = 1.0 + layer * 0.75;\n"
        + "      float zc = 12.0 - layer * 3.6 + sin(uT * 0.3 + layer) * 0.4;\n"
        + "      float x = (h1(did * 1.7 + 0.3) - 0.5) * (zc * 1.8 + 4.0);\n"
        + "      float H = 9.0;\n"
        + "      float y = 5.0 - mod(h1(did + 9.1) * H + uDist * 1.8 * sp + layer * 1.3, H) + k * (0.45 + 0.25 * layer) * uSig.y * (1.0 + uKick * 1.2);\n"
        + "      x += y * (uVar.x - 0.5) * 0.5;\n"
        + "      p = vec3(x, y, zc);\n"
        + "      float head = k < 0.5 ? 1.0 : 0.0;\n"
        + "      b = (0.14 + head * 0.34 + (4.0 - k) * 0.05) * (0.8 + layer * 0.15) * (0.75 + uHigh * 0.5) * (1.0 + uKickB * 0.2);\n"
        + "      sz = (0.045 + head * 0.035) * (1.0 + layer * 0.3); hue = layer / 3.0 + 0.1; acc = head;\n"
        + "      if (y < -3.1) b = 0.0;\n"
        + "    } else if (cat < 0.94) {\n"
        + "      float x = (aS.y - 0.5) * 24.0; float z = 2.0 + aS.z * 18.0;\n"
        + "      float r = rip(uRipX.x, uRipZ.x, uAge.x, x, z) + rip(uRipX.y, uRipZ.y, uAge.y, x, z)\n"
        + "              + rip(uRipX.z, uRipZ.z, uAge.z, x, z) + rip(uRipX.w, uRipZ.w, uAge.w, x, z);\n"
        + "      p = vec3(x, -3.2 + r * 0.45, z);\n"
        + "      b = 0.09 + r * 1.1 + uBass * 0.06; sz = 0.16 * (1.0 + r * 1.2); hue = 0.55 + r * 0.3; acc = min(r, 1.0);\n"
        + "      b *= smoothstep(22.0, 14.0, z);\n"
        + "    } else {\n"
        + "      p = vec3((aS.y - 0.5) * 60.0, -3.2 + aS.z * 6.0, 25.0 + aS.w * 15.0);\n"
        + "      b = 0.014 * (1.0 - aS.z); sz = 4.5; hue = 0.9;\n"
        + "    }\n"
        + "  } else if (uScene < 4.5) {\n" // galaxy
        + "    vec3 q = vec3(0.0);\n"
        + "    if (cat < 0.80) {\n"
        + "      float na = 2.0 + floor(uVar.y * 2.99); float arm = floor(aS.y * na); float r = pow(aS.z, 0.75) * 7.5 + 0.3;\n"
        + "      float wv = shocks(r, 9.0, 0.8);\n"
        + "      float ang = arm * 6.2832 / na + r * (0.28 + uVar.x * 0.18) + uSpin * 0.32 + 0.14 * sin(uSpin * 0.22 + r * 0.8) + (h1(aS.w * 91.0) - 0.5) * 1.1 / (1.0 + r * 0.12);\n"
        + "      float yy = (h1(aS.w * 57.0 + 1.0) - 0.5) * 0.35 / (1.0 + r * 0.2) + wv * 0.35;\n"
        + "      float rk = r * (1.0 + uKick * 0.05);\n"
        + "      q = vec3(cos(ang) * rk, yy, sin(ang) * rk);\n"
        + "      b = 0.24 * (1.2 - r / 9.0) * (0.8 + uMid * 0.5) + wv * 0.30; hue = 0.3 + 0.7 * r / 8.0; sz = (0.075 + 0.04 * h1(aS.w * 3.0)) * (1.0 + wv * 0.7); acc = min(wv, 1.0);\n"
        + "    } else if (cat < 0.93) {\n"
        + "      float s = h1(aS.w * 13.0) * 6.2832; float rr = aS.y * aS.y * 1.3;\n"
        + "      q = vec3(cos(s) * rr, (aS.z - 0.5) * 0.5 * (1.0 - aS.y), sin(s) * rr);\n"
        + "      b = 0.15 * (1.0 + uBass * 0.5 + uKickB * 0.3); sz = 0.09 * (1.0 + uKick * 0.8); gold = 1.0;\n"
        + "    } else {\n"
        + "      float az = aS.y * 6.2832; float el = (aS.z - 0.5) * 2.4; float r = 9.0 + aS.w * 5.0;\n"
        + "      q = vec3(cos(az) * cos(el), sin(el), sin(az) * cos(el)) * r;\n"
        + "      b = 0.10 + 0.25 * pow(0.5 + 0.5 * sin(uT * 2.0 + aS.w * 70.0), 4.0); sz = 0.07; hue = 0.8; acc = 1.0;\n"
        + "    }\n"
        + "    float tg = 0.75 + uVar.x * 0.45; float ct = cos(tg), st = sin(tg);\n"
        + "    q = vec3(q.x, q.y * ct - q.z * st, q.y * st + q.z * ct);\n"
        + "    p = q + vec3(0.0, 0.0, 11.0 - 2.5 * sin(uT * 0.05));\n"
        + "  } else {\n" // beam hall
        + "    if (cat < 0.28) {\n"
        + "      float a = aS.y * 6.2832; float rr = aS.z * aS.z * 0.8 * (1.0 + uKick * 0.5 + uBass * 0.5);\n"
        + "      float y = (aS.w - 0.5) * 16.0;\n"
        + "      p = vec3(cos(a) * rr, y, 9.0 + sin(a) * rr * 0.5);\n"
        + "      b = (0.50 + uKick * 0.12) * (1.0 - abs(y) / 9.5) * (1.0 - aS.z * 0.55); sz = 0.12 + aS.z * 0.25; hue = 0.8; acc = 0.55 + 0.4 * (1.0 - aS.z);\n"
        + "    } else if (cat < 0.48) {\n"
        + "      float j = floor(aS.w * 3.0);\n"
        + "      float x = sin(uT * (0.14 + 0.05 * j) + j * 2.1) * 3.8 + (j - 1.0) * 2.2;\n"
        + "      float y = (aS.y - 0.5) * 12.0;\n"
        + "      p = vec3(x + sin(y * 0.4 + uT * 0.3 + j) * 0.4, y, 4.0 + aS.z * 14.0);\n"
        + "      b = (0.20 + uMid * 0.16 + shocks(abs(y), 6.0, 1.0) * 0.26) * smoothstep(0.0, 3.0, y + 6.0) * smoothstep(0.0, 3.0, 6.0 - y); hue = 0.3 + 0.2 * j; sz = 0.12;\n"
        + "    } else if (cat < 0.66) {\n"
        + "      float j = floor(aS.w * 3.0); float t = aS.z;\n"
        + "      float x0 = sin(uT * (0.10 + 0.04 * j) + j * 2.3) * 4.0 + (j - 1.0) * 2.5;\n"
        + "      float an = 0.35 * sin(uT * 0.15 + j * 1.7) + (j - 1.0) * 0.25;\n"
        + "      vec3 top = vec3(x0, 7.0, 6.0 + j * 2.5);\n"
        + "      p = top + vec3(sin(an), -cos(an), 0.15 * j) * t * 14.0;\n"
        + "      p.x += (aS.y - 0.5) * (0.15 + t * 0.7); p.z += (aS.y - 0.5) * 0.8;\n"
        + "      b = (0.20 + uMid * 0.14) * (1.0 - t * 0.55) * smoothstep(0.0, 0.1, t); sz = 0.12 + t * 0.15; hue = 0.4 + 0.2 * j; acc = 0.3;\n"
        + "    } else {\n"
        + "      float side = aS.y < 0.5 ? -1.0 : 1.0;\n"
        + "      float strip = step(0.62, aS.w);\n"
        + "      float z = mod(mix(h1(aS.w * 17.0) * 30.0, floor(h1(aS.w * 5.0) * 10.0) * 3.0, strip) - uDist * 0.8, 30.0) + 1.0;\n"
        + "      float tw = pow(0.5 + 0.5 * sin(uT * 2.5 + aS.w * 90.0), 3.0);\n"
        + "      p = vec3(side * (5.5 + mix(aS.w * 1.5, 0.2, strip)), (aS.z - 0.5) * 12.0, z);\n"
        + "      b = (0.10 + 0.3 * tw + uHigh * 0.3 * tw + strip * 0.12) * smoothstep(31.0, 20.0, z) * smoothstep(1.0, 3.0, z); sz = 0.09 + strip * 0.04; hue = aS.z; acc = tw;\n"
        + "    }\n"
        + "    b *= 1.0 + uKickB * 0.2;\n"
        + "  }\n"
        + "  if (p.z < 0.15 || b <= 0.0) { gl_Position = vec4(2.0, 2.0, 2.0, 1.0); gl_PointSize = 0.0; vA = 0.0; vCol = vec3(0.0); return; }\n"
        + "  if (uSig.z > 0.5 && fract(aS.w * 7.0) > 0.5) p.x = -p.x;\n"
        + "  vec2 sc = vec2(p.x / (p.z * " + TAN_H + " * uAspect), p.y / (p.z * " + TAN_H + "));\n"
        + "  sc = (sc + uOff) * (1.0 + uPunch);\n"
        + "  sc += sc * uBurst * (0.05 + 0.09 * h1(aS.z * 7.1));\n"
        + "  gl_Position = vec4(sc, p.z / 60.0, 1.0);\n"
        + "  gl_PointSize = clamp(sz * uSize * uSig.x * uPx / p.z * (1.0 + uBloom * 0.25 + uBurst * 0.35), 1.5, 7.5);\n"
        + "  float hh = clamp(hue + uDrift, 0.0, 1.0);\n"
        + "  vec3 col = mix(mix(uC0, uC1, hh), uC2, clamp(acc, 0.0, 1.0));\n"
        + "  vCol = mix(col, uC2, gold);\n"
        + "  vA = min(b, 0.8) * uAlpha * (1.0 + uBloom * 0.25);\n"
        + "}\n";

    private static final String FS =
        "precision mediump float;\n"
        + "varying vec3 vCol;\nvarying float vA;\n"
        + "void main() {\n"
        + "  vec2 d = gl_PointCoord - 0.5; float r = dot(d, d) * 4.0;\n"
        + "  if (r > 1.0) discard;\n"
        + "  float a = 1.0 - r; a *= a;\n"
        + "  gl_FragColor = vec4(vCol, a * vA);\n"
        + "}\n";

    private static final String POST_VS = "attribute vec2 aP;\n"
        + "varying vec2 vUV;\n"
        + "void main() { vUV = aP * 0.5 + 0.5; gl_Position = vec4(aP, 0.0, 1.0); }\n";
    private static final String POST_FS = "precision mediump float;\n"
        + "varying vec2 vUV;\nuniform sampler2D uScene;\nuniform float uGain, uDim;\nuniform vec3 uBgT, uBgB, uRingCol;\nuniform vec4 uRing;\n"
        + "void main() {\n"
        + "  vec3 c = texture2D(uScene, vUV).rgb * uGain;\n"
        + "  float v = max(c.r, max(c.g, c.b));\n"
        + "  if (v > 0.55) { float v2 = 0.55 + 0.25 * (1.0 - exp(-(v - 0.55) / 0.25)); c *= v2 / v; v = v2; }\n"
        + "  c = max(c, vec3(v * 0.22));\n"
        + "  c += mix(uBgB, uBgT, vUV.y) * (1.0 - min(1.0, v * 1.3));\n"
        + "  c *= uDim;\n"
        + "  float ra = uRing.x;\n"
        + "  if (ra < 0.999) {\n"
        + "    float d = length((vUV - 0.5) * vec2(1.7778, 1.0));\n"
        + "    float wd = 0.005 + 0.009 * ra;\n"
        + "    float r1 = exp(-pow((d - ra * uRing.z) / wd, 2.0));\n"
        + "    float r2 = exp(-pow((d - max(ra - 0.12, 0.0) * uRing.z) / wd, 2.0)) * step(0.12, ra);\n"
        + "    c += uRingCol * (r1 + 0.5 * r2) * uRing.y * (1.0 - ra);\n"
        + "  }\n"
        + "  gl_FragColor = vec4(c, 1.0);\n"
        + "}\n";
    private static final String AVG_FS = "precision mediump float;\n"
        + "varying vec2 vUV;\nuniform sampler2D uScene;\n"
        + "void main() { gl_FragColor = vec4(texture2D(uScene, vUV).rgb, 1.0); }\n";
    private static final String DAN_VS = "attribute vec2 aP;\n"
        + "uniform vec2 uOff, uSc;\nuniform mat4 uMat;\n"
        + "varying vec2 vUV;\nvarying float vY;\n"
        + "void main() {\n"
        + "  vec2 uv = aP * 0.5 + 0.5;\n"
        + "  vUV = (uMat * vec4(uv, 0.0, 1.0)).xy; vY = uv.y;\n"
        + "  gl_Position = vec4(uOff + aP * uSc, 0.0, 1.0);\n"
        + "}\n";
    private static final String DAN_FS = "#extension GL_OES_EGL_image_external : require\n"
        + "precision mediump float;\n"
        + "varying vec2 vUV;\nvarying float vY;\n"
        + "uniform samplerExternalOES uTex;\n"
        + "uniform vec3 uC0, uC1;\nuniform float uAlpha, uStyle;\nuniform vec2 uPix;\n"
        + "float L(vec2 uv) { return smoothstep(0.05, 0.85, texture2D(uTex, uv).r); }\n"
        + "void main() {\n"
        + "  float l = L(vUV);\n"
        + "  vec3 rgb = vec3(0.74); float al = l * uAlpha;\n"
        + "  if (uStyle > 0.5 && uStyle < 1.5) rgb = uC0;\n"
        + "  else if (uStyle > 1.5 && uStyle < 2.5) rgb = mix(uC0, uC1, vY);\n"
        + "  else if (uStyle > 2.5 && uStyle < 3.5) {\n"
        + "    float n = (L(vUV + vec2(uPix.x, 0.0)) + L(vUV - vec2(uPix.x, 0.0)) + L(vUV + vec2(0.0, uPix.y)) + L(vUV - vec2(0.0, uPix.y))) * 0.25;\n"
        + "    al = clamp(abs(l - n) * 3.5, 0.0, 1.0) * uAlpha; rgb = mix(uC0, vec3(0.74), 0.5);\n"
        + "  } else if (uStyle > 3.5) {\n"
        + "    float l1 = L(vUV + vec2(uPix.x * 6.0, 0.0)); float l2 = L(vUV - vec2(uPix.x * 6.0, 0.0));\n"
        + "    rgb = (uC0 * l1 + uC1 * l2) * uAlpha; al = 1.0;\n"
        + "  }\n"
        + "  gl_FragColor = vec4(rgb, al);\n"
        + "}\n";

    private static final int DISC_N = 64;
    private static final String DISC_VS = "attribute vec2 aP;\n"
        + "uniform vec2 uC, uR;\n"
        + "void main() { gl_Position = vec4(uC + aP * uR, 0.0, 1.0); }\n";
    private static final String DISC_FS = "precision mediump float;\n"
        + "uniform vec4 uCol;\n"
        + "void main() { gl_FragColor = uCol; }\n";
    private int dC, dR, dCol;

    private final Pulse pulse;
    private final GLSurfaceView view;
    private final HashMap<String, Integer> locs = new HashMap<>();
    private FloatBuffer data;
    private int prog, vbo = -1;
    private float aspect = 1.78f, px = 435f;
    private boolean running, kickHit;

    private volatile String songKey = "";
    private volatile boolean songDirty = true;
    private volatile long keyAt;
    private volatile int skipReq;
    private volatile int forced = -1;
    private volatile boolean dForce;

    private static int lastFirst = -1, lastPal = -1;
    private int[] plan = new int[12], pals = new int[12], planVar = new int[12], planFg = new int[12];
    private int planLen, planIdx, cur = -1, prev = -1, palCur, palPrev;
    private float fade = 1f, since;
    private long lastNs;
    private float time, dist, drift;
    private final float[] bgTop = new float[3], bgBot = new float[3];
    // impact: a thin ring on strong kicks, and a dim-then-slam when the music drops out and comes back
    private float ringAge = 1f, ringStr, ringMax = 0.9f, dim = 1f, quietT, loudT, prevKickEnv;
    private boolean inBreak;
    private long lastRingMs;

    private void impactStep(float dt, float en) {
        float ke = pulse.kickEnv();
        long nowMs = SystemClock.uptimeMillis();
        boolean edge = ke >= 0.95f && prevKickEnv < 0.95f;
        prevKickEnv = ke;
        if (en > 0.22f) loudT = 4f; else loudT = Math.max(0f, loudT - dt);
        if (en < 0.06f && !edge) quietT += dt; else quietT = 0f;
        boolean brk = quietT > 0.30f && quietT < 5f && loudT > 0f;
        if (brk) {
            inBreak = true;
            dim += (0.12f - dim) * Math.min(1f, dt / 0.25f);
        } else {
            if (inBreak && quietT == 0f) { // the music is back: slam
                inBreak = false;
                ringAge = 0f;
                ringStr = 0.9f;
                ringMax = 1.4f;
                dim = Math.max(dim, 0.55f);
                lastRingMs = nowMs;
            }
            if (quietT >= 5f) inBreak = false;
            dim += (1f - dim) * Math.min(1f, dt / (quietT >= 5f ? 1.5f : 0.07f));
        }
        if (edge && nowMs - lastRingMs >= 500 && en > 0.15f) { // at most two rings a second
            lastRingMs = nowMs;
            ringAge = 0f;
            ringStr = 0.45f + 0.35f * Math.min(1f, bass);
            ringMax = 0.9f;
        }
        if (ringAge < 1f) ringAge = Math.min(1f, ringAge + dt / (ringMax > 1f ? 1.1f : 0.8f));
    }
    private final float[] bgHsv = new float[3];

    /** Hue/saturation/value to rgb 0..1. */
    private void hsv3(float h, float s, float v, float[] out) {
        bgHsv[0] = ((h % 360f) + 360f) % 360f;
        bgHsv[1] = s;
        bgHsv[2] = v;
        int col = Color.HSVToColor(bgHsv);
        out[0] = Color.red(col) / 255f;
        out[1] = Color.green(col) / 255f;
        out[2] = Color.blue(col) / 255f;
    }

    private float cinemaTarget = 25f, cinPrevK, cinPrevS;
    private float peakE, loudK = 1f;
    private boolean cinKick, cinSnare, cinDown, lowRes;
    private int lowCount;

    private boolean cinemaOn() {
        return !"off".equals(Config.get(ctx).get("react_cinema", "on"));
    }

    /** Cinema: the clip drawn over the finished frame, with the beat layer and the comfort grade (GL thread). */
    private void drawCinema(float dt) {
        if (!cinema.active()) return;
        float e = fade * fade * (3f - 2f * fade);
        if (fade >= 1f) cinema.dropOut();
        float night = Math.max(0f, Math.min(1f, (0.7f - DayLight.k()) / 0.4f));
        cinema.draw(dt, e, cur == CINEMA, kickEnv, cinKick, cinDown, cinSnare, snareEnv, hatEnv, night, SystemClock.uptimeMillis(), surfW, surfH);
    }

    private float mrot, mdir = 1f, mease = 1f;
    private long lastBarN = -1;
    private float spin, kickB, varA, varB, dwellTarget = 28f;
    private long lastBrightMs;
    private static final long[] lastUsed = new long[128];
    private static final int[] recentIds = new int[12];
    private static int recentN;
    private static long serial;
    private static final int[] recentPal = {-1, -1, -1};
    private int discProg, discVbo = -1;

    static final int NCORE = 6, NNEW = 32, CANVAS0 = 100, CINEMA = 120;
    /** The Canvas engine's styles that stay in the pool (Pulse draws them above the dark GL view). */
    static final String[] CANVAS = {"bars", "wave", "mountains", "ring", "orbit", "fountain", "ripples", "aurora", "fireworks", "helix", "blob",
        "note_marble", "note_keys", "note_bottles", "note_harp", "note_musicbox", "note_stars", "note_ribbon", "note_parade", "note_water"};
    private static final int[] FAMILY = {1, 0, 0, 4, 0, 2, 1, 1, 1, 1, 2, 0, 4, 4, 3, 3, 4, 2, 0, 2, 4, 0, 0, 1, 3, 0, 2, 3, 4, 1, 3, 2, 4, 0, 2, 3, 1, 2, 0, 2};
    /** Viz Lab pass 1 (see viz-lab/CHANGELOG.md): scenes with no clear subject, static or nearly empty frames, or soft-disc clutter. */
    private static final String[] REMOVED = {"embers", "planet_horizon", "c_helix", "c_fountain", "c_ripples"};
    private static final int[] ALL = new int[NCORE + NNEW + CANVAS.length - 1 - REMOVED.length]; // bokeh_window (19), ink_flow (20) and REMOVED are out of the rotation
    private static final String[][] GENRE_LISTS = {
        {"hiphop", "city_drive", "synth_road", "embers", "plexus", "tunnel", "vortex", "torus_knot", "sound_lines", "supernova", "portal_door", "mandala_rings"},
        {"piano", "c_note_marble", "c_note_keys", "c_note_bottles", "c_note_harp", "c_note_musicbox", "c_note_stars", "c_note_ribbon", "c_note_parade", "c_note_water", "c_bars", "aurora_ridge", "pond_ripples", "ink_flow", "c_blob", "lanterns", "eclipse", "snowfall", "bubbles", "dot_sheet", "planet_horizon", "underwater_night"},
        {"country", "firefly_meadow", "sunset_rays", "lanterns", "night_ocean", "c_aurora", "planet_horizon", "dot_sheet"},
        {"edm", "hyperspace", "crystal_tunnel", "helix", "mandala_bloom", "vortex", "beam", "supernova", "atom_orbits", "torus_knot", "mandala_rings", "portal_door"},
        {"rock", "embers", "city_drive", "eclipse", "synth_road", "hyperspace", "c_fireworks", "supernova", "sound_lines", "portal_door"},
        {"rnb", "lanterns", "jellyfish", "sunset_rays", "night_ocean", "ink_flow", "bubbles", "snowfall", "lissajous_ribbons", "underwater_night", "portal_door"},
        {"pop", "mandala_bloom", "jellyfish", "synth_road", "hyperspace", "c_fountain", "c_ring", "lissajous_ribbons", "pendulum_wave", "bubbles", "mandala_rings", "dot_sheet"},
        {"jazz", "jellyfish", "city_drive", "ink_flow", "night_ocean", "plexus", "pendulum_wave", "bubbles", "underwater_night"},
        {"reggae", "sunset_rays", "firefly_meadow", "night_ocean", "lanterns", "dunes", "underwater_night"},
        {"gospel", "sunset_rays", "eclipse", "lanterns", "aurora_ridge", "galaxy", "atom_orbits", "jellyfish", "planet_horizon", "mandala_rings"}};

    static {
        int k = 0;
        for (int i = 0; i < NCORE + NNEW; i++) if (i != 19 && i != 20 && !removed(sceneName(i))) ALL[k++] = i;
        for (int i = 0; i < CANVAS.length; i++) if (!removed(sceneName(CANVAS0 + i))) ALL[k++] = CANVAS0 + i;
        ALL[k++] = CINEMA;
    }

    private static boolean removed(String n) {
        for (String r : REMOVED) if (r.equals(n)) return true;
        return false;
    }

    static String sceneName(int id) {
        if (id < 0) return "none";
        if (id < NCORE) return NAMES[id];
        if (id < NCORE + NNEW) return GlowScenes.NAMES[id - NCORE];
        if (id >= CANVAS0 + CANVAS.length) return "cinema";
        return "c_" + CANVAS[Math.max(0, Math.min(CANVAS.length - 1, id - CANVAS0))];
    }

    static int idOf(String n) {
        for (int i = 0; i < ALL.length; i++) if (sceneName(ALL[i]).equals(n)) return ALL[i];
        return -1;
    }

    private static int family(int id) {
        return id == CINEMA ? 7 : id >= CANVAS0 + 11 ? 6 : id >= CANVAS0 ? 5 : FAMILY[id];
    }

    interface CanvasListener {
        void style(String canvasStyleOrNull);
    }

    interface LabelListener {
        void label(String textOrNull);
    }

    interface GenreSource {
        String genre();
    }

    // a palette built per song: cover art, seed, genre bank, artist theme
    private static final float[] hueHist = new float[10];
    private static int hueN;
    private volatile int[] cover;
    private volatile boolean coverNew;
    private volatile GenreSource gsrc;
    private float hueTarget, hueShow = 200f, palSat = 0.48f, palVal = 0.68f;
    private int harmSeed;
    private String genre = "unknown", palSrc = "seed", lastPlanKey = "";
    private boolean hueCommitted;
    private float genreT;
    private boolean genreReplanned;
    private volatile CanvasListener canvasL;
    private volatile LabelListener labelL;
    private int curVar, prevVar, curFg = -1, curFgVar, galleryIdx;
    private volatile float gallerySecs = 8f;
    private volatile int galleryVars = 4;
    private float sceneFpsSum, sceneFpsMin = 999f;
    private int sceneFpsN;
    private static final class Prog {
        final int id;
        final HashMap<String, Integer> locs = new HashMap<>();

        Prog(int id) {
            this.id = id;
        }
    }
    private Prog cp, roomProg;
    private final HashMap<Integer, Prog> progs = new HashMap<>();
    private long planAtMs;
    private final float[][] colBuf = new float[64][9];
    private final float[] hsvTmp = new float[3];
    // a signature per song, from its seed
    private float sigSize = 1f, sigDens = 1f, sigTrail = 1f, sigSym, camAmp, camFreq, camPh;
    private int sigWob, ringN = 4;
    // the beat engine
    private static final int RENDER_LAT = 35;
    private volatile int baseSync, userDelay;
    private volatile boolean calibrate;
    private float punchEnv, burstEnv, snareEnv, hatEnv, preV, bassFast, bassSlow, bPrev, fluxAvg, markerEnv, offX, offY, ringGapB;
    private long lastBeatIdx = Long.MIN_VALUE, lastOnsMs, lastFireMs, lastSnareMs, lastSnareSeen, lastQueueMs, syncLogMs;
    private boolean lockedPrev, locked, barStart;
    private int lastSnares = -1, lastHats = -1, snareDir = 1, qn;
    private final long[] fireT = new long[512], onsT = new long[512], bq = new long[16];
    private int fireN, onsN;
    private float confSum;
    private int confN;
    // the dancer director
    private volatile String dancerSongs = "Michael Jackson|Beat It";
    private boolean dSong;
    private int dApp, dStyleName, lastStyleUsed = -1, lastArrUsed = -1;
    private float dStartT, dDur;
    private final int[] styleOrd = new int[5], arrOrd = new int[5];
    // post-process: the scene renders into a 1024x512 target, then a soft-knee tone map writes the screen
    private static final int SW = 1024, SH = 512;
    private static final String DTAG = "dumbdancer";
    private int fbo, sceneTex, avgFbo, avgTex, quadVbo, postProg, avgProg, danProg, oesTex;
    private float gain = 1f, avgLum, avgT;
    private final ByteBuffer px4 = ByteBuffer.allocateDirect(16 * 8 * 4).order(ByteOrder.nativeOrder());
    private int surfW = 960, surfH = 540;
    // the dancer: one MediaPlayer into one external texture, drawn up to five times
    private final Context ctx;
    private CinemaGl cinema;
    private SurfaceTexture st;
    private Surface surf;
    private MediaPlayer mp;
    private volatile boolean dReady, dFrame;
    private final Object dLock = new Object();
    private final float[] texMat = new float[16];
    private int dState, dStyle, dArr, dN, dMax = 5, dCount;
    private boolean dGlow = true;
    private float songT, hiE, dWait, dEndAt, dLow, dGovT, dSpeedT, kickSong, dFadeV, dLastEnd = -1000f, dRestUntil;
    private final float[] cx = new float[5], cy = new float[5], cs = new float[5], ca = new float[5];
    private final boolean[] cflip = new boolean[5];
    private final int[] ccol = new int[5];

    private float kickEnv, bass, mid, high, ener, fastE, slowE, bloom, ringGap;
    private int lastKicks = -1, section, secCand, ringNext;
    private float secHold;
    private long lastBloomMs;
    private final float[] age = {-1, -1, -1, -1}, ripX = new float[4], ripZ = new float[4];
    private final Random rnd = new Random();
    private float frameAvg = 0.016f, govT, logT;
    private int count = 10000, level;
    private long lastLogMs;

    Glow(Context c, Pulse p) {
        pulse = p;
        ctx = c.getApplicationContext();
        view = new GLSurfaceView(c);
        cinema = new CinemaGl(ctx, view);
        view.setEGLContextClientVersion(2);
        view.setEGLConfigChooser(8, 8, 8, 8, 0, 0);
        view.getHolder().setFixedSize(1920, 1080); // native 1080p (Cinema needs it); the governor in step() drops to 960x540 if the other scenes cannot hold 48 fps
        view.setRenderer(this);
        view.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
        view.onPause();
    }

    GLSurfaceView view() {
        return view;
    }

    private volatile int pinId = -1, pinVar;
    private volatile long pinUntil;
    private volatile boolean pinReq;

    /** Viz Lab: holds one scene and preset for secs seconds (name or id); null releases it. Returns the scene name, or null. */
    String pin(String scene, int preset, int secs) {
        if (scene == null) {
            pinId = -1;
            pinReq = true;
            return null;
        }
        int id = idOf(scene);
        if (id < 0) {
            try {
                int n = Integer.parseInt(scene);
                for (int i = 0; i < ALL.length; i++) if (ALL[i] == n) id = n;
                if (id < 0 && n >= 0 && n < ALL.length) id = ALL[n];
            } catch (NumberFormatException e) {
                id = -1;
            }
        }
        if (id < 0) return null;
        pinVar = Math.max(0, Math.min(3, preset));
        pinUntil = SystemClock.uptimeMillis() + Math.max(3, secs) * 1000L;
        pinId = id;
        pinReq = true;
        return sceneName(id);
    }

    float markerLevel() {
        return markerEnv;
    }

    private volatile long frameAtMs;
    private int kicks;
    private long kickWindow;
    private volatile boolean badScene;
    private final android.os.Handler wdHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    /** If the surface stops delivering frames for 3 s while it should run (lost context, stuck resume), pause and resume it. */
    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            long now = SystemClock.uptimeMillis();
            if (view.getVisibility() == android.view.View.VISIBLE && now - frameAtMs > 3000) {
                if (now - kickWindow > 60000) {
                    kickWindow = now;
                    kicks = 0;
                }
                if (kicks++ < 4) {
                    Log.w(TAG, "no frames for " + (now - frameAtMs) + " ms: restarting the surface (" + kicks + ")");
                    lastNs = 0;
                    view.onPause();
                    view.onResume();
                    frameAtMs = now;
                } else if (kicks == 5) {
                    Log.w(TAG, "surface will not render: the Canvas visual takes over");
                    CanvasListener cl = canvasL;
                    if (cl != null) cl.style("auto");
                }
            }
            wdHandler.postDelayed(this, 1500);
        }
    };

    void setRunning(boolean on) {
        if (on == running) return;
        running = on;
        wdHandler.removeCallbacks(watchdog);
        if (on) {
            lastNs = 0;
            frameAtMs = SystemClock.uptimeMillis();
            view.onResume();
            wdHandler.postDelayed(watchdog, 3000);
        } else {
            view.onPause();
            releasePlayer();
            cinema.glRelease();
        }
    }

    void destroy() {
        setRunning(false);
    }

    /** "glowroom" rotates the rooms; "glowroom_dunes" and friends stay in one room (the palette still changes). */
    void setStyle(String s) {
        int f = -1;
        if ("gallery".equals(s)) f = -2;
        for (int i = 0; i < NCORE; i++) if (("glowroom_" + NAMES[i]).equals(s)) f = i;
        for (int i = 0; i < NNEW; i++) if (("glow_" + GlowScenes.NAMES[i]).equals(s)) f = NCORE + i;
        if ("glow_cinema".equals(s)) f = CINEMA;
        if (f == NCORE + 13 || f == NCORE + 14) f = -1; // migration: bokeh_window and ink_flow were removed
        if (f >= 0 && removed(sceneName(f))) f = -1;
        if (f != forced) {
            forced = f;
            songDirty = true;
        }
    }

    void setSong(String artist, String title) {
        String k = (artist == null ? "" : artist) + "|" + (title == null ? "" : title);
        if (!k.equals(songKey)) {
            songKey = k;
            cover = null; // the last song's cover must not colour this one
            coverNew = false;
            keyAt = SystemClock.uptimeMillis();
            songDirty = true;
        }
    }

    /** react_dancer=on: a test switch that shows the dancer on any song. */
    void setDancerForce(boolean on) {
        dForce = on;
    }

    /** react_dancer_songs: "artist|title" pairs separated by ";" - both parts must be contained in the playing track. */
    void setDancerSongs(String list) {
        dancerSongs = list == null ? "" : list;
    }

    /** Canvas scenes (the older Pulse styles) are shown by the host: it gets the style, or null when GL draws again. */
    void setCanvasListener(CanvasListener l) {
        canvasL = l;
    }

    void setLabelListener(LabelListener l) {
        labelL = l;
    }

    /** react_style=gallery: every scene and variant, secs each. */
    void setGallery(float secs, int variants) {
        gallerySecs = Math.max(2f, secs);
        galleryVars = Math.max(1, Math.min(4, variants));
    }

    void setCover(int[] palette) {
        cover = palette;
        coverNew = true;
    }

    void setGenreSource(GenreSource g) {
        gsrc = g;
    }

    /** react_sync_ms: how long the picture is held back to meet the sound. */
    void setBaseSync(int ms) {
        baseSync = ms;
    }

    /** react_delay_ms, -300..300: the extra offset the calibration mode nudges. */
    void setDelay(int ms) {
        userDelay = Math.max(-300, Math.min(300, ms));
    }

    int delay() {
        return userDelay;
    }

    void setCalibrate(boolean on) {
        calibrate = on;
    }

    boolean calibrating() {
        return calibrate;
    }

    void skip(int dir) {
        skipReq = dir == 0 ? 1 : dir;
    }

    // ---- GL ----

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig cfg) {
        locs.clear();
        int vs = compile(GLES20.GL_VERTEX_SHADER, VS), fs = compile(GLES20.GL_FRAGMENT_SHADER, FS);
        prog = GLES20.glCreateProgram();
        GLES20.glAttachShader(prog, vs);
        GLES20.glAttachShader(prog, fs);
        GLES20.glBindAttribLocation(prog, 0, "aS");
        GLES20.glLinkProgram(prog);
        int[] ok = new int[1];
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) Log.w(TAG, "link failed: " + GLES20.glGetProgramInfoLog(prog));
        roomProg = new Prog(prog);
        progs.clear();
        cp = roomProg;
        if (data == null) {
            Random r = new Random(7);
            ByteBuffer bb = ByteBuffer.allocateDirect(MAXN * 16).order(ByteOrder.nativeOrder());
            data = bb.asFloatBuffer();
            for (int i = 0; i < MAXN * 4; i++) data.put(r.nextFloat());
            data.position(0);
        }
        int[] b = new int[1];
        GLES20.glGenBuffers(1, b, 0);
        vbo = b[0];
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo);
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, MAXN * 16, data, GLES20.GL_STATIC_DRAW);
        discProg = GLES20.glCreateProgram();
        GLES20.glAttachShader(discProg, compile(GLES20.GL_VERTEX_SHADER, DISC_VS));
        GLES20.glAttachShader(discProg, compile(GLES20.GL_FRAGMENT_SHADER, DISC_FS));
        GLES20.glBindAttribLocation(discProg, 0, "aP");
        GLES20.glLinkProgram(discProg);
        dC = GLES20.glGetUniformLocation(discProg, "uC");
        dR = GLES20.glGetUniformLocation(discProg, "uR");
        dCol = GLES20.glGetUniformLocation(discProg, "uCol");
        float[] fan = new float[(DISC_N + 2) * 2];
        for (int i = 0; i <= DISC_N; i++) {
            double a = i * 2 * Math.PI / DISC_N;
            fan[(i + 1) * 2] = (float) Math.cos(a);
            fan[(i + 1) * 2 + 1] = (float) Math.sin(a);
        }
        FloatBuffer fb = ByteBuffer.allocateDirect(fan.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        fb.put(fan).position(0);
        int[] db = new int[1];
        GLES20.glGenBuffers(1, db, 0);
        discVbo = db[0];
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, discVbo);
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, fan.length * 4, fb, GLES20.GL_STATIC_DRAW);
        // full-screen quad, post-process programs, render targets
        FloatBuffer qb = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer();
        qb.put(new float[] {-1, -1, 1, -1, -1, 1, 1, 1}).position(0);
        int[] qv = new int[1];
        GLES20.glGenBuffers(1, qv, 0);
        quadVbo = qv[0];
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, quadVbo);
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, 32, qb, GLES20.GL_STATIC_DRAW);
        postProg = makeProg(POST_VS, POST_FS);
        avgProg = makeProg(POST_VS, AVG_FS);
        danProg = makeProg(DAN_VS, DAN_FS);
        cinema.glInit();
        int[] pok = new int[1], rok = new int[1];
        GLES20.glGetProgramiv(postProg, GLES20.GL_LINK_STATUS, pok, 0);
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, rok, 0);
        if (pok[0] == 0 || rok[0] == 0) {
            Log.w(TAG, "core programs failed to link: the Canvas visual takes over");
            CanvasListener bl = canvasL;
            if (bl != null) bl.style("auto");
        }
        int[] ids = makeTarget(SW, SH);
        fbo = ids[0];
        sceneTex = ids[1];
        ids = makeTarget(16, 8);
        avgFbo = ids[0];
        avgTex = ids[1];
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        // the dancer's external texture
        int[] ot = new int[1];
        GLES20.glGenTextures(1, ot, 0);
        oesTex = ot[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        releasePlayer();
        synchronized (dLock) {
            if (st != null) st.release();
            if (surf != null) surf.release();
            st = new SurfaceTexture(oesTex);
            st.setOnFrameAvailableListener(new SurfaceTexture.OnFrameAvailableListener() {
                @Override
                public void onFrameAvailable(SurfaceTexture t) {
                    dFrame = true;
                }
            });
            surf = new Surface(st);
        }
        view.post(new Runnable() {
            @Override
            public void run() {
                initPlayer();
            }
        });
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo);
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE);
        lastNs = 0;
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int w, int h) {
        surfW = Math.max(1, w);
        surfH = Math.max(1, h);
        aspect = 16f / 9f; // the scene is drawn 16:9 into the 2:1 target and stretched back to the screen
        px = SH * 0.5f / TAN_H;
    }

    private static int makeProg(String vs, String fs) {
        int pr = GLES20.glCreateProgram();
        GLES20.glAttachShader(pr, compile(GLES20.GL_VERTEX_SHADER, vs));
        GLES20.glAttachShader(pr, compile(GLES20.GL_FRAGMENT_SHADER, fs));
        GLES20.glBindAttribLocation(pr, 0, "aP");
        GLES20.glLinkProgram(pr);
        return pr;
    }

    /** A colour texture and the framebuffer that renders into it. */
    private static int[] makeTarget(int w, int h) {
        int[] t = new int[1];
        GLES20.glGenTextures(1, t, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t[0]);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        int[] f = new int[1];
        GLES20.glGenFramebuffers(1, f, 0);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, f[0]);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, t[0], 0);
        return new int[] {f[0], t[0]};
    }

    private static int compile(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) Log.w(TAG, "shader failed: " + GLES20.glGetShaderInfoLog(s));
        return s;
    }

    private int loc(String n) {
        Integer i = cp.locs.get(n);
        if (i == null) {
            i = GLES20.glGetUniformLocation(cp.id, n);
            cp.locs.put(n, i);
        }
        return i;
    }

    /** One vertex program per added scene, built from the shared head and tail around the scene's own body, on first use. */
    private Prog progFor(int scene) {
        if (scene < NCORE) return roomProg;
        Prog pr = progs.get(scene);
        if (pr != null) return pr.id == 0 ? null : pr;
        String name = sceneName(scene);
        int hi = VS.indexOf("void main() {");
        int ti = VS.indexOf("  if (p.z < 0.15");
        String src = VS.substring(0, hi) + GlowScenes.EXTRA + GlowScenes.MAIN_START + GlowScenes.BODY[scene - NCORE] + "\n" + VS.substring(ti);
        int vs = compile(GLES20.GL_VERTEX_SHADER, src);
        int fs = compile(GLES20.GL_FRAGMENT_SHADER, FS);
        int id = GLES20.glCreateProgram();
        GLES20.glAttachShader(id, vs);
        GLES20.glAttachShader(id, fs);
        GLES20.glBindAttribLocation(id, 0, "aS");
        GLES20.glLinkProgram(id);
        int[] ok = new int[1];
        GLES20.glGetProgramiv(id, GLES20.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) {
            Log.w(TAG, "scene " + name + " failed to build: " + GLES20.glGetProgramInfoLog(id));
            progs.put(scene, new Prog(0));
            return null;
        }
        pr = new Prog(id);
        progs.put(scene, pr);
        Log.i(TAG, "scene " + name + " built");
        return pr;
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        frameAtMs = SystemClock.uptimeMillis();
        long ns = System.nanoTime();
        float dt = lastNs == 0 ? 0.016f : Math.min(0.1f, (ns - lastNs) / 1e9f);
        lastNs = ns;
        step(dt);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo);
        GLES20.glViewport(0, 0, SW, SH);
        GLES20.glClearColor(0.012f, 0.014f, 0.022f, 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        if (cur >= 0) {
            drawWorld();
            drawDancer();
            drawMarker();
        }
        measure(dt);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glViewport(0, 0, surfW, surfH);
        GLES20.glDisable(GLES20.GL_BLEND);
        GLES20.glUseProgram(postProg);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, quadVbo);
        GLES20.glEnableVertexAttribArray(0);
        GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 0, 0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, sceneTex);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(postProg, "uScene"), 0);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(postProg, "uGain"), gain);
        float dk = DayLight.k();
        float bh = hueShow;
        float[] bt = bgTop, bb = bgBot;
        hsv3(bh + 35f, 0.50f, 0.34f * dk, bt);
        hsv3(bh - 40f, 0.55f, 0.15f * dk, bb);
        GLES20.glUniform3f(GLES20.glGetUniformLocation(postProg, "uBgT"), bt[0], bt[1], bt[2]);
        GLES20.glUniform3f(GLES20.glGetUniformLocation(postProg, "uBgB"), bb[0], bb[1], bb[2]);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(postProg, "uDim"), dim);
        GLES20.glUniform4f(GLES20.glGetUniformLocation(postProg, "uRing"), ringAge, ringStr, ringMax, 0f);
        hsv3(bh + 28f, 0.62f, 0.95f, bt);
        GLES20.glUniform3f(GLES20.glGetUniformLocation(postProg, "uRingCol"), bt[0] * 0.55f, bt[1] * 0.55f, bt[2] * 0.55f);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(0);
        drawCinema(dt);
        GLES20.glEnable(GLES20.GL_BLEND);
    }

    private void drawWorld() {
        float e = fade * fade * (3f - 2f * fade);
        if (e < 1f && prev >= 0) drawLayer(prev, prevVar, 1f - e, 1f, 1f);
        if (curFg >= 0) { // two layers: a slow dim background and a lighter foreground accent of another type
            drawLayer(cur, curVar, e * 0.5f, 0.35f, 0.7f);
            drawLayer(curFg, curFgVar, e * 0.85f, 1f, 0.4f);
        } else {
            drawLayer(cur, curVar, e, 1f, 1f);
        }
    }

    private void drawLayer(int scene, int variant, float alpha, float ks, float cs) {
        if (alpha <= 0.01f || scene < 0 || scene >= CANVAS0) return;
        Prog pr = progFor(scene);
        if (pr == null) {
            if (scene == cur) badScene = true;
            return;
        }
        cp = pr;
        GLES20.glUseProgram(pr.id);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo);
        GLES20.glEnableVertexAttribArray(0);
        GLES20.glVertexAttribPointer(0, 4, GLES20.GL_FLOAT, false, 0, 0);
        if (scene == 2) drawDisc(alpha);
        GLES20.glUniform1f(loc("uT"), time);
        GLES20.glUniform1f(loc("uDist"), dist);
        GLES20.glUniform1f(loc("uKick"), Math.min(1.3f, kickEnv) * ks);
        GLES20.glUniform1f(loc("uBass"), bass);
        GLES20.glUniform1f(loc("uMid"), mid);
        GLES20.glUniform1f(loc("uHigh"), high * 0.7f + hatEnv * 0.6f);
        GLES20.glUniform1f(loc("uEner"), ener);
        GLES20.glUniform1f(loc("uAspect"), aspect);
        GLES20.glUniform1f(loc("uSize"), 1f);
        GLES20.glUniform1f(loc("uBloom"), bloom);
        GLES20.glUniform1f(loc("uPx"), px);
        GLES20.glUniform1f(loc("uDrift"), drift);
        GLES20.glUniform1f(loc("uSpin"), spin);
        GLES20.glUniform1f(loc("uMrot"), mrot);
        GLES20.glUniform1f(loc("uPunch"), (punchEnv - 0.012f * preV) * ks);
        GLES20.glUniform1f(loc("uBurst"), burstEnv * ks);
        float camX = sigWob == 1 ? camAmp * (float) Math.sin(time * camFreq * 6.2832f + camPh) : sigWob == 2 ? camAmp * 0.4f * (float) Math.sin(time * camFreq * 3f) : 0f;
        float camY = sigWob == 2 ? camAmp * (float) Math.sin(time * camFreq * 6.2832f + camPh) : camAmp * 0.3f * (float) Math.sin(time * camFreq * 4f + 1f);
        GLES20.glUniform2f(loc("uOff"), camX + offX, camY + offY);
        GLES20.glUniform4f(loc("uSig"), sigSize, sigTrail, 0f, 0f);
        GLES20.glUniform1f(loc("uKickB"), kickB * 0.6f * ks);
        GLES20.glUniform2f(loc("uVar"), varA, varB);
        GLES20.glUniform4f(loc("uAge"), age[0], age[1], age[2], age[3]);
        GLES20.glUniform4f(loc("uRipX"), ripX[0], ripX[1], ripX[2], ripX[3]);
        GLES20.glUniform4f(loc("uRipZ"), ripZ[0], ripZ[1], ripZ[2], ripZ[3]);
        GLES20.glUniform1f(loc("uScene"), scene);
        GLES20.glUniform1f(loc("uVr"), variant);
        GLES20.glUniform1f(loc("uSnare"), snareEnv);
        GLES20.glUniform1f(loc("uAlpha"), alpha * 0.9f);
        float[] c = colorsFor(scene);
        GLES20.glUniform3f(loc("uC0"), c[0], c[1], c[2]);
        GLES20.glUniform3f(loc("uC1"), c[3], c[4], c[5]);
        GLES20.glUniform3f(loc("uC2"), c[6], c[7], c[8]);
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, Math.max(2500, (int) (count * sigDens * cs)));
        GLES20.glDisableVertexAttribArray(0);
    }

    /** Average brightness of the scene, four times a second; the gain backs off if it goes above about a third. */
    private void measure(float dt) {
        avgT += dt;
        if (avgT >= 0.25f) {
            avgT = 0f;
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, sceneTex);
            GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR_MIPMAP_NEAREST);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, avgFbo);
            GLES20.glViewport(0, 0, 16, 8);
            GLES20.glDisable(GLES20.GL_BLEND);
            GLES20.glUseProgram(avgProg);
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, quadVbo);
            GLES20.glEnableVertexAttribArray(0);
            GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 0, 0);
            GLES20.glUniform1i(GLES20.glGetUniformLocation(avgProg, "uScene"), 0);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            px4.position(0);
            GLES20.glReadPixels(0, 0, 16, 8, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, px4);
            float sum = 0f;
            for (int q = 0; q < 128; q++) sum += (px4.get(q * 4) & 255) + (px4.get(q * 4 + 1) & 255) + (px4.get(q * 4 + 2) & 255);
            avgLum = sum / (128f * 3f * 255f);
            GLES20.glDisableVertexAttribArray(0);
            GLES20.glEnable(GLES20.GL_BLEND);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, sceneTex);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo);
            GLES20.glViewport(0, 0, SW, SH);
        }
        float target = Math.min(1f, 0.33f / Math.max(avgLum, 0.001f));
        gain += (target - gain) * Math.min(1f, dt * (target < gain ? 4f : 0.6f));
    }

    /** Calibration: a big disc that pulses on every beat the picture fires, to line up with the sound. */
    private void drawMarker() {
        if (!calibrate) return;
        float a = 0.10f + 0.72f * markerEnv;
        GLES20.glUseProgram(discProg);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, discVbo);
        GLES20.glEnableVertexAttribArray(0);
        GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 0, 0);
        GLES20.glUniform2f(dC, 0f, -0.62f);
        GLES20.glUniform2f(dR, 0.15f / aspect, 0.15f);
        GLES20.glUniform4f(dCol, 0.70f, 0.70f, 0.72f, a);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, DISC_N + 2);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE);
    }

    private void setCopy(int i, float x, float y, float sc, boolean flip, int col, float a) {
        cx[i] = x;
        cy[i] = y;
        cs[i] = sc;
        cflip[i] = flip;
        ccol[i] = col;
        ca[i] = a;
    }

    private void buildArrangement() {
        switch (dArr) {
            case 0: // single
                dN = 1;
                setCopy(0, 0f, 0f, 0.95f, false, 0, 0.85f);
                break;
            case 1: // mirrored pair
                dN = 2;
                setCopy(0, -0.5f, 0f, 0.8f, false, 0, 0.8f);
                setCopy(1, 0.5f, 0f, 0.8f, true, 1, 0.8f);
                break;
            case 2: // trio
                dN = 3;
                setCopy(0, 0f, 0f, 0.95f, false, 0, 0.85f);
                setCopy(1, -0.62f, 0f, 0.72f, false, 1, 0.7f);
                setCopy(2, 0.62f, 0f, 0.72f, true, 2, 0.7f);
                break;
            case 3: // a line of three to five, each in its own colour
                dN = 3 + rnd.nextInt(3);
                for (int i = 0; i < dN; i++) setCopy(i, -0.82f + 1.64f * i / (dN - 1), 0f, 0.62f, (i & 1) == 1, i % 5, 0.75f);
                break;
            default: // wide echo with depth: big and bright in front, small and faint behind
                dN = 5;
                for (int i = 0; i < 5; i++) setCopy(i, (i % 2 == 0 ? 1f : -1f) * 0.11f * i, 0.07f * i, 1f - 0.125f * i, false, i % 3, 0.85f - 0.16f * i);
                break;
        }
    }

    private static void mix3(float[] pal, int idx, float[] out) {
        switch (idx) {
            case 0: out[0] = pal[0]; out[1] = pal[1]; out[2] = pal[2]; break;
            case 1: out[0] = pal[3]; out[1] = pal[4]; out[2] = pal[5]; break;
            case 2: out[0] = pal[6]; out[1] = pal[7]; out[2] = pal[8]; break;
            case 3: out[0] = (pal[0] + pal[6]) * 0.5f; out[1] = (pal[1] + pal[7]) * 0.5f; out[2] = (pal[2] + pal[8]) * 0.5f; break;
            default: out[0] = (pal[3] + pal[6]) * 0.5f; out[1] = (pal[4] + pal[7]) * 0.5f; out[2] = (pal[5] + pal[8]) * 0.5f; break;
        }
    }

    private final float[] tmpA = new float[3], tmpB = new float[3];

    private void drawCopy(float[] pal, int i, float scaleMul, float alpha, int style) {
        mix3(pal, ccol[i], tmpA);
        mix3(pal, (ccol[i] + 1) % 5, tmpB);
        if (style == 4) { // soft colour split uses the palette's two main colours
            mix3(pal, 0, tmpA);
            mix3(pal, 1, tmpB);
        }
        float s2 = cs[i] * scaleMul;
        GLES20.glUniform2f(GLES20.glGetUniformLocation(danProg, "uOff"), cx[i], cy[i] * 0.5f);
        GLES20.glUniform2f(GLES20.glGetUniformLocation(danProg, "uSc"), cflip[i] ? -s2 : s2, s2);
        GLES20.glUniform3f(GLES20.glGetUniformLocation(danProg, "uC0"), tmpA[0], tmpA[1], tmpA[2]);
        GLES20.glUniform3f(GLES20.glGetUniformLocation(danProg, "uC1"), tmpB[0], tmpB[1], tmpB[2]);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(danProg, "uStyle"), style);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(danProg, "uAlpha"), alpha);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
    }

    /** The dancer over the current room: additive, so there is never a backdrop. */
    private void drawDancer() {
        if (dState == 0 || dFadeV <= 0.01f || !dReady) return;
        if (dFrame) {
            dFrame = false;
            try {
                st.updateTexImage();
                st.getTransformMatrix(texMat);
            } catch (RuntimeException e) {
                return;
            }
        }
        GLES20.glUseProgram(danProg);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, quadVbo);
        GLES20.glEnableVertexAttribArray(0);
        GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 0, 0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(danProg, "uTex"), 0);
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(danProg, "uMat"), 1, false, texMat, 0);
        GLES20.glUniform2f(GLES20.glGetUniformLocation(danProg, "uPix"), 1f / 960f, 1f / 540f);
        float f = dFadeV * dFadeV * (3f - 2f * dFadeV);
        float[] pal = colorsFor(Math.max(0, cur));
        int n = Math.min(dN, dMax);
        if (dGlow) drawCopy(pal, 0, 1.12f, ca[0] * f * 0.25f, dStyle == 3 || dStyle == 4 ? 1 : dStyle);
        for (int i = n - 1; i >= 0; i--) drawCopy(pal, i, 1f, ca[i] * f, dStyle);
        GLES20.glDisableVertexAttribArray(0);
    }

    // ---- the dancer's clip: one MediaPlayer, muted, paused until a dance starts

    private void initPlayer() { // main thread
        synchronized (dLock) {
            if (surf == null || mp != null) return;
            try {
                File f = new File(ctx.getCacheDir(), "dancer.mp4");
                if (!f.exists() || f.length() == 0) {
                    InputStream in = ctx.getAssets().open("dancer.mp4");
                    OutputStream out = new FileOutputStream(f);
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    out.close();
                    in.close();
                }
                MediaPlayer m = new MediaPlayer();
                m.setSurface(surf);
                m.setDataSource(f.getAbsolutePath());
                m.setLooping(true);
                m.setVolume(0f, 0f);
                m.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                    @Override
                    public void onPrepared(MediaPlayer x) {
                        dReady = true;
                        Log.i(DTAG, "clip ready");
                    }
                });
                m.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                    @Override
                    public boolean onError(MediaPlayer x, int what, int extra) {
                        Log.w(DTAG, "player error " + what + "/" + extra);
                        dReady = false;
                        return true;
                    }
                });
                m.prepareAsync();
                mp = m;
            } catch (Exception e) {
                Log.w(DTAG, "clip unavailable: " + e);
            }
        }
    }

    private void releasePlayer() {
        synchronized (dLock) {
            dReady = false;
            dState = 0;
            dFadeV = 0f;
            if (mp != null) {
                try {
                    mp.release();
                } catch (RuntimeException e) {
                    // already gone
                }
                mp = null;
            }
        }
    }

    private void playerRun(boolean on) {
        synchronized (dLock) {
            if (mp == null || !dReady) return;
            try {
                if (on) mp.start();
                else mp.pause();
            } catch (RuntimeException e) {
                Log.w(DTAG, "player: " + e);
            }
        }
    }

    private void playerSpeed(float sp) {
        synchronized (dLock) {
            if (mp == null || !dReady) return;
            try {
                mp.setPlaybackParams(new PlaybackParams().setSpeed(sp));
            } catch (RuntimeException e) {
                // keep the current speed
            }
        }
    }

    // ---- when the dancer appears: only on the listed songs, on choruses and other loud sections

    private void dancerStep(float dt, float fps) {
        songT += dt;
        boolean loud = (ener > 0.20f && ener > slowE * 1.10f) || ener > 0.34f;
        if (loud && section != 3) hiE += dt;
        else hiE = Math.max(0f, hiE - dt * 2f);
        switch (dState) {
            case 0:
                if (dReady && (dSong || dForce) && songT >= 20f && time >= dRestUntil && time - dLastEnd >= 12f && hiE >= 1.5f) {
                    dState = 1;
                    dWait = 0f;
                }
                break;
            case 1:
                dWait += dt;
                if (hiE < 0.8f) dState = 0;
                else if (barStart || (!locked && kickHit && dWait > 0.5f) || dWait > 4f) beginDancer(fps);
                break;
            case 2: {
                dFadeV = Math.min(1f, dFadeV + dt / 0.8f);
                float len = time - dStartT;
                if (ener < slowE * 0.85f || ener < 0.12f) dLow += dt;
                else dLow = 0f;
                boolean sectionEnd = len >= 12f && dLow > 1.5f;
                if (len >= dDur || len >= 30f || sectionEnd) {
                    dState = 3;
                    Log.i(DTAG, "end " + (sectionEnd ? "section" : "time") + " after " + Math.round(len) + "s fps=" + Math.round(fps));
                }
                dSpeedT += dt;
                if (dSpeedT > 1f) {
                    dSpeedT = 0f;
                    Tempo tp = pulse.tempo();
                    float bpm = tp.conf() > 0.3f ? (float) tp.bpm() : 120f;
                    float barMs = 240000f / Math.max(60f, bpm);
                    float bars = Math.max(1f, Math.round(14000f / barMs)); // the 14 s clip spans whole bars, so its loop meets a bar line
                    playerSpeed(Math.max(0.85f, Math.min(1.15f, 14000f / (bars * barMs))));
                    if (((int) (time)) % 5 == 0) {
                        Log.i(DTAG, "status copies=" + Math.min(dN, dMax) + " glow=" + dGlow + " fps=" + Math.round(fps)
                            + " avg=" + Math.round(avgLum * 100) + "% gain=" + Math.round(gain * 100) + "%");
                    }
                }
                if (fps < 30f) dGovT += dt;
                else dGovT = Math.max(0f, dGovT - dt);
                if (dGovT > 2f) {
                    dGovT = 0f;
                    if (dMax > 3) dMax = 3;
                    else if (dMax > 1) dMax = 1;
                    else if (dGlow) dGlow = false;
                    else {
                        dState = 3;
                        dRestUntil = time + 120f;
                        Log.i(DTAG, "end early: fps " + Math.round(fps) + ", resting 2 min");
                    }
                    Log.i(DTAG, "slow: copies=" + Math.min(dN, dMax) + " glow=" + dGlow + " fps=" + Math.round(fps));
                }
                break;
            }
            default:
                dFadeV -= dt / 0.8f;
                if (dFadeV <= 0f) {
                    dFadeV = 0f;
                    dState = 0;
                    dCount++;
                    dApp++;
                    dLastEnd = time;
                    playerRun(false);
                }
                break;
        }
    }

    private void beginDancer(float fps) {
        dStyleName = styleOrd[dApp % 5];
        if (dStyleName == lastStyleUsed) dStyleName = styleOrd[(dApp + 1) % 5];
        dArr = arrOrd[dApp % 5];
        if (dArr == lastArrUsed) dArr = arrOrd[(dApp + 1) % 5];
        lastStyleUsed = dStyleName;
        lastArrUsed = dArr;
        dStyle = dArr == 3 && dStyleName == 0 ? 1 : dStyleName; // a line in different colours needs a tint
        buildArrangement();
        dMax = 5;
        dGlow = true;
        dGovT = 0f;
        dLow = 0f;
        dSpeedT = 1f;
        dDur = 12f + rnd.nextFloat() * 18f;
        dStartT = time;
        dEndAt = time + dDur;
        dFadeV = 0f;
        dState = 2;
        playerStart();
        String[] sn = {"white", "tint", "gradient", "outline", "split"};
        String[] an = {"single", "mirrored", "trio", "line", "echo"};
        Log.i(DTAG, "start style=" + sn[dStyleName] + (dStyle != dStyleName ? "(tint)" : "") + " arrangement=" + an[dArr] + " copies=" + dN
            + " dur=" + Math.round(dDur) + "s #" + (dApp + 1) + " song=\"" + songKey + "\" fps=" + Math.round(fps));
    }

    private void playerStart() {
        synchronized (dLock) {
            if (mp == null || !dReady) return;
            try {
                mp.seekTo(0); // the clip starts on the bar line it was triggered on
                mp.start();
            } catch (RuntimeException e) {
                Log.w(DTAG, "player: " + e);
            }
        }
    }

    /** An opaque dark disc the size of the planet, so the ring, moons and stars behind it never show through. */
    private void drawDisc(float alpha) {
        float cz = 6.5f + 0.4f * (float) Math.sin(time * 0.2f);
        float rad = 1.6f * (1f + bass * 0.25f + kickEnv * 0.07f);
        float rr = rad / (float) Math.sqrt(cz * cz - rad * rad) / TAN_H * (1f + kickEnv * 0.03f);
        GLES20.glUseProgram(discProg);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, discVbo);
        GLES20.glEnableVertexAttribArray(0);
        GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 0, 0);
        GLES20.glUniform2f(dC, 0f, 0f);
        GLES20.glUniform2f(dR, rr / aspect, rr);
        GLES20.glUniform4f(dCol, 0.004f, 0.005f, 0.009f, alpha);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, DISC_N + 2);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE);
        GLES20.glUseProgram(cp.id);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo);
        GLES20.glVertexAttribPointer(0, 4, GLES20.GL_FLOAT, false, 0, 0);
    }

    // ---- the song's palette

    private static float angDist(float a, float b) {
        float d = Math.abs(a - b) % 360f;
        return d > 180f ? 360f - d : d;
    }

    private static float[] bank(String g) {
        switch (g) {
            case "hiphop": return new float[] {38f, 355f, 275f};
            case "piano": return new float[] {175f, 345f, 260f};
            case "country": return new float[] {35f, 200f, 100f};
            case "edm": return new float[] {185f, 305f};
            case "rock": return new float[] {350f, 22f, 210f};
            case "rnb": return new float[] {300f, 335f, 40f};
            case "pop": return new float[] {330f, 180f, 55f};
            case "jazz": return new float[] {30f, 225f, 345f};
            case "reggae": return new float[] {120f, 48f, 6f};
            case "gospel": return new float[] {45f, 200f, 28f};
            default: return null;
        }
    }

    private static final String[] ARTISTS = {"michael jackson", "bob marley", "drake", "queen", "kendrick", "taylor swift", "pink floyd", "ac/dc", "prince"};
    private static final float[] ARTIST_HUE = {355f, 115f, 42f, 350f, 20f, 285f, 215f, 8f, 290f};

    private String artistLower() {
        int i = songKey.indexOf('|');
        return (i < 0 ? songKey : songKey.substring(0, i)).toLowerCase();
    }

    private String titleLower() {
        int i = songKey.indexOf('|');
        return (i < 0 ? "" : songKey.substring(i + 1)).toLowerCase();
    }

    /** Cover art first, else the artist's theme, else the genre bank; the song seed always nudges it. */
    private float desiredHue(Random r) {
        float want = Float.NaN;
        String src = "seed";
        int[] cv = cover;
        if (cv != null) {
            float best = -1f;
            for (int c : cv) {
                Color.colorToHSV(c | 0xFF000000, hsvTmp);
                float sc = hsvTmp[1] * (0.4f + hsvTmp[2]);
                if (hsvTmp[1] > 0.18f && sc > best) {
                    best = sc;
                    want = hsvTmp[0];
                    src = "cover";
                }
            }
        }
        if (Float.isNaN(want)) {
            String a = artistLower();
            for (int i = 0; i < ARTISTS.length; i++) if (a.contains(ARTISTS[i])) {
                want = ARTIST_HUE[i];
                src = "artist";
            }
        }
        if (Float.isNaN(want)) {
            float[] bk = bank(genre);
            if (bk != null) {
                want = bk[(int) (r.nextFloat() * bk.length) % bk.length];
                src = "genre";
            } else {
                want = r.nextFloat() * 360f;
            }
        }
        palSrc = src;
        return (want + (r.nextFloat() - 0.5f) * 48f + 720f) % 360f;
    }

    /** The nearest hue to want that is at least 60 degrees from the last song and 20 from the nine before it. */
    private float pickHue(float want, boolean replaceLast) {
        int start = replaceLast ? 1 : 0;
        for (int pass = 0; pass < 3; pass++) {
            float others = pass == 0 ? 20f : pass == 1 ? 10f : 0f;
            for (int d = 0; d <= 180; d += 5) {
                for (int sg = -1; sg <= 1; sg += 2) {
                    float h = (want + sg * d + 720f) % 360f;
                    boolean ok = !(start < hueN && angDist(h, hueHist[start]) < 60f);
                    for (int i = start + 1; ok && i < hueN; i++) if (angDist(h, hueHist[i]) < others) ok = false;
                    if (ok) return h;
                    if (d == 0) break;
                }
            }
        }
        return want;
    }

    private void commitHue(float h, boolean replaceLast) {
        if (!replaceLast) {
            for (int i = Math.min(hueN, 9); i > 0; i--) hueHist[i] = hueHist[i - 1];
            hueN = Math.min(10, hueN + 1);
        }
        hueHist[0] = h;
    }

    private void moodColors() {
        switch (genre) {
            case "piano": palSat = 0.62f; palVal = 0.78f; break;
            case "edm": palSat = 0.78f; palVal = 0.84f; break;
            case "rock": palSat = 0.72f; palVal = 0.80f; break;
            case "hiphop": palSat = 0.74f; palVal = 0.82f; break;
            default: palSat = 0.70f; palVal = 0.80f; break;
        }
    }

    /** The cover art arrived (or the genre did) after the plan: pick the hue again, and the palette glides there. */
    private void rehue() {
        Random r = new Random(songKey.hashCode() * 131L + 5);
        float h = pickHue(desiredHue(r), hueCommitted);
        commitHue(h, hueCommitted);
        hueCommitted = true;
        hueTarget = h;
        Log.i(TAG, "palette main=" + Math.round(h) + " src=" + palSrc + " genre=" + genre);
    }

    private void put(float[] o, int at, float h, float sat, float val) {
        hsvTmp[0] = (h % 360f + 360f) % 360f;
        hsvTmp[1] = Math.min(0.58f, sat);
        hsvTmp[2] = Math.min(0.76f, val);
        int col = Color.HSVToColor(hsvTmp);
        o[at] = Color.red(col) / 255f;
        o[at + 1] = Color.green(col) / 255f;
        o[at + 2] = Color.blue(col) / 255f;
    }

    /** Main, second and accent colour of a scene: analogous, complementary or triad around the song's hue. */
    private float[] colorsFor(int scene) {
        int si = scene < 0 ? 0 : scene < CANVAS0 ? Math.min(scene, NCORE + NNEW - 1) : NCORE + NNEW + (scene - CANVAS0) % CANVAS.length;
        float[] o = colBuf[si];
        int base = scene >= 0 && scene < SCENE_HARM.length ? SCENE_HARM[scene] : Math.abs(scene) % 3;
        int hm = (base + harmSeed) % 3;
        float h = hueShow;
        float h1 = hm == 0 ? h + 28f : hm == 1 ? h + 165f : h + 120f;
        float h2 = hm == 0 ? h - 22f : hm == 1 ? h + 30f : h + 240f;
        put(o, 0, h, palSat, palVal);
        put(o, 3, h1, palSat, palVal - 0.02f);
        put(o, 6, h2, palSat * 0.78f, palVal + 0.08f);
        return o;
    }

    // ---- director ----

    /** One line for the debug overlay (hold OK for 3 s): family and preset, beat confidence, bar, fps. */
    String debugLine() {
        Tempo t = pulse.tempo();
        int pv = planIdx >= 0 && planIdx < planVar.length ? planVar[planIdx] : 0;
        return "scene=" + sceneName(Math.max(0, cur)) + " family=" + family(Math.max(0, cur)) + " preset=" + pv
            + " res=" + surfW + "x" + surfH + "\nfps=" + Math.round(1f / Math.max(0.004f, frameAvg))
            + " beatConf=" + Math.round(t.conf() * 100) + "% bpm=" + Math.round(t.bpm())
            + " bar=" + t.bars(SystemClock.uptimeMillis()) + " since=" + Math.round(since) + "s"
            + (cur == CINEMA ? "\n" + cinema.debug(since, cinemaTarget) : "")
            + "\nlights=" + Lights.get(view.getContext()).writeRate() + " writes/s";
    }

    private void step(float dt) {
        if (pinReq) {
            pinReq = false;
            beginScene("pin");
        }
        if (pinId >= 0 && SystemClock.uptimeMillis() > pinUntil) {
            pinId = -1;
            beginScene("unpin");
        }
        if (badScene) {
            badScene = false;
            Log.w(TAG, "scene " + sceneName(cur) + " could not be built: next scene");
            beginScene("build-failed");
        }
        // quieter passages run the scenes slower: loudness against a slowly falling ceiling (Viz Lab: motion did not follow the music's dynamics)
        peakE = Math.max(peakE * (float) Math.exp(-dt / 60f), fastE);
        loudK = Math.max(0.35f, Math.min(1f, fastE / Math.max(0.15f, peakE)));
        time += dt * (0.4f + 0.6f * loudK);
        frameAvg += (dt - frameAvg) * 0.05f;
        float fps = 1f / Math.max(0.004f, frameAvg);

        float b = pulse.bass(), m = pulse.mid(), h = pulse.high(), en = pulse.ener();
        float k = Math.min(1f, dt * 8f);
        bass += (b - bass) * k;
        mid += (m - mid) * k * 0.5f;
        high += (h - high) * k;
        ener += (en - ener) * k * 0.5f;
        Lights.setAccent(hueShow, palSat);
        impactStep(dt, en);
        beatEngine(dt, b);
        cinKick = kickEnv > 0.9f && cinPrevK <= 0.9f;
        cinPrevK = kickEnv;
        cinSnare = snareEnv > 0.9f && cinPrevS <= 0.9f;
        cinPrevS = snareEnv;
        cinDown = false;
        if (cinKick) {
            Tempo ct = pulse.tempo();
            cinDown = ct.conf() >= 0.5f && ct.beatInBar(SystemClock.uptimeMillis()) == 0;
        }
        if (!lowRes && fps < 48f && cur != CINEMA) {
            if (++lowCount > 420) {
                lowRes = true;
                Log.w(TAG, "fps under 48 for 7 s at 1080p: surface back to 960x540");
                view.post(new Runnable() {
                    @Override
                    public void run() {
                        view.getHolder().setFixedSize(960, 540);
                    }
                });
            }
        } else if (fps >= 52f) {
            lowCount = 0;
        }
        for (int i = 0; i < 4; i++) if (age[i] >= 0f) {
            age[i] += dt;
            if (age[i] > 3f) age[i] = -1f;
        }
        bloom = Math.max(0f, bloom - dt * 1.4f);
        dist += (dt * (1.2f + ener * 1.6f) + kickEnv * dt * 3f) * 0.85f;
        drift += dt * (0.01f + mid * 0.03f);
        if (drift > 0.3f) drift -= 0.3f;

        // sections from the loudness trend: a surge is a drop, a sag is a break
        fastE += (en - fastE) * Math.min(1f, dt / 0.6f);
        slowE += (en - slowE) * Math.min(1f, dt / 10f);
        int cand = fastE > slowE * 1.3f + 0.04f ? 2 : fastE < slowE * 0.65f ? 3 : 0;
        boolean changed = false;
        if (cand == section) {
            secHold = 0f;
        } else {
            if (cand != secCand) {
                secCand = cand;
                secHold = 0f;
            }
            secHold += dt;
            if (secHold > 0.5f) {
                section = cand;
                secHold = 0f;
                changed = true;
                long now = SystemClock.uptimeMillis();
                if (section == 2 && now - lastBloomMs > 8000) {
                    lastBloomMs = now;
                    bloom = 1f;
                    if (now - lastBrightMs > 500) {
                        lastBrightMs = now;
                        kickB = 1f;
                    }
                }
            }
        }

        // fps governor: 12k -> 8k -> 5k points, and back when there is room
        govT += dt;
        if (govT > 3f) {
            govT = 0f;
            if (fps < 30f && level < 2) level++;
            else if (fps < 50f && level < 0) level++;
            else if (fps > 57f && level > -1 && level < 1) level--;
            else if (fps > 56f && level > 0) level--;
            count = level < 0 ? 16000 : level == 0 ? 12000 : level == 1 ? 8000 : 5000;
        }

        float dh = ((hueTarget - hueShow + 540f) % 360f) - 180f;
        hueShow = (hueShow + Math.max(-120f * dt, Math.min(120f * dt, dh)) + 360f) % 360f;
        if (coverNew) {
            coverNew = false;
            if (planAtMs > 0 && SystemClock.uptimeMillis() - planAtMs < 40000) rehue();
        }
        sceneFpsSum += fps;
        sceneFpsN++;
        sceneFpsMin = Math.min(sceneFpsMin, fps);
        genreT += dt;
        GenreSource gs0 = gsrc;
        if (gs0 != null && genreT > 2f && planAtMs > 0 && SystemClock.uptimeMillis() - planAtMs < 40000) {
            genreT = 0f;
            String g0 = gs0.genre();
            if (g0 != null && !g0.equals(genre)) {
                genre = g0;
                moodColors();
                if (!"cover".equals(palSrc) && !"artist".equals(palSrc)) rehue();
                else Log.i(TAG, "genre=" + genre);
                if (forced == -1 && !genreReplanned && since < 30f && !"unknown".equals(genre)) {
                    genreReplanned = true;
                    replanForGenre();
                }
            }
        }
        // genre lookups can be slow or empty: a song the note estimator hears as piano gets the piano playlist (note-world 3x)
        if (forced == -1 && !genreReplanned && !"piano".equals(genre) && since < 60f
                && pulse.notes.pianoLikely(SystemClock.uptimeMillis(), 6000)) {
            genre = "piano";
            genreReplanned = true;
            moodColors();
            Log.i(TAG, "piano heard: genre=piano");
            replanForGenre();
        }
        dancerStep(dt, fps);
        since += dt;
        if (cur != CINEMA || cinema.curReady()) fade = Math.min(1f, fade + dt / FADE); // a clip that is still preparing holds the crossfade
        // bar clock: scene changes wait for the next bar downbeat; the mandala reverses every 8th bar (phrase)
        Tempo bt = pulse.tempo();
        boolean confident = bt.conf() >= 0.5f;
        long barNow = bt.bars(SystemClock.uptimeMillis());
        boolean barNew = barNow != lastBarN && lastBarN != -1;
        if (barNow != lastBarN) {
            if (confident && Math.floorMod(barNow, 8L) == 0) mdir = -mdir;
            lastBarN = barNow;
        }
        if (!confident) mdir = Math.sin(time * 0.09f) >= 0 ? 1f : -1f;
        mease += (mdir - mease) * Math.min(1f, dt * 3f); // ~1 s ease through zero
        mrot += mease * dt * 0.35f;
        boolean edge = confident ? barNew : kickHit;
        int sk = skipReq;
        // artist and title arrive separately: plan once the key has been still for a moment
        if (songDirty && SystemClock.uptimeMillis() - keyAt > 1200) {
            songDirty = false;
            newPlan();
            beginScene("song");
        } else if (cur == CINEMA && !cinema.curReady() && since > 4f) {
            beginScene("cinema-wait"); // the clip never became ready: move on
        } else if (sk != 0) {
            skipReq = 0;
            beginScene("skip");
        } else if (pinId >= 0) {
            // Viz Lab holds this scene
        } else if (forced == -2 ? since >= dwellTarget : (since >= (cur == CINEMA ? cinemaTarget : MIN_DWELL) && (edge || since >= (cur == CINEMA ? cinemaTarget + 8f : MAX_DWELL)))) {
            beginScene(changed ? "section" : "dwell");
        }
        kickHit = false;
        long nowMs = SystemClock.uptimeMillis();
        syncLog(nowMs, false);
        if (nowMs - lastLogMs > 10000) {
            lastLogMs = nowMs;
            Log.i(TAG, "status scene=" + sceneName(Math.max(0, cur)) + " hue=" + Math.round(hueShow) + " points=" + count
                + " section=" + section + " fps=" + Math.round(fps) + " avg=" + Math.round(avgLum * 1000) / 10f + "% gain=" + Math.round(gain * 100) + "%");
        }
    }

    private static float median(float[] a, int n) {
        if (n == 0) return 0f;
        float[] c = new float[n];
        System.arraycopy(a, 0, c, 0, n);
        java.util.Arrays.sort(c);
        return c[n / 2];
    }

    /**
     * Pulses come from the PLL's predicted beat times (held back by the sync offset), per band: the kick on every
     * beat (bigger on bar and phrase starts), the snare on the backbeat or its own onsets, hats as quick ticks.
     * When the clock is not confident the onsets themselves fire the pulses, queued by the same offset.
     */
    private void beatEngine(float dt, float b) {
        long nowMs = SystemClock.uptimeMillis();
        Tempo tp = pulse.tempo();
        float conf = tp.conf();
        confSum += conf;
        confN++;
        int off = baseSync + userDelay - RENDER_LAT;
        bassFast += (bass - bassFast) * Math.min(1f, dt * 5f);
        bassSlow += (bass - bassSlow) * Math.min(1f, dt * 0.16f);

        // onsets: the capture's kick detector, plus rising edges of the low band over an adaptive threshold
        boolean onset = false;
        int kc = pulse.kicks();
        if (lastKicks < 0) lastKicks = kc;
        if (kc != lastKicks) {
            lastKicks = kc;
            onset = true;
        }
        float fl = Math.max(0f, b - bPrev);
        bPrev = b;
        if (fl > 0f) fluxAvg += (fl - fluxAvg) * Math.min(1f, dt * 2f);
        if (fl > fluxAvg * 2.6f + 0.03f && nowMs - lastOnsMs > 140) onset = true;
        if (onset && nowMs - lastOnsMs > 110) {
            VizLab.ev(nowMs, "kick", Math.min(1f, b), "actual", sceneName(Math.max(0, cur)), curVar);
            lastOnsMs = nowMs;
            onsT[onsN++ & 511] = nowMs;
        }

        locked = conf >= 0.5f && tp.anchor() != 0;
        boolean beat = false;
        int bib = -1;
        long bars = 0;
        float preT = 0f;
        if (locked) {
            long at = nowMs - off;
            long bi = tp.beats(at);
            if (lockedPrev && bi != lastBeatIdx) {
                beat = true;
                bib = tp.beatInBar(at);
                bars = tp.bars(at);
            }
            lastBeatIdx = bi;
            lockedPrev = true;
            if (tp.beatInBar(at) == 3) {
                float ph = tp.phase(at);
                float x = Math.max(0f, Math.min(1f, (ph - 0.5f) / 0.5f));
                preT = x * x * (3f - 2f * x); // the beat before a downbeat: a slight pull back, then the hit
            }
        } else {
            lockedPrev = false;
            if (onset && nowMs - lastQueueMs > 110) {
                lastQueueMs = nowMs;
                bq[qn++ & 15] = nowMs + Math.max(0, off);
            }
            for (int i = 0; i < 16; i++) {
                if (bq[i] != 0 && nowMs >= bq[i]) {
                    bq[i] = 0;
                    if (nowMs - lastFireMs > 90) beat = true;
                }
            }
        }
        preV += (preT - preV) * Math.min(1f, dt * 12f);
        barStart = beat && bib == 0;
        if (beat) {
            VizLab.ev(nowMs, "beat" + (bib >= 0 ? bib : 9), 1f, locked ? "predicted" : "queued", sceneName(Math.max(0, cur)), curVar);
            onBeat(bib, bars, nowMs);
            lastFireMs = nowMs;
            fireT[fireN++ & 511] = nowMs;
            markerEnv = 1f;
        }

        // snare: the backbeat when the clock is locked and a snare is actually there, else its own onsets
        int sc = pulse.snares();
        if (lastSnares < 0) lastSnares = sc;
        boolean snRaw = sc != lastSnares;
        if (snRaw) {
            VizLab.ev(nowMs, "snare", 1f, "actual", sceneName(Math.max(0, cur)), curVar);
            lastSnares = sc;
            lastSnareSeen = nowMs;
        }
        boolean snare = locked ? (beat && (bib == 1 || bib == 3) && nowMs - lastSnareSeen < 4000) : (snRaw && nowMs - lastSnareMs > 100);
        if (snare) {
            lastSnareMs = nowMs;
            snareEnv = 1f;
            snareDir = -snareDir;
            offX += snareDir * 0.022f;
            hatEnv = Math.max(hatEnv, 0.8f);
        }
        int hc = pulse.hats();
        if (lastHats < 0) lastHats = hc;
        if (hc != lastHats) {
            VizLab.ev(nowMs, "hat", 0.6f, "actual", sceneName(Math.max(0, cur)), curVar);
            lastHats = hc;
            hatEnv = Math.max(hatEnv, 0.6f);
        }

        kickEnv *= (float) Math.exp(-dt * 6f);
        punchEnv *= (float) Math.exp(-dt * 9f);
        burstEnv *= (float) Math.exp(-dt * 7f);
        kickB *= (float) Math.exp(-dt * 4f);
        snareEnv *= (float) Math.exp(-dt * 8f);
        hatEnv *= (float) Math.exp(-dt * 14f);
        markerEnv *= (float) Math.exp(-dt * 9f);
        offX *= (float) Math.exp(-dt * 6f);
        offY *= (float) Math.exp(-dt * 6f);
        ringGapB += dt;
        spin += dt * 0.5f * (0.6f + ener) + kickEnv * dt * 2f;
    }

    /** One beat: motion every time, bigger on the first beat of a bar and of a phrase; brightness only on downbeats. */
    private void onBeat(int bib, long bars, long nowMs) {
        float dyn = Math.max(0.7f, Math.min(1.25f, 0.55f + 1.1f * bassFast / Math.max(0.12f, bassSlow)));
        boolean down = bib == 0;
        boolean phrase = down && (bars & 3) == 0;
        float amp = bib < 0 ? 1f : down ? (phrase ? 1.7f : 1.35f) : bib == 2 ? 1.05f : 0.9f;
        float a = amp * dyn;
        kickEnv = Math.max(kickEnv, Math.min(1.4f, a));
        punchEnv = Math.max(punchEnv, Math.max(0.035f, Math.min(0.08f, 0.04f * a)));
        burstEnv = Math.max(burstEnv, Math.min(1.3f, 0.5f * a));
        offX += (rnd.nextFloat() - 0.5f) * 0.012f * a;
        offY += (rnd.nextFloat() - 0.5f) * 0.012f * a;
        if (ringGapB > 0.25f) {
            ringGapB = 0f;
            int r = ringNext++ % Math.max(1, ringN);
            age[r] = 0f;
            ripX[r] = (rnd.nextFloat() - 0.5f) * 14f;
            ripZ[r] = 3f + rnd.nextFloat() * 12f;
        }
        if (down && nowMs - lastBrightMs > 500) {
            lastBrightMs = nowMs;
            kickB = phrase ? 1f : 0.8f;
        }
        kickHit = true;
    }

    /** Every ten seconds (and at each song's end): how many of the real kick onsets met a pulse, and how far off. */
    private void syncLog(long nowMs, boolean force) {
        if (!force && nowMs - syncLogMs < 10000) return;
        syncLogMs = nowMs;
        int off = baseSync + userDelay - RENDER_LAT;
        int n = 0, hit = 0, ln = 0, gn = 0;
        float[] lags = new float[512], grid = new float[512];
        Tempo tp = pulse.tempo();
        double per = tp.period();
        long anc = tp.anchor();
        for (int i = 0; i < Math.min(onsN, 512); i++) {
            long t = onsT[(onsN - 1 - i) & 511];
            if (nowMs - t > 60000) continue;
            n++;
            long bd = Long.MAX_VALUE;
            for (int j = 0; j < Math.min(fireN, 512); j++) {
                long d = fireT[(fireN - 1 - j) & 511] - (t + off);
                if (Math.abs(d) < Math.abs(bd)) bd = d;
            }
            if (bd != Long.MAX_VALUE && Math.abs(bd) <= 70) {
                hit++;
                lags[ln++] = bd;
            }
            if (anc != 0 && per > 100) {
                double ph = ((t - anc) % per + per) % per;
                grid[gn++] = (float) (ph > per / 2 ? ph - per : ph);
            }
        }
        if (n == 0 && !force) return;
        Log.i("dumbsync", (force ? "song-end " : "") + "mode=" + (locked ? "PLL" : "onset") + " conf=" + Math.round(100f * (confN == 0 ? 0f : confSum / confN))
            + "% bpm=" + Math.round(tp.bpm()) + " onsets=" + n + " pulses=" + Math.min(fireN, 512) + " hit=" + (n == 0 ? 0 : Math.round(100f * hit / n))
            + "% lag=" + Math.round(median(lags, ln)) + "ms gridErr=" + Math.round(median(grid, gn)) + "ms offset=" + off + "ms delay=" + userDelay
            + "ms song=\"" + songKey + "\"");
    }

    private void newPlan() {
        Random r = new Random(songKey.hashCode() * 31L + 17);
        if (forced == -2) {
            planLen = 1;
            plan[0] = 0;
            planVar[0] = 0;
            planFg[0] = -1;
        } else if (forced >= 0) {
            planLen = 8;
            for (int i = 0; i < planLen; i++) {
                plan[i] = forced;
                planVar[i] = (i + (int) (varA * 4f)) & 3;
                planFg[i] = -1;
            }
        } else {
            buildPlaylist(r);
        }
        syncLog(SystemClock.uptimeMillis(), true);
        boolean sameSong = songKey.equals(lastPlanKey);
        lastPlanKey = songKey;
        Random sr = new Random(songKey.hashCode() * 7919L + 3);
        sigSize = 0.8f + sr.nextFloat() * 0.55f;
        sigDens = 0.65f + sr.nextFloat() * 0.35f;
        sigTrail = 0.7f + sr.nextFloat() * 0.9f;
        sigSym = sr.nextFloat() < 0.35f ? 1f : 0f;
        sigWob = sr.nextInt(3);
        camAmp = 0.006f + sr.nextFloat() * 0.03f;
        camFreq = 0.04f + sr.nextFloat() * 0.10f;
        camPh = sr.nextFloat() * 6.2832f;
        ringN = 2 + sr.nextInt(3);
        harmSeed = sr.nextInt(3);
        GenreSource gs = gsrc;
        String g = gs == null ? null : gs.genre();
        genre = g == null ? "unknown" : g;
        moodColors();
        if (!sameSong || !hueCommitted) {
            float h = pickHue(desiredHue(sr), false);
            commitHue(h, false);
            hueCommitted = true;
            hueTarget = h;
            hueShow = h;
        }
        planAtMs = SystemClock.uptimeMillis();
        fireN = 0;
        onsN = 0;
        confSum = 0f;
        confN = 0;
        // the dancer is for the songs on the list only
        dSong = false;
        for (String pair : dancerSongs.split(";")) {
            String[] pt = pair.split("\\|");
            if (pt.length >= 2 && !pt[0].trim().isEmpty() && !pt[1].trim().isEmpty()
                && artistLower().contains(pt[0].trim().toLowerCase()) && titleLower().contains(pt[1].trim().toLowerCase())) dSong = true;
        }
        for (int i = 0; i < 5; i++) {
            styleOrd[i] = i;
            arrOrd[i] = i;
        }
        for (int i = 4; i > 0; i--) {
            int j = sr.nextInt(i + 1), t = styleOrd[i];
            styleOrd[i] = styleOrd[j];
            styleOrd[j] = t;
            j = sr.nextInt(i + 1);
            t = arrOrd[i];
            arrOrd[i] = arrOrd[j];
            arrOrd[j] = t;
        }
        dApp = 0;
        genreReplanned = false;
        dCount = 0;
        songT = 0f;
        hiE = 0f;
        if (dState == 1) dState = 0;
        if (dState == 2) dState = 3;
        int hsh = songKey.hashCode();
        varA = (hsh & 1023) / 1023f;
        varB = ((hsh >> 10) & 1023) / 1023f;
        planIdx = 0;
        cur = -1;
        prev = -1;
        Log.i(TAG, "song=\"" + songKey + "\" rooms=" + roomList() + " style=" + (forced >= 0 ? sceneName(forced) : forced == -2 ? "gallery" : "glowroom")
            + " main=" + Math.round(hueTarget) + " src=" + palSrc + " genre=" + genre + " harm=" + harmSeed
            + " sig[size=" + Math.round(sigSize * 100) / 100f + " dens=" + Math.round(sigDens * 100) / 100f + " trail=" + Math.round(sigTrail * 100) / 100f
            + " sym=" + (int) sigSym + " wob=" + sigWob + " cam=" + Math.round(camAmp * 1000) / 1000f + "@" + Math.round(camFreq * 100) / 100f + " rings=" + ringN + "]"
            + " dancer=" + (dSong ? "yes" : "no"));
        Log.i("dumbvariety", "song=\"" + songKey + "\" genre=" + genre + " playlist=" + roomList());
    }

    /** 6-10 scenes of different types, weighted by genre, least recently used first, the last 12 played excluded. */
    private void buildPlaylist(Random r) {
        int n = 6 + r.nextInt(5);
        int[] gl = null;
        for (String[] gl0 : GENRE_LISTS) {
            if (gl0[0].equals(genre)) {
                gl = new int[gl0.length - 1];
                for (int i = 1; i < gl0.length; i++) gl[i - 1] = idOf(gl0[i]);
            }
        }
        boolean[] inG = new boolean[128], taken = new boolean[128], recent = new boolean[128];
        if (gl != null) for (int id : gl) if (id >= 0) inG[id] = true;
        for (int i = 0; i < recentN; i++) recent[recentIds[i]] = true;
        int maxCore = Math.max(1, (int) (n * 0.4f));
        int famCap = (n + 2) / 3;
        int core = 0, pn = 0;
        int[] famN = new int[8], pick = new int[12];
        float[] w = new float[ALL.length];
        for (int step = 0; step < n; step++) {
            boolean surprise = gl != null && step < 2; // two picks from outside the genre's list
            for (int pass = 0; pass < 2; pass++) {
                float tot = 0f;
                for (int k = 0; k < ALL.length; k++) {
                    int id = ALL[k];
                    w[k] = 0f;
                    if (id == CINEMA) continue; // Cinema is added below, by its own share
                    if (taken[id] || (pass == 0 && recent[id])) continue;
                    if (id < NCORE && core >= maxCore) continue;
                    if (famN[family(id)] >= famCap) continue;
                    float base = gl == null ? 1f : surprise ? (inG[id] ? 0f : 1f) : (inG[id] ? 4f : 1f);
                    if (id >= CANVAS0) base *= 0.7f;
                    if (id >= CANVAS0 + 11) base *= "piano".equals(genre) ? 3f : 0.25f; // note-world: 3x for piano songs, rare otherwise
                    if (id >= NCORE + 18 && id < CANVAS0) base *= 3f; // the newest scenes come up more often
                    float agePart = Math.min(6f, (serial - lastUsed[id]) / 8f);
                    w[k] = base * (1f + agePart);
                    tot += w[k];
                }
                if (tot <= 0f) continue;
                float x = r.nextFloat() * tot;
                int ch = -1;
                for (int k = 0; k < ALL.length; k++) {
                    if (w[k] > 0f) ch = k;
                    x -= w[k];
                    if (x <= 0f && w[k] > 0f) break;
                }
                int id = ALL[ch];
                taken[id] = true;
                pick[pn++] = id;
                if (id < NCORE) core++;
                famN[family(id)]++;
                break;
            }
        }
        // Cinema: about a quarter of the scenes (half for piano songs) when the clip library has anything and the toggle is on
        if (cinemaOn() && CinemaLib.count(ctx) > 0 && pn > 1) {
            float share = "piano".equals(genre) ? 0.5f : 0.25f;
            int want = Math.max(1, Math.round(pn * share + (r.nextFloat() - 0.5f) * 0.8f));
            for (int q = 0; q < want; q++) {
                int at = r.nextInt(pn);
                int tries = 0;
                while (pick[at] == CINEMA && tries++ < 12) at = r.nextInt(pn);
                if (pick[at] != CINEMA && !(at == 0 && pn > 2 && q == 0 && r.nextBoolean())) pick[at] = CINEMA;
            }
        }
        // order them so the same family never plays twice in a row
        boolean[] used = new boolean[12];
        int lastFam = -1;
        planLen = 0;
        for (int s2 = 0; s2 < pn; s2++) {
            int cnt = 0;
            for (int i = 0; i < pn; i++) if (!used[i] && family(pick[i]) != lastFam) cnt++;
            int want = cnt == 0 ? -1 : r.nextInt(cnt);
            int chosen = -1;
            for (int i = 0; i < pn; i++) {
                if (used[i]) continue;
                if (cnt == 0 || family(pick[i]) != lastFam) {
                    if (want <= 0) {
                        chosen = i;
                        break;
                    }
                    want--;
                }
            }
            used[chosen] = true;
            plan[planLen] = pick[chosen];
            lastFam = family(pick[chosen]);
            planLen++;
        }
        int hs = songKey.hashCode();
        for (int i = 0; i < planLen; i++) {
            planVar[i] = ((hs ^ (plan[i] * 0x9E3779B1)) >>> 7) & 3;
            planFg[i] = -1;
            if (plan[i] < CANVAS0 && r.nextFloat() < 0.30f) { // a second, lighter layer from another type
                int tries = 0;
                while (tries++ < 20) {
                    int cand = r.nextInt(NCORE + NNEW);
                    if (cand != plan[i] && cand != 19 && cand != 20 && family(cand) != family(plan[i])) {
                        planFg[i] = cand;
                        break;
                    }
                }
            }
        }
    }

    /** The genre is only known a few seconds into a song: build the playlist again with its weights, keeping what is on screen. */
    private void replanForGenre() {
        int c = cur, cv = curVar, cf = curFg;
        buildPlaylist(new Random(songKey.hashCode() * 31L + 91));
        if (c >= 0 && planLen < 12) {
            for (int i = planLen; i > 0; i--) {
                plan[i] = plan[i - 1];
                planVar[i] = planVar[i - 1];
                planFg[i] = planFg[i - 1];
            }
            plan[0] = c;
            planVar[0] = cv;
            planFg[0] = cf;
            planLen++;
            planIdx = 1;
        } else {
            planIdx = 0;
        }
        Log.i("dumbvariety", "replan genre=" + genre + " playlist=" + roomList());
    }

    private String roomList() {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < planLen; i++) {
            sb.append(i > 0 ? "," : "").append(sceneName(plan[i])).append(':').append(planVar[i]);
            if (planFg[i] >= 0) sb.append('+').append(sceneName(planFg[i]));
        }
        return sb.append(']').toString();
    }

    private void beginScene(String why) {
        if (cur >= 0 && sceneFpsN > 0) {
            Log.i("dumbvariety", "end scene=" + sceneName(cur) + ":" + curVar + " fps avg=" + Math.round(sceneFpsSum / sceneFpsN) + " min=" + Math.round(sceneFpsMin));
        }
        sceneFpsSum = 0f;
        sceneFpsN = 0;
        sceneFpsMin = 999f;
        prev = cur;
        prevVar = curVar;
        if (pinId >= 0) {
            cur = pinId;
            curVar = pinVar;
            curFg = -1;
            dwellTarget = 999f;
        } else if (forced == -2) { // gallery: every scene and variant in turn
            int gv = galleryVars;
            int total = ALL.length * gv;
            int gi = galleryIdx++ % total;
            cur = ALL[gi / gv];
            curVar = gi % gv;
            curFg = -1;
            dwellTarget = gallerySecs;
        } else {
            int i = planIdx++ % planLen;
            cur = plan[i];
            curVar = planVar[i];
            curFg = planFg[i];
            curFgVar = (curVar + 1 + (int) (varB * 3f)) & 3;
            dwellTarget = 15f + rnd.nextFloat() * 15f;
        }
        if (prev == CINEMA && cur != CINEMA) cinema.leave();
        if (cur == CINEMA) {
            cinemaTarget = 12f + rnd.nextFloat() * 28f;
            float bp = pulse.tempo().bpm();
            CinemaLib.Clip cc = CinemaLib.pick(ctx, songKey, genre, bp, rnd);
            if (cc != null) {
                cinema.start(cc);
                Log.i("dumbcinema", "scene clip=" + cc.id + " tags=" + cc.tagText() + " target=" + Math.round(cinemaTarget) + "s");
            }
        }
        lastUsed[cur] = ++serial;
        if (curFg >= 0) lastUsed[curFg] = serial;
        for (int i = Math.min(recentN, 11); i > 0; i--) recentIds[i] = recentIds[i - 1];
        recentIds[0] = cur;
        recentN = Math.min(12, recentN + 1);
        CanvasListener cl = canvasL;
        if (cl != null) cl.style(cur >= CANVAS0 && cur < CANVAS0 + CANVAS.length ? CANVAS[cur - CANVAS0] : null);
        LabelListener ll = labelL;
        if (ll != null) ll.label(null); // the scene name goes to the log only, never on screen
        fade = prev < 0 ? 1f : 0f;
        since = 0f;
        Log.i("dumbvariety", "scene=" + sceneName(cur) + " variant=" + curVar + (curFg >= 0 ? " fg=" + sceneName(curFg) + ":" + curFgVar : "")
            + " family=" + family(cur) + " why=" + why + " dwell=" + Math.round(dwellTarget) + "s song=\"" + songKey + "\"");
    }
}
