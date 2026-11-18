from __future__ import annotations

import importlib.util
import unittest
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[1] / "ui_visual_regression.py"
SPEC = importlib.util.spec_from_file_location("memora_ui_visual_regression", SCRIPT)
assert SPEC and SPEC.loader
visual = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(visual)


class GalleryReadinessTests(unittest.TestCase):
    def test_visible_compose_heading_marks_gallery_ready(self) -> None:
        hierarchy = """<hierarchy><node text="Memora · komponentlər" bounds="[24,100][800,180]" /></hierarchy>"""
        self.assertTrue(visual.gallery_content_is_ready(hierarchy))

    def test_missing_heading_does_not_allow_early_screenshot(self) -> None:
        hierarchy = """<hierarchy><node text="Davam et" bounds="[24,200][800,300]" /></hierarchy>"""
        self.assertFalse(visual.gallery_content_is_ready(hierarchy))

    def test_confirmation_modal_is_ready_from_its_visible_title(self) -> None:
        hierarchy = """<hierarchy><node text="Dəyişikliyi təsdiqlə" bounds="[180,900][900,980]" /></hierarchy>"""
        self.assertTrue(visual.gallery_content_is_ready(hierarchy, "Dəyişikliyi təsdiqlə"))

    def test_zero_bounds_heading_is_not_visible(self) -> None:
        hierarchy = """<hierarchy><node text="Memora · komponentlər" bounds="[0,0][0,0]" /></hierarchy>"""
        self.assertFalse(visual.gallery_content_is_ready(hierarchy))

    def test_heading_without_screen_bounds_is_not_ready(self) -> None:
        hierarchy = """<hierarchy><node text="Memora · komponentlər" /></hierarchy>"""
        self.assertFalse(visual.gallery_content_is_ready(hierarchy))

    def test_heading_without_screen_bounds_is_not_ready(self) -> None:
        hierarchy = """<hierarchy><node text="Memora · komponentlər" /></hierarchy>"""
        self.assertFalse(visual.gallery_content_is_ready(hierarchy))

    def test_malformed_hierarchy_is_not_ready(self) -> None:
        self.assertFalse(visual.gallery_content_is_ready("<hierarchy>"))


if __name__ == "__main__":
    unittest.main()
