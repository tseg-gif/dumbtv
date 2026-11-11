void main() {
  vec2 p = (vUv - 0.5) * vec2(uRes.x / uRes.y, 1.0);
  vec3 c = grade(scene(p));
  c += (h21(gl_FragCoord.xy + fract(uT) * 61.0) - 0.5) / 200.0 * uBright;
  gl_FragColor = vec4(max(c, vec3(0.0)), 1.0);
}
