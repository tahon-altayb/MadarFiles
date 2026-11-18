package com.madar.files;
import android.graphics.Bitmap;
import java.util.*;
/** Detects visible straight table rules; does not infer borderless tables from prose. */
final class RasterRules {
 static List<WordTables.Rule> detect(Bitmap bitmap,float pageW,float pageH){int w=bitmap.getWidth(),h=bitmap.getHeight();int[] p=new int[w*h];bitmap.getPixels(p,0,w,0,0,w,h);List<WordTables.Rule> out=new ArrayList<>();float sx=pageW/w,sy=pageH/h;
  for(int y=0;y<h;y++){int from=-1,gap=0;for(int x=0;x<=w;x++){boolean dark=x<w&&dark(p[y*w+x]);if(dark){if(from<0)from=x;gap=0;}else if(from>=0){gap++;if(gap>2||x==w){int end=x-gap+1;if(end-from>=Math.max(32,w/8))add(out,new WordTables.Rule(from*sx,y*sy,end*sx,y*sy));from=-1;gap=0;}}}if(out.size()>1200)return Collections.emptyList();}
  for(int x=0;x<w;x++){int from=-1,gap=0;for(int y=0;y<=h;y++){boolean dark=y<h&&dark(p[y*w+x]);if(dark){if(from<0)from=y;gap=0;}else if(from>=0){gap++;if(gap>2||y==h){int end=y-gap+1;if(end-from>=Math.max(32,h/12))add(out,new WordTables.Rule(x*sx,from*sy,x*sx,end*sy));from=-1;gap=0;}}}if(out.size()>1200)return Collections.emptyList();}return out;
 }
 private static boolean dark(int p){return ((p>>>24)&255)>180&&(((p>>>16)&255)*299+((p>>>8)&255)*587+(p&255)*114)<125000;}
 private static void add(List<WordTables.Rule> rules,WordTables.Rule r){for(int i=rules.size()-1;i>=Math.max(0,rules.size()-80);i--){WordTables.Rule a=rules.get(i);if(Math.abs(a.x1-r.x1)<2&&Math.abs(a.x2-r.x2)<2&&Math.abs(a.y1-r.y1)<2&&Math.abs(a.y2-r.y2)<2)return;}rules.add(r);}
}
