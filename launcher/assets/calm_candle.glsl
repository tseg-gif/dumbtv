vec3 scene(vec2 p) {
  float fl = 1.0 + 0.035 * sin(uT * 1.7) + 0.025 * sin(uT * 3.1 + 1.3) + 0.015 * sin(uT * 0.6);
  vec2 fp = vec2(0.0, -0.02);
  vec2 dd = p - fp;
  vec3 col = vec3(0.012, 0.010, 0.012);
  col += vec3(0.95, 0.50, 0.18) * exp(-dot(dd, dd) * 7.0) * 0.50 * fl * (1.0 + 0.06 * uBreath);
  col += vec3(1.0, 0.65, 0.3) * exp(-dot(dd, dd) * 60.0) * 0.30 * fl;
  float table = smoothstep(-0.40, -0.43, p.y);
  col += vec3(0.55, 0.28, 0.10) * table * exp(-p.x * p.x * 6.0) * 0.30 * fl;
  col = mix(col, vec3(0.03, 0.022, 0.02), smoothstep(-0.44, -0.46, p.y));
  float cy = (p.y + 0.43) / 0.31;
  float bx = abs(p.x) - 0.034;
  float body = smoothstep(0.003, 0.0, bx) * step(0.0, cy) * step(cy, 1.0);
  vec3 wax = vec3(0.88, 0.72, 0.50) * (0.30 + 0.35 * exp(-dd.y * dd.y * 18.0) * fl) * (0.8 + 0.2 * smoothstep(0.034, -0.034, p.x));
  col = mix(col, wax, body);
  col += vec3(1.0, 0.6, 0.25) * body * exp(-pow(p.y + 0.13, 2.0) * 900.0) * 0.5;
  float sway = 0.006 * sin(uT * 1.1) + 0.003 * sin(uT * 2.3 + 1.0);
  vec2 q = p - vec2(0.0, -0.105);
  float h = q.y / 0.145;
  float w = 0.030 * pow(sin(PI * clamp(h, 0.0, 1.0)), 0.8) * (1.0 - 0.45 * h);
  float qx = q.x - sway * h * h * 2.0;
  float flame = smoothstep(w, w * 0.4, abs(qx)) * smoothstep(-0.005, 0.01, h) * smoothstep(1.02, 0.9, h);
  vec3 fc = mix(vec3(0.55, 0.65, 1.0), vec3(1.0, 0.82, 0.45), smoothstep(0.0, 0.25, h));
  fc = mix(fc, vec3(1.0, 0.45, 0.14), smoothstep(0.35, 1.0, h));
  col = mix(col, fc * fl, flame * 0.92);
  col += vec3(0.01, 0.01, 0.02) * (vn(p * 3.0 + uT * 0.02) - 0.5);
  return col;
}
