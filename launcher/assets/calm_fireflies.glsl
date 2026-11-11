vec3 scene(vec2 p) {
  vec3 col = mix(vec3(0.020, 0.040, 0.045), vec3(0.006, 0.014, 0.022), smoothstep(-0.5, 0.5, p.y));
  col *= 1.0 + 0.08 * uBreath;
  col += vec3(0.02, 0.03, 0.025) * exp(-pow(p.y + 0.28, 2.0) * 18.0) * (0.6 + 0.4 * vn(vec2(p.x * 2.0 + uT * 0.02, 3.0)));
  float r1 = -0.30 + 0.09 * vn(vec2(p.x * 2.5, 2.0)) + 0.04 * vn(vec2(p.x * 8.0, 5.0));
  float r2 = -0.40 + 0.06 * vn(vec2(p.x * 3.5, 8.0)) + 0.05 * vn(vec2(p.x * 12.0, 1.0));
  col = mix(col, vec3(0.010, 0.022, 0.024), smoothstep(r1 + 0.008, r1 - 0.008, p.y));
  col = mix(col, vec3(0.003, 0.007, 0.008), smoothstep(r2 + 0.008, r2 - 0.008, p.y));
  float nf = mix(14.0, 8.0, uLite);
  for (int i = 0; i < 24; i++) {
    if (float(i) >= nf) break;
    float fi = float(i);
    float hx = h11(fi + 1.0), hy = h11(fi + 40.0), hs = h11(fi + 80.0);
    vec2 pos = vec2((hx - 0.5) * 1.7, -0.42 + hy * 0.78);
    pos += vec2(sin(uT * 0.10 * (0.5 + hs) + fi * 3.0), sin(uT * 0.13 * (0.5 + hx) + fi * 5.0)) * 0.07;
    vec2 d = p - pos;
    float a = pow(0.5 + 0.5 * sin(uT * (0.30 + 0.35 * hs) + fi * 7.0), 2.0);
    float k = 4000.0 + 5000.0 * hs;
    col += vec3(0.75, 1.0, 0.35) * (exp(-dot(d, d) * k) * 0.9 + exp(-dot(d, d) * 400.0) * 0.14) * a * (0.5 + 0.4 * hs);
  }
  return col;
}
