import unittest
import sys
from pathlib import Path

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


if __name__ == "__main__":
    unittest.main()
