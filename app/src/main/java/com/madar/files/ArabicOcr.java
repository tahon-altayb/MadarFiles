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
  int width=original.getWidth(),height=original.getHeight();float scale=Math.min(1,960f/Math.max(width,height));int dw=Math.max(32,Math.round(width*scale/32)*32),dh=Math.max(32,Math.round(height*scale/32)*32);
  Bitmap small=Bitmap.createScaledBitmap(original,dw,dh,true);float[] tensor=tensor(small,dw,dh,true);if(small!=original)small.recycle();float[] probability=detector.infer(tensor,1,3,dh,dw);long[] dims=detector.dimensions();if(dims.length!=4)throw new IOException("خرج كشف النص غير صالح");int mh=(int)dims[2],mw=(int)dims[3];if(mh*mw!=probability.length)throw new IOException("أبعاد كشف النص غير صالحة");boolean[] seen=new boolean[probability.length];int[] queue=new int[probability.length];List<Line> lines=new ArrayList<>();
  for(int pixel=0;pixel<probability.length;pixel++){
   if(seen[pixel]||probability[pixel]<.3f)continue;int head=0,tail=0,minX=mw,minY=mh,maxX=0,maxY=0;double score=0;seen[pixel]=true;queue[tail++]=pixel;
   while(head<tail){int at=queue[head++],x=at%mw,y=at/mw;score+=probability[at];minX=Math.min(minX,x);maxX=Math.max(maxX,x);minY=Math.min(minY,y);maxY=Math.max(maxY,y);for(int dy=-1;dy<=1;dy++)for(int dx=-1;dx<=1;dx++){int nx=x+dx,ny=y+dy;if(nx<0||nx>=mw||ny<0||ny>=mh)continue;int n=ny*mw+nx;if(!seen[n]&&probability[n]>=.3f){seen[n]=true;queue[tail++]=n;}}}
   if(tail<5||maxX-minX<2||maxY-minY<2||score/tail<.5)continue;if(lines.size()>=500)break;
   float pad=Math.max(2,(maxY-minY+1)*.45f);Rect box=new Rect(Math.max(0,(int)((minX-pad)*width/mw)),Math.max(0,(int)((minY-pad)*height/mh)),Math.min(width,(int)Math.ceil((maxX+1+pad)*width/mw)),Math.min(height,(int)Math.ceil((maxY+1+pad)*height/mh)));
   if(box.width()<2||box.height()<2)continue;Bitmap crop=Bitmap.createBitmap(original,box.left,box.top,box.width(),box.height());try{Line line=recognize(crop,box);if(!line.text.trim().isEmpty()&&line.confidence>=.4)lines.add(line);}finally{if(crop!=original)crop.recycle();}
  }
  lines.sort(Comparator.comparingInt(l->l.box.top));return lines;
 }
 private Line recognize(Bitmap image,Rect box)throws Exception{
  int width=Math.max(320,Math.min(1280,(int)Math.ceil(48.0*image.getWidth()/image.getHeight()/32)*32));int resized=Math.min(width,(int)Math.ceil(48.0*image.getWidth()/image.getHeight()));Bitmap small=Bitmap.createScaledBitmap(image,Math.max(1,resized),48,true);float[] input=new float[3*48*width],data=tensor(small,resized,48,false);if(small!=image)small.recycle();for(int c=0;c<3;c++)for(int y=0;y<48;y++)System.arraycopy(data,c*resized*48+y*resized,input,c*width*48+y*width,resized);
  float[] predictions=recognizer.infer(input,1,3,48,width);long[] dims=recognizer.dimensions();if(dims.length!=3||dims[2]!=alphabet.size())throw new IOException("قاموس OCR لا يطابق النموذج");int classes=(int)dims[2],steps=(int)dims[1],previous=-1,n=0;double confidence=0;StringBuilder text=new StringBuilder();for(int step=0;step<steps;step++){int best=0;float score=-Float.MAX_VALUE;for(int ch=0;ch<classes;ch++)if(predictions[step*classes+ch]>score){score=predictions[step*classes+ch];best=ch;}if(best!=0&&best!=previous){text.append(alphabet.get(best));confidence+=score;n++;}previous=best;}
  return new Line(box,logicalArabic(text.toString()).trim(),(float)(confidence/Math.max(1,n)));
 }
 static String logicalArabic(String visual){
  if(!visual.codePoints().anyMatch(ch->Character.UnicodeScript.of(ch)==Character.UnicodeScript.ARABIC))return visual;
  List<String> parts=new ArrayList<>();StringBuilder latin=new StringBuilder();for(int at=0;at<visual.length();){int ch=visual.codePointAt(at);at+=Character.charCount(ch);boolean ascii=ch<128&&(Character.isLetterOrDigit(ch)||" .,:/\\-+%()".indexOf(ch)>=0);if(ascii){latin.appendCodePoint(ch);continue;}if(latin.length()>0){parts.add(latin.toString());latin.setLength(0);}if(Character.getType(ch)==Character.NON_SPACING_MARK&&!parts.isEmpty()){int last=parts.size()-1;parts.set(last,parts.get(last)+new String(Character.toChars(ch)));}else parts.add(new String(Character.toChars(ch)));}if(latin.length()>0)parts.add(latin.toString());Collections.reverse(parts);StringBuilder logical=new StringBuilder();for(String p:parts)logical.append(p);return TextQuality.clean(logical.toString());
 }
 static String text(List<Line> lines){List<List<Line>> rows=new ArrayList<>();List<Line> sorted=new ArrayList<>(lines);sorted.sort(Comparator.comparingDouble(l->l.box.exactCenterY()));for(Line line:sorted){List<Line> row=rows.isEmpty()?null:rows.get(rows.size()-1);if(row==null||Math.abs(row.get(0).box.exactCenterY()-line.box.exactCenterY())>Math.max(row.get(0).box.height(),line.box.height())*.55f){row=new ArrayList<>();rows.add(row);}row.add(line);}StringBuilder text=new StringBuilder();for(List<Line> row:rows){row.sort((a,b)->Integer.compare(b.box.left,a.box.left));if(text.length()>0)text.append('\n');for(int i=0;i<row.size();i++){if(i>0)text.append('\t');text.append(row.get(i).text);}}return text.toString();}
 private static float[] tensor(Bitmap bitmap,int width,int height,boolean detect){int[] pixels=new int[width*height];bitmap.getPixels(pixels,0,width,0,0,width,height);float[] out=new float[3*pixels.length];float[] mean={.485f,.456f,.406f},std={.229f,.224f,.225f};for(int i=0;i<pixels.length;i++){int p=pixels[i],alpha=Color.alpha(p);int[] values={Color.blue(p),Color.green(p),Color.red(p)};for(int c=0;c<3;c++){float value=(values[c]*alpha/255f+255-alpha)/255f;out[c*pixels.length+i]=detect?(value-mean[c])/std[c]:(value-.5f)*2;}}return out;}
 @Override public void close(){recognizer.close();detector.close();}
}
