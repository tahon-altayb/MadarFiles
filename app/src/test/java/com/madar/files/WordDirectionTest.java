package com.madar.files;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.zip.*;
import org.junit.Test;
import static org.junit.Assert.*;
public class WordDirectionTest {
 @Test public void arabicParagraphKeepsLatinAmountInItsOwnLtrRun()throws Exception{File file=File.createTempFile("word-direction-",".docx");try{TextProcessor.docx(file,"قيمة المنتجات\t125.00 SAR");String xml="";try(ZipInputStream zip=new ZipInputStream(new FileInputStream(file))){ZipEntry e;while((e=zip.getNextEntry())!=null)if(e.getName().equals("word/document.xml"))xml=new String(zip.readAllBytes(),StandardCharsets.UTF_8);}assertTrue(xml.contains("<w:bidi/>"));assertTrue(xml.contains("<w:rtl w:val=\"0\"/>"));assertTrue(xml.contains("125.00 SAR"));assertTrue(xml.contains("<w:tab/>"));javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));}finally{file.delete();}}
}
