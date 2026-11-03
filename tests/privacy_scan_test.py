"""Run with: python3 tests/privacy_scan_test.py (from anywhere)."""
import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'tools'))
import privacy_scan as ps  # noqa: E402

LIST = """package:com.android.settings
package:/data/app/x/base.apk=com.example.player
package:tv.anoki.acr.anokiacroptin
package:com.miui.tv.analytics
package:com.vendor.tracking.helper
package:com.vendor.acr.service
package:com.vendor.accra.tv
package:com.google.android.tvlauncher
garbage line
"""


class PrivacyScanTest(unittest.TestCase):
    def test_packages_reads_plain_and_path_lines_and_skips_junk(self):
        got = ps.packages(LIST)
        self.assertIn('com.android.settings', got)
        self.assertIn('com.example.player', got)
        self.assertNotIn('garbage line', got)
        self.assertEqual(len(got), 8)

    def test_possible_is_by_word_or_acr_part_only(self):
        self.assertTrue(ps.possible('com.vendor.tracking.helper'))
        self.assertTrue(ps.possible('com.vendor.acr.service'))
        self.assertFalse(ps.possible('com.vendor.accra.tv'))      # acr only inside a longer word
        self.assertFalse(ps.possible('com.android.settings'))

    def test_scan_splits_known_from_possible_and_reports_off(self):
        known, maybe = ps.scan(ps.packages(LIST), {'tv.anoki.acr.anokiacroptin'})
        self.assertEqual([k[0] for k in known], ['com.miui.tv.analytics', 'tv.anoki.acr.anokiacroptin'])
        self.assertEqual([k[2] for k in known], [False, True])
        self.assertEqual([m[0] for m in maybe], ['com.vendor.acr.service', 'com.vendor.tracking.helper'])

    def test_known_never_listed_twice(self):
        known, maybe = ps.scan({'com.miui.tv.analytics'}, set())
        self.assertEqual(len(known), 1)
        self.assertEqual(maybe, [])


if __name__ == '__main__':
    unittest.main()
