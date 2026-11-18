package com.madar.files;

/** Profile identity is snapshotted per job; absent options retain the existing model. */
final class MusicPipeline {
 static final String LEGACY="voc_ft",FX="inst_hq_4_3";
 static final String FX_SHA="e165729735b0493b2711b73de37f17cd57dc0a550bd2dff1d4c627586ed32a55";
 static String checked(String value){if(value==null||value.isEmpty()||LEGACY.equals(value))return LEGACY;if(FX.equals(value))return FX;throw new IllegalArgumentException("طريقة فصل الموسيقى غير معروفة");}
 static String fingerprint(String value){return FX.equals(checked(value))?FX_SHA:ColabMusic.MODEL_SHA;}
 private MusicPipeline(){}
}
