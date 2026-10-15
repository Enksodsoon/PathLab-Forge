"""Fixed-command bridge for the permissively licensed libisyntax decoder."""
import io
import json
import math
import pathlib
import sys

runtime_root = pathlib.Path(sys.argv[1]).resolve()
sys.path.insert(0, str(runtime_root))

from isyntax import ISyntax
from PIL import Image


def rgb_image(array):
    image = Image.fromarray(array, "RGBA")
    background = Image.new("RGBA", image.size, (255, 255, 255, 255))
    return Image.alpha_composite(background, image).convert("RGB")


def render(slide, x, y, width, height, output_width, output_height):
    requested_downsample = max(width / output_width, height / output_height)
    level = 0
    for index, downsample in enumerate(slide.level_downsamples):
        if downsample <= requested_downsample:
            level = index
    downsample = slide.level_downsamples[level]
    level_width, level_height = slide.level_dimensions[level]
    level_x = max(0, math.floor(x / downsample))
    level_y = max(0, math.floor(y / downsample))
    read_width = min(level_width - level_x, max(1, math.ceil(width / downsample)))
    read_height = min(level_height - level_y, max(1, math.ceil(height / downsample)))
    image = rgb_image(slide.read_region(level_x, level_y, read_width, read_height, level))
    if image.size != (output_width, output_height):
        image = image.resize((output_width, output_height), Image.Resampling.BILINEAR)
    return image


command = sys.argv[2]
source = pathlib.Path(sys.argv[3]).resolve()
with ISyntax.open(source, cache_size=512) as slide:
    if command == "metadata":
        width, height = slide.dimensions
        payload = {
            "width": width,
            "height": height,
            "levels": slide.level_count,
            "mppX": slide.mpp_x,
            "mppY": slide.mpp_y,
            "levelDimensions": slide.level_dimensions,
        }
        sys.stdout.buffer.write(json.dumps(payload, separators=(",", ":")).encode("utf-8"))
    elif command in ("jpeg", "png", "rgb"):
        values = [int(value) for value in sys.argv[4:10]]
        image = render(slide, *values)
        if command == "rgb":
            sys.stdout.buffer.write(image.tobytes())
        else:
            output = io.BytesIO()
            if command == "jpeg":
                image.save(output, "JPEG", quality=86, optimize=True)
            else:
                image.save(output, "PNG", optimize=True)
            sys.stdout.buffer.write(output.getvalue())
    else:
        raise ValueError("Unsupported bridge command")
