vec3 scene(vec2 p) {
  float r = 0.11 + 0.16 * uOrb;
  float d = length(p);
  vec3 bg = mix(vec3(0.016, 0.022, 0.040), vec3(0.045, 0.030, 0.034), 1.0 - smoothstep(0.0, 0.9, length(p * vec2(0.8, 1.0))));
  bg *= 1.0 + 0.10 * uBreath;
  float core = smoothstep(r, r * 0.55, d);
  float halo = exp(-pow(max(d - r, 0.0) / (0.10 + 0.14 * uOrb), 2.0));
  vec3 c1 = vec3(1.0, 0.74, 0.46), c2 = vec3(1.0, 0.50, 0.34);
  vec3 orb = mix(c2, c1, 1.0 - d / r) * (0.55 + 0.30 * uOrb);
  vec3 col = bg + orb * core + c2 * halo * 0.22 * (0.6 + 0.4 * uOrb);
  col += c1 * exp(-d * d * 2.5) * 0.04;
  for (int i = 0; i < 8; i++) {
    float fi = float(i);
    vec2 dp = vec2(sin(uT * 0.05 * (0.6 + h11(fi)) + fi * 2.3) * 0.7, mod(h11(fi + 3.0) + uT * 0.004 * (0.5 + h11(fi + 9.0)), 1.0) - 0.5);
    col += c1 * exp(-dot(p - dp, p - dp) * 9000.0) * 0.20;
  }
  return col;
}
