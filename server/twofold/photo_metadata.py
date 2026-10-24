"""Read a small EXIF/GPS summary without changing any original image bytes."""
import math
import json
from pathlib import Path
import re
import warnings
from PIL import Image

FIELDS = {
    270: 'image_description', 271: 'make', 272: 'model', 305: 'software', 306: 'date_time', 274: 'orientation', 315: 'artist',
    33434: 'exposure_time', 33437: 'f_number', 34855: 'iso', 36867: 'date_time_original',
    36868: 'date_time_digitized', 36880: 'offset_time', 36881: 'offset_time_original',
    36882: 'offset_time_digitized', 37377: 'shutter_speed', 37378: 'aperture',
    37380: 'exposure_bias', 37385: 'flash', 37386: 'focal_length',
    37521: 'subsec_time_original', 41989: 'focal_length_35mm', 42035: 'lens_make', 42036: 'lens_model',
    37383: 'metering_mode', 41986: 'exposure_mode', 41987: 'white_balance',
}


def twofold_capture(raw):
    """Only summarize bounded scalar context from our native camera's EXIF comment."""
    if isinstance(raw, bytes):
        if raw.startswith(b'ASCII\x00\x00\x00'):
            raw = raw[8:].decode('utf-8', errors='replace')
        elif raw.startswith(b'UNICODE\x00'):
            payload = raw[8:]
            raw = payload.decode('utf-16' if payload.startswith((b'\xff\xfe', b'\xfe\xff')) else 'utf-16-be', errors='replace')
        else:
            return None
    if not isinstance(raw, str) or len(raw) > 8192:
        return None
    try:
        parsed = json.loads(raw.rstrip('\x00'))
    except (ValueError, TypeError):
        return None
    if not isinstance(parsed, dict) or parsed.get('app') != 'Twofold':
        return None
    keys = ('app', 'capture_group', 'author', 'camera_id', 'camera_facing', 'capture_mode',
            'captured_at', 'time_source', 'sensor_timestamp_ns', 'location_accuracy_m', 'location_fix_at')
    return {key: parsed[key] for key in keys if key in parsed
            and isinstance(parsed[key], (str, int, float)) and not isinstance(parsed[key], bool)
            and (not isinstance(parsed[key], float) or math.isfinite(parsed[key]))
            and (not isinstance(parsed[key], str) or len(parsed[key]) <= 512)}


def value(raw):
    if isinstance(raw, bytes):
        return raw.decode('utf-8', errors='replace').rstrip('\x00')[:512]
    if isinstance(raw, str):
        return raw.rstrip('\x00')[:512]
    if isinstance(raw, int):
        return raw
    if hasattr(raw, 'numerator') and hasattr(raw, 'denominator'):
        if not raw.denominator:
            return None
        return str(raw.numerator) if raw.denominator == 1 else f'{raw.numerator}/{raw.denominator}'
    if isinstance(raw, float) and math.isfinite(raw):
        return raw
    return None


def coordinate(parts, reference, negative):
    degrees, minutes, seconds = map(float, parts)
    result = degrees + minutes / 60 + seconds / 3600
    if isinstance(reference, bytes):
        reference = reference.decode('ascii')
    if reference == negative:
        result = -result
    return result


def details(path):
    result = {'exif': {}, 'capture_time_source': 'moment'}
    try:
        with warnings.catch_warnings():
            warnings.simplefilter('ignore', UserWarning)
            with Image.open(Path(path)) as image:
                result.update(width=image.width, height=image.height)
                exif = image.getexif()
                tags = dict(exif)
                try:
                    tags.update(exif.get_ifd(34665))
                except (ValueError, TypeError, OSError, KeyError):
                    pass
                for tag, name in FIELDS.items():
                    parsed = value(tags.get(tag))
                    if parsed is not None:
                        result['exif'][name] = parsed
                capture = twofold_capture(tags.get(37510))
                if capture:
                    result['twofold_capture'] = capture
                for name in ('date_time_original', 'date_time_digitized', 'date_time'):
                    if re.search(r'[1-9]\d{3}[:-]\d{2}[:-]\d{2}[ T]\d{2}:\d{2}:\d{2}', str(result['exif'].get(name, ''))):
                        result['capture_time_source'] = 'exif'
                        break
                embedded = image.info.get('xmp', tags.get(700, b''))
                if isinstance(embedded, str):
                    embedded = embedded.encode('utf-8', errors='replace')
                if isinstance(embedded, bytes) and re.search(rb'(DateTimeOriginal|CreateDate|DateTimeCreated|DateCreated)[^\r\n]{0,80}[1-9]\d{3}[-:]\d{2}[-:]\d{2}', embedded):
                    result['capture_time_source'] = 'embedded_xmp'
                try:
                    gps = exif.get_ifd(34853)
                    latitude = coordinate(gps[2], gps[1], 'S')
                    longitude = coordinate(gps[4], gps[3], 'W')
                    if math.isfinite(latitude) and math.isfinite(longitude) and abs(latitude) <= 90 and abs(longitude) <= 180:
                        result['location'] = {'latitude': latitude, 'longitude': longitude}
                        if 6 in gps:
                            altitude = float(gps[6])
                            if gps.get(5) in (1, b'\x01'):
                                altitude = -altitude
                            if math.isfinite(altitude):
                                result['location']['altitude_m'] = altitude
                except (KeyError, ValueError, TypeError, ZeroDivisionError, OSError, OverflowError):
                    pass
    except (OSError, ValueError, TypeError, SyntaxError):
        pass
    return result
