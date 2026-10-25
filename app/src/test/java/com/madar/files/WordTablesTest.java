package com.madar.files;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;
public class WordTablesTest {
 private List<WordTables.Rule> grid(boolean merged){List<WordTables.Rule> r=new ArrayList<>();for(int y:new int[]{50,80,110})r.add(new WordTables.Rule(20,y,220,y));for(int x:new int[]{20,120,220})r.add(new WordTables.Rule(x,merged&&x==120?80:50,x,110));return r;}
 @Test public void reconstructsEditableCellsAndMergedHeader(){WordTables.Table t=WordTables.detect(grid(true)).get(0);assertEquals(2,t.rows);assertEquals(t.root(0),t.root(1));assertNotEquals(t.root(2),t.root(3));LayoutWordExporter.Line l=new LayoutWordExporter.Line();l.x=25;l.y=55;l.w=80;l.h=10;l.text="عنوان & value";l.font="Arial-Bold";l.size=12;l.rtl=true;assertTrue(t.add(l));String xml=t.xml();assertTrue(xml.contains("<w:tbl>"));assertTrue(xml.contains("<w:gridSpan w:val=\"2\"/>"));assertTrue(xml.contains("عنوان &amp; value"));assertTrue(xml.contains("<w:bidi/>"));assertFalse(xml.contains("txbxContent"));}
 @Test public void underlinesAndOpenGridsAreNotTables(){assertTrue(WordTables.detect(Arrays.asList(new WordTables.Rule(20,50,220,50))).isEmpty());List<WordTables.Rule> r=grid(false);r.remove(0);assertTrue(WordTables.detect(r).isEmpty());}
 @Test public void missingHorizontalBoundaryCreatesVerticalMerge(){List<WordTables.Rule> r=grid(false);r.remove(1);r.add(new WordTables.Rule(120,80,220,80));WordTables.Table t=WordTables.detect(r).get(0);assertEquals(t.root(0),t.root(2));assertTrue(t.xml().contains("<w:vMerge w:val=\"restart\"/>"));assertTrue(t.xml().contains("<w:vMerge/>"));}
 @Test public void proseCrossingAnInternalBorderIsNeverSqueezedIntoACell(){WordTables.Table t=WordTables.detect(grid(false)).get(0);LayoutWordExporter.Line l=new LayoutWordExporter.Line();l.x=25;l.y=55;l.w=180;l.h=12;l.size=12;l.text="A complete paragraph spans the decoration";assertFalse(t.add(l));assertTrue(t.cells[t.root(0)].isEmpty());assertTrue(t.cells[t.root(1)].isEmpty());}
}
