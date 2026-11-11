attribute vec4 aA;
attribute vec2 aB;
attribute vec2 aC;
uniform float uT, uAspect, uMode, uRMul, uTrail, uRainAmt, uSpeedMul;
varying vec2 vL;
varying float vR, vA, vTr;
void main() {
  float t = mod(uT, 240.0);
  float r = aA.w * uRMul;
  float grow = 1.0;
  float cx = (aA.x - 0.5) * uAspect;
  float cy, fade = 1.0;
  if (uMode < 0.5) {
    float ph = fract(aA.y + t * aA.z * uSpeedMul / 1.2 + 0.015 * sin(t * 0.5 + aB.y * 40.0));
    cy = 0.6 - ph * 1.2;
    grow = 0.78 + 0.4 * smoothstep(0.0, 0.5, ph); // beads up, then slides
    fade = smoothstep(0.0, 0.03, ph) * smoothstep(1.0, 0.97, ph);
  } else {
    cy = aA.y - 0.5;
    fade = 0.85 + 0.15 * sin(t * 0.3 + aB.y * 30.0);
  }
  r *= grow;
  float vis = 1.0 - smoothstep(uRainAmt - 0.04, uRainAmt, aB.x);
  float trail = uMode < 0.5 ? uTrail : 0.0;
  vec2 off = aC.y > 0.0 ? vec2(aC.x * r * (trail > 0.0 ? 0.45 : 1.3), r * 1.3 + trail * r) : vec2(aC.x * r * 1.3, -r * 1.3);
  vec2 pos = vec2(cx, cy) + off;
  vL = off / r;
  vR = r;
  vTr = trail;
  vA = vis * fade;
  gl_Position = vec4(pos.x / (0.5 * uAspect), pos.y / 0.5, 0.0, 1.0);
  if (vA < 0.01) gl_Position = vec4(3.0, 3.0, 3.0, 1.0); // not shown: costs no fill
}
