from __future__ import annotations

from pathlib import Path

from PIL import Image
import tifffile

from pathlab_ai_data.synthetic_pathology import SYNTHETIC_ARTIFACTS, generate_suite


def test_generates_deterministic_nonclinical_ome_tiff_suite(tmp_path: Path) -> None:
    first = generate_suite(tmp_path / "first", width=128, height=128, seed=31)
    second = generate_suite(tmp_path / "second", width=128, height=128, seed=31)
    assert first["research_only"] is True
    assert first["not_diagnostic"] is True
    assert len(first["fixtures"]) == len(SYNTHETIC_ARTIFACTS) + 1
    assert [item["sha256"] for item in first["fixtures"]] == [item["sha256"] for item in second["fixtures"]]
    assert all(item["diagnostic_evidence"] is False for item in first["fixtures"])
    with Image.open(tmp_path / "first" / "synthetic-fold.ome.tif") as image:
        assert image.size == (128, 128)
        assert "OME" in str(image.tag_v2[270])
    with tifffile.TiffFile(tmp_path / "first" / "synthetic-fold.ome.tif") as slide:
        assert slide.is_ome
        assert slide.pages.first.is_tiled


def test_rejects_unbounded_fixture_dimensions(tmp_path: Path) -> None:
    try:
        generate_suite(tmp_path, width=8, height=8)
    except ValueError as error:
        assert "between 128 and 4096" in str(error)
    else:
        raise AssertionError("unsafe dimensions were accepted")
