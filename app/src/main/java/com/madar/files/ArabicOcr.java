package com.madar.files;
import android.content.Context;
import android.graphics.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
/** PP-OCRv5 mobile detection + Arabic/English CTC recognition, fully on-device. */
final class ArabicOcr implements AutoCloseable {
 static final class Line {final Rect box;final String text;final float confidence;Line(Rect b,String t,float c){box=b;text=t;confidence=c;}}
 private final VisionSession detector,recognizer;private final List<String> alphabet=new ArrayList<>();
 ArabicOcr(Context context)throws Exception{
  detector=new VisionSession(OfflineAssets.file(context,"ocr-det.onnx"));VisionSession next=null;try{next=new VisionSession(OfflineAssets.file(context,"ocr-arabic.onnx"));alphabet.add("");try(BufferedReader reader=new BufferedReader(new InputStreamReader(new FileInputStream(OfflineAssets.file(context,"ocr-arabic.txt")),StandardCharsets.UTF_8))){String line;while((line=reader.readLine())!=null)alphabet.add(line);}alphabet.add(" ");recognizer=next;}catch(Exception|Error e){detector.close();if(next!=null)next.close();throw e;}
 }
 List<Line> read(Bitmap original)throws Exception{
  List<Line> lines=readWindow(original);int width=original.getWidth(),height=original.getHeight();
  // A full-page 960px detector loses small print. Overlapping native-resolution
  // windows recover it; clipped edge detections must not replace complete lines.
  if(Math.max(width,height)>1400){
   for(int top=0;top<height;top+=800)for(int left=0;left<width;left+=800){
    if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException();
    int w=Math.min(960,width-left),h=Math.min(960,height-top);if(w<100||h<100)continue;
    Bitmap tile=Bitmap.createBitmap(original,left,top,w,h);
    try{for(Line local:readWindow(tile)){
     Rect b=local.box;if(left>0&&b.left<4||top>0&&b.top<4||left+w<width&&b.right>w-4||top+h<height&&b.bottom>h-4)continue;
     Rect box=new Rect(b);box.offset(left,top);Line incoming=new Line(box,local.text,local.confidence);int duplicate=-1;
     for(int i=0;i<lines.size();i++){Rect old=lines.get(i).box;int overlap=Math.max(0,Math.min(old.right,box.right)-Math.max(old.left,box.left))*Math.max(0,Math.min(old.bottom,box.bottom)-Math.max(old.top,box.top));float area=Math.max(1,Math.min(old.width()*old.height(),box.width()*box.height()));if(overlap/area>.65f&&Math.abs(old.exactCenterY()-box.exactCenterY())<Math.max(old.height(),box.height())*.45f){duplicate=i;break;}}
     if(duplicate<0){if(lines.size()<2000)lines.add(incoming);}else{Line old=lines.get(duplicate);if(incoming.confidence>old.confidence+.015f||incoming.text.length()>old.text.length()&&incoming.confidence>=old.confidence-.02f)lines.set(duplicate,incoming);}
    }}finally{if(tile!=original)tile.recycle();}
   }
  }
  return unique(lines);
 }
 private List<Line> readWindow(Bitmap original)throws Exception{
  int width=original.getWidth(),height=original.getHeight();float scale=Math.min(1,960f/Math.max(width,height));int dw=Math.max(32,Math.round(width*scale/32)*32),dh=Math.max(32,Math.round(height*scale/32)*32);
  Bitmap small=Bitmap.createScaledBitmap(original,dw,dh,true);float[] tensor=tensor(small,dw,dh,true);if(small!=original)small.recycle();float[] probability=detector.infer(tensor,1,3,dh,dw);long[] dims=detector.dimensions();if(dims.length!=4)throw new IOException("خرج كشف النص غير صالح");int mh=(int)dims[2],mw=(int)dims[3];if(mh*mw!=probability.length)throw new IOException("أبعاد كشف النص غير صالحة");boolean[] seen=new boolean[probability.length];int[] queue=new int[probability.length];List<Line> lines=new ArrayList<>();
  for(int pixel=0;pixel<probability.length;pixel++){
   if(seen[pixel]||probability[pixel]<.3f)continue;int head=0,tail=0,minX=mw,minY=mh,maxX=0,maxY=0;double score=0;seen[pixel]=true;queue[tail++]=pixel;
   while(head<tail){int at=queue[head++],x=at%mw,y=at/mw;score+=probability[at];minX=Math.min(minX,x);maxX=Math.max(maxX,x);minY=Math.min(minY,y);maxY=Math.max(maxY,y);for(int dy=-1;dy<=1;dy++)for(int dx=-1;dx<=1;dx++){int nx=x+dx,ny=y+dy;if(nx<0||nx>=mw||ny<0||ny>=mh)continue;int n=ny*mw+nx;if(!seen[n]&&probability[n]>=.3f){seen[n]=true;queue[tail++]=n;}}}
   if(tail<5||maxX-minX<2||maxY-minY<2||score/tail<.5)continue;if(lines.size()>=500)break;
   float pad=Math.max(2,(maxY-minY+1)*.45f);Rect box=new Rect(Math.max(0,(int)((minX-pad)*width/mw)),Math.max(0,(int)((minY-pad)*height/mh)),Math.min(width,(int)Math.ceil((maxX+1+pad)*width/mw)),Math.min(height,(int)Math.ceil((maxY+1+pad)*height/mh)));
   if(box.width()<2||box.height()<2)continue;Bitmap crop=Bitmap.createBitmap(original,box.left,box.top,box.width(),box.height());try{Line line=recognize(crop,box);if(!line.text.trim().isEmpty()&&line.confidence>=.4)lines.add(line);}finally{if(crop!=original)crop.recycle();}
  }
  // Recover complete lines on simple printed cards. DB sometimes detects only
  // the middle of a large highlighted heading or treats vowel marks as words.
  for(Rect box:printedRows(original)){
   boolean overlaps=false;for(Line line:lines)if(sameRow(line.box,box)){overlaps=true;break;}if(!overlaps)continue;
   Bitmap crop=Bitmap.createBitmap(original,box.left,box.top,box.width(),box.height());try{Line candidate=recognize(crop,box);if(candidate.confidence>=.5f&&!candidate.text.isBlank()){
    List<Line> row=new ArrayList<>();for(Line old:lines)if(sameRow(old.box,box)||box.contains(old.box)&&old.box.height()<box.height()*.55)row.add(old);
    int count=0;double score=0;for(Line old:row){count+=old.text.length();score+=old.confidence*old.text.length();}
    // Confidence alone must not let a short fragment erase a complete line.
    if(candidate.text.length()>=count*.8&&candidate.confidence>=score/Math.max(1,count)-.025){lines.removeAll(row);lines.add(candidate);}
   }}finally{if(crop!=original)crop.recycle();}
  }
  return unique(lines);
 }
 private Line recognize(Bitmap image,Rect box)throws Exception{
  Line original=recognizeOnce(image,box);if(original.confidence>=.94f)return original;
  Bitmap contrast=printedContrast(image);try{Line improved=recognizeOnce(contrast,box);return improved.confidence>original.confidence+.04f&&improved.text.length()>=original.text.length()*.75?improved:original;}finally{contrast.recycle();}
 }
 private Line recognizeOnce(Bitmap image,Rect box)throws Exception{
  int width=Math.max(320,Math.min(3072,(int)Math.ceil(48.0*image.getWidth()/image.getHeight()/32)*32));int resized=Math.min(width,(int)Math.ceil(48.0*image.getWidth()/image.getHeight()));Bitmap small=Bitmap.createScaledBitmap(image,Math.max(1,resized),48,true);float[] input=new float[3*48*width],data=tensor(small,resized,48,false);if(small!=image)small.recycle();for(int c=0;c<3;c++)for(int y=0;y<48;y++)System.arraycopy(data,c*resized*48+y*resized,input,c*width*48+y*width,resized);
  float[] predictions=recognizer.infer(input,1,3,48,width);long[] dims=recognizer.dimensions();if(dims.length!=3||dims[2]!=alphabet.size())throw new IOException("قاموس OCR لا يطابق النموذج");int classes=(int)dims[2],steps=(int)dims[1],previous=-1,n=0;double confidence=0;StringBuilder text=new StringBuilder();for(int step=0;step<steps;step++){int best=0;float score=-Float.MAX_VALUE;for(int ch=0;ch<classes;ch++)if(predictions[step*classes+ch]>score){score=predictions[step*classes+ch];best=ch;}if(best!=0&&best!=previous){text.append(alphabet.get(best));confidence+=score;n++;}previous=best;}
  return new Line(box,logicalArabic(text.toString()).trim(),(float)(confidence/Math.max(1,n)));
 }
 static String logicalArabic(String visual){
  if(!visual.codePoints().anyMatch(ch->Character.UnicodeScript.of(ch)==Character.UnicodeScript.ARABIC))return visual;
  // The Arabic CTC dictionary is trained in Paddle's visual character order.
  // Reverse its individual code points, including marks, using the upstream
  // pred_reverse convention. Grouping a mark with the visual previous letter
  // moves that mark to the wrong logical letter.
  List<String> parts=new ArrayList<>();StringBuilder latin=new StringBuilder();for(int at=0;at<visual.length();){int ch=visual.codePointAt(at);at+=Character.charCount(ch);boolean ascii=ch<128&&(Character.isLetterOrDigit(ch)||" :*./%+-".indexOf(ch)>=0);if(ascii){latin.appendCodePoint(ch);continue;}if(latin.length()>0){parts.add(latin.toString());latin.setLength(0);}parts.add(new String(Character.toChars(ch)));}if(latin.length()>0)parts.add(latin.toString());Collections.reverse(parts);StringBuilder logical=new StringBuilder();for(String p:parts)logical.append(p);return TextQuality.clean(logical.toString());
 }
 static boolean sameRow(Rect a,Rect b){int intersection=Math.max(0,Math.min(a.right,b.right)-Math.max(a.left,b.left));return intersection>=Math.min(a.width(),b.width())*.6&&Math.abs(a.exactCenterY()-b.exactCenterY())<Math.min(a.height(),b.height())*.45;}
 static List<Line> unique(List<Line> source){List<Line> sorted=new ArrayList<>(source);sorted.sort((a,b)->Float.compare(b.confidence,a.confidence));List<Line> out=new ArrayList<>();for(Line line:sorted){boolean duplicate=false;for(Line old:out)if(sameRow(old.box,line.box)){int overlap=Math.max(0,Math.min(old.box.right,line.box.right)-Math.max(old.box.left,line.box.left));if(overlap>=Math.min(old.box.width(),line.box.width())*.85&&old.text.length()>=line.text.length()*.8){duplicate=true;break;}}if(!duplicate)out.add(line);}out.sort(Comparator.comparingInt(l->l.box.top));return out;}
 private static Bitmap printedContrast(Bitmap b){int w=b.getWidth(),h=b.getHeight();int[] pixels=new int[w*h];b.getPixels(pixels,0,w,0,0,w,h);for(int i=0;i<pixels.length;i++){int p=pixels[i],l=(Color.red(p)*299+Color.green(p)*587+Color.blue(p)*114)/1000;pixels[i]=l<110?Color.BLACK:Color.WHITE;}return Bitmap.createBitmap(pixels,w,h,Bitmap.Config.ARGB_8888);}
 static List<Rect> printedRows(Bitmap b){int w=b.getWidth(),h=b.getHeight();List<Rect> out=new ArrayList<>();if(w<150||h<150||w*(long)h>10000000)return out;int[] p=new int[w*h];b.getPixels(p,0,w,0,0,w,h);boolean[] ink=new boolean[p.length];int[] column=new int[w],row=new int[h];for(int y=0;y<h;y++)for(int x=0;x<w;x++){int c=p[y*w+x];if(Color.alpha(c)>180&&Color.red(c)*299+Color.green(c)*587+Color.blue(c)*114<110000){ink[y*w+x]=true;column[x]++;}}
  // Ignore page frames. They are not characters and must not widen every row.
  for(int y=0;y<h;y++)for(int x=0;x<w;x++)if(column[x]<h*.55&&ink[y*w+x])row[y]++;
  int start=-1,last=-1;for(int y=0;y<=h+4;y++){boolean active=y<h&&row[y]>Math.max(4,w*.008);if(active){if(start<0)start=y;last=y;}if(start>=0&&y-last>4){int height=last-start+1;if(height>=8&&height<h*.22){int left=w,right=0,count=0;for(int yy=start;yy<=last;yy++)for(int x=0;x<w;x++)if(column[x]<h*.55&&ink[yy*w+x]){left=Math.min(left,x);right=Math.max(right,x);count++;}if(right-left>w*.25&&count/(double)Math.max(1,(right-left+1)*height)<.55)out.add(new Rect(Math.max(0,left-4),Math.max(0,start-3),Math.min(w,right+5),Math.min(h,last+4)));}start=-1;}}
  return out;
 }
 static String text(List<Line> lines){List<List<Line>> rows=new ArrayList<>();List<Line> sorted=new ArrayList<>(lines);sorted.sort(Comparator.comparingDouble(l->l.box.exactCenterY()));for(Line line:sorted){List<Line> row=rows.isEmpty()?null:rows.get(rows.size()-1);if(row==null||Math.abs(row.get(0).box.exactCenterY()-line.box.exactCenterY())>Math.max(row.get(0).box.height(),line.box.height())*.55f){row=new ArrayList<>();rows.add(row);}row.add(line);}StringBuilder text=new StringBuilder();for(List<Line> row:rows){long ar=0,latin=0;for(Line l:row){ar+=l.text.codePoints().filter(ch->Character.UnicodeScript.of(ch)==Character.UnicodeScript.ARABIC).count();latin+=l.text.codePoints().filter(ch->Character.UnicodeScript.of(ch)==Character.UnicodeScript.LATIN).count();}final boolean rtl=ar>latin;row.sort((a,b)->rtl?Integer.compare(b.box.left,a.box.left):Integer.compare(a.box.left,b.box.left));if(text.length()>0)text.append('\n');for(int i=0;i<row.size();i++){if(i>0)text.append('\t');text.append(row.get(i).text);}}return text.toString();}
 private static float[] tensor(Bitmap bitmap,int width,int height,boolean detect){int[] pixels=new int[width*height];bitmap.getPixels(pixels,0,width,0,0,width,height);float[] out=new float[3*pixels.length];float[] mean={.485f,.456f,.406f},std={.229f,.224f,.225f};for(int i=0;i<pixels.length;i++){int p=pixels[i],alpha=Color.alpha(p);int[] values={Color.blue(p),Color.green(p),Color.red(p)};for(int c=0;c<3;c++){float value=(values[c]*alpha/255f+255-alpha)/255f;out[c*pixels.length+i]=detect?(value-mean[c])/std[c]:(value-.5f)*2;}}return out;}
 @Override public void close(){recognizer.close();detector.close();}
}
