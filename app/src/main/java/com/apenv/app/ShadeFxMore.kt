package com.apenv.app

private fun fp(key: String, def: String) = FxParam(key, false, def)
private fun vp(key: String, def: String) = FxParam(key, true, def)

private const val BLEND_FN = """
float blendCh(int m, float a, float b){
  if(m==0) return (1.0-2.0*b)*a*a + 2.0*b*a;
  if(m==1) return a<0.5 ? 2.0*a*b : 1.0-2.0*(1.0-a)*(1.0-b);
  if(m==2) return b<0.5 ? 2.0*a*b : 1.0-2.0*(1.0-a)*(1.0-b);
  if(m==3) return a*b*2.0;
  if(m==4) return b<0.5 ? 1.0-(1.0-a)/max(2.0*b,0.001) : a/max(2.0*(1.0-b),0.001);
  if(m==5) return a+2.0*b-1.0;
  return a+b-0.5;
}
"""

// More effects, recreated in GLSL. Parameter names are the INI keys so presets apply their own values.
// Some are approximations (marked with a note): the real shaders use depth, history or several passes.
internal object ShadeFxMore {
    val defs: Map<String, FxDef> = mapOf(
        "vignette" to FxDef(listOf(fp("Type", "0"), fp("Ratio", "1.0"), vp("Center", "0.5,0.5"), fp("Radius", "1.0"),
            fp("Amount", "-1.0"), fp("Slope", "2")), """
vec3 effect(vec3 c, vec2 uv){
  float aspect = uPx.y/uPx.x;
  vec2 tc = (uv - Center.xy) * vec2(aspect, Ratio) / max(Radius, 0.001);
  float v = max(dot(tc,tc), 0.000001);
  return c * (1.0 + pow(v, Slope*0.5)*Amount);
}"""),

        "fxaa" to FxDef(listOf(fp("EdgeThreshold", "0.125"), fp("EdgeThresholdMin", "0.0"), fp("Subpix", "0.25")), """
vec3 effect(vec3 c, vec2 uv){
  float lM = lum(c);
  float lNW = lum(smp(uv+vec2(-1.0,-1.0)*uPx));
  float lNE = lum(smp(uv+vec2( 1.0,-1.0)*uPx));
  float lSW = lum(smp(uv+vec2(-1.0, 1.0)*uPx));
  float lSE = lum(smp(uv+vec2( 1.0, 1.0)*uPx));
  float lMin = min(lM, min(min(lNW,lNE), min(lSW,lSE)));
  float lMax = max(lM, max(max(lNW,lNE), max(lSW,lSE)));
  if(lMax - lMin < max(EdgeThresholdMin, lMax*EdgeThreshold)) return c;
  vec2 dir = vec2(-((lNW+lNE)-(lSW+lSE)), ((lNW+lSW)-(lNE+lSE)));
  float dirReduce = max((lNW+lNE+lSW+lSE)*0.03125, 0.0078125);
  float rcpDirMin = 1.0/(min(abs(dir.x),abs(dir.y))+dirReduce);
  dir = clamp(dir*rcpDirMin, vec2(-8.0), vec2(8.0))*uPx;
  vec3 rgbA = 0.5*(smp(uv+dir*(1.0/3.0-0.5)) + smp(uv+dir*(2.0/3.0-0.5)));
  vec3 rgbB = rgbA*0.5 + 0.25*(smp(uv+dir*(-0.5)) + smp(uv+dir*0.5));
  float lB = lum(rgbB);
  vec3 res = (lB<lMin || lB>lMax) ? rgbA : rgbB;
  return mix(c, res, clamp(0.5+Subpix*2.0, 0.0, 1.0));
}"""),

        "smaa" to FxDef(listOf(fp("EdgeDetectionThreshold", "0.1")), """
vec3 effect(vec3 c, vec2 uv){
  float thr = EdgeDetectionThreshold;
  float l = lum(c);
  vec3 cL = smp(uv-vec2(uPx.x,0.0));
  vec3 cR = smp(uv+vec2(uPx.x,0.0));
  vec3 cT = smp(uv-vec2(0.0,uPx.y));
  vec3 cB = smp(uv+vec2(0.0,uPx.y));
  float dL = abs(l-lum(cL)), dR = abs(l-lum(cR)), dT = abs(l-lum(cT)), dB = abs(l-lum(cB));
  if(max(max(dL,dR),max(dT,dB)) < thr) return c;
  vec3 acc = c; float n = 1.0;
  if(dL>=thr){ acc+=cL; n+=1.0; }
  if(dR>=thr){ acc+=cR; n+=1.0; }
  if(dT>=thr){ acc+=cT; n+=1.0; }
  if(dB>=thr){ acc+=cB; n+=1.0; }
  return mix(c, acc/n, 0.6);
}""", note = "approximation of SMAA (edge-aware smoothing)"),

        "lut" to FxDef(listOf(fp("fLUT_AmountChroma", "1.0"), fp("fLUT_AmountLuma", "1.0"),
            fp("LUT_TileSizeXY", "32"), fp("LUT_TileAmount", "32")), """
vec3 effect(vec3 c, vec2 uv){
  float ts = LUT_TileSizeXY;
  vec2 texel = vec2(1.0/(ts*LUT_TileAmount), 1.0/ts);
  vec3 lc = vec3((c.xy*ts - c.xy + 0.5)*texel, c.z*ts - c.z);
  float lf = fract(lc.z);
  lc.x += (lc.z - lf)*texel.y;
  vec3 lutc = mix(texture(uLut, lc.xy).rgb, texture(uLut, vec2(lc.x+texel.y, lc.y)).rgb, lf);
  float l0 = max(length(c), 0.00001), l1 = max(length(lutc), 0.00001);
  return mix(c/l0, lutc/l1, fLUT_AmountChroma) * mix(l0, l1, fLUT_AmountLuma);
}""", lut = true),

        "reallongexposure" to FxDef(emptyList(), """
vec3 effect(vec3 c, vec2 uv){ return c; }""", note = "starts only when triggered in game, passes through here"),

        "quantv_post" to FxDef(listOf(
            vp("qBright", "1,1"), vp("qContrast", "0,0"), vp("qGamma", "1,1"), vp("qSaturation", "1,1"),
            fp("UseLevels", "0"), vp("iBlack", "0,0,0"), vp("iWhite", "1,1,1"), vp("oBlack", "0,0,0"), vp("oWhite", "1,1,1"),
            vp("Tint_d", "0.5,0.5,0.5"), vp("Tinti", "0,0"), vp("ColorMood_day", "0.5,0.5,0.5"), vp("ColorMoodIntensity", "0,0"),
            vp("ctemp", "6600,6600"), vp("ctemp_intensity", "0,0"), fp("Sharpen", "0.0"), fp("Noise", "0.0")), """
vec3 kelvin(float k){
  float t = k/100.0; vec3 o;
  o.r = t<=66.0 ? 1.0 : clamp(329.698727446*pow(max(t-60.0,0.001),-0.1332047592)/255.0, 0.0, 1.0);
  o.g = t<=66.0 ? clamp((99.4708025861*log(max(t,1.0))-161.1195681661)/255.0, 0.0, 1.0)
                : clamp(288.1221695283*pow(max(t-60.0,0.001),-0.0755148492)/255.0, 0.0, 1.0);
  o.b = t>=66.0 ? 1.0 : (t<=19.0 ? 0.0 : clamp((138.5177312231*log(max(t-10.0,1.0))-305.0447927307)/255.0, 0.0, 1.0));
  return o;
}
vec3 effect(vec3 c, vec2 uv){
  vec3 o = c;
  if(UseLevels > 0.5){
    o = (o - iBlack)/max(iWhite - iBlack, vec3(0.001));
    o = mix(oBlack, oWhite, clamp(o,0.0,1.0));
  }
  o *= qBright.x;
  o = (o-0.5)*(1.0+qContrast.x)+0.5;
  o = pow(max(o,0.0), vec3(1.0/max(qGamma.x,0.01)));
  o = mix(vec3(lum(o)), o, qSaturation.x);
  o = mix(o, o*Tint_d*2.0, clamp(Tinti.x,0.0,1.0));
  o = mix(o, o*ColorMood_day*2.0, clamp(ColorMoodIntensity.x,0.0,1.0));
  o = mix(o, o*kelvin(ctemp.x)/kelvin(6600.0), clamp(ctemp_intensity.x,0.0,1.0));
  if(Sharpen > 0.0){
    vec3 bl = (smp(uv+vec2(uPx.x,0.0))+smp(uv-vec2(uPx.x,0.0))+smp(uv+vec2(0.0,uPx.y))+smp(uv-vec2(0.0,uPx.y)))*0.25;
    o += (c - bl)*Sharpen*0.2;
  }
  if(Noise > 0.0){
    float h = fract(sin(dot(uv*vec2(1.0,uPx.y/uPx.x)+fract(uTime), vec2(12.9898,78.233)))*43758.5453);
    o += (h-0.5)*Noise;
  }
  return o;
}""", note = "colour grading and sharpen only, uses the day values"),

        "colourfulness" to FxDef(listOf(fp("colourfulness", "0.4"), fp("lim_luma", "0.7")), """
vec3 effect(vec3 c, vec2 uv){
  float l = lum(c);
  vec3 d = c - l;
  float maxd = max(abs(d.r), max(abs(d.g), abs(d.b)));
  float amt = colourfulness * (1.0 - clamp(maxd*2.0, 0.0, 1.0));
  vec3 o = l + d*(1.0+amt);
  o += (l - lum(o))*lim_luma;
  return o;
}""", note = "approximation"),

        "filmgrain" to FxDef(listOf(fp("Intensity", "0.5"), fp("Mean", "0.5"), fp("SignalToNoiseRatio", "6"), fp("Variance", "0.4")), """
vec3 effect(vec3 c, vec2 uv){
  float inv = dot(c, vec3(-1.0/3.0)) + 1.0;
  float stn = SignalToNoiseRatio != 0.0 ? pow(abs(inv), SignalToNoiseRatio) : 1.0;
  float variance = Variance*Variance*stn;
  float t = fract(uTime*0.37);
  float seed = dot(uv, vec2(12.9898,78.233));
  float u1 = fract(sin(seed)*43758.5453 + t);
  float u2 = fract(cos(seed)*53758.5453 - t);
  float g = sqrt(-2.0*log(max(u1,0.0001))) * cos(6.2831853*u2);
  return c + g*sqrt(variance)*Intensity*0.25;
}"""),

        "chromaticaberration" to FxDef(listOf(vp("Shift", "2.5,-0.5"), fp("Strength", "0.5")), """
vec3 effect(vec3 c, vec2 uv){
  vec3 o = c;
  o.r = smp(uv + uPx*Shift.xy).r;
  o.b = smp(uv - uPx*Shift.xy).b;
  return mix(c, o, Strength);
}"""),

        "prism" to FxDef(listOf(fp("Aberration", "6"), fp("Curve", "1.0")), """
vec3 effect(vec3 c, vec2 uv){
  vec2 d = uv - 0.5;
  float r = length(d);
  vec2 dir = d/max(r, 0.0001);
  vec2 off = dir*uPx*(Aberration*pow(clamp(r*1.4142,0.0,2.0), max(Curve,0.01)));
  return vec3(smp(uv+off).r, c.g, smp(uv-off).b);
}""", note = "approximation"),

        "unsharp" to FxDef(listOf(fp("Amount", "1.0"), fp("BlurScale", "1.0")), """
vec3 effect(vec3 c, vec2 uv){
  vec2 p = uPx*BlurScale;
  vec3 b = c*4.0
    + (smp(uv+vec2(p.x,0.0))+smp(uv-vec2(p.x,0.0))+smp(uv+vec2(0.0,p.y))+smp(uv-vec2(0.0,p.y)))*2.0
    + smp(uv+p)+smp(uv-p)+smp(uv+vec2(p.x,-p.y))+smp(uv+vec2(-p.x,p.y));
  b /= 16.0;
  return c + (c - b)*Amount;
}"""),

        "cas" to FxDef(listOf(fp("Contrast", "0.0"), fp("Sharpening", "1.0")), """
vec3 effect(vec3 e, vec2 uv){
  vec3 a = smp(uv+vec2(-1.0,-1.0)*uPx), b = smp(uv+vec2(0.0,-1.0)*uPx), c2 = smp(uv+vec2(1.0,-1.0)*uPx);
  vec3 d = smp(uv+vec2(-1.0,0.0)*uPx), f = smp(uv+vec2(1.0,0.0)*uPx);
  vec3 g = smp(uv+vec2(-1.0,1.0)*uPx), h = smp(uv+vec2(0.0,1.0)*uPx), i = smp(uv+vec2(1.0,1.0)*uPx);
  vec3 mn = min(min(min(d,e),min(f,b)),h); vec3 mn2 = min(mn, min(min(a,c2),min(g,i))); mn += mn2;
  vec3 mx = max(max(max(d,e),max(f,b)),h); vec3 mx2 = max(mx, max(max(a,c2),max(g,i))); mx += mx2;
  vec3 amp = clamp(min(mn, 2.0-mx)/max(mx, vec3(0.0001)), 0.0, 1.0);
  amp = inversesqrt(max(amp, vec3(0.0001)));
  float peak = -3.0*Contrast + 8.0;
  vec3 w = -1.0/(amp*peak);
  vec3 rcpW = 1.0/(4.0*w+1.0);
  vec3 o = clamp(((b+d)+(f+h))*w + e, 0.0, 1.0)*rcpW;
  return mix(e, o, Sharpening);
}"""),

        "adaptivesharpen" to FxDef(listOf(fp("curve_height", "1.0")), """
vec3 effect(vec3 c, vec2 uv){
  vec3 b = (smp(uv+vec2(uPx.x,0.0))+smp(uv-vec2(uPx.x,0.0))+smp(uv+vec2(0.0,uPx.y))+smp(uv-vec2(0.0,uPx.y)))*0.25;
  float s = clamp((lum(c)-lum(b))*curve_height*1.5, -0.08, 0.08);
  return c + s;
}""", note = "approximation"),

        "highpasssharpen" to FxDef(listOf(fp("HighPassSharpRadius", "2"), fp("HighPassSharpOffset", "1.0"), fp("HighPassBlendMode", "1"),
            fp("HighPassSharpStrength", "0.4"), fp("HighPassBlendIfDark", "0"), fp("HighPassBlendIfLight", "255"),
            fp("HighPassDarkIntensity", "1.0"), fp("HighPassLightIntensity", "1.0")), BLEND_FN + """
vec3 effect(vec3 c, vec2 uv){
  float sp = HighPassSharpRadius*0.5 + HighPassSharpOffset*0.25;
  float acc = 0.0, wsum = 0.0;
  for(int i=-1;i<=1;i++) for(int j=-1;j<=1;j++){
    float k = exp(-float(i*i+j*j)*0.6);
    acc += lum(smp(uv + vec2(float(i),float(j))*uPx*sp*2.0))*k; wsum += k;
  }
  float l = lum(c);
  float hp = clamp(l - acc/wsum + 0.5, 0.0, 1.0);
  int m = int(HighPassBlendMode+0.5);
  vec3 bl = vec3(blendCh(m,c.r,hp), blendCh(m,c.g,hp), blendCh(m,c.b,hp));
  float mask = smoothstep(0.0, max(HighPassBlendIfDark/255.0,0.001), l + 0.0001) * (1.0 - smoothstep(HighPassBlendIfLight/255.0, 1.001, l));
  if(HighPassBlendIfDark <= 0.0) mask = 1.0 - smoothstep(HighPassBlendIfLight/255.0, 1.001, l);
  float amt = HighPassSharpStrength * mix(HighPassDarkIntensity, HighPassLightIntensity, l) * mask;
  return mix(c, clamp(bl,0.0,1.0), clamp(amt,0.0,1.0));
}"""),

        "gaussianblur" to FxDef(listOf(fp("GaussianBlurRadius", "1"), fp("GaussianBlurOffset", "1.0"), fp("GaussianBlurStrength", "0.3")), """
vec3 effect(vec3 c, vec2 uv){
  float s = GaussianBlurOffset*max(GaussianBlurRadius,1.0);
  vec3 acc = vec3(0.0); float ws = 0.0;
  for(int i=-2;i<=2;i++) for(int j=-2;j<=2;j++){
    float w = exp(-float(i*i+j*j)*0.35);
    acc += smp(uv + vec2(float(i),float(j))*uPx*s)*w; ws += w;
  }
  return mix(c, acc/ws, clamp(GaussianBlurStrength,0.0,1.0));
}"""),

        "bloom" to FxDef(listOf(fp("fBloomThreshold", "0.8"), fp("fBloomAmount", "0.8"), fp("fBloomSaturation", "0.8"),
            vp("fBloomTint", "0.7,0.8,1.0"), fp("iBloomMixmode", "2")), """
vec3 effect(vec3 c, vec2 uv){
  vec3 acc = vec3(0.0); float ws = 0.0;
  for(int i=2;i<=6;i++){ float w = 1.0/float(i-1); acc += smpL(uv, float(i))*w; ws += w; }
  vec3 b = acc/ws;
  b = max(b - vec3(fBloomThreshold), 0.0) / max(1.0 - fBloomThreshold, 0.05);
  b = mix(vec3(lum(b)), b, fBloomSaturation) * fBloomTint * fBloomAmount * 2.0;
  int m = int(iBloomMixmode+0.5);
  if(m==1) return c + b;
  if(m==2) return 1.0 - (1.0-c)*(1.0-b);
  if(m==3) return mix(c, max(c,b), 0.8);
  return max(c, b);
}""", mips = true, note = "approximation (no lens flares)"),

        "bessel_bloom" to FxDef(listOf(fp("Threshold", "0.7"), fp("SoftKnee", "0.5"), fp("Intensity", "1.0"),
            fp("Saturation", "1.0"), fp("Gamma", "2.2")), """
vec3 effect(vec3 c, vec2 uv){
  vec3 acc = vec3(0.0); float ws = 0.0;
  for(int i=2;i<=6;i++){ float w = 1.0/float(i-1); acc += pow(max(smpL(uv, float(i)),0.0), vec3(Gamma))*w; ws += w; }
  vec3 b = acc/ws;
  float br = max(max(b.r,b.g),b.b);
  float soft = clamp(br - Threshold + SoftKnee, 0.0, 2.0*SoftKnee);
  soft = soft*soft/(4.0*SoftKnee + 0.0001);
  b *= max(soft, br - Threshold)/max(br, 0.0001);
  b = mix(vec3(lum(b)), b, Saturation)*Intensity;
  return pow(pow(max(c,0.0), vec3(Gamma)) + b, vec3(1.0/Gamma));
}""", mips = true, note = "approximation")
    )
}
