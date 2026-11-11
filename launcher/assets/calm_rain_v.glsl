attribute vec4 aA;
attribute vec2 aC;
uniform float uT, uAspect, uSpd, uLen, uWid, uYTop, uYEnd, uDens, uGate, uSpan, uShim, uVib, uWind;
varying vec2 vV;
varying float vA;
void main() {
  float t = mod(uT, 240.0);
  float span = uYTop - uYEnd;
  float ph = fract(aA.y + t * uSpd * (0.7 + 0.6 * aA.z) / span);
  float y = uYTop - ph * span;
  float h2 = fract(aA.w * 17.0), h3 = fract(aA.w * 131.0);
  float sz = 0.55 + 0.9 * h2;            // random length
  float bri = 0.45 + 0.55 * h3;          // random brightness
  // slow wind sway of +-3 degrees plus a random lean per streak
  float sway = 0.052 * uWind * sin(t * 0.23 + 1.3 * sin(t * 0.07));
  float slope = 0.035 + sway + (h3 - 0.5) * 0.06 * uWind;
  float x0 = (aA.x - 0.5) * (uAspect + 0.2) * uSpan;
  float x = x0 + (uYTop - y) * slope;
  // clustered gusts: slow noise moves a band of denser rain across the picture
  float g = 0.5 + 0.5 * sin(x * 2.7 + t * 0.21 + 2.0 * sin(t * 0.06 + y * 1.3));
  g *= 0.6 + 0.4 * sin(x0 * 1.3 - t * 0.13 + 1.7);
  float thr = uDens * (0.30 + 1.35 * g);
  float vis = 1.0 - smoothstep(thr - 0.03, thr, aA.w);
  float gate = mix(1.0, smoothstep(-0.3, 0.7, sin(x * 7.0 + 1.3 + 0.4 * sin(t * 0.13)) + 0.5 * sin(x * 17.0 + t * 0.07 + aA.z * 3.0)), uGate);
  float fade = smoothstep(0.0, 0.05, ph) * (1.0 - smoothstep(0.88, 1.0, ph));
  float shim = 1.0 - uShim * (0.45 + 0.45 * sin(t * (2.0 + 4.0 * aA.z) + aA.w * 60.0));
  vec2 dir = normalize(vec2(slope, -1.0));
  vec2 perp = vec2(-dir.y, dir.x);
  vec2 head = vec2(x + uVib * uGate * sin(t * 41.0 + aA.x * 90.0), y);
  vec2 pos = head - dir * aC.y * uLen * sz + perp * aC.x * uWid * sz;
  vV = aC;
  float edge = 1.0 - smoothstep(0.7, 1.0, abs(x0) / (0.5 * (uAspect + 0.2) * uSpan)); // no hard side edge on a narrow column
  vA = vis * fade * bri * shim * edge * mix(1.0, 0.25 + 0.75 * gate, uGate);
  gl_Position = vec4(pos.x / (0.5 * uAspect), pos.y / 0.5, 0.0, 1.0);
  if (vA < 0.01) gl_Position = vec4(3.0, 3.0, 3.0, 1.0); // not shown: costs no fill
}
