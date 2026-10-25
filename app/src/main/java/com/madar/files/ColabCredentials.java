package com.madar.files;
/** Native, one-use, two-minute handoff. Never persisted or passed in an Intent. */
final class ColabCredentials {
 static final class Grant {final String token,project;Grant(String t,String p){token=t;project=p;}}
 private static Grant grant;private static long expires;
 static synchronized void put(String t,String p){grant=new Grant(t,p);expires=android.os.SystemClock.elapsedRealtime()+120000;}
 static synchronized boolean ready(){if(android.os.SystemClock.elapsedRealtime()>expires)grant=null;return grant!=null;}
 static synchronized Grant take(){if(!ready())return null;Grant g=grant;grant=null;return g;}
 static synchronized void clear(){grant=null;}
}
