from __future__ import annotations

import csv
import json
import tempfile
import unittest
from pathlib import Path

from openpyxl import Workbook
from pathlab_ai_data.bracs_roi import (
    BracsRoiPreparationConfig,
    RoiPreparationError,
    prepare_bracs_roi,
)
from PIL import Image


class BracsRoiPreparationTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.raw = self.root / "raw"
        self.output = self.root / "prepared"
        self.summary = self.root / "BRACS.xlsx"

    def tearDown(self) -> None:
        self.temp.cleanup()

    def _write_summary(self, rows: list[tuple[object, ...]]) -> None:
        workbook = Workbook()
        sheet = workbook.active
        sheet.title = "WSI_Information"
        sheet.append(("WSI Filename", "Patient Id", "RoI ", "WSI label", "Set"))
        for row in rows:
            sheet.append(row)
        workbook.save(self.summary)

    def _write_png(self, relative: str, color: tuple[int, int, int]) -> None:
        path = self.raw / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        Image.new("RGB", (160, 144), color).save(path)

    def test_subset_produces_patient_safe_manifests(self) -> None:
        self._write_summary(
            [
                ("BRACS_10", 100, 1, "N", "Training"),
                ("BRACS_20", 200, 1, "PB", "Validation"),
                ("BRACS_30", 300, 1, "IC", "Testing"),
            ]
        )
        self._write_png("train/0_N/BRACS_10_N_1.png", (220, 10, 10))
        self._write_png("val/1_PB/BRACS_20_PB_1.png", (10, 220, 10))
        self._write_png("test/6_IC/BRACS_30_IC_1.png", (10, 10, 220))

        report = prepare_bracs_roi(
            BracsRoiPreparationConfig(
                raw_root=self.raw,
                summary_path=self.summary,
                output_root=self.output,
                enforce_official_counts=False,
                minimum_file_bytes=1,
            )
        )

        self.assertEqual(report["images"], 3)
        self.assertTrue(report["patient_disjoint"])
        with (self.output / "manifest.csv").open(encoding="utf-8") as handle:
            rows = list(csv.DictReader(handle))
        self.assertEqual({row["patient_id"] for row in rows}, {"100", "200", "300"})
        provenance = json.loads((self.output / "provenance.json").read_text())
        self.assertEqual(
            provenance["license_declared_by_current_source"], "CC-BY-NC-4.0"
        )
        self.assertFalse(provenance["raw_data_copied"])

    def test_rejects_patient_leakage(self) -> None:
        self._write_summary(
            [
                ("BRACS_10", 100, 1, "N", "Training"),
                ("BRACS_20", 100, 1, "PB", "Validation"),
            ]
        )
        self._write_png("train/0_N/BRACS_10_N_1.png", (220, 10, 10))
        self._write_png("val/1_PB/BRACS_20_PB_1.png", (10, 220, 10))

        with self.assertRaisesRegex(RoiPreparationError, "patient leakage"):
            prepare_bracs_roi(
                BracsRoiPreparationConfig(
                    raw_root=self.raw,
                    summary_path=self.summary,
                    output_root=self.output,
                    enforce_official_counts=False,
                    minimum_file_bytes=1,
                )
            )

    def test_rejects_filename_folder_label_mismatch(self) -> None:
        self._write_summary([("BRACS_10", 100, 1, "N", "Training")])
        self._write_png("train/0_N/BRACS_10_PB_1.png", (220, 10, 10))
        with self.assertRaisesRegex(
            RoiPreparationError, "filename/folder label mismatch"
        ):
            prepare_bracs_roi(
                BracsRoiPreparationConfig(
                    raw_root=self.raw,
                    summary_path=self.summary,
                    output_root=self.output,
                    enforce_official_counts=False,
                    minimum_file_bytes=1,
                )
            )


if __name__ == "__main__":
    unittest.main()
