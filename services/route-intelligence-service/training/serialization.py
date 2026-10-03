import hashlib
import json


def json_bytes(value: object) -> bytes:
    return (json.dumps(value, sort_keys=True, indent=2, allow_nan=False) + "\n").encode("utf-8")


def payload_checksum(value: object) -> str:
    return hashlib.sha256(json_bytes(value)).hexdigest()
