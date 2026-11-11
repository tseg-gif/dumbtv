varying vec2 vUv;
uniform vec2 uRes;
uniform float uT, uRain, uSpeed, uSize, uBreath, uSpark, uBright, uThunder, uLite, uOrb, uHasVid;
#ifdef VID
uniform samplerExternalOES uTex;
uniform mat4 uST;
uniform vec2 uCover;
#endif
const float PI = 3.14159265;

float h11(float n) { vec3 p3 = fract(vec3(n) * 0.1031); p3 += dot(p3, p3.yzx + 33.33); return fract((p3.x + p3.y) * p3.z); }
float h21(vec2 p) { vec3 p3 = fract(vec3(p.xyx) * 0.1031); p3 += dot(p3, p3.yzx + 33.33); return fract((p3.x + p3.y) * p3.z); }
uniform sampler2D uNoise, uPhoto, uPhotoB, uRip;
uniform vec3 uCam;
// smooth value noise baked on the CPU (32 cells, tiling): one texture read instead of four hashes
float vn(vec2 p) { return texture2D(uNoise, p * (1.0 / 32.0)).r; }
float fbm(vec2 p) {
  float a = 0.5, s = 0.0;
  for (int i = 0; i < 3; i++) { s += a * vn(p); p = p * 2.03 + vec2(11.7, 5.3); a *= 0.5; }
  return s;
}

#ifdef VID
vec3 video(vec2 p) {
  vec2 uv = p / vec2(uRes.x / uRes.y, 1.0) + 0.5;
  uv = (uv - 0.5) * uCover + 0.5;
  uv = clamp(uv, 0.002, 0.998);
  uv = (uST * vec4(uv, 0.0, 1.0)).xy;
  return texture2D(uTex, uv).rgb;
}
#endif

// the backdrop: a photo (or a video frame) with a slow camera drift/zoom; blur 0..1 mixes in a soft copy
vec3 srcP(vec2 u) {
#ifdef VID
  return video((u - 0.5) * vec2(uRes.x / uRes.y, 1.0));
#else
  return texture2D(uPhoto, vec2(u.x, 1.0 - u.y)).rgb;
#endif
}
vec3 srcB(vec2 u) {
#ifdef VID
  return (srcP(u + vec2(0.012, 0.0)) + srcP(u - vec2(0.012, 0.0)) + srcP(u + vec2(0.0, 0.02)) + srcP(u - vec2(0.0, 0.02))) * 0.25;
#else
  return texture2D(uPhotoB, vec2(u.x, 1.0 - u.y)).rgb;
#endif
}
vec2 camUv(vec2 uv) { return clamp((uv - 0.5) / uCam.z + 0.5 + uCam.xy, 0.003, 0.997); }
vec3 photo(vec2 uv, float blur) {
  vec2 u = camUv(uv);
  vec3 a = srcP(u);
  return blur > 0.002 ? mix(a, srcB(u), min(blur, 1.0)) : a;
}

const vec3 WARM = vec3(1.0, 0.94, 0.84);

// the shared look of every pixel: warm, saturation capped at 0.65 (foliage stays lush, nothing neon), brightness capped
vec3 grade(vec3 c) {
  c = min(c * WARM, vec3(1.0));
  float mx = max(c.r, max(c.g, c.b)), mn = min(c.r, min(c.g, c.b));
  float s = (mx - mn) / max(mx, 0.001);
  if (s > 0.65) c = mix(vec3(mx), c, 0.65 / s);
  return c * uBright;
}
