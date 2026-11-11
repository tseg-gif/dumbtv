// leaves as small tilted ellipses in jittered cells; returns coverage, colour through the out parameter
float leafCells(vec2 ql, float dens, float seed, out vec3 col, float lit) {
  vec2 id = floor(ql);
  vec2 f = fract(ql) - 0.5;
  float h = h21(id + seed);
  float h2 = h21(id * 1.37 + seed + 9.0);
  vec2 jit = (vec2(h21(id + seed + 3.0), h21(id + seed + 5.0)) - 0.5) * 0.35;
  f -= jit;
  float a = h2 * 6.2832;
  vec2 ff = vec2(cos(a) * f.x + sin(a) * f.y, -sin(a) * f.x + cos(a) * f.y);
  float e = length(ff * vec2(1.0, 2.6)) / (0.30 + 0.12 * h);
  float cov = (1.0 - smoothstep(0.82, 1.0, e)) * step(h, dens);
  float vein = 0.85 + 0.15 * smoothstep(0.0, 0.05, abs(ff.y));
  vec3 g1 = mix(vec3(0.06, 0.16, 0.06), vec3(0.34, 0.50, 0.14), lit);
  vec3 g2 = mix(vec3(0.05, 0.12, 0.05), vec3(0.22, 0.42, 0.12), lit);
  vec3 summer = mix(g1, g2, h2);
  vec3 a1 = mix(vec3(0.20, 0.07, 0.02), vec3(0.78, 0.40, 0.08), lit);
  vec3 a2 = mix(vec3(0.16, 0.04, 0.03), vec3(0.62, 0.17, 0.08), lit);
  vec3 a3 = mix(vec3(0.20, 0.13, 0.03), vec3(0.82, 0.62, 0.16), lit);
  
  col = summer * vein * (0.8 + 0.4 * h);
  return cov;
}


// the generated garden: a wall of crisp, layered leaves in rain light (no soft blobs); a real photo replaces it when present
vec3 garden(vec2 q) {
  float br = uBreath;
  vec3 col = mix(vec3(0.06, 0.14, 0.09), vec3(0.34, 0.50, 0.30), clamp(q.y + 0.6, 0.0, 1.0));
  col += vec3(0.20, 0.28, 0.10) * smoothstep(0.8, 0.0, length(q - vec2(0.15, 0.30)));
  for (int l = 0; l < 4; l++) {
    float fl = float(l), d = fl / 3.0;
    float sc = 7.0 + fl * 7.0;
    vec2 ql = q * sc + vec2(fl * 13.1, fl * 7.7);
    ql += vec2(sin(uT * 0.21 + fl * 1.7 + q.y * 2.0), cos(uT * 0.17 + fl * 2.3 + q.x * 2.0)) * (0.08 + 0.06 * d);
    float dens = clamp(0.30 + 0.30 * d + 0.25 * smoothstep(0.1, 0.6, q.y) * d, 0.0, 0.92);
    float lit = clamp(0.25 + 0.5 * clamp(q.y + 0.6, 0.0, 1.0) - 0.2 * d + 0.1 * br, 0.0, 1.0);
    vec3 lc;
    float cov = leafCells(ql, dens, fl * 19.7, lc, lit);
    col = mix(col, lc, cov * 0.92);
    vec3 lc2;
    float cv2 = leafCells(ql * 1.8 + 3.0, dens * 0.7, fl * 19.7 + 31.0, lc2, lit);
    col = mix(col, lc2, cv2 * 0.92);
  }
  return col;
}

// Lush garden rain: foliage above, a rain-pocked puddle below the horizon YH that mirrors it (256x256 ripple heightfield).
const float YH = 0.30;

vec3 gardenAt(vec2 uv, float blur) {
  vec2 p = (uv - 0.5) * vec2(uRes.x / uRes.y, 1.0);
#ifdef PHOTO
  float rack = 0.20 + 0.22 * (0.5 + 0.5 * sin(uT * 0.09));
  vec3 col = photo(uv, clamp(rack + blur + 0.25 * smoothstep(0.25, 0.9, length(p * vec2(0.8, 1.0))), 0.0, 1.0)) * vec3(0.96, 1.02, 0.93);
  float mist = fbm(vec2(p.x * 1.3 + uT * 0.02, p.y * 2.0)) * smoothstep(0.4, -0.45, p.y);
  col = mix(col, vec3(0.50, 0.62, 0.52) * 0.8, mist * 0.16);
#else
  vec3 col = garden(p);
#endif
  col *= 1.0 + 0.07 * uBreath;
  col += vec3(0.5, 0.6, 0.35) * uThunder * 0.08 * smoothstep(-0.1, 0.5, p.y);
  return col;
}

vec3 backdrop(vec2 uv, float blur) { return gardenAt(uv, blur); }

vec3 scene(vec2 p) {
  vec2 uv = vUv;
  vec3 col = uv.y > YH ? gardenAt(uv, 0.0) : waterCol(uv, YH, 0.8, 1.0);
  col += vec3(0.20, 0.26, 0.16) * exp(-abs(uv.y - YH) * 30.0) * 0.5;
  col *= 1.0 - 0.35 * smoothstep(0.55, 1.0, length(p * vec2(0.8, 1.0)));
  return col;
}
