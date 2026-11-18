# Motion reliability — 1.9.23 (58)

The v57 failure was reproduced in an actual hosted Colab runtime using Python
3.13.18. `speech_runtime.python()` reached `speech_install / validate` and failed
while SILMA imported Matplotlib. Colab exported
`MPLBACKEND=module://matplotlib_inline.backend_inline`; that notebook backend was
not installed in the isolated Python 3.11 speech environment. The exception was
`ValueError: Key backend ... is not a valid value for backend`.

Setting `MPLBACKEND=Agg` in the speech child environment allowed the same installed
environment and SILMA API import to pass in Colab. The notebook's own backend
remains unchanged. The approved reference, voice model, inference settings and
all other tools' environments are unchanged.

The visible voice selection now overrides stale voice words in a full brief.
After a failed motion, voice-only replies (including `خليه بدون صوت رجالي`) and
`كمل` after a dropdown change continue the stored motion directly. A changed
voice does not reuse a checkpoint belonging to the previous voice. New tasks and
thread restoration show their own voice selection. The native report records
the actual submitted voice mode before allocating a runtime.

Elapsed time now updates independently of network progress events. The v57
screen recording showed a fixed `0:07` while connection work continued; the
regression test advances 65 seconds without another progress callback.

Validation includes the exact failed dropdown/brief combination, colloquial
voice correction, dropdown-only continuation, reload and v57 saved-state cases,
and native project creation from a silent field with stale male wording. The
real voice workflow uses Python 3.13 with Colab's inline backend, proves the old
import error in the installed environment, and tests speech, alignment, MP4 and
recovery with the corrected child environment. Silent full-generation fixtures
poison the speech module, so any unexpected speech setup fails the test.
