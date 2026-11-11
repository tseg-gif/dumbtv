vec3 scene(vec2 p) {
  vec3 col = mix(vec3(0.010, 0.018, 0.040), vec3(0.020, 0.035, 0.060), smoothstep(0.5, -0.4, p.y));
  col *= 1.0 + 0.08 * uBreath;
  vec2 sg = p * 90.0;
  float star = step(0.992, h21(floor(sg))) * smoothstep(0.35, 0.0, length(fract(sg) - 0.5));
  col += vec3(0.8, 0.85, 1.0) * star * (0.30 + 0.20 * sin(uT * 0.8 + h21(floor(sg)) * 40.0)) * smoothstep(-0.2, 0.3, p.y);
  for (int i = 0; i < 3; i++) {
    float fi = float(i);
    float y0 = 0.02 + 0.10 * fi + 0.12 * sin(p.x * (1.9 + 0.4 * fi) + uT * (0.05 + 0.01 * fi) + fi * 2.0) + 0.06 * sin(p.x * 4.3 - uT * 0.08 + fi);
    float up = p.y - y0;
    float band = smoothstep(-0.015, 0.01, up) * exp(-max(up, 0.0) * (3.0 + fi));
    float rays = 0.55 + 0.45 * vn(vec2(p.x * 24.0 + fi * 7.0 + uT * 0.05, p.y * 1.5));
    vec3 ac = mix(vec3(0.15, 0.95, 0.55), vec3(0.50, 0.30, 0.90), smoothstep(0.0, 0.40, up));
    col += ac * band * rays * (0.42 - 0.08 * fi) * (0.85 + 0.15 * uBreath);
  }
  float ridge = -0.30 + 0.07 * vn(vec2(p.x * 3.0, 1.0)) + 0.04 * vn(vec2(p.x * 9.0, 4.0));
  col = mix(col, vec3(0.006, 0.010, 0.014), smoothstep(ridge + 0.006, ridge - 0.006, p.y));
  return col;
}
