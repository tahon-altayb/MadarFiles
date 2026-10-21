package com.madar.files;
import java.text.Normalizer;
final class TextQuality {
 static String clean(String value){return Normalizer.normalize(value==null?"":value,Normalizer.Form.NFKC).replaceAll("[\\x00-\\x08\\x0B\\x0E-\\x1F\\uFFFD\\uE000-\\uF8FF]","").trim();}
 static boolean usable(String value,String language){String text=clean(value);int arabic=0,latin=0,other=0,digits=0;for(int at=0;at<text.length();){int ch=text.codePointAt(at);at+=Character.charCount(ch);if(Character.isDigit(ch))digits++;if(!Character.isLetter(ch))continue;if(Character.UnicodeScript.of(ch)==Character.UnicodeScript.ARABIC)arabic++;else if(Character.UnicodeScript.of(ch)==Character.UnicodeScript.LATIN)latin++;else other++;}int letters=arabic+latin+other;if(letters<3)return digits>=3;if(language.equals("ara")&&arabic<(letters*.55))return false;if(other>letters*.3)return false;if(arabic>0&&latin>Math.max(8,arabic*.35))return false;int fragments=0,words=0;for(String word:text.split("\\s+")){if(word.matches(".*[\\p{L}].*")){words++;if(word.codePointCount(0,word.length())<=1)fragments++;}}return words<4||fragments<words*.55;}
}
