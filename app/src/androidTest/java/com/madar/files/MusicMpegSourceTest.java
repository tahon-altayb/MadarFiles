package com.madar.files;

import android.content.Context;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class MusicMpegSourceTest {
    @Test public void copiesCompressedMpegAndHonorsTheRequestedEnd() throws Exception {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        Context target = instrumentation.getTargetContext();
        File original = File.createTempFile("fixture_mpeg_", ".mp3", target.getCacheDir());
        AudioPipeline.Compressed compact = null;
        MediaExtractor probe = new MediaExtractor();
        try {
            try (InputStream input = instrumentation.getContext().getAssets().open("watch-source.mp3");
                 FileOutputStream out = new FileOutputStream(original)) {
                FilesUtil.copy(input, out);
            }
            compact = AudioPipeline.audioOnly(target, original, 8_000_000, new AtomicBoolean());
            assertNotNull(compact);
            assertTrue(compact.file.length() < original.length() / 2);
            assertTrue(compact.durationUs >= 7_900_000 && compact.durationUs <= 8_000_000);
            probe.setDataSource(compact.file.getAbsolutePath());
            assertEquals("audio/mpeg", probe.getTrackFormat(0).getString(MediaFormat.KEY_MIME));
        } finally {
            probe.release();
            if (compact != null) compact.file.delete();
            original.delete();
        }
    }
}
