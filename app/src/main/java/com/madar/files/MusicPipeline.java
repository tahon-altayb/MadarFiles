package com.madar.files;

final class MusicPipeline {
 static final String LEGACY="voc_ft",AVCASS="avcass_roformer_sw";
 static final String AVCASS_SHA="3d2597992a157d0e57d04a875f74d2afcd96c02cd0c027c5e651cb62e786c6516";
 static String checked(String value){
  if(value==null||value.isEmpty()||LEGACY.equals(value))return LEGACY;
  // Old saved jobs have audio-only consent and fall back to voice.
  if(AVCASS.equals(value))return AVCASS;
  if("inst_hq_4_3".equals(value)||"bandit_v2_multi".equals(value))return LEGACY;
  throw new IllegalArgumentException("طريقة فصل الموسيقى غير معروفة");
 }
 static String fingerprint(String value){return AVCASS.equals(checked(value))?AVCASS_SHA:ColabMusic.MODEL_SHA;}
 private MusicPipeline(){}
}
