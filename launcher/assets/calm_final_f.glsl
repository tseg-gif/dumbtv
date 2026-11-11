uniform sampler2D uBg;
void main() {
  vec3 c = texture2D(uBg, vUv).rgb;
  vec2 p = (vUv - 0.5) * vec2(uRes.x / uRes.y, 1.0);
  c *= 1.0 - 0.20 * smoothstep(0.6, 1.1, length(p * vec2(0.8, 1.0)));
  c += (h21(gl_FragCoord.xy + fract(uT) * 61.0) - 0.5) / 110.0 * uBright; // very light film grain
  gl_FragColor = vec4(max(c, vec3(0.0)), 1.0);
}
