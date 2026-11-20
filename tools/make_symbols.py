"""Renders the icons (white PNGs with transparency) from the Material Symbols Rounded font.
Usage: python3 -I make_symbols.py FONT.ttf CODEPOINTS OUT_DIR [EM_PX]"""
import os
import sys

from PIL import Image, ImageDraw, ImageFilter, ImageFont

# name used in the app -> (Material Symbols name, filled?)
MAP = {
    "sunny": ("sunny", 1), "clear_night": ("clear_night", 1), "partly_cloudy_day": ("partly_cloudy_day", 1),
    "partly_cloudy_night": ("partly_cloudy_night", 1), "cloud": ("cloud", 1), "foggy": ("foggy", 1),
    "rainy": ("rainy", 1),
    "thunderstorm": ("thunderstorm", 1), "snowflake": ("ac_unit", 0),
    "tv": ("tv", 0), "gamepad": ("sports_esports", 1), "hdmi": ("settings_input_hdmi", 1),
    "apps": ("apps", 1), "settings": ("settings", 1), "speaker": ("speaker", 1),
    "store": ("shopping_bag", 0),
    "sparkles": ("auto_awesome", 1), "bedtime": ("bedtime", 1), "home": ("home", 1),
    "chevron_right": ("chevron_right", 0), "bell": ("notifications", 1), "sync": ("sync", 0),
    "check": ("check", 0), "play": ("play_arrow", 1), "tv_settings": ("display_settings", 1),
    "chevron_down": ("keyboard_arrow_down", 0), "sunrise": ("wb_twilight", 1), "sunset": ("wb_twilight", 1),
    "live_tv": ("live_tv", 1), "bitcoin": ("currency_bitcoin", 0), "recents": ("view_carousel", 0),
    "wifi": ("wifi", 1), "ethernet": ("lan", 1), "bluetooth": ("bluetooth_connected", 1), "usb": ("usb", 1),
    "keystone": ("settings_overscan", 1), "cast": ("cast", 1),
    "accessibility": ("accessibility_new", 0),
    "lightbulb": ("lightbulb", 1), "toggle": ("toggle_on", 1), "fan": ("mode_fan", 1), "blinds": ("blinds", 1),
    "bolt": ("bolt", 1), "smart_button": ("smart_button", 1),
}


def main():
    font_path, cp_path, out = sys.argv[1], sys.argv[2], sys.argv[3]
    em = int(sys.argv[4]) if len(sys.argv) > 4 else 172
    codes = {}
    for line in open(cp_path, encoding="utf-8"):
        parts = line.split()
        if len(parts) == 2:
            codes[parts[0]] = int(parts[1], 16)
    os.makedirs(out, exist_ok=True)
    big_font = ImageFont.truetype(font_path, em * 8)
    axes = [a["name"] if isinstance(a["name"], str) else a["name"].decode() for a in big_font.get_variation_axes()]
    for name, (material, fill) in MAP.items():
        values = []
        for axis in axes:
            a = axis.lower()
            values.append(fill if "fill" in a else 0 if "grad" in a else 48 if "optical" in a or "opsz" in a else 600)
        big_font.set_variation_by_axes(values)
        ch = chr(codes[material])
        # Drawn 8x larger without smoothing (the variable font's overlapping contours leave no gaps), then scaled down.
        big = Image.new("L", (em * 16, em * 16), 0)
        draw = ImageDraw.Draw(big)
        draw.fontmode = "1"
        draw.text((em * 4, em * 4), ch, font=big_font, fill=255)
        # Closes the hairline gaps between the variable font's outline and its fill (dilate, then erode).
        big = big.filter(ImageFilter.MaxFilter(9)).filter(ImageFilter.MinFilter(9))
        box = big.getbbox()
        pad = 16
        big = big.crop((box[0] - pad, box[1] - pad, box[2] + pad, box[3] + pad))
        img = big.resize((max(1, round(big.width / 8)), max(1, round(big.height / 8))), Image.LANCZOS)
        rgba = Image.new("RGBA", img.size, (255, 255, 255, 0))
        rgba.putalpha(img)
        rgba.save(os.path.join(out, name + ".png"), optimize=True)
    print("ok:", len(MAP), "icons in", out, "axes:", axes)


if __name__ == "__main__":
    main()
