import csv
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

from openpyxl import Workbook
from pathlab_ai_data.bracs import (
    BracsPreparationConfig,
    PreparationError,
    prepare_bracs,
)

LABEL_GROUP = {
    "N": "BT",
    "PB": "BT",
    "UDH": "BT",
    "FEA": "AT",
    "ADH": "AT",
    "DCIS": "MT",
    "IC": "MT",
}


class BracsPreparationTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.raw = self.root / "raw"
        self.summary = self.root / "summary.csv"

    def tearDown(self):
        self.temporary.cleanup()

    def _slide(self, split, label, number, payload=None):
        folder = self.raw / "Whole Slide Image Set" / split / LABEL_GROUP[label] / label
        folder.mkdir(parents=True, exist_ok=True)
        path = folder / f"BRACS_{number}.svs"
        unique_payload = payload if payload is not None else f"slide-{number}".encode()
        path.write_bytes(b"II*\x00" + unique_payload)
        return path

    def _summary(self, rows):
        with self.summary.open("w", newline="", encoding="utf-8") as handle:
            writer = csv.DictWriter(
                handle,
                fieldnames=[
                    "slide_id",
                    "patient_id",
                    "label",
                    "reference_set",
                    "roi_count",
                ],
            )
            writer.writeheader()
            writer.writerows(rows)

    def _config(self, output, slides, patients):
        return BracsPreparationConfig(
            raw_root=self.raw,
            summary_path=self.summary,
            output_root=output,
            expected_slides=slides,
            expected_patients=patients,
            min_slide_bytes=8,
        )

    def test_prepares_normalized_train_ready_manifests(self):
        rows = []
        splits = ["Train", "Train", "Train", "Train", "Train", "Validation", "Test"]
        for number, (label, split) in enumerate(zip(LABEL_GROUP, splits), start=1001):
            self._slide(split, label, number)
            rows.append(
                {
                    "slide_id": f"BRACS_{number}.svs",
                    "patient_id": f"patient-{number}",
                    "label": label,
                    "reference_set": split,
                    "roi_count": number % 3,
                }
            )
        self._summary(rows)

        output = self.root / "prepared"
        report = prepare_bracs(self._config(output, slides=7, patients=7))

        self.assertEqual(report["clean_slide_count"], 7)
        self.assertEqual(report["patient_count"], 7)
        self.assertEqual(report["rejected_slide_count"], 0)
        self.assertEqual(
            set(report["class_counts"]),
            {"N", "PB", "UDH", "FEA", "ADH", "DCIS", "IC"},
        )
        self.assertTrue((output / "manifest.csv").is_file())
        self.assertTrue((output / "splits" / "train.csv").is_file())
        self.assertTrue((output / "splits" / "validation.csv").is_file())
        self.assertTrue((output / "splits" / "test.csv").is_file())
        self.assertTrue((output / "checksums.sha256").is_file())
        self.assertTrue((output / "dataset_manifest.json").is_file())

        with (output / "manifest.csv").open(newline="", encoding="utf-8") as handle:
            manifest = list(csv.DictReader(handle))
        normal = next(row for row in manifest if row["label_code"] == "N")
        self.assertEqual(normal["label_name"], "Normal")
        self.assertEqual(normal["coarse_group"], "BT")
        self.assertEqual(normal["split"], "train")
        self.assertRegex(normal["sha256"], r"^[0-9a-f]{64}$")
        self.assertNotIn(str(self.raw), normal["relative_path"])

    def test_rejects_patient_leakage_across_official_splits(self):
        self._slide("Train", "PB", 2001)
        self._slide("Validation", "UDH", 2002)
        self._summary(
            [
                {
                    "slide_id": "BRACS_2001",
                    "patient_id": "same-patient",
                    "label": "PB",
                    "reference_set": "train",
                    "roi_count": 1,
                },
                {
                    "slide_id": "BRACS_2002",
                    "patient_id": "same-patient",
                    "label": "UDH",
                    "reference_set": "validation",
                    "roi_count": 1,
                },
            ]
        )

        with self.assertRaisesRegex(PreparationError, "patient leakage"):
            prepare_bracs(self._config(self.root / "prepared", slides=2, patients=1))

    def test_rejects_path_and_summary_label_disagreement(self):
        self._slide("Train", "PB", 3001)
        self._summary(
            [
                {
                    "slide_id": "BRACS_3001",
                    "patient_id": "patient-3001",
                    "label": "IC",
                    "reference_set": "train",
                    "roi_count": 0,
                }
            ]
        )

        with self.assertRaisesRegex(PreparationError, "label disagreement"):
            prepare_bracs(self._config(self.root / "prepared", slides=1, patients=1))

    def test_rejects_duplicate_slide_content(self):
        duplicate = b"identical-content"
        self._slide("Train", "PB", 4001, duplicate)
        self._slide("Train", "UDH", 4002, duplicate)
        self._summary(
            [
                {
                    "slide_id": "BRACS_4001",
                    "patient_id": "patient-4001",
                    "label": "PB",
                    "reference_set": "train",
                    "roi_count": 0,
                },
                {
                    "slide_id": "BRACS_4002",
                    "patient_id": "patient-4002",
                    "label": "UDH",
                    "reference_set": "train",
                    "roi_count": 0,
                },
            ]
        )

        with self.assertRaisesRegex(PreparationError, "duplicate slide content"):
            prepare_bracs(self._config(self.root / "prepared", slides=2, patients=2))

    def test_output_is_reproducible(self):
        self._slide("Train", "PB", 5001)
        self._summary(
            [
                {
                    "slide_id": "BRACS_5001",
                    "patient_id": "patient-5001",
                    "label": "Pathological Benign",
                    "reference_set": "training",
                    "roi_count": 2,
                }
            ]
        )

        first = self.root / "first"
        second = self.root / "second"
        prepare_bracs(self._config(first, slides=1, patients=1))
        prepare_bracs(self._config(second, slides=1, patients=1))

        for relative in ["manifest.csv", "checksums.sha256", "dataset_manifest.json"]:
            self.assertEqual(
                (first / relative).read_bytes(), (second / relative).read_bytes()
            )
        manifest = json.loads(
            (first / "dataset_manifest.json").read_text(encoding="utf-8")
        )
        self.assertEqual(manifest["pipeline_version"], "bracs-clean-v1")

    def test_reads_the_official_summary_shape_from_xlsx(self):
        self._slide("Validation", "ADH", 6001)
        workbook = Workbook()
        sheet = workbook.active
        sheet.append(
            ["WSI ID", "Patient ID", "WSI label", "Reference set", "Number of ROIs"]
        )
        sheet.append(
            [
                "BRACS_6001.svs",
                "patient-6001",
                "Atypical Ductal Hyperplasia",
                "validation",
                3,
            ]
        )
        xlsx = self.root / "summary.xlsx"
        workbook.save(xlsx)
        config = BracsPreparationConfig(
            raw_root=self.raw,
            summary_path=xlsx,
            output_root=self.root / "prepared",
            expected_slides=1,
            expected_patients=1,
            min_slide_bytes=8,
        )

        report = prepare_bracs(config)

        self.assertEqual(report["class_counts"], {"ADH": 1})
        self.assertEqual(report["split_counts"], {"validation": 1})

    def test_accepts_descriptive_official_folder_names(self):
        folder = (
            self.raw
            / "Whole Slide Image Set"
            / "Training"
            / "Benign"
            / "Pathological Benign"
        )
        folder.mkdir(parents=True)
        (folder / "BRACS_6101.svs").write_bytes(b"II*\x00descriptive-folders")
        self._summary(
            [
                {
                    "slide_id": "BRACS_6101",
                    "patient_id": "patient-6101",
                    "label": "PB",
                    "reference_set": "train",
                    "roi_count": 0,
                }
            ]
        )

        report = prepare_bracs(
            self._config(self.root / "prepared", slides=1, patients=1)
        )

        self.assertEqual(report["class_counts"], {"PB": 1})

    def test_rejects_invalid_svs_container_signature(self):
        slide = self._slide("Test", "IC", 7001)
        slide.write_bytes(b"not-a-tiff-container")
        self._summary(
            [
                {
                    "slide_id": "BRACS_7001",
                    "patient_id": "patient-7001",
                    "label": "IC",
                    "reference_set": "test",
                    "roi_count": 0,
                }
            ]
        )

        with self.assertRaisesRegex(PreparationError, "invalid TIFF/SVS signature"):
            prepare_bracs(self._config(self.root / "prepared", slides=1, patients=1))

    def test_rejects_incomplete_expected_distribution(self):
        self._slide("Train", "PB", 8001)
        self._summary(
            [
                {
                    "slide_id": "BRACS_8001",
                    "patient_id": "patient-8001",
                    "label": "PB",
                    "reference_set": "train",
                    "roi_count": 0,
                }
            ]
        )
        config = BracsPreparationConfig(
            raw_root=self.raw,
            summary_path=self.summary,
            output_root=self.root / "prepared",
            expected_slides=547,
            expected_patients=189,
            min_slide_bytes=8,
        )

        with self.assertRaisesRegex(PreparationError, "expected 547 slides"):
            prepare_bracs(config)

    @unittest.skipUnless(
        importlib.util.find_spec("tiffslide")
        and importlib.util.find_spec("tifffile")
        and importlib.util.find_spec("numpy"),
        "decoded WSI QC dependencies are optional",
    )
    def test_decodes_pyramid_and_measures_tissue(self):
        import numpy as np
        from PIL import Image
        from tifffile import TiffWriter

        path = self._slide("Train", "PB", 9001)
        base = np.full((1024, 1024, 3), 248, dtype=np.uint8)
        base[128:896, 160:864] = np.array([175, 82, 145], dtype=np.uint8)
        base[320:700, 300:760] = np.array([105, 45, 100], dtype=np.uint8)
        small = np.asarray(Image.fromarray(base).resize((512, 512)))
        with TiffWriter(path, bigtiff=True) as writer:
            writer.write(
                base,
                tile=(256, 256),
                photometric="rgb",
                subifds=1,
                metadata=None,
            )
            writer.write(
                small,
                tile=(256, 256),
                photometric="rgb",
                subfiletype=1,
                metadata=None,
            )
        self._summary(
            [
                {
                    "slide_id": "BRACS_9001",
                    "patient_id": "patient-9001",
                    "label": "PB",
                    "reference_set": "train",
                    "roi_count": 1,
                }
            ]
        )
        output = self.root / "prepared"
        config = BracsPreparationConfig(
            raw_root=self.raw,
            summary_path=self.summary,
            output_root=output,
            expected_slides=1,
            expected_patients=1,
            min_slide_bytes=8,
            minimum_dimension=512,
            probe_mode="tiffslide",
        )

        report = prepare_bracs(config)

        self.assertEqual(report["validation_level"], "decoded-wsi-tissue-qc")
        with (output / "manifest.csv").open(newline="", encoding="utf-8") as handle:
            row = next(csv.DictReader(handle))
        self.assertEqual(row["width"], "1024")
        self.assertEqual(row["height"], "1024")
        self.assertEqual(row["level_count"], "2")
        self.assertGreater(float(row["tissue_fraction"]), 0.2)
        self.assertEqual(row["integrity_status"], "decoded+tissue-qc+sha256")


if __name__ == "__main__":
    unittest.main()
