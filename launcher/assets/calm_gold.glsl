// Golden Cloud (fully procedural): a soft cloud lit from within, over a dark reflective surface. The golden
// streaks that fall out of it are geometry. Low-band energy makes the glow breathe.
const float SURF = -0.28;
vec3 gold(vec2 q) {
  float br = uBreath;
  vec3 col = mix(vec3(0.050, 0.032, 0.026), vec3(0.012, 0.010, 0.016), smoothstep(-0.25, 0.5, q.y));
  float dens = 0.0;
  for (int i = 0; i < 3; i++) {
    if (uLite > 0.5 && i == 1) continue;
    float fi = float(i);
    vec2 oc = vec2(0.10 * sin(uT * 0.03 + fi * 2.1), 0.03 * cos(uT * 0.025 + fi * 1.3));
    vec2 pp = (q - vec2(0.0, 0.20) - oc) / vec2(0.95 - 0.12 * fi, 0.32 - 0.03 * fi);
    float env = smoothstep(1.0, 0.15, length(pp));
    float n = fbm(q * vec2(2.4, 3.6) * (1.0 + 0.45 * fi) + vec2(uT * 0.012 * (1.0 + fi), fi * 7.0));
    dens += env * smoothstep(0.22, 0.78, n) * 0.55;
  }
  dens = clamp(dens, 0.0, 1.0);
  vec2 gp = (q - vec2(0.0, 0.21)) / vec2(0.75, 0.25);
  float core = exp(-dot(gp, gp) * 1.6) * (1.0 + 0.30 * br);
  vec3 cc = mix(vec3(0.16, 0.08, 0.04), vec3(0.85, 0.60, 0.25), clamp(core * (0.5 + 0.6 * dens), 0.0, 1.0));
  col = mix(col, cc, dens);
  col += vec3(1.0, 0.62, 0.22) * (exp(-dot(gp, gp) * 0.45) * 0.14 + core * 0.10) * (1.0 + 0.30 * br);
  return col;
}

vec3 scene(vec2 p) {
  vec3 col;
  if (p.y > SURF) {
    col = gold(p);
    col += vec3(1.0, 0.66, 0.26) * exp(-pow((p.y - SURF) * 22.0, 2.0)) * 0.10 * exp(-p.x * p.x * 3.0) * (0.5 + uRain * 0.7);
  } else {
    float d = SURF - p.y;
    vec2 q = vec2(p.x + (vn(vec2(p.x * 9.0, d * 40.0 - uT * 0.2)) - 0.5) * 0.03 * (0.3 + d * 2.0), 2.0 * SURF - p.y);
    float F = clamp(0.62 - d * 0.9, 0.12, 0.62);
    col = vec3(0.010, 0.009, 0.013) + gold(q) * F;
    col += vec3(1.0, 0.70, 0.28) * exp(-pow(d * 18.0, 2.0)) * 0.08 * exp(-p.x * p.x * 3.0);
    // the cloud's glow pooled on the surface, shimmering
    col += vec3(0.85, 0.55, 0.22) * 0.20 * exp(-p.x * p.x * 2.2) * exp(-d * 5.0) * (0.7 + 0.6 * vn(vec2(p.x * 22.0 + uT * 0.1, d * 60.0)));
  }
  col += vec3(0.5, 0.35, 0.2) * uThunder * 0.05 * smoothstep(-0.2, 0.5, p.y);
  return col;
}
