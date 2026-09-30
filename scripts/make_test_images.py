"""Create the test images for the panamage-jxl module.

Writes gradient.png (64x48, 8-bit RGBA with a varying alpha channel) using only
the standard library and converts it losslessly to gradient.jxl with cjxl.
gradient.rgba holds the same pixels as raw interleaved RGBA bytes and serves
as the expected decoder output.

For the JPEG transcoding tests, a synthetic photo-like 256x192 image is saved
as three JPEG variants with Pillow (baseline 4:2:0 with EXIF, progressive
4:4:4, grayscale), and the first one is transcoded with cjxl as a reference.

All files are checked in under panamage-jxl/src/test/resources.

Also writes photo-orient6-xmp.jpg (EXIF orientation 6 and an XMP packet) for
the metadata tests.

Usage:
  python make_test_images.py [--tools DIR]
"""

import argparse
import os
import random
import struct
import subprocess
import sys
import zlib
from pathlib import Path

from tools_dir import DEFAULT_TOOLS_DIR, ENVIRONMENT_VARIABLE, PROJECT_DIR, tools_dir

RESOURCES_DIR = PROJECT_DIR / "panamage-jxl" / "src" / "test" / "resources"
LIBJXL_DIR = "libjxl-0.12.0"

WIDTH = 64
HEIGHT = 48

PHOTO_WIDTH = 256
PHOTO_HEIGHT = 192
PHOTO_SEED = 20260930


def pixel(x: int, y: int) -> tuple[int, int, int, int]:
    """RGBA value at (x, y); every channel varies so channel mix-ups are detected."""
    return (x * 4) & 0xFF, (y * 5) & 0xFF, (x * 2 + y * 3) & 0xFF, 255 - ((x + y) & 0x7F)


def png_chunk(kind: bytes, data: bytes) -> bytes:
    crc = zlib.crc32(kind + data) & 0xFFFFFFFF
    return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", crc)


def rgba_rows() -> list[bytes]:
    return [b"".join(bytes(pixel(x, y)) for x in range(WIDTH)) for y in range(HEIGHT)]


def write_rgba(path: Path) -> None:
    path.write_bytes(b"".join(rgba_rows()))
    print(f"Wrote {path}")


def write_png(path: Path) -> None:
    # Each scanline is prefixed with filter type 0 (none).
    raw = b"".join(b"\x00" + row for row in rgba_rows())
    header = struct.pack(">IIBBBBB", WIDTH, HEIGHT, 8, 6, 0, 0, 0)  # 8-bit RGBA
    data = (b"\x89PNG\r\n\x1a\n"
            + png_chunk(b"IHDR", header)
            + png_chunk(b"IDAT", zlib.compress(raw, 9))
            + png_chunk(b"IEND", b""))
    path.write_bytes(data)
    print(f"Wrote {path}")


def photo_pixels() -> bytes:
    """Photo-like RGB content: smooth gradients, hard edges and sensor-like noise."""
    rng = random.Random(PHOTO_SEED)
    shapes = [(rng.randrange(PHOTO_WIDTH), rng.randrange(PHOTO_HEIGHT), rng.randrange(12, 48),
               (rng.randrange(256), rng.randrange(256), rng.randrange(256))) for _ in range(12)]
    out = bytearray()
    for y in range(PHOTO_HEIGHT):
        for x in range(PHOTO_WIDTH):
            r = 40 + x * 180 // PHOTO_WIDTH
            g = 60 + y * 150 // PHOTO_HEIGHT
            b = 200 - (x + y) * 120 // (PHOTO_WIDTH + PHOTO_HEIGHT)
            for cx, cy, radius, color in shapes:
                if (x - cx) ** 2 + (y - cy) ** 2 < radius * radius:
                    r, g, b = color
            noise = rng.randint(-10, 10)
            out.extend(max(0, min(255, v + noise)) for v in (r, g, b))
    return bytes(out)


XMP_PACKET = """<?xpacket begin="﻿" id="W5M0MpCehiHzreSzNTczkc9d"?>
<x:xmpmeta xmlns:x="adobe:ns:meta/">
 <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
  <rdf:Description rdf:about="" xmlns:dc="http://purl.org/dc/elements/1.1/">
   <dc:title><rdf:Alt><rdf:li xml:lang="x-default">Panamage orientation test</rdf:li></rdf:Alt></dc:title>
  </rdf:Description>
 </rdf:RDF>
</x:xmpmeta>
<?xpacket end="w"?>"""


def write_orientation_xmp_jpeg(photo, exif) -> None:
    """Saves the photo with EXIF orientation 6 (pixels not rotated) and an XMP segment."""
    import io
    rotated_exif = type(exif)()
    for tag, value in exif.items():
        rotated_exif[tag] = value
    rotated_exif[0x0112] = 6  # Orientation: rotate 90 degrees clockwise to display
    buffer = io.BytesIO()
    photo.save(buffer, "JPEG", quality=85, exif=rotated_exif.tobytes())
    jpeg = buffer.getvalue()

    payload = b"http://ns.adobe.com/xap/1.0/\x00" + XMP_PACKET.encode("utf-8")
    app1 = b"\xff\xe1" + struct.pack(">H", len(payload) + 2) + payload
    assert jpeg[:2] == b"\xff\xd8"
    # Insert after the leading APPn segments (JFIF APP0 must stay first).
    pos = 2
    while jpeg[pos] == 0xFF and 0xE0 <= jpeg[pos + 1] <= 0xEF:
        pos += 2 + struct.unpack(">H", jpeg[pos + 2:pos + 4])[0]
    path = RESOURCES_DIR / "photo-orient6-xmp.jpg"
    path.write_bytes(jpeg[:pos] + app1 + jpeg[pos:])
    (RESOURCES_DIR / "photo-orient6-xmp.xmp").write_bytes(XMP_PACKET.encode("utf-8"))
    print(f"Wrote {path} ({path.stat().st_size} bytes)")


def write_jpegs(cjxl: Path) -> None:
    try:
        from PIL import Image
    except ImportError:
        raise SystemExit("Pillow is required to create the JPEG test images")

    photo = Image.frombytes("RGB", (PHOTO_WIDTH, PHOTO_HEIGHT), photo_pixels())
    exif = Image.Exif()
    exif[0x010F] = "Panamage"             # Make
    exif[0x0110] = "Synthetic Test Image"  # Model
    exif[0x0132] = "2026:09:30 12:00:00"   # DateTime

    variants = {
        "photo-420-exif.jpg": (photo, dict(quality=85, subsampling="4:2:0", exif=exif.tobytes())),
        "photo-444-progressive.jpg": (photo, dict(quality=92, subsampling="4:4:4", progressive=True)),
        "photo-gray.jpg": (photo.convert("L"), dict(quality=85)),
    }
    for name, (image, options) in variants.items():
        path = RESOURCES_DIR / name
        image.save(path, "JPEG", **options)
        print(f"Wrote {path} ({path.stat().st_size} bytes)")

    write_orientation_xmp_jpeg(photo, exif)

    jpeg = RESOURCES_DIR / "photo-420-exif.jpg"
    jxl = RESOURCES_DIR / "photo-420-exif.jxl"
    subprocess.run([str(cjxl), "--lossless_jpeg=1", "--effort=7", "--quiet", str(jpeg), str(jxl)],
                   check=True)
    print(f"Wrote {jxl} ({jxl.stat().st_size} bytes)")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--tools", type=Path, default=None,
                        help=f"tools directory of fetch_tools.py (default: ${ENVIRONMENT_VARIABLE} "
                             f"or {DEFAULT_TOOLS_DIR})")
    args = parser.parse_args()

    jxl_home = tools_dir(args.tools) / LIBJXL_DIR
    cjxl = jxl_home / "bin" / ("cjxl.exe" if os.name == "nt" else "cjxl")
    if not cjxl.is_file():
        raise SystemExit(f"cjxl not found at {cjxl}")

    RESOURCES_DIR.mkdir(parents=True, exist_ok=True)
    png = RESOURCES_DIR / "gradient.png"
    jxl = RESOURCES_DIR / "gradient.jxl"
    write_png(png)
    write_rgba(RESOURCES_DIR / "gradient.rgba")
    subprocess.run([str(cjxl), "--distance=0", "--effort=7", "--quiet", str(png), str(jxl)],
                   check=True)
    print(f"Wrote {jxl} ({jxl.stat().st_size} bytes)")
    write_jpegs(cjxl)
    return 0


if __name__ == "__main__":
    sys.exit(main())
