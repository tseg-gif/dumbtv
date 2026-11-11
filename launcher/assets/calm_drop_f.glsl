uniform sampler2D uBg;
varying vec2 vL;
varying float vR, vA, vTr;
vec3 base(vec2 uv) { return texture2D(uBg, uv).rgb; }
void main() {
  float d = length(vL * vec2(1.0, 0.9));
  float m = smoothstep(1.0, 0.72, d);
  vec2 suv = gl_FragCoord.xy / uRes;
  vec2 asp = vec2(uRes.x / uRes.y, 1.0);
  vec2 off = -vL * vR * 1.9 / asp * m; // inverted image, as through a water lens
  vec2 cu = clamp(suv + off, 0.002, 0.998);
  vec3 col = (base(cu) * 2.0 + base(clamp(cu + vec2(0.006, 0.004), 0.002, 0.998)) + base(clamp(cu - vec2(0.006, 0.004), 0.002, 0.998))) * 0.25 * 1.12;
  col *= 1.0 - 0.18 * smoothstep(0.6, 1.0, d) * m;
  float hl = smoothstep(0.38, 0.0, length(vL - vec2(-0.3, 0.35)));
  float rim = smoothstep(0.6, 1.0, d) * (1.0 - smoothstep(1.0, 1.08, d)) * 0.10;
  float spk = 0.55 + 0.7 * uSpark * (0.6 + 0.4 * sin(uT * 1.7 + vR * 3000.0));
  vec3 add = vec3(0.9, 1.0, 0.95) * (hl * spk * 0.9 + rim) * uBright * m;
  float tl = clamp((vL.y - 1.0) / max(vTr + 0.3, 0.3), 0.0, 1.0);
  float tr = step(1.0, vL.y) * smoothstep(0.22, 0.0, abs(vL.x) * (1.0 + 1.5 * tl)) * (1.0 - tl) * (1.0 - tl) * 0.14;
  add += vec3(0.9, 1.0, 0.95) * tr * uBright;
  gl_FragColor = vec4(col + add, clamp(max(m, tr * 6.0), 0.0, 1.0) * vA);
}
