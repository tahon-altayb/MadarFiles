package com.madar.files;
import android.app.*;
import android.content.*;
/** Tap a completed background job to return to its saved results in Madar. */
final class WorkNotifications {
 static void complete(Context context,int id){
  NotificationManager manager=context.getSystemService(NotificationManager.class);manager.createNotificationChannel(new NotificationChannel("madar-completed","الملفات المكتملة",NotificationManager.IMPORTANCE_DEFAULT));
  PendingIntent open=PendingIntent.getActivity(context,id,new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
  manager.notify(id,new Notification.Builder(context,"madar-completed").setSmallIcon(com.madar.files.R.drawable.ic_launcher).setContentTitle("مدار · تم الانتهاء").setContentText("ملفك جاهز في المحادثة والملفات الأخيرة").setContentIntent(open).setAutoCancel(true).build());
 }
}
