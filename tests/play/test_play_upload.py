"""scripts/play-upload.py: which Play releases go out at once and which wait as drafts.

Run: python3 -m unittest discover -s tests/play (the Checks workflow, on every pull request).
"""

import importlib.machinery
import importlib.util
import sys
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[2] / "scripts" / "play-upload.py"


def load():
    loader = importlib.machinery.SourceFileLoader("play_upload", str(SCRIPT))
    spec = importlib.util.spec_from_loader("play_upload", loader)
    module = importlib.util.module_from_spec(spec)
    loader.exec_module(module)
    return module


class ReleaseStatus(unittest.TestCase):
    def setUp(self):
        self.play = load()

    def test_internal_and_closed_testing_go_out_at_once(self):
        # Every alpha upload is a fresh closed-track release: Play makes its pre-launch report.
        self.assertEqual(self.play.release_status("internal"), "completed")
        self.assertEqual(self.play.release_status("alpha"), "completed")

    def test_a_draft_is_asked_for_and_open_testing_and_production_stay_drafts(self):
        self.assertEqual(self.play.release_status("alpha", draft=True), "draft")
        # Internal testing ignores the draft input, as it always went out at once.
        self.assertEqual(self.play.release_status("internal", draft=True), "completed")
        for track in ("beta", "production"):
            self.assertEqual(self.play.release_status(track), "draft")
            self.assertEqual(self.play.release_status(track, draft=True), "draft")

    def test_the_workflows_draft_input_as_it_reaches_the_script(self):
        for raw in ("true", "True", " true ", "1", "yes"):
            self.assertTrue(self.play.draft_requested(raw), raw)
        for raw in ("false", "", None, "0", "no", "draft"):
            self.assertFalse(self.play.draft_requested(raw), raw)

    def test_loads_without_googles_libraries(self):
        # They load inside the functions that call Google, so the rules above run anywhere.
        self.assertNotIn("googleapiclient", sys.modules)
        self.assertNotIn("google.auth", sys.modules)


class Workflow(unittest.TestCase):
    def test_play_yml_passes_the_draft_input_and_defaults_to_out_at_once(self):
        text = (SCRIPT.parents[1] / ".github" / "workflows" / "play.yml").read_text()
        self.assertIn("      draft:\n", text)
        self.assertIn("        type: boolean\n        default: false\n", text)
        self.assertIn("PLAY_DRAFT: ${{ inputs.draft && 'true' || 'false' }}", text)


if __name__ == "__main__":
    unittest.main()
