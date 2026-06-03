import unittest
import unittest.mock
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

    def test_inspect_zip_content_even_with_bin_extension(self):
        with TemporaryDirectory() as tmp:
            archive = Path(tmp) / "download.bin"
            with zipfile.ZipFile(archive, "w") as zf:
                zf.writestr("cube.stl", "solid cube\nendsolid cube\n")

            result = main._inspect_download_file(archive)

        self.assertEqual(result["kind"], "archive")
        self.assertEqual(result["supported_entries"][0]["path"], "cube.stl")

    def test_inspect_binary_stl_even_with_bin_extension(self):
        with TemporaryDirectory() as tmp:
            stl = Path(tmp) / "download.bin"
            header = b"Rhinoceros Binary STL".ljust(80, b" ")
            stl.write_bytes(header + (1).to_bytes(4, "little") + (b"\0" * 50))

            result = main._inspect_download_file(stl)

        self.assertEqual(result["kind"], "geometry")
        self.assertEqual(result["supported_entries"][0]["filename"], "download.stl")

    def test_create_import_ignores_entry_path_for_direct_3mf(self):
        with TemporaryDirectory() as tmp:
            data_dir = Path(tmp)
            source = data_dir / "direct.3mf"
            with zipfile.ZipFile(source, "w") as zf:
                zf.writestr("[Content_Types].xml", "<Types/>")
                zf.writestr("3D/3dmodel.model", "<model/>")

            old_downloads = main.remote_downloads
            old_imports = main.imported_models
            main.remote_downloads = {
                "download-id": main.RemoteDownloadJob(
                    id="download-id",
                    url="https://example.invalid/direct.3mf",
                    filename="direct.3mf",
                    output_dir=str(data_dir),
                    status="succeeded",
                    path=str(source),
                    size=source.stat().st_size,
                )
            }
            main.imported_models = {}
            try:
                with unittest.mock.patch.dict(main.os.environ, {"ORCA_SERVICE_DATA_DIR": str(data_dir)}):
                    result = main.create_import(main.ImportRequest(download_id="download-id", entry_path="direct.3mf"))
            finally:
                main.remote_downloads = old_downloads
                main.imported_models = old_imports

        self.assertEqual(result["imported_model"]["filename"], "direct.3mf")
        self.assertEqual(result["imported_model"]["import_type"], "project_or_geometry")

    def test_create_import_renames_binary_stl_downloaded_as_bin(self):
        with TemporaryDirectory() as tmp:
            data_dir = Path(tmp)
            source = data_dir / "download.bin"
            header = b"Rhinoceros Binary STL".ljust(80, b" ")
            source.write_bytes(header + (1).to_bytes(4, "little") + (b"\0" * 50))

            old_downloads = main.remote_downloads
            old_imports = main.imported_models
            main.remote_downloads = {
                "download-id": main.RemoteDownloadJob(
                    id="download-id",
                    url="https://example.invalid/download",
                    filename="download.bin",
                    output_dir=str(data_dir),
                    status="succeeded",
                    path=str(source),
                    size=source.stat().st_size,
                )
            }
            main.imported_models = {}
            try:
                with unittest.mock.patch.dict(main.os.environ, {"ORCA_SERVICE_DATA_DIR": str(data_dir)}):
                    result = main.create_import(main.ImportRequest(download_id="download-id"))
            finally:
                main.remote_downloads = old_downloads
                main.imported_models = old_imports

        self.assertEqual(result["imported_model"]["filename"], "download.stl")
        self.assertEqual(result["imported_model"]["import_type"], "geometry")


if __name__ == "__main__":
    unittest.main()
