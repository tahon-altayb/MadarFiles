package com.madar.files;

import static org.junit.Assert.*;
import org.junit.Test;

/** The chat brain thinks for real only where it pays; greetings and clear commands are answered at once. */
public class ConversationThoughtTest {
 @Test public void clearRequestsAnswerAtOnce(){
  for(String quick:new String[]{"هلو","السلام عليكم","حولها jpg","أزل الموسيقى من الفيديو","قص من 2 إلى 8 ثوان","سوي موشن لمحلي"})assertFalse(quick,LocalAgent.needsThought(quick));
 }
 @Test public void opinionsPlansAndLongRequestsThink(){
  for(String deep:new String[]{"وش رأيك أخلي الإعلان 15 ثانية ولا 30؟","ايش الأفضل للمحل، عمودي ولا أفقي؟","ليش الصوت طلع واطي؟","اقترح لي خطة لإعلان افتتاح الفرع","قص البداية ثم حسّن الصوت وبعدين صدّر المقطع"})assertTrue(deep,LocalAgent.needsThought(deep));
  StringBuilder longText=new StringBuilder();for(int i=0;i<30;i++)longText.append("أبي الإعلان يكون مرتب ");assertTrue(LocalAgent.needsThought(longText.toString()));
 }
}
