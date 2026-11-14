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

    /** Stroke buffer onto its layer: 0 paint, 1 erase, 2 paint with alpha lock, 3 erase with alpha lock (no-op). */
    const val FS_MERGE = H + """
in vec2 vUv;
uniform sampler2D uLayer; uniform sampler2D uStroke; uniform float uOpacity; uniform int uMode;
out vec4 o;
void main(){
  vec4 l = texture(uLayer, vUv);
  vec4 s = texture(uStroke, vUv) * uOpacity;
  if (uMode == 0) o = s + l * (1.0 - s.a);
  else if (uMode == 1) o = l * (1.0 - s.a);
  else if (uMode == 2) o = vec4(l.rgb * (1.0 - s.a) + s.rgb * l.a, l.a);
  else o = l;
}
"""

    /** Brush stamps, instanced: per instance (x, y, size, angle) and (r, g, b, alpha). */
    const val VS_STAMP = H + """
layout(location=0) in vec2 aCorner;
layout(location=1) in vec4 aStamp;
layout(location=2) in vec4 aColor;
uniform vec2 uCanvas; uniform float uRoundness;
out vec2 vUv; out vec2 vCanvas; out vec4 vColor;
void main(){
  float c = cos(aStamp.w), s = sin(aStamp.w);
  vec2 p = aCorner * aStamp.z * vec2(1.0, uRoundness);
  p = vec2(p.x * c - p.y * s, p.x * s + p.y * c) + aStamp.xy;
  vUv = aCorner + 0.5; vCanvas = p; vColor = aColor;
  gl_Position = vec4(p / uCanvas * 2.0 - 1.0, 0.0, 1.0);
}
"""

    const val FS_STAMP = H + """
in vec2 vUv; in vec2 vCanvas; in vec4 vColor;
uniform sampler2D uShape; uniform sampler2D uGrain; uniform float uGrainScale; uniform float uGrainDepth;
out vec4 o;
void main(){
  float m = texture(uShape, vUv).r;
  float g = texture(uGrain, vCanvas / uGrainScale).r;
  float a = m * mix(1.0, g, uGrainDepth) * vColor.a;
  o = vec4(vColor.rgb * a, a);
}
"""

    /** One smudge stamp: mixes the layer under it with paint picked up at the previous stamp. */
    const val VS_SMUDGE = H + """
layout(location=0) in vec2 aCorner;
uniform vec4 uStamp; uniform vec2 uCanvas; uniform float uRoundness;
out vec2 vUv; out vec2 vCanvas;
void main(){
  float c = cos(uStamp.w), s = sin(uStamp.w);
  vec2 p = aCorner * uStamp.z * vec2(1.0, uRoundness);
  p = vec2(p.x * c - p.y * s, p.x * s + p.y * c) + uStamp.xy;
  vUv = aCorner + 0.5; vCanvas = p;
  gl_Position = vec4(p / uCanvas * 2.0 - 1.0, 0.0, 1.0);
}
"""

    const val FS_SMUDGE = H + """
in vec2 vUv; in vec2 vCanvas;
uniform sampler2D uShape; uniform sampler2D uGrain; uniform float uGrainScale; uniform float uGrainDepth;
uniform sampler2D uDstCopy; uniform vec2 uDstOrigin;
uniform sampler2D uPick; uniform vec2 uPickOrigin; uniform vec2 uDelta;
uniform float uRegion; uniform float uStrength;
out vec4 o;
void main(){
  float m = texture(uShape, vUv).r;
  float g = texture(uGrain, vCanvas / uGrainScale).r;
  float k = m * mix(1.0, g, uGrainDepth) * uStrength;
  vec4 d = texture(uDstCopy, (vCanvas - uDstOrigin) / uRegion);
  vec4 p = texture(uPick, (vCanvas - uDelta - uPickOrigin) / uRegion);
  o = mix(d, p, k);
}
"""

    /** The canvas on screen: quad corners already in NDC. */
    const val FS_SCREEN = H + """
in vec2 vUv; uniform sampler2D uTex; out vec4 o;
void main(){ o = texture(uTex, vUv); }
"""
}
