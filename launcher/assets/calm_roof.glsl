// Dusk, seen from under a thin roof edge: water pours off the edge, rain beyond, splash rings on the yard.
const float HORIZON = -0.12;

vec3 lanternHalo(vec2 p, float flick) {
  vec2 d = p - vec2(-0.50, 0.20);
  float r2 = dot(d, d);
  return vec3(1.0, 0.62, 0.25) * (exp(-r2 * 22.0) * 0.75 + exp(-r2 * 3.2) * 0.22) * flick;
}

vec3 roofWorld(vec2 p, float flick) {
  float br = uBreath;
  vec3 sky = mix(vec3(0.13, 0.16, 0.21), vec3(0.045, 0.06, 0.10), smoothstep(HORIZON, 0.5, p.y));
  sky *= 1.0 + 0.10 * br;
  sky += vec3(0.10, 0.07, 0.05) * 0.5 * exp(-pow(p.y - HORIZON, 2.0) * 40.0);
  sky += vec3(0.10, 0.12, 0.18) * uThunder * 0.35 * smoothstep(-0.1, 0.5, p.y) * exp(-pow(p.x - 0.4, 2.0) * 2.0);
  float ridge = HORIZON + 0.06 * vn(vec2(p.x * 4.0 + 1.0, 0.0)) + 0.03 * vn(vec2(p.x * 11.0, 3.0));
  float sil = smoothstep(ridge + 0.012, ridge - 0.012, p.y) * step(HORIZON, p.y);
  sky = mix(sky, vec3(0.035, 0.05, 0.06), sil * 0.85);
  if (p.y < HORIZON) {
    float gy = HORIZON - p.y;
    float z = 1.0 / (gy + 0.07);
    vec2 w = vec2(p.x * z * 1.6, z);
    float tw = mod(uT, 240.0);
    float rings = 0.0;
    for (int L = 0; L < 2; L++) {
      vec2 g = w * (1.6 + 1.1 * float(L)) + vec2(float(L) * 5.3, 0.0);
      vec2 id = floor(g), f = fract(g) - 0.5;
      float h = h21(id + float(L) * 7.0);
      float ex = step(h, 0.55 * uRain);
      float age = fract(tw * (0.5 + 0.4 * h) * uSpeed + h * 9.0);
      vec2 c = vec2(h21(id + 3.1) - 0.5, h21(id + 6.7) - 0.5) * 0.4;
      float d = length(f - c);
      float rr = age * 0.42;
      float ring = exp(-pow((d - rr) / (0.028 * uSize), 2.0)) + 0.5 * exp(-pow((d - rr * 0.6) / (0.025 * uSize), 2.0));
      rings += ring * (1.0 - age) * (1.0 - age) * ex;
    }
    float fog = smoothstep(0.0, 0.12, gy);
    vec3 gcol = mix(vec3(0.07, 0.085, 0.11), vec3(0.025, 0.03, 0.04), smoothstep(0.0, 0.38, gy));
    gcol += vec3(0.04, 0.05, 0.07) * (1.0 - gy * 2.0);
    float lx = p.x + rings * 0.01 * z + (vn(vec2(p.x * 30.0, uT * 0.5)) - 0.5) * 0.01;
    gcol += vec3(1.0, 0.55, 0.2) * flick * exp(-pow(lx + 0.50, 2.0) * 40.0) * 0.5 * smoothstep(0.38, 0.0, gy) * (0.5 + 0.5 * vn(vec2(p.y * 40.0, uT)));
    gcol += vec3(0.55, 0.65, 0.78) * rings * 0.30 * fog;
    sky = gcol * fog + sky * (1.0 - fog);
  }
  return sky;
}

float roofRings(vec2 p) {
  float gy = max(HORIZON - p.y, 0.0);
  float z = 1.0 / (gy + 0.07);
  vec2 w = vec2(p.x * z * 1.6, z);
  float tw = mod(uT, 240.0), rings = 0.0;
  for (int L = 0; L < 2; L++) {
    vec2 g = w * (1.6 + 1.1 * float(L)) + vec2(float(L) * 5.3, 0.0);
    vec2 id = floor(g), f = fract(g) - 0.5;
    float h = h21(id + float(L) * 7.0);
    float ex = step(h, 0.55 * uRain);
    float age = fract(tw * (0.5 + 0.4 * h) * uSpeed + h * 9.0);
    vec2 c = vec2(h21(id + 3.1) - 0.5, h21(id + 6.7) - 0.5) * 0.4;
    float d = length((f - c) * vec2(1.0, 1.0 + 0.25 * sin(h * 40.0)));
    float rr = age * 0.42;
    float ring = exp(-pow((d - rr) / (0.028 * uSize), 2.0)) + 0.5 * exp(-pow((d - rr * 0.6) / (0.025 * uSize), 2.0));
    rings += ring * (1.0 - age) * (1.0 - age) * ex;
  }
  return rings;
}

vec3 scene(vec2 p) {
#ifdef PHOTO
  vec3 col = photo(vUv, 0.0) * vec3(0.95, 1.0, 1.04);
  float rg = roofRings(p) * smoothstep(-0.10, -0.2, p.y);
  col += vec3(0.55, 0.65, 0.78) * rg * 0.22 * (0.4 + dot(col, vec3(0.6)));
  col *= 1.0 + 0.08 * uBreath;
  col += vec3(0.3, 0.35, 0.45) * uThunder * 0.06 * smoothstep(-0.1, 0.5, p.y);
  return col;
#else
  float flick = 1.0 + 0.03 * sin(uT * 1.3) + 0.02 * sin(uT * 2.9 + 1.0);
  vec3 col = roofWorld(p, flick);
  vec3 lh = lanternHalo(p, flick);
  float lipY = 0.36;
  float under = smoothstep(lipY - 0.004, lipY + 0.01, p.y);
  col = mix(col, vec3(0.012, 0.015, 0.02) + lh * 0.12, under);
  col += vec3(0.30, 0.35, 0.42) * smoothstep(0.006, 0.0, abs(p.y - lipY)) * 0.35;
  vec2 ld = p - vec2(-0.50, 0.215);
  float body = smoothstep(0.004, 0.0, max(abs(ld.x) - 0.017, abs(ld.y) - 0.026));
  float cord = smoothstep(0.002, 0.0, abs(p.x + 0.50)) * step(0.24, p.y) * step(p.y, lipY);
  col = mix(col, vec3(1.0, 0.72, 0.38) * flick, body * 0.9);
  col = mix(col, vec3(0.03, 0.025, 0.02), cord);
  col += lh * (0.55 + 0.25 * under);
  float gate = 0.2 + 0.8 * smoothstep(0.28, 0.6, vn(vec2(p.x * 5.5 + 3.0, uT * 0.12)));
  float foam = gate * smoothstep(-0.47, -0.40, p.y) * smoothstep(-0.30, -0.40, p.y) * (0.25 + 0.75 * vn(vec2(p.x * 55.0, uT * 7.0))) * (0.4 + 0.6 * uRain);
  col += vec3(0.62, 0.70, 0.80) * foam * 0.45;
  col *= 1.0 - 0.40 * smoothstep(0.55, 1.05, length(p * vec2(0.8, 1.0)));
  return col;
#endif
}
