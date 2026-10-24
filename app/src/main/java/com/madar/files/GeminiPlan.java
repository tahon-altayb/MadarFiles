package com.madar.files;
/** Plans bounded inline WAV requests; reserves space for base64, prompts and output. */
final class GeminiPlan {
 static final int RATE=16000,CONTEXT=3*RATE;
 static final long REQUEST_BYTES=20_000_000L,RESERVE_BYTES=512_000L;
 // Output length also matters: do not fit hours of compressed audio into one subtitle response.
 static final int MAX_SECONDS=180;
 static int step(){long budget=((REQUEST_BYTES-RESERVE_BYTES)/4*3-44)/2-2L*CONTEXT;return (int)Math.min(MAX_SECONDS*RATE,budget);}
 static int requests(long frames,boolean review){if(frames<=0)throw new IllegalArgumentException("Empty audio");return Math.toIntExact((1+(frames-1)/step())*(review?2:1));}
 static long encodedWavBytes(long frames){return 4*((44+frames*2+2)/3);}
}
