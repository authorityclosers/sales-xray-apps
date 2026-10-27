"""scripts/testlab.py: which of Test Lab's devices a run takes, and the keyless credential file for gcloud.

Run: python3 -m unittest discover -s tests/testlab (the Checks workflow, on every pull request).
"""

import importlib.machinery
import importlib.util
import json
import os
import stat
import tempfile
import unittest
from pathlib import Path
from unittest import mock

SCRIPT = Path(__file__).resolve().parents[2] / "scripts" / "testlab.py"


def load():
    loader = importlib.machinery.SourceFileLoader("testlab", str(SCRIPT))
    spec = importlib.util.spec_from_loader("testlab", loader)
    module = importlib.util.module_from_spec(spec)
    loader.exec_module(module)
    return module


def model(id, form, versions, brand="Google", factor="PHONE", density=420, tags=()):
    return {
        "id": id,
        "form": form,
        "formFactor": factor,
        "brand": brand,
        "screenDensity": density,
        "supportedVersionIds": [str(v) for v in versions],
        "tags": list(tags),
    }


# A catalogue shaped like `gcloud firebase test android models list --format=json`.
CATALOGUE = [
    model("oriole", "PHYSICAL", [31, 32, 33], brand="Google", density=411),
    model("a10", "PHYSICAL", [28, 29], brand="samsung", density=320, tags=["deprecated=28"]),
    model("redfin", "PHYSICAL", [30], brand="Google", density=440),
    model("a14", "PHYSICAL", [33, 34], brand="samsung", density=300),
    model("MediumPhone.arm", "VIRTUAL", [26, 30, 32, 33, 34, 35]),
    model("SmallPhone.arm", "VIRTUAL", [26, 29, 30, 34], density=280),
    model("MediumTablet.arm", "VIRTUAL", [30, 34], factor="TABLET"),
    model("Pixel2.arm", "VIRTUAL", [26, 27, 28]),
]


class Devices(unittest.TestCase):
    def setUp(self):
        self.lab = load()

    def test_a_run_takes_a_physical_budget_phone_and_old_and_new_virtual_phones(self):
        chosen = self.lab.choose(CATALOGUE)
        self.assertEqual(
            chosen,
            [
                # Samsung (a budget brand) before Google; of those, the lower density; its newest Android in range.
                "model=a14,version=34,locale=en,orientation=portrait",
                "model=SmallPhone.arm,version=29,locale=en,orientation=portrait",
                "model=MediumPhone.arm,version=35,locale=en,orientation=portrait",
            ],
        )

    def test_spark_quota_one_physical_and_at_most_two_virtual_a_run(self):
        chosen = self.lab.choose(CATALOGUE)
        self.assertEqual(sum("arm," not in line for line in chosen), 1)
        self.assertLessEqual(sum("arm," in line for line in chosen), 2)

    def test_deprecated_old_tablet_and_out_of_range_versions_are_left_out(self):
        self.assertEqual(self.lab.usable_versions(CATALOGUE[1]), [29])
        self.assertEqual(self.lab.usable_versions(CATALOGUE[7]), [])  # Android 8 and 9 only
        ids = " ".join(self.lab.choose(CATALOGUE))
        self.assertNotIn("MediumTablet", ids)
        self.assertNotIn("Pixel2", ids)

    def test_one_virtual_phone_covering_both_ends_is_used_once_per_version(self):
        only = [model("MediumPhone.arm", "VIRTUAL", [29, 35])]
        self.assertEqual(
            self.lab.choose(only),
            [
                "model=MediumPhone.arm,version=29,locale=en,orientation=portrait",
                "model=MediumPhone.arm,version=35,locale=en,orientation=portrait",
            ],
        )
        same = [model("MediumPhone.arm", "VIRTUAL", [34])]
        self.assertEqual(len(self.lab.choose(same)), 1)

    def test_no_phone_in_range_is_an_error_not_an_empty_run(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "models.json"
            path.write_text(json.dumps([CATALOGUE[7]]), encoding="utf-8")
            self.assertEqual(self.lab.main(["devices", str(path)]), 1)


class Credentials(unittest.TestCase):
    def setUp(self):
        self.lab = load()

    def test_the_file_is_play_uploads_keyless_shape_and_private(self):
        env = {
            "TESTLAB_WIF_PROVIDER": "projects/1/locations/global/workloadIdentityPools/github/providers/apps",
            "TESTLAB_SERVICE_ACCOUNT": "runner@lab.iam.gserviceaccount.com",
            "ACTIONS_ID_TOKEN_REQUEST_URL": "https://token.example/request?x=1",
            "ACTIONS_ID_TOKEN_REQUEST_TOKEN": "fictional-request-token",
        }
        with tempfile.TemporaryDirectory() as folder, mock.patch.dict(os.environ, env, clear=False):
            path = Path(folder) / "creds.json"
            self.assertEqual(self.lab.main(["credentials", str(path)]), 0)
            info = json.loads(path.read_text(encoding="utf-8"))
            if os.name == "posix":
                self.assertEqual(stat.S_IMODE(path.stat().st_mode), 0o600)
        self.assertEqual(info["type"], "external_account")
        self.assertEqual(info["audience"], "//iam.googleapis.com/" + env["TESTLAB_WIF_PROVIDER"])
        self.assertTrue(info["service_account_impersonation_url"].endswith("runner@lab.iam.gserviceaccount.com:generateAccessToken"))
        self.assertIn(
            "&audience=https%3A%2F%2Fiam.googleapis.com%2Fprojects%2F1%2Flocations",
            info["credential_source"]["url"],
        )
        self.assertNotIn("private_key", json.dumps(info))

    def test_without_the_runs_oidc_token_it_refuses(self):
        with tempfile.TemporaryDirectory() as folder, mock.patch.dict(os.environ, {}, clear=True):
            self.assertEqual(self.lab.main(["credentials", str(Path(folder) / "creds.json")]), 1)


if __name__ == "__main__":
    unittest.main()
