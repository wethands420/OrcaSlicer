import unittest
import sys
import zipfile
from pathlib import Path
from tempfile import TemporaryDirectory

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app import main


class CoreHelpersTest(unittest.TestCase):
    def test_safe_name_removes_paths_and_unsafe_chars(self):
        self.assertEqual(main._safe_name("../bad:name?.stl"), "bad_name_.stl")

    def test_mqtt_remaining_length_uses_mqtt_variable_encoding(self):
        self.assertEqual(main._mqtt_remaining_length(127), b"\x7f")
        self.assertEqual(main._mqtt_remaining_length(128), b"\x80\x01")
        self.assertEqual(main._mqtt_remaining_length(321), b"\xc1\x02")

    def test_summarize_reports_keeps_latest_print_values(self):
        reports = [
            {"print": {"gcode_state": "PREPARE", "nozzle_temper": 40}},
            {"print": {"gcode_state": "RUNNING", "mc_percent": 1}},
        ]

        self.assertEqual(
            main._summarize_reports(reports),
            {"gcode_state": "RUNNING", "nozzle_temper": 40, "mc_percent": 1},
        )

    def test_detect_supported_file_type(self):
        self.assertEqual(main._detect_import_type(Path("part.stl")), "geometry")
        self.assertEqual(main._detect_import_type(Path("project.3mf")), "project_or_geometry")
        self.assertEqual(main._detect_import_type(Path("bundle.zip")), "archive")
        self.assertIsNone(main._detect_import_type(Path("notes.txt")))

    def test_inspect_zip_lists_supported_models(self):
        with TemporaryDirectory() as tmp:
            archive = Path(tmp) / "models.zip"
            with zipfile.ZipFile(archive, "w") as zf:
                zf.writestr("folder/cube.stl", "solid cube\nendsolid cube\n")
                zf.writestr("readme.txt", "ignore")

            result = main._inspect_download_file(archive)

        self.assertEqual(result["kind"], "archive")
        self.assertEqual(result["supported_entries"][0]["path"], "folder/cube.stl")
        self.assertEqual(result["supported_entries"][0]["import_type"], "geometry")

    def test_inspect_zip_rejects_path_traversal(self):
        with TemporaryDirectory() as tmp:
            archive = Path(tmp) / "bad.zip"
            with zipfile.ZipFile(archive, "w") as zf:
                zf.writestr("../escape.stl", "solid bad\nendsolid bad\n")

            with self.assertRaises(ValueError):
                main._inspect_download_file(archive)


if __name__ == "__main__":
    unittest.main()
