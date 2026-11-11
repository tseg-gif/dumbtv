// Wet ground below the horizon yH (screen uv): the picture above, mirrored, bent by the ripple heightfield,
// blurrier and less reflective the nearer to the camera, with a fresnel term and glints on bright lights.
vec3 backdrop(vec2 uv, float blur);
vec3 waterCol(vec2 uv, float yH, float amp, float blurK) {
  float d = max(yH - uv.y, 0.0), k = d / yH;
  float asp = uRes.x / uRes.y;
  float z = 1.0 / (d + 0.05);
  vec2 s = vec2(0.5 + (uv.x - 0.5) * asp * z * 0.18, z * 0.045);
  vec2 g = (texture2D(uRip, s).rg - 0.5) * (1.0 / 12.0);
  g *= amp * smoothstep(0.0, 0.2, k);
  // slow irregular surface noise, so ripples are never perfect circles
  vec2 nz = (vec2(vn(uv * vec2(34.0, 15.0) + uT * 0.07), vn(uv * vec2(15.0, 34.0) - uT * 0.06 + 4.0)) - 0.5) * 0.006 * (0.3 + k);
  vec2 m = vec2(uv.x, 2.0 * yH - uv.y) + g * vec2(1.0, 1.6) + nz;
  vec3 refl = backdrop(m, clamp((0.12 + 1.0 * k) * blurK, 0.0, 1.0));
  refl += max(refl - 0.5, vec3(0.0)) * 1.4;
  float cosT = 0.1 + 0.9 * k;
  float F = clamp(0.24 + 0.7 * pow(1.0 - cosT, 3.0), 0.0, 1.0);
  vec3 body = vec3(0.012, 0.016, 0.022);
  return body + refl * F;
}
