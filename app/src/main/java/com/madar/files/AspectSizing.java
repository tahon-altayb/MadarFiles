package com.madar.files;

/** Explicit pixel stretching, with even dimensions for hardware video encoders. */
final class AspectSizing {
 static int[] dimensions(String ratio,int shortEdge){
  if(shortEdge!=720&&shortEdge!=1080&&shortEdge!=2160)throw new IllegalArgumentException("اختر دقة 720 أو 1080 أو 2160");
  int a,b;switch(ratio){case "16:9":a=16;b=9;break;case "9:16":a=9;b=16;break;case "4:3":a=4;b=3;break;case "3:4":a=3;b=4;break;case "1:1":a=1;b=1;break;default:throw new IllegalArgumentException("نسبة الأبعاد غير مدعومة");}
  int w=a>=b?Math.round(shortEdge*(float)a/b):shortEdge,h=b>=a?Math.round(shortEdge*(float)b/a):shortEdge;
  return new int[]{(w+1)/2*2,(h+1)/2*2};
 }
}
