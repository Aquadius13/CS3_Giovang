#!/usr/bin/env python3
"""
validate_cs_schema.py
======================
Kiểm tra file giovang_iptv.json (output của crawler_giovang.py) có đúng
schema mà GioVangProvider.kt (Cloudstream3 plugin) mong đợi hay không.

Chạy SAU mỗi lần crawler tạo file JSON, TRƯỚC khi publish lên CDN/GitHub,
để đảm bảo app Cloudstream không bị crash hoặc hiển thị sai khi đọc dữ liệu.

Sử dụng:
    python3 validate_cs_schema.py giovang_iptv.json
"""

import json
import sys
from pathlib import Path


REQUIRED_ROOT = ["id", "name", "groups"]
REQUIRED_GROUP = ["id", "name", "channels"]
REQUIRED_CHANNEL = ["id", "name", "sources"]
REQUIRED_SOURCE = ["name", "contents"]
REQUIRED_CONTENT = ["streams"]
REQUIRED_STREAM = ["stream_links"]
REQUIRED_LINK = ["url"]


def err(path: str, msg: str, errors: list):
    errors.append(f"  ❌ [{path}] {msg}")


def validate(data: dict) -> list:
    errors = []

    for key in REQUIRED_ROOT:
        if key not in data:
            err("root", f"thiếu field bắt buộc '{key}'", errors)

    groups = data.get("groups", [])
    if not isinstance(groups, list):
        err("root.groups", "phải là list", errors)
        return errors
    if not groups:
        errors.append("  ⚠️  [root.groups] danh sách rỗng — app sẽ không hiển thị gì")

    for gi, g in enumerate(groups):
        gpath = f"groups[{gi}]"
        for key in REQUIRED_GROUP:
            if key not in g:
                err(gpath, f"thiếu field '{key}'", errors)

        channels = g.get("channels", [])
        if not isinstance(channels, list):
            err(f"{gpath}.channels", "phải là list", errors)
            continue

        for ci, ch in enumerate(channels):
            cpath = f"{gpath}.channels[{ci}]"
            for key in REQUIRED_CHANNEL:
                if key not in ch:
                    err(cpath, f"thiếu field '{key}'", errors)

            # id phải là string không rỗng và duy nhất (check ở cuối)
            if not isinstance(ch.get("id"), str) or not ch.get("id"):
                err(cpath, "field 'id' phải là string không rỗng", errors)

            if not isinstance(ch.get("name"), str) or not ch.get("name"):
                err(cpath, "field 'name' phải là string không rỗng "
                           "(đây là tiêu đề hiển thị trận đấu)", errors)

            img = ch.get("image")
            if img is not None and not isinstance(img, dict):
                err(cpath, "field 'image' phải là object hoặc null", errors)
            elif isinstance(img, dict) and not img.get("url"):
                errors.append(f"  ⚠️  [{cpath}.image] thiếu 'url' — "
                              f"thumbnail sẽ trống trong app")

            sources = ch.get("sources", [])
            if not isinstance(sources, list):
                err(f"{cpath}.sources", "phải là list", errors)
                continue
            if not sources:
                errors.append(f"  ⚠️  [{cpath}.sources] rỗng — "
                              f"trận đấu sẽ không phát được")

            for si, src in enumerate(sources):
                spath = f"{cpath}.sources[{si}]"
                for key in REQUIRED_SOURCE:
                    if key not in src:
                        err(spath, f"thiếu field '{key}'", errors)

                contents = src.get("contents", [])
                for coi, content in enumerate(contents):
                    copath = f"{spath}.contents[{coi}]"
                    for key in REQUIRED_CONTENT:
                        if key not in content:
                            err(copath, f"thiếu field '{key}'", errors)

                    streams = content.get("streams", [])
                    for sti, stream in enumerate(streams):
                        stpath = f"{copath}.streams[{sti}]"
                        for key in REQUIRED_STREAM:
                            if key not in stream:
                                err(stpath, f"thiếu field '{key}'", errors)

                        links = stream.get("stream_links", [])
                        if not links:
                            errors.append(f"  ⚠️  [{stpath}] không có "
                                          f"stream_links nào")

                        for li, link in enumerate(links):
                            lpath = f"{stpath}.stream_links[{li}]"
                            for key in REQUIRED_LINK:
                                if key not in link:
                                    err(lpath, f"thiếu field '{key}'", errors)

                            url = link.get("url", "")
                            if url and not (
                                url.startswith("http://") or
                                url.startswith("https://")
                            ):
                                err(lpath, f"url không hợp lệ: {url[:50]}", errors)

                            link_type = link.get("type", "")
                            if link_type not in ("hls", "mp4", "link", ""):
                                errors.append(
                                    f"  ⚠️  [{lpath}] type='{link_type}' "
                                    f"không chuẩn (Kotlin provider hiểu: "
                                    f"hls/mp4/link)"
                                )

                            headers = link.get("request_headers", [])
                            for hi, h in enumerate(headers):
                                hpath = f"{lpath}.request_headers[{hi}]"
                                if "key" not in h or "value" not in h:
                                    err(hpath, "thiếu 'key' hoặc 'value'", errors)

    # Check trùng id channel (Kotlin dùng id làm key duy nhất trong map)
    all_ids = [
        ch.get("id") for g in groups for ch in g.get("channels", [])
        if ch.get("id")
    ]
    dupes = {x for x in all_ids if all_ids.count(x) > 1}
    if dupes:
        errors.append(f"  ❌ [root] channel id bị trùng: {sorted(dupes)[:5]}"
                      f"{'...' if len(dupes) > 5 else ''}")

    return errors


def main():
    if len(sys.argv) < 2:
        print("Sử dụng: python3 validate_cs_schema.py <file.json>")
        sys.exit(1)

    path = Path(sys.argv[1])
    if not path.exists():
        print(f"❌ Không tìm thấy file: {path}")
        sys.exit(1)

    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as e:
        print(f"❌ File không phải JSON hợp lệ: {e}")
        sys.exit(1)

    errors = validate(data)

    n_groups   = len(data.get("groups", []))
    n_channels = sum(len(g.get("channels", [])) for g in data.get("groups", []))

    print(f"\n📄 {path.name}")
    print(f"   {n_groups} nhóm, {n_channels} trận đấu\n")

    if not errors:
        print("✅ Schema hợp lệ — sẵn sàng cho GioVangProvider.kt đọc.")
        sys.exit(0)
    else:
        critical = [e for e in errors if "❌" in e]
        warnings = [e for e in errors if "⚠️" in e]
        if critical:
            print(f"❌ {len(critical)} lỗi nghiêm trọng (sẽ làm app crash hoặc "
                  f"không hiển thị):")
            for e in critical:
                print(e)
        if warnings:
            print(f"\n⚠️  {len(warnings)} cảnh báo (app vẫn chạy nhưng thiếu sót):")
            for e in warnings:
                print(e)
        sys.exit(1 if critical else 0)


if __name__ == "__main__":
    main()
