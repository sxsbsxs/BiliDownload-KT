"""Python parsing helpers embedded in BiliDownload-KT.

This module deliberately does not store cookies, sign requests, bypass membership,
or circumvent geographic/copyright restrictions. Authenticated requests and all
Android download lifecycle work remain in Kotlin.
"""
from __future__ import annotations

import json
import re
from urllib.parse import urlparse

_BVID = re.compile(r"BV[0-9A-Za-z]{10}", re.IGNORECASE)
_AVID = re.compile(r"av(\d+)", re.IGNORECASE)


def normalize_video_input(value: str) -> str:
    """Return a canonical BV id or av<id>, preserving unsupported input."""
    original = str(value or "").strip()
    if not original:
        return original

    # Bare IDs are accepted directly.
    if _BVID.fullmatch(original):
        return original
    match = _AVID.fullmatch(original)
    if match:
        return "av" + match.group(1)
    if original.isdigit():
        return "av" + original

    candidate = original if "://" in original else "https://" + original
    try:
        parsed = urlparse(candidate)
    except ValueError:
        return original

    host = (parsed.hostname or "").lower()
    allowed = (
        host == "bilibili.com"
        or host.endswith(".bilibili.com")
        or host in {"b23.tv", "www.b23.tv", "bili2233.cn"}
    )
    if not allowed:
        return original

    match = _BVID.search(parsed.path)
    if match:
        return match.group(0)
    match = re.search(r"/video/av(\d+)", parsed.path, re.IGNORECASE)
    if match:
        return "av" + match.group(1)
    return original


def summarize_playurl(payload: dict) -> dict:
    """Return only quality ids backed by actual tracks in the API response."""
    data = payload.get("data", payload) if isinstance(payload, dict) else {}
    data = data if isinstance(data, dict) else {}
    dash = data.get("dash") if isinstance(data.get("dash"), dict) else {}
    videos = dash.get("video") if isinstance(dash.get("video"), list) else []
    dolby = dash.get("dolby") if isinstance(dash.get("dolby"), dict) else {}
    dolby_videos = dolby.get("video") if isinstance(dolby.get("video"), list) else []
    tracks = [x for x in videos + dolby_videos if isinstance(x, dict)]

    track_ids = {
        int(x["id"]) for x in tracks
        if str(x.get("id", "")).isdigit() and int(x.get("id", 0)) > 0
    }
    accepted = data.get("accept_quality")
    labels = data.get("accept_description")
    accepted = accepted if isinstance(accepted, list) else []
    labels = labels if isinstance(labels, list) else []

    qualities = []
    for index, raw_id in enumerate(accepted):
        try:
            quality_id = int(raw_id)
        except (TypeError, ValueError):
            continue
        if quality_id <= 0 or (track_ids and quality_id not in track_ids):
            continue
        label = labels[index] if index < len(labels) else ""
        qualities.append({
            "id": quality_id,
            "description": str(label or f"画质 {quality_id}"),
        })

    audio = dash.get("audio") if isinstance(dash.get("audio"), list) else []
    return {
        "qualities": sorted(qualities, key=lambda item: item["id"], reverse=True),
        "video_track_count": len(tracks),
        "audio_track_count": len(audio),
        "has_progressive_stream": bool(data.get("durl")),
    }


def summarize_playurl_json(raw_json: str) -> str:
    """JSON-in/JSON-out adapter for Chaquopy."""
    try:
        payload = json.loads(raw_json)
    except (TypeError, ValueError):
        payload = {}
    return json.dumps(summarize_playurl(payload), ensure_ascii=False)
