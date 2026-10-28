package com.madar.files;

/** Fixed messages only: no signed URL, cookies or arbitrary server text in diagnostics. */
@androidx.annotation.OptIn(markerClass=androidx.media3.common.util.UnstableApi.class)
final class MediaFailure {
 static String describe(Throwable error,boolean audio){
  for(Throwable cause=error;cause!=null;cause=cause.getCause()){
   if(cause instanceof androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException){int code=((androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)cause).responseCode;return code==401||code==403?"رفض السيرفر طلب الوسائط؛ قد يكون الرابط منتهيًا أو يتطلب ترويسات من التطبيق المصدر ("+code+")":code==404||code==410?"رابط الوسائط غير متاح أو انتهت صلاحيته ("+code+")":"تعذر الاتصال بسيرفر الوسائط (HTTP "+code+")";}
   if(cause instanceof java.net.SocketTimeoutException)return "انتهت مهلة قراءة الوسائط من السيرفر؛ أعد المحاولة";
   if(cause instanceof java.net.UnknownHostException)return "تعذر الوصول إلى عنوان سيرفر الوسائط";
   if(cause instanceof javax.net.ssl.SSLException)return "تعذر الاتصال المشفّر بسيرفر الوسائط";
   if(cause instanceof android.media.MediaCodec.CodecException)return "تعذر فك ترميز مسار الوسائط على الجهاز";
  }
  if(error instanceof androidx.media3.common.PlaybackException){int code=((androidx.media3.common.PlaybackException)error).errorCode;if(code>=4000&&code<5000)return "تعذر فك ترميز الفيديو على الجهاز";if(code>=2000&&code<3000)return "تعذرت قراءة الوسائط من المصدر؛ تحقق من اتصال السيرفر وصلاحية الرابط";if(code>=3000&&code<4000)return "تعذر قراءة صيغة الوسائط التي أعادها السيرفر";}
  return audio?"تعذرت قراءة مسار الصوت من الرابط؛ قد يرفض المصدر طلب الاستخراج":"تعذر تشغيل الوسائط؛ افتح تفاصيل التشغيل لمعرفة رمز الخطأ";
 }
}
