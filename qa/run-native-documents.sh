#!/usr/bin/env bash
# The emulator action runs script lines in separate shells; keep status in this process.
set -uo pipefail
adb shell wm density 180 || exit $?
status=0
gradle --no-daemon -Dorg.gradle.jvmargs="-Xmx1536m -Dfile.encoding=UTF-8" --max-workers=2 -PvalidationBuild=true :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.madar.files.DocumentToolsTest,com.madar.files.ImageBatchTest,com.madar.files.CaptionExportTest,com.madar.files.AudioWaveformTest,com.madar.files.InteractivePreviewTest,com.madar.files.PlayerLayoutTest,com.madar.files.TranslationPathsTest,com.madar.files.FrameStepperTest,com.madar.files.LocalMediaServerTest,com.madar.files.EditorAudioProcessorTest,com.madar.files.PreviewRecoveryTest,com.madar.files.GeminiQualityTest,com.madar.files.OnlineTranslationTest,com.madar.files.WordTableExportTest,com.madar.files.EditableDocumentTest,com.madar.files.CloudDocumentsTest,com.madar.files.RecentFilesTest,com.madar.files.AssistantConversationTest,com.madar.files.WorkspaceToolsTest,com.madar.files.AgentIntentsTest,com.madar.files.CutoutBrushTest,com.madar.files.LinkDownloadsEngineTest --stacktrace || status=$?
mkdir -p phone-screenshots || status=1
adb pull /data/local/tmp/madar-editor.png phone-screenshots/editor.png || true
adb pull /data/local/tmp/madar-editor-failed.png phone-screenshots/editor-failed.png || true
adb pull /data/local/tmp/madar-pdf.png phone-screenshots/pdf.png || true
python3 qa/collect-table-document.py || status=1
exit "$status"
