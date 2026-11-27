package com.apenv.app

internal class FxParam(val key: String, val vec: Boolean, val def: String)
internal class FxDef(
    val params: List<FxParam>,
    val body: String,
    val mips: Boolean = false,   // the shader samples blurred mip levels (smpL)
    val lut: Boolean = false,    // the shader needs the LUT image (uLut)
    val note: String? = null     // shown under the effect name, e.g. "approximation"
)

private fun f(key: String, def: String) = FxParam(key, false, def)
private fun v(key: String, def: String) = FxParam(key, true, def)

// Colour effects recreated in GLSL from the public ReShade/SweetFX shader logic. Parameter names
// are the INI keys, so any preset using these techniques is applied with its own values.
internal object ShadeFx {
    private val needsDepth = "needs the game's depth buffer, which screen capture can't provide"

    // Set by ShadeStore when a LUT image has been loaded.
    @Volatile var lutReady = false

    fun status(file: String): String? = when {
        file == "lut" && !lutReady -> "needs a LUT image: load one in the LUT card below"
        file in defs -> null
        file.contains("mxao") || file.contains("rtgi") || file.contains("globalillumination") || file.contains("ssr") ||
            file.contains("dof") || file.contains("depth") || file.contains("ssdo") || file.contains("ssao") ||
            file.contains("ssgi") || file.contains("radiantgi") || file.contains("pcgi") || file.contains("volumetric") -> needsDepth
        file.startsWith("quantv") -> "QuantV replaces the game's own rendering, which a screen overlay can't do"
        file.contains("eyeadaption") || file.contains("longexposure") || file.contains("motionblur") || file.contains("taa") -> "needs previous frames (not supported yet)"
        file == "multilut" -> "needs several LUT images (not supported yet)"
        else -> "not supported yet"
    }

    fun def(file: String): FxDef? = defs[file]

    private val baseDefs: Map<String, FxDef> = mapOf(
        "vibrance" to FxDef(listOf(f("Vibrance", "0.15"), v("VibranceRGBBalance", "1,1,1")), """
vec3 effect(vec3 c, vec2 uv){
  float luma = dot(c, vec3(0.212656,0.715158,0.072186));
  float sat = max(c.r,max(c.g,c.b)) - min(c.r,min(c.g,c.b));
  vec3 k = VibranceRGBBalance * Vibrance;
  return mix(vec3(luma), c, 1.0 + k*(1.0 - sign(k)*sat));
}"""),
        "curves" to FxDef(listOf(f("Mode", "0"), f("Formula", "4"), f("Contrast", "0.65")), """
vec3 effect(vec3 c, vec2 uv){
  float PI = 3.14159265;
  float luma = dot(c, vec3(0.2126,0.7152,0.0722));
  vec3 chroma = c - luma;
  int mode = int(Mode+0.5); int fm = int(Formula+0.5);
  float blend = Contrast;
  vec3 x = mode==0 ? vec3(luma) : (mode==1 ? chroma*0.5+0.5 : c);
  x = clamp(x,0.0,1.0);
  if(fm==1){ x = sin(PI*0.5*x); x *= x; }
  else if(fm==2){ x = x-0.5; x = x/(0.5+abs(x))+0.5; }
  else if(fm==3){ x = x*x*(3.0-2.0*x); }
  else if(fm==4){ x = (1.0524*exp(6.0*x)-1.05248)/(exp(6.0*x)+20.0855); }
  else if(fm==5){ x = x*(x*(1.5-x)+0.5); blend *= 2.0; }
  else if(fm==6){ x = x*x*x*(x*(x*6.0-15.0)+10.0); }
  else if(fm==7){ x = -0.5*cos(PI*x)+0.5; }
  else { x = smoothstep(0.0,1.0,x); }
  if(mode==0) return c + (mix(vec3(luma), x, blend) - luma);
  if(mode==1) return luma + mix(chroma, (x-0.5)*2.0, blend);
  return mix(c, x, blend);
}"""),
        "dpx" to FxDef(listOf(f("Strength", "0.2"), v("RGB_Curve", "8,8,8"), v("RGB_C", "0.36,0.36,0.34"),
            f("Contrast", "0.1"), f("Saturation", "3.0"), f("Colorfulness", "2.5")), """
const mat3 RGBm = mat3(2.6714711726599600,-1.2672360578624100,-0.4109956021722270,
  -1.0251070139898500,1.9840911624108900,0.0439502493584124,
  0.0610009456429445,-0.2236707508128630,1.1590210416706100);
const mat3 XYZm = mat3(0.5003033835433160,0.3380975732227390,0.1645897795458570,
  0.2579688942747580,0.6761952591447060,0.0658358459823868,
  0.0234517888692628,0.1126992737203000,0.8668396731242010);
vec3 effect(vec3 c, vec2 uv){
  vec3 B = c;
  B = B*(1.0-Contrast) + (0.5*Contrast);
  vec3 Bt = 1.0/(1.0+exp(RGB_Curve/2.0));
  B = (1.0/(1.0+exp(-RGB_Curve*(B-RGB_C)))/(-2.0*Bt+1.0)) + (-Bt/(-2.0*Bt+1.0));
  float value = max(max(B.r,B.g),B.b);
  vec3 col = B/max(value,0.0001);
  col = pow(max(col,0.0), vec3(Colorfulness));
  col *= value;
  vec3 c0 = col*XYZm;
  float luma = dot(c0, vec3(0.2126,0.7152,0.0722));
  c0 = (1.0-Saturation)*luma + Saturation*c0;
  c0 = c0*RGBm;
  return mix(c, c0, Strength);
}"""),
        "filmicpass" to FxDef(listOf(f("Strength", "1.0"), f("BaseGamma", "1.0"), f("Fade", "0.4"),
            f("Contrast", "1.0"), f("Bleach", "0.0"), f("Saturation", "0.0"), v("LumCoeff", "0.212656,0.715158,0.072186"),
            f("Linearization", "0.5"), f("BaseCurve", "1.5"), f("EffectGamma", "0.65"), f("EffectGammaR", "1.0"),
            f("EffectGammaG", "1.0"), f("EffectGammaB", "1.0"), f("RedCurve", "1.0"), f("GreenCurve", "1.0"), f("BlueCurve", "1.0")), """
vec3 effect(vec3 c, vec2 uv){
  vec3 G = c;
  vec3 B = clamp(c,0.0,1.0);
  B = pow(B, vec3(Linearization));
  B = mix(vec3(0.01), B, Contrast);
  float A = dot(B, LumCoeff);
  vec3 D = vec3(A);
  B = pow(B, vec3(1.0/BaseGamma));
  float a = RedCurve, b = GreenCurve, cc = BlueCurve, d = BaseCurve;
  float y = 1.0/(1.0+exp(a/2.0)), z = 1.0/(1.0+exp(b/2.0)), w = 1.0/(1.0+exp(cc/2.0)), vv = 1.0/(1.0+exp(d/2.0));
  vec3 C = B;
  D.r = (1.0/(1.0+exp(-a*(D.r-0.5))) - y)/(1.0-2.0*y);
  D.g = (1.0/(1.0+exp(-b*(D.g-0.5))) - z)/(1.0-2.0*z);
  D.b = (1.0/(1.0+exp(-cc*(D.b-0.5))) - w)/(1.0-2.0*w);
  D = pow(max(D,0.0), vec3(1.0/EffectGamma));
  D = mix(D, 1.0-D, Bleach);
  D.r = pow(abs(D.r), 1.0/EffectGammaR);
  D.g = pow(abs(D.g), 1.0/EffectGammaG);
  D.b = pow(abs(D.b), 1.0/EffectGammaB);
  C.r = D.r<0.5 ? (2.0*D.r-1.0)*(B.r-B.r*B.r)+B.r : (2.0*D.r-1.0)*(sqrt(B.r)-B.r)+B.r;
  C.g = D.g<0.5 ? (2.0*D.g-1.0)*(B.g-B.g*B.g)+B.g : (2.0*D.g-1.0)*(sqrt(B.g)-B.g)+B.g;
  C.b = D.b<0.5 ? (2.0*D.b-1.0)*(B.b-B.b*B.b)+B.b : (2.0*D.b-1.0)*(sqrt(B.b)-B.b)+B.b;
  vec3 F = mix(B, C, Strength);
  F = (1.0/(1.0+exp(-d*(F-0.5))) - vv)/(1.0-2.0*vv);
  float R = dot(F, LumCoeff);
  F = mix(vec3(R), F, 1.0 + Saturation);
  F = mix(F, G, clamp(Fade,0.0,1.0)*0.5);
  return F;
}"""),
        "clarity" to FxDef(listOf(f("ClarityRadius", "3"), f("ClarityOffset", "2.0"), f("ClarityBlendMode", "2"),
            f("ClarityStrength", "0.4"), f("ClarityBlendIfDark", "50"), f("ClarityBlendIfLight", "205"),
            f("ClarityDarkIntensity", "0.4"), f("ClarityLightIntensity", "0.000")), """
float blendCh(int m, float a, float b){
  if(m==0) return (1.0-2.0*b)*a*a + 2.0*b*a;
  if(m==1) return a<0.5 ? 2.0*a*b : 1.0-2.0*(1.0-a)*(1.0-b);
  if(m==2) return b<0.5 ? 2.0*a*b : 1.0-2.0*(1.0-a)*(1.0-b);
  if(m==3) return a*b*2.0;
  if(m==4) return b<0.5 ? 1.0-(1.0-a)/max(2.0*b,0.001) : a/max(2.0*(1.0-b),0.001);
  if(m==5) return a+2.0*b-1.0;
  return a+b-0.5;
}
vec3 effect(vec3 c, vec2 uv){
  float sp = ClarityRadius*0.5 + ClarityOffset*0.25;
  float acc = 0.0, wsum = 0.0;
  for(int i=-1;i<=1;i++) for(int j=-1;j<=1;j++){
    float k = exp(-float(i*i+j*j)*0.6);
    acc += lum(smp(uv + vec2(float(i),float(j))*uPx*sp*2.0))*k; wsum += k;
  }
  float blur = acc/wsum;
  float l = lum(c);
  float hp = clamp(l - blur + 0.5, 0.0, 1.0);
  int m = int(ClarityBlendMode+0.5);
  vec3 bl = vec3(blendCh(m,c.r,hp), blendCh(m,c.g,hp), blendCh(m,c.b,hp));
  float mask = smoothstep(0.0, max(ClarityBlendIfDark/255.0,0.001), l) * (1.0 - smoothstep(ClarityBlendIfLight/255.0, 1.0, l));
  float amt = ClarityStrength * mix(1.0+ClarityDarkIntensity, 1.0+ClarityLightIntensity, l) * mask;
  return mix(c, clamp(bl,0.0,1.0), clamp(amt,0.0,1.0));
}"""),
        "lumasharpen" to FxDef(listOf(f("sharp_strength", "0.65"), f("sharp_clamp", "0.035"), f("pattern", "2"), f("offset_bias", "1.0")), """
vec3 effect(vec3 c, vec2 uv){
  vec2 px = uPx*offset_bias;
  vec3 blur = (smp(uv+vec2(px.x,-px.y)*0.5)+smp(uv+vec2(-px.x,-px.y)*0.5)+smp(uv+vec2(px.x,px.y)*0.5)+smp(uv+vec2(-px.x,px.y)*0.5))*0.25;
  if(int(pattern+0.5)==3){
    blur = (smp(uv+vec2(px.x,0.0))+smp(uv-vec2(px.x,0.0))+smp(uv+vec2(0.0,px.y))+smp(uv-vec2(0.0,px.y)))*0.25;
  }
  vec3 sharp = c - blur;
  float s = dot(sharp, vec3(0.2126,0.7152,0.0722))*sharp_strength;
  s = clamp(s, -sharp_clamp, sharp_clamp);
  return c + s;
}"""),
        "levels" to FxDef(listOf(f("BlackPoint", "16"), f("WhitePoint", "235"), f("HighlightClipping", "0")), """
vec3 effect(vec3 c, vec2 uv){
  float bp = BlackPoint/255.0, wp = WhitePoint/255.0;
  return (c-bp)/max(wp-bp, 0.001);
}"""),
        "liftgammagain" to FxDef(listOf(v("RGB_Lift", "1,1,1"), v("RGB_Gamma", "1,1,1"), v("RGB_Gain", "1,1,1")), """
vec3 effect(vec3 c, vec2 uv){
  c = c*(1.5-0.5*RGB_Lift) + 0.5*RGB_Lift - 0.5;
  c = clamp(c*RGB_Gain, 0.0, 1.0);
  return pow(c, 1.0/max(RGB_Gamma, vec3(0.01)));
}"""),
        "tonemap" to FxDef(listOf(f("Gamma", "1.0"), f("Exposure", "0.0"), f("Saturation", "0.0"), f("Bleach", "0.0"),
            f("Defog", "0.0"), v("FogColor", "0,0,1")), """
vec3 effect(vec3 c, vec2 uv){
  c = clamp(c - Defog*FogColor*2.55, 0.0, 1.0);
  c *= pow(2.0, Exposure);
  c = pow(c, vec3(Gamma));
  float l = dot(c, vec3(0.2126,0.7152,0.0722));
  vec3 blend = vec3(l);
  float L = clamp(10.0*(l-0.45), 0.0, 1.0);
  vec3 A2 = Bleach*c;
  vec3 r1 = 2.0*c*blend;
  vec3 r2 = 1.0 - 2.0*(1.0-blend)*(1.0-c);
  vec3 nc = mix(r1, r2, L);
  vec3 mixRGB = A2*nc;
  c += (1.0-A2)*mixRGB;
  vec3 mid = vec3(dot(c, vec3(1.0/3.0)));
  vec3 diff = c - mid;
  return (c + diff*Saturation)/(1.0 + diff*Saturation);
}"""),
        "fakehdr" to FxDef(listOf(f("HDRPower", "1.30"), f("radius1", "0.793"), f("radius2", "0.87")), """
vec3 ring(vec2 uv, float r){
  vec2 p = uPx*r;
  vec3 s = smp(uv+vec2(1.5,-1.5)*p)+smp(uv+vec2(-1.5,-1.5)*p)+smp(uv+vec2(1.5,1.5)*p)+smp(uv+vec2(-1.5,1.5)*p)
    +smp(uv+vec2(0.0,-2.5)*p)+smp(uv+vec2(0.0,2.5)*p)+smp(uv+vec2(-2.5,0.0)*p)+smp(uv+vec2(2.5,0.0)*p);
  return s;
}
vec3 effect(vec3 c, vec2 uv){
  vec3 b1 = ring(uv, radius1*1.0)*0.005;
  vec3 b2 = ring(uv, radius2*2.0)*0.010;
  float dist = radius2 - radius1;
  vec3 hdr = (c + (b2 - b1))*dist;
  vec3 blend = hdr + c;
  return pow(abs(blend), vec3(abs(HDRPower))) + hdr;
}"""),
        "monochrome" to FxDef(listOf(v("Monochrome_conversion_values", "0.21,0.72,0.07"), f("Monochrome_color_saturation", "0.0")), """
vec3 effect(vec3 c, vec2 uv){
  float g = dot(c, Monochrome_conversion_values);
  return mix(vec3(g), c, Monochrome_color_saturation);
}""")
    )
    val defs: Map<String, FxDef> = baseDefs + ShadeFxMore.defs
}
