vec3 scene(vec2 p) {
  const float HZ = 0.05;
  vec2 moon = vec2(0.22, 0.28);
  vec3 col;
  if (p.y > HZ) {
    col = mix(vec3(0.050, 0.065, 0.100), vec3(0.012, 0.020, 0.045), smoothstep(HZ, 0.5, p.y));
    col *= 1.0 + 0.08 * uBreath;
    vec2 md = p - moon;
    col += vec3(1.0, 0.88, 0.65) * (smoothstep(0.034, 0.028, length(md)) * 0.9 + exp(-dot(md, md) * 90.0) * 0.20 + exp(-dot(md, md) * 9.0) * 0.05);
    vec2 sg = p * 80.0;
    col += vec3(0.8, 0.85, 1.0) * step(0.993, h21(floor(sg))) * smoothstep(0.35, 0.0, length(fract(sg) - 0.5)) * 0.22;
  } else {
    float gy = HZ - p.y;
    float z = 1.0 / (gy + 0.025);
    float wv = sin(p.x * z * 5.0 + uT * 0.35 + z * 1.7) * 0.5 + sin(p.x * z * 11.0 - uT * 0.5 + z * 3.1) * 0.3 + sin(z * 4.0 - uT * 0.3) * 0.4;
    col = mix(vec3(0.028, 0.040, 0.070), vec3(0.006, 0.012, 0.026), smoothstep(0.0, 0.5, gy));
    col *= 1.0 + 0.08 * uBreath + 0.05 * wv;
    float path = exp(-pow((p.x - moon.x + wv * 0.002 * z) * (2.8 + gy * 4.0), 2.0));
    float sp = pow(vn(vec2(p.x * z * 9.0 + wv, z * 13.0 + uT * 0.5)), 5.0) * 3.0;
    col += vec3(1.0, 0.85, 0.6) * path * (0.10 + sp * (0.35 + 0.35 * uSpark)) * smoothstep(0.0, 0.03, gy);
  }
  col += vec3(0.05, 0.06, 0.09) * exp(-pow((p.y - HZ) * 30.0, 2.0)) * 0.6;
  return col;
}
