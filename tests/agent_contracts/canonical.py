"""Reference-only canonicalization and digest for the first agent contract slice.

The Android implementation must be independently tested against the same vectors;
this module is not an authorization boundary or an application runtime.
"""
import hashlib
import json


def _validate(value):
    if value is None or isinstance(value, bool):
        return
    if isinstance(value, int):
        if not -(2 ** 63) <= value < 2 ** 63:
            raise ValueError("integer out of signed 64-bit range")
        return
    if isinstance(value, str):
        value.encode("utf-8", errors="strict")
        return
    if isinstance(value, list):
        for entry in value:
            _validate(entry)
        return
    if isinstance(value, dict):
        for key, entry in value.items():
            if not isinstance(key, str) or not key.isascii():
                raise ValueError("object keys must be ASCII strings")
            _validate(entry)
        return
    raise ValueError("unsupported JSON type")


def canonical_bytes(value):
    _validate(value)
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False).encode("utf-8")


def command_digest(envelope):
    # Confirmation is produced after preparation; it binds this digest separately.
    payload = {k: v for k, v in envelope.items() if k not in ("payloadDigest", "confirmationId")}
    return hashlib.sha256(canonical_bytes(payload)).hexdigest()
