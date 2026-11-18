package com.madar.files;
import org.junit.Test;
import static org.junit.Assert.*;
public class TextQualityTest {
 @Test public void brokenPdfMappingTriggersVisualReading(){assertFalse(TextQuality.usable("الصفحة CVADUAL E0 Atyl MANS wee QZ","ara"));assertFalse(TextQuality.usable("X Y Z ا و ب ل","ara"));assertTrue(TextQuality.usable("قيمة المنتجات بعد التخفيض والتوصيل والمجموع","ara"));}
 @Test public void legitimateEnglishAndNumbersAreRetained(){assertTrue(TextQuality.usable("Invoice subtotal and delivery","eng"));assertTrue(TextQuality.usable("Invoice subtotal and delivery","ara+eng"));assertTrue(TextQuality.usable("152.00 22.50 174.50","ara"));assertEquals("لا",TextQuality.clean("ﻻ"));}
}
