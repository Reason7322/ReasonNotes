"""Read an Android multi-item ReasonNotes ZIP carrier without extracting files.

All members are verified before *any* repository writes. The archive has a
content-addressed filename; entries retain their canonical original IDs.
"""

from io import BytesIO
import hashlib
import re
from zipfile import BadZipFile, ZipFile

from .envelope import EnvelopeError, Revision
from .receipt import ReceiptEnvelope, ReceiptError

ARCHIVE_NAME = re.compile(
    r"reasonnotes-bundle-([0-9a-f]{64})(?: \(([1-9][0-9]*)\))?\.zip"
)
MEMBER_NAME = re.compile(r"([0-9a-f]{64})(\.receipt)?\.md")
MAX_ITEMS = 256
MAX_ITEM_BYTES = 16 * 1024 * 1024
MAX_TOTAL_BYTES = 64 * 1024 * 1024
MAX_ARCHIVE_BYTES = 64 * 1024 * 1024


class BundleError(ValueError):
    """An untrusted transfer archive cannot be imported safely."""


def is_bundle_name(name: str) -> bool:
    return ARCHIVE_NAME.fullmatch(name) is not None


def read_bundle(data: bytes, name: str) -> tuple[tuple[str, bytes], ...]:
    match = ARCHIVE_NAME.fullmatch(name)
    if match is None:
        raise BundleError("not a canonical ReasonNotes bundle filename")
    if len(data) > MAX_ARCHIVE_BYTES:
        raise BundleError("bundle exceeds archive size limit")
    if hashlib.sha256(data).hexdigest() != match.group(1):
        raise BundleError("bundle content hash differs from filename")

    try:
        with ZipFile(BytesIO(data), "r") as archive:
            info = archive.infolist()
            if not 2 <= len(info) <= MAX_ITEMS:
                raise BundleError("bundle must contain 2 to 256 items")
            names: set[str] = set()
            total = 0
            results: list[tuple[str, bytes]] = []
            for entry in info:
                if entry.filename in names:
                    raise BundleError("duplicate bundle member")
                names.add(entry.filename)
                member = MEMBER_NAME.fullmatch(entry.filename)
                if member is None or entry.is_dir() or entry.flag_bits & 1:
                    raise BundleError("unsafe or unsupported bundle member")
                if entry.compress_type not in (0, 8):
                    raise BundleError("unsupported ZIP compression")
                if entry.file_size > MAX_ITEM_BYTES:
                    raise BundleError("bundle member exceeds size limit")
                total += entry.file_size
                if total > MAX_TOTAL_BYTES:
                    raise BundleError("bundle exceeds uncompressed size limit")
                with archive.open(entry) as reader:
                    contents = reader.read(MAX_ITEM_BYTES + 1)
                if len(contents) != entry.file_size or len(contents) > MAX_ITEM_BYTES:
                    raise BundleError("bundle member size mismatch")
                try:
                    if member.group(2):
                        parsed = ReceiptEnvelope.parse(contents)
                        identity = parsed.receipt_id
                    else:
                        parsed = Revision.parse(contents)
                        identity = parsed.revision_id
                except (ReceiptError, EnvelopeError) as error:
                    raise BundleError("invalid bundle member envelope") from error
                if identity != member.group(1):
                    raise BundleError("bundle member identity differs from filename")
                results.append((entry.filename, contents))
            return tuple(results)
    except (BadZipFile, EOFError, OSError) as error:
        raise BundleError("malformed or corrupt ZIP bundle") from error
