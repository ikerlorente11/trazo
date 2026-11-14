package dev.pistation.trazo.engine

/** GLSL ES 3.0 sources. Every texture holds premultiplied RGBA; canvas y grows downwards = texture rows. */
object Shaders {
    private const val H = "#version 300 es\nprecision highp float;\n"

    /** Full-target quad: aPos in NDC, aUv texture coordinates. */
    const val VS_QUAD = H + """
layout(location=0) in vec2 aPos;
layout(location=1) in vec2 aUv;
out vec2 vUv;
void main(){ vUv = aUv; gl_Position = vec4(aPos, 0.0, 1.0); }
"""

    /** Mesh in canvas (or screen) pixels mapped by uM to NDC. */
    const val VS_MESH = H + """
layout(location=0) in vec2 aPos;
layout(location=1) in vec2 aUv;
uniform mat3 uM;
out vec2 vUv;
void main(){ vUv = aUv; gl_Position = vec4((uM * vec3(aPos, 1.0)).xy, 0.0, 1.0); }
"""

    const val FS_COPY = H + """
in vec2 vUv; uniform sampler2D uTex; uniform float uOpacity; out vec4 o;
void main(){ o = texture(uTex, vUv) * uOpacity; }
"""

    const val FS_COLOR = H + """
uniform vec4 uColor; out vec4 o;
void main(){ o = uColor; }
"""

    /** Checkerboard under transparent canvases, in screen pixels. */
    const val FS_CHECKER = H + """
out vec4 o;
void main(){
  vec2 c = floor(gl_FragCoord.xy / 16.0);
  float k = mod(c.x + c.y, 2.0);
  o = vec4(mix(vec3(0.86), vec3(0.97), k), 1.0);
}
"""

    /** Texture times a single-channel mask (layer mask or selection); uInvert uses 1 - mask. */
    const val FS_MASKED = H + """
in vec2 vUv; uniform sampler2D uTex; uniform sampler2D uMask; uniform int uInvert; out vec4 o;
void main(){
  float m = texture(uMask, vUv).r;
  if (uInvert == 1) m = 1.0 - m;
  o = texture(uTex, vUv) * m;
}
"""

    /** Layer blending (W3C compositing formulas), with layer opacity and clipping mask. */
    const val FS_BLEND = H + """
in vec2 vUv;
uniform sampler2D uDst; uniform sampler2D uSrc; uniform sampler2D uClip;
uniform float uOpacity; uniform int uMode; uniform int uUseClip;
out vec4 o;

float lum(vec3 c){ return dot(c, vec3(0.3, 0.59, 0.11)); }
vec3 clipColor(vec3 c){
  float l = lum(c); float n = min(min(c.r,c.g),c.b); float x = max(max(c.r,c.g),c.b);
  if (n < 0.0) c = l + (c - l) * l / max(l - n, 1e-5);
  if (x > 1.0) c = l + (c - l) * (1.0 - l) / max(x - l, 1e-5);
  return c;
}
vec3 setLum(vec3 c, float l){ return clipColor(c + (l - lum(c))); }
float sat(vec3 c){ return max(max(c.r,c.g),c.b) - min(min(c.r,c.g),c.b); }
vec3 setSat(vec3 c, float s){
  float mx = max(max(c.r,c.g),c.b); float mn = min(min(c.r,c.g),c.b);
  return mx > mn ? (c - mn) * s / (mx - mn) : vec3(0.0);
}
float softL(float b, float s){
  if (s <= 0.5) return b - (1.0 - 2.0*s) * b * (1.0 - b);
  float d = b <= 0.25 ? ((16.0*b - 12.0)*b + 4.0)*b : sqrt(b);
  return b + (2.0*s - 1.0) * (d - b);
}
float dodge(float b, float s){ return b == 0.0 ? 0.0 : (s >= 1.0 ? 1.0 : min(1.0, b / (1.0 - s))); }
float burn(float b, float s){ return b >= 1.0 ? 1.0 : (s <= 0.0 ? 0.0 : 1.0 - min(1.0, (1.0 - b) / s)); }
vec3 hardL(vec3 b, vec3 s){
  return mix(b * 2.0 * s, 1.0 - (1.0 - b) * (1.0 - (2.0*s - 1.0)), step(0.5, s));
}

vec3 blendFn(vec3 b, vec3 s){
  if (uMode == 1) return b * s;
  if (uMode == 2) return b + s - b * s;
  if (uMode == 3) return hardL(s, b);
  if (uMode == 4) return min(b, s);
  if (uMode == 5) return max(b, s);
  if (uMode == 6) return vec3(dodge(b.r,s.r), dodge(b.g,s.g), dodge(b.b,s.b));
  if (uMode == 7) return vec3(burn(b.r,s.r), burn(b.g,s.g), burn(b.b,s.b));
  if (uMode == 8) return hardL(b, s);
  if (uMode == 9) return vec3(softL(b.r,s.r), softL(b.g,s.g), softL(b.b,s.b));
  if (uMode == 10) return abs(b - s);
  if (uMode == 11) return b + s - 2.0 * b * s;
  if (uMode == 12) return min(b + s, 1.0);
  if (uMode == 13) return max(b - s, 0.0);
  if (uMode == 14) return setLum(setSat(s, sat(b)), lum(b));
  if (uMode == 15) return setLum(setSat(b, sat(s)), lum(b));
  if (uMode == 16) return setLum(s, lum(b));
  if (uMode == 17) return setLum(b, lum(s));
  if (uMode == 18) return max(b + s - 1.0, 0.0);
  if (uMode == 19) return vec3(s.r > 0.0 ? min(b.r/s.r,1.0) : 1.0, s.g > 0.0 ? min(b.g/s.g,1.0) : 1.0, s.b > 0.0 ? min(b.b/s.b,1.0) : 1.0);
  if (uMode == 20) return lum(s) < lum(b) ? s : b;
  if (uMode == 21) return lum(s) > lum(b) ? s : b;
  return s;
}

void main(){
  vec4 d = texture(uDst, vUv);
  vec4 s = texture(uSrc, vUv);
  float k = uOpacity;
  if (uUseClip == 1) k *= texture(uClip, vUv).a;
  s *= k;
  if (uMode == 0) { o = s + d * (1.0 - s.a); return; }
  vec3 cs = s.a > 0.0 ? s.rgb / s.a : vec3(0.0);
  vec3 cd = d.a > 0.0 ? d.rgb / d.a : vec3(0.0);
  vec3 b = clamp(blendFn(cd, cs), 0.0, 1.0);
  vec3 rgb = (1.0 - d.a) * s.rgb + s.a * d.a * b + (1.0 - s.a) * d.rgb;
  o = vec4(rgb, s.a + d.a * (1.0 - s.a));
}
"""

    /**
     * Stroke buffer onto its target: 0 paint, 1 erase, 2 paint with alpha lock, 3 nothing.
     * The selection (if any) limits the stroke.
     */
    const val FS_MERGE = H + """
in vec2 vUv;
uniform sampler2D uLayer; uniform sampler2D uStroke; uniform sampler2D uSel;
uniform float uOpacity; uniform int uMode; uniform int uUseSel;
out vec4 o;
void main(){
  vec4 l = texture(uLayer, vUv);
  float k = uOpacity;
  if (uUseSel == 1) k *= texture(uSel, vUv).r;
  vec4 s = texture(uStroke, vUv) * k;
  if (uMode == 0) o = s + l * (1.0 - s.a);
  else if (uMode == 1) o = l * (1.0 - s.a);
  else if (uMode == 2) o = vec4(l.rgb * (1.0 - s.a) + s.rgb * l.a, l.a);
  else o = l;
}
"""

    /** Brush stamps, instanced: per instance (x, y, size, angle) and (r, g, b, alpha). uM maps pixels to NDC. */
    const val VS_STAMP = H + """
layout(location=0) in vec2 aCorner;
layout(location=1) in vec4 aStamp;
layout(location=2) in vec4 aColor;
uniform mat3 uM; uniform float uRoundness;
out vec2 vUv; out vec2 vCanvas; out vec4 vColor;
void main(){
  float c = cos(aStamp.w), s = sin(aStamp.w);
  vec2 p = aCorner * aStamp.z * vec2(1.0, uRoundness);
  p = vec2(p.x * c - p.y * s, p.x * s + p.y * c) + aStamp.xy;
  vUv = aCorner + 0.5; vCanvas = p; vColor = aColor;
  gl_Position = vec4((uM * vec3(p, 1.0)).xy, 0.0, 1.0);
}
"""

    /** uClone = 1 takes the colour from uSrc at an offset (clone tool). */
    const val FS_STAMP = H + """
in vec2 vUv; in vec2 vCanvas; in vec4 vColor;
uniform sampler2D uShape; uniform sampler2D uGrain; uniform float uGrainScale; uniform float uGrainDepth;
uniform int uClone; uniform sampler2D uSrc; uniform vec2 uOffset; uniform vec2 uCanvasSize;
out vec4 o;
void main(){
  float m = texture(uShape, vUv).r;
  float g = texture(uGrain, vCanvas / uGrainScale).r;
  float a = m * mix(1.0, g, uGrainDepth) * vColor.a;
  if (uClone == 1) { o = texture(uSrc, (vCanvas + uOffset) / uCanvasSize) * a; return; }
  o = vec4(vColor.rgb * a, a);
}
"""

    /** One smudge / wet-paint stamp: mixes what is under it with paint picked up at the previous stamp. */
    const val VS_SMUDGE = H + """
layout(location=0) in vec2 aCorner;
uniform vec4 uStamp; uniform mat3 uM; uniform float uRoundness;
out vec2 vUv; out vec2 vCanvas;
void main(){
  float c = cos(uStamp.w), s = sin(uStamp.w);
  vec2 p = aCorner * uStamp.z * vec2(1.0, uRoundness);
  p = vec2(p.x * c - p.y * s, p.x * s + p.y * c) + uStamp.xy;
  vUv = aCorner + 0.5; vCanvas = p;
  gl_Position = vec4((uM * vec3(p, 1.0)).xy, 0.0, 1.0);
}
"""

    const val FS_SMUDGE = H + """
in vec2 vUv; in vec2 vCanvas;
uniform sampler2D uShape; uniform sampler2D uGrain; uniform float uGrainScale; uniform float uGrainDepth;
uniform sampler2D uDstCopy; uniform vec2 uDstOrigin;
uniform sampler2D uPick; uniform vec2 uPickOrigin; uniform vec2 uDelta;
uniform float uRegion; uniform float uStrength;
uniform int uUseColor; uniform vec3 uColor; uniform float uWet;
uniform int uUseSel; uniform sampler2D uSel; uniform vec2 uCanvasSize;
out vec4 o;
void main(){
  float m = texture(uShape, vUv).r;
  float g = texture(uGrain, vCanvas / uGrainScale).r;
  float k = m * mix(1.0, g, uGrainDepth) * uStrength;
  if (uUseSel == 1) k *= texture(uSel, vCanvas / uCanvasSize).r;
  vec4 d = texture(uDstCopy, (vCanvas - uDstOrigin) / uRegion);
  vec4 p = texture(uPick, (vCanvas - uDelta - uPickOrigin) / uRegion);
  if (uUseColor == 1) p = mix(vec4(uColor, 1.0), p, uWet);
  o = mix(d, p, k);
}
"""

    /** The canvas on screen: quad corners already in NDC. */
    const val FS_SCREEN = H + """
in vec2 vUv; uniform sampler2D uTex; out vec4 o;
void main(){ o = texture(uTex, vUv); }
"""

    /** Marching ants around the selection and a veil outside it. uTexel = canvas uv per screen pixel. */
    const val FS_SELOUT = H + """
in vec2 vUv; uniform sampler2D uSel; uniform vec2 uTexel; uniform float uTime; out vec4 o;
void main(){
  float s = texture(uSel, vUv).r;
  float e = 0.0;
  e = max(e, abs(s - texture(uSel, vUv + vec2(uTexel.x, 0.0)).r));
  e = max(e, abs(s - texture(uSel, vUv - vec2(uTexel.x, 0.0)).r));
  e = max(e, abs(s - texture(uSel, vUv + vec2(0.0, uTexel.y)).r));
  e = max(e, abs(s - texture(uSel, vUv - vec2(0.0, uTexel.y)).r));
  if (e > 0.25) {
    float k = step(4.0, mod(gl_FragCoord.x + gl_FragCoord.y + uTime, 8.0));
    o = vec4(vec3(k), 1.0);
  } else if (s < 0.5) {
    o = vec4(0.0, 0.0, 0.0, 0.22);
  } else discard;
}
"""

    /** Selection limits an adjustment: original where unselected. */
    const val FS_SELMIX = H + """
in vec2 vUv; uniform sampler2D uOrig; uniform sampler2D uAdj; uniform sampler2D uSel; uniform int uUseSel; uniform int uKeepAlpha; out vec4 o;
void main(){
  vec4 a = texture(uOrig, vUv); vec4 b = texture(uAdj, vUv);
  float k = uUseSel == 1 ? texture(uSel, vUv).r : 1.0;
  o = mix(a, b, k);
}
"""

    /** 3D model: lit, textured with the canvas. */
    const val VS_MODEL = H + """
layout(location=0) in vec3 aPos; layout(location=1) in vec3 aNormal; layout(location=2) in vec2 aUv;
uniform mat4 uMvp; uniform mat4 uModel;
out vec2 vUv; out vec3 vN;
void main(){ vUv = aUv; vN = mat3(uModel) * aNormal; gl_Position = uMvp * vec4(aPos, 1.0); }
"""

    const val FS_MODEL = H + """
in vec2 vUv; in vec3 vN; uniform sampler2D uTex; uniform vec3 uBase; uniform int uPick; out vec4 o;
void main(){
  if (uPick == 1) {
    vec2 u = clamp(vUv, 0.0, 1.0) * 65535.0;
    vec2 hi = floor(u / 256.0); vec2 lo = mod(u, 256.0);
    o = vec4(hi.x / 255.0, lo.x / 255.0, hi.y / 255.0, lo.y / 255.0);
    return;
  }
  vec4 t = texture(uTex, vUv);
  vec3 c = t.rgb + uBase * (1.0 - t.a);
  float l = 0.35 + 0.65 * max(dot(normalize(vN), normalize(vec3(0.4, 0.6, 0.7))), 0.0);
  o = vec4(c * l, 1.0);
}
"""

    /** All image adjustments. Colour ones work on unpremultiplied colour; filters on premultiplied. */
    const val FS_ADJUST = H + """
in vec2 vUv;
uniform sampler2D uTex; uniform sampler2D uAux; uniform sampler2D uLut;
uniform int uKind; uniform float uP[12]; uniform vec2 uSize;
out vec4 o;

vec3 rgb2hsv(vec3 c){
  vec4 K = vec4(0.0, -1.0/3.0, 2.0/3.0, -1.0);
  vec4 p = mix(vec4(c.bg, K.wz), vec4(c.gb, K.xy), step(c.b, c.g));
  vec4 q = mix(vec4(p.xyw, c.r), vec4(c.r, p.yzx), step(p.x, c.r));
  float d = q.x - min(q.w, q.y); float e = 1.0e-10;
  return vec3(abs(q.z + (q.w - q.y) / (6.0 * d + e)), d / (q.x + e), q.x);
}
vec3 hsv2rgb(vec3 c){
  vec3 p = abs(fract(c.xxx + vec3(1.0, 2.0/3.0, 1.0/3.0)) * 6.0 - 3.0);
  return c.z * mix(vec3(1.0), clamp(p - 1.0, 0.0, 1.0), c.y);
}
float lum(vec3 c){ return dot(c, vec3(0.299, 0.587, 0.114)); }
float hash(vec2 p){ return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }
float vnoise(vec2 p){
  vec2 i = floor(p); vec2 f = fract(p); f = f*f*(3.0-2.0*f);
  return mix(mix(hash(i), hash(i+vec2(1,0)), f.x), mix(hash(i+vec2(0,1)), hash(i+vec2(1,1)), f.x), f.y);
}
vec4 un(vec4 t){ return vec4(t.a > 0.0 ? t.rgb / t.a : vec3(0.0), t.a); }
vec4 pm(vec3 c, float a){ return vec4(clamp(c, 0.0, 1.0) * a, a); }

void main(){
  vec4 t = texture(uTex, vUv);
  vec2 px = vUv * uSize;
  if (uKind == 0) { // hue / saturation / brightness
    vec4 u = un(t); vec3 h = rgb2hsv(u.rgb);
    h.x = fract(h.x + uP[0] / 360.0);
    h.y = clamp(h.y * (1.0 + uP[1]), 0.0, 1.0);
    vec3 c = hsv2rgb(h);
    c = uP[2] >= 0.0 ? mix(c, vec3(1.0), uP[2]) : c * (1.0 + uP[2]);
    o = pm(c, u.a);
  } else if (uKind == 1) { // colour balance: (cyan-red, magenta-green, yellow-blue) x (shadows, mids, highlights)
    vec4 u = un(t); float l = lum(u.rgb);
    float ws = clamp(1.0 - l * 3.0, 0.0, 1.0);
    float wh = clamp(l * 3.0 - 2.0, 0.0, 1.0);
    float wm = 1.0 - ws - wh;
    vec3 sh = vec3(uP[0], uP[1], uP[2]), md = vec3(uP[3], uP[4], uP[5]), hi = vec3(uP[6], uP[7], uP[8]);
    vec3 c = u.rgb + (sh * ws + md * wm + hi * wh) * 0.5;
    o = pm(c, u.a);
  } else if (uKind == 2) { // curves (LUT r,g,b)
    vec4 u = un(t);
    vec3 c = vec3(texture(uLut, vec2(u.r * 255.0/256.0 + 0.5/256.0, 0.5)).r,
                  texture(uLut, vec2(u.g * 255.0/256.0 + 0.5/256.0, 0.5)).g,
                  texture(uLut, vec2(u.b * 255.0/256.0 + 0.5/256.0, 0.5)).b);
    o = pm(c, u.a);
  } else if (uKind == 3) { // gradient map
    vec4 u = un(t); float l = lum(u.rgb);
    vec3 g = texture(uLut, vec2(l * 255.0/256.0 + 0.5/256.0, 0.5)).rgb;
    o = pm(mix(u.rgb, g, uP[0]), u.a);
  } else if (uKind == 4) { // gaussian blur, one direction: uP[0] sigma px, uP[1] 0 = x, 1 = y
    float sigma = max(uP[0], 0.01);
    vec2 dir = (uP[1] < 0.5 ? vec2(1.0, 0.0) : vec2(0.0, 1.0)) / uSize;
    float step_ = max(1.0, sigma / 12.0);
    vec4 acc = vec4(0.0); float wsum = 0.0;
    for (int i = -36; i <= 36; i++) {
      float x = float(i) * step_;
      if (abs(x) > sigma * 3.0) continue;
      float w = exp(-0.5 * x * x / (sigma * sigma));
      acc += texture(uTex, vUv + dir * x) * w; wsum += w;
    }
    o = acc / wsum;
  } else if (uKind == 5) { // motion blur: uP[0] distance px, uP[1] angle
    vec2 d = vec2(cos(uP[1]), sin(uP[1])) * uP[0] / uSize;
    vec4 acc = vec4(0.0);
    for (int i = 0; i < 48; i++) acc += texture(uTex, vUv + d * (float(i) / 47.0 - 0.5));
    o = acc / 48.0;
  } else if (uKind == 6) { // perspective (zoom) blur around uP[1], uP[2] (px); uP[0] amount
    vec2 c = vec2(uP[1], uP[2]) / uSize;
    vec4 acc = vec4(0.0);
    for (int i = 0; i < 48; i++) {
      float s = 1.0 - uP[0] * 0.25 * float(i) / 47.0;
      acc += texture(uTex, c + (vUv - c) * s);
    }
    o = acc / 48.0;
  } else if (uKind == 7) { // sharpen: uAux = blurred
    vec4 b = texture(uAux, vUv);
    o = clamp(t + (t - b) * uP[0], 0.0, 1.0); o.rgb = min(o.rgb, vec3(o.a));
  } else if (uKind == 8) { // noise: amount, scale px, octaves, colour
    vec4 u = un(t);
    float n = 0.0, amp = 0.5, fr = 1.0 / max(uP[1], 1.0);
    for (int i = 0; i < 6; i++) { if (float(i) >= uP[2]) break; n += amp * vnoise(px * fr + float(i) * 17.0); amp *= 0.5; fr *= 2.0; }
    vec3 nn = uP[3] > 0.5 ? vec3(n, vnoise(px / max(uP[1],1.0) + 41.0), vnoise(px / max(uP[1],1.0) + 83.0)) : vec3(n);
    o = pm(u.rgb + (nn - 0.5) * uP[0], u.a);
  } else if (uKind == 9) { // chromatic aberration: amount px, centre, falloff
    vec2 c = vec2(uP[1], uP[2]) / uSize;
    vec2 d = (vUv - c); float f = pow(clamp(length(d * uSize) / max(uSize.x, uSize.y) * 2.0, 0.0, 1.0), uP[3]);
    vec2 off = normalize(d + 1e-6) * uP[0] * f / uSize;
    vec4 r = texture(uTex, vUv + off), g = t, b = texture(uTex, vUv - off);
    o = vec4(r.r, g.g, b.b, max(max(r.a, g.a), b.a));
  } else if (uKind == 10) { // bloom bright pass: threshold
    vec4 u = un(t); float l = lum(u.rgb);
    float k = smoothstep(uP[0], uP[0] + 0.1, l);
    o = vec4(u.rgb * k * u.a, k * u.a);
  } else if (uKind == 11) { // bloom combine: uAux = blurred bright, intensity
    vec4 b = texture(uAux, vUv) * uP[1];
    o = t + b - t * b; // screen
  } else if (uKind == 12) { // glitch: amount, block px, seed, mode
    float blk = max(uP[1], 2.0);
    vec2 cell = floor(px / vec2(blk * 6.0, blk));
    float r = hash(cell + uP[2]);
    vec2 uv = vUv;
    if (uP[3] < 0.5) { if (r < uP[0]) uv.x += (hash(cell.yx + uP[2]) - 0.5) * 0.2 * uP[0]; }
    else if (uP[3] < 1.5) { uv.x += sin(px.y / blk + uP[2]) * 0.01 * uP[0]; }
    else { uv.x += (hash(vec2(cell.y, uP[2])) - 0.5) * 0.04 * uP[0] * step(0.7, r); }
    vec4 a = texture(uTex, uv);
    vec4 sr = texture(uTex, uv + vec2(0.006 * uP[0], 0.0));
    vec4 sb = texture(uTex, uv - vec2(0.006 * uP[0], 0.0));
    o = vec4(sr.r, a.g, sb.b, max(a.a, max(sr.a, sb.a)));
  } else if (uKind == 13) { // halftone: dot px, mode (0 colour, 1 CMY, 2 mono)
    float s = max(uP[0], 2.0);
    vec4 u = un(t);
    if (uP[1] > 1.5) {
      float ang = 0.785; mat2 R = mat2(cos(ang), -sin(ang), sin(ang), cos(ang));
      vec2 q = R * px; vec2 c = (floor(q / s) + 0.5) * s;
      float l = 1.0 - lum(u.rgb);
      float d = length(q - c) / (s * 0.7071);
      float ink = 1.0 - smoothstep(sqrt(l) - 0.06, sqrt(l) + 0.06, d);
      o = pm(vec3(1.0 - ink), u.a);
    } else {
      vec3 res = vec3(1.0);
      vec3 cmy = 1.0 - u.rgb;
      for (int ch = 0; ch < 3; ch++) {
        float ang = 0.26 + float(ch) * 0.52;
        mat2 R = mat2(cos(ang), -sin(ang), sin(ang), cos(ang));
        vec2 q = R * px; vec2 c = (floor(q / s) + 0.5) * s;
        float v = ch == 0 ? cmy.r : (ch == 1 ? cmy.g : cmy.b);
        float d = length(q - c) / (s * 0.7071);
        float ink = 1.0 - smoothstep(sqrt(v) - 0.06, sqrt(v) + 0.06, d);
        if (ch == 0) res.r -= ink; else if (ch == 1) res.g -= ink; else res.b -= ink;
      }
      o = pm(uP[1] > 0.5 ? res : mix(res, u.rgb, 0.35), u.a);
    }
  } else if (uKind == 14) { // invert colours
    vec4 u = un(t); o = pm(1.0 - u.rgb, u.a);
  } else if (uKind == 15) { // liquify: uAux = displacement in px (RG)
    vec2 d = texture(uAux, vUv).rg;
    o = texture(uTex, vUv + d / uSize);
  } else if (uKind == 16) { // levels-like: brightness/contrast
    vec4 u = un(t);
    vec3 c = (u.rgb - 0.5) * (1.0 + uP[1]) + 0.5 + uP[0];
    o = pm(c, u.a);
  } else o = t;
}
"""
}
