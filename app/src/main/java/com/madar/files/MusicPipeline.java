package com.madar.files;

/** Profile identity is snapshotted per job; absent options retain the existing model. */
final class MusicPipeline {
 static final String LEGACY="voc_ft",FX="inst_hq_4_3",BANDIT="bandit_v2_multi",AVCASS="avcass_roformer_sw";
 static final String FX_SHA="e165729735b0493b2711b73de37f17cd57dc0a550bd2dff1d4c627586ed32a55";
 static final String BANDIT_SHA="65d9dae8baf15a817ad28dc3551a13f316fec61d269307098769aeff3f3ee11a";
 static final String AVCASS_SHA="3d2597992a157d0e57d04a875f74d2afcd96c02cd0c027c5e651cb62e786c6516";
 static String checked(String value){if(value==null||value.isEmpty()||LEGACY.equals(value))return LEGACY;if(FX.equals(value)||BANDIT.equals(value)||AVCASS.equals(value))return value;throw new IllegalArgumentException("طريقة فصل الموسيقى غير معروفة");}
 static String fingerprint(String value){switch(checked(value)){case FX:return FX_SHA;case BANDIT:return BANDIT_SHA;case AVCASS:return AVCASS_SHA;default:return ColabMusic.MODEL_SHA;}}
 private MusicPipeline(){}
}
