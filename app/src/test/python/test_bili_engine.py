import unittest

from bili_engine import normalize_video_input, summarize_playurl


class NormalizeTests(unittest.TestCase):
    def test_bvid_url(self):
        self.assertEqual(
            normalize_video_input("https://www.bilibili.com/video/BV1xx411c7mD"),
            "BV1xx411c7mD",
        )

    def test_av_forms(self):
        self.assertEqual(normalize_video_input("av170001"), "av170001")
        self.assertEqual(
            normalize_video_input("https://www.bilibili.com/video/av170001"),
            "av170001",
        )

    def test_numeric_id(self):
        self.assertEqual(normalize_video_input("170001"), "av170001")

    def test_untrusted_host_is_preserved(self):
        value = "https://example.com/video/BV1xx411c7mD"
        self.assertEqual(normalize_video_input(value), value)


class PlayurlTests(unittest.TestCase):
    def test_actual_tracks_filter_quality_list(self):
        result = summarize_playurl({
            "data": {
                "accept_quality": [127, 120, 80, 64],
                "accept_description": ["8K", "4K", "1080P", "720P"],
                "dash": {
                    "video": [{"id": 80}, {"id": 64}],
                    "audio": [{"id": 30280}],
                },
            }
        })
        self.assertEqual([x["id"] for x in result["qualities"]], [80, 64])
        self.assertEqual(result["audio_track_count"], 1)


if __name__ == "__main__":
    unittest.main()
