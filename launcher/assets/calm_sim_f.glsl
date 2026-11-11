precision highp float;
varying vec2 vUv;
uniform sampler2D uPrev;
uniform vec4 uInj[4]; // x, y, amplitude, radius (sim uv); a polynomial falloff, exp() of large negatives misbehaves on this GPU
// height and velocity are each packed into two 8-bit channels (16 bit): this GPU's float targets lose small waves
float dec(vec2 t) { return (t.x + t.y / 255.0) * 2.0 - 1.0; }
vec2 enc(float x) {
  float a = clamp(x * 0.5 + 0.5, 0.0, 1.0) * 255.0, hi = floor(a);
  return vec2(hi / 255.0, a - hi);
}
float bump(vec4 j) {
  vec2 dd = vUv - j.xy;
  float rr = max(j.w, 0.001);
  float k = max(0.0, 1.0 - dot(dd, dd) / (rr * rr * 6.0));
  return j.z * k * k;
}
void main() {
  const float e = 1.0 / 256.0;
  vec4 c = texture2D(uPrev, vUv);
  float h0 = dec(c.rg), v0 = dec(c.ba);
  float l = dec(texture2D(uPrev, vUv - vec2(e, 0.0)).rg), r = dec(texture2D(uPrev, vUv + vec2(e, 0.0)).rg);
  float d = dec(texture2D(uPrev, vUv - vec2(0.0, e)).rg), u = dec(texture2D(uPrev, vUv + vec2(0.0, e)).rg);
  float v = (v0 + (l + r + d + u - 4.0 * h0) * 0.235) * 0.9925;  // damped 2D wave equation
  float h = (h0 + v) * 0.9995;
  h += bump(uInj[0]) + bump(uInj[1]) + bump(uInj[2]) + bump(uInj[3]);
  gl_FragColor = vec4(enc(h), enc(v));
}
