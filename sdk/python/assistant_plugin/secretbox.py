"""Envelope encryption for per-user credentials a plugin must keep (AES-256-GCM).

A fresh data key (DEK) encrypts each secret; a master key (KEK) from a private
file wraps the DEK. Associated data binds the ciphertext to its owner, so a row
copied to another actor or record fails to decrypt. Rotation re-wraps DEKs only.
"""

import base64
import os
import stat
from dataclasses import dataclass

from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.hkdf import HKDF

FORMAT = 1


class SecretUnavailable(Exception):
    """The master key is missing or the envelope does not match its owner."""


class Secret:
    """Holds a credential in memory without ever printing it."""

    __slots__ = ("_value",)

    def __init__(self, value):
        self._value = value

    def reveal(self):
        return self._value

    def __repr__(self):
        return "Secret(***)"

    __str__ = __repr__

    def __reduce__(self):
        raise TypeError("secrets are not picklable")


@dataclass(frozen=True)
class Sealed:
    kek_id: str
    wrap_nonce: bytes
    wrapped_dek: bytes
    data_nonce: bytes
    ciphertext: bytes
    format: int = FORMAT

    def __repr__(self):
        return f"Sealed(kek_id={self.kek_id!r}, format={self.format})"


def read_key_file(path):
    """A base64 32-byte key from a file only this process's user can read."""
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    with os.fdopen(fd, "rb") as stream:
        info = os.fstat(stream.fileno())
        if not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid() or info.st_mode & 0o077:
            raise SecretUnavailable("master key file must be private to the service user")
        raw = stream.read(200)
    try:
        key = base64.b64decode(raw.strip(), validate=True)
    except ValueError:
        raise SecretUnavailable("master key file is not base64") from None
    if len(key) != 32:
        raise SecretUnavailable("master key must be 32 bytes")
    return key


class Keyring:
    def __init__(self, keys, primary):
        if primary not in keys or any(not isinstance(k, bytes) or len(k) != 32 for k in keys.values()):
            raise SecretUnavailable("invalid keyring")
        self._keys, self.primary = dict(keys), primary

    def __repr__(self):
        return f"Keyring(primary={self.primary!r}, ids={sorted(self._keys)!r})"

    @classmethod
    def from_files(cls, entries, primary):
        return cls({entry["id"]: read_key_file(entry["file"]) for entry in entries}, primary)

    @property
    def ids(self):
        return frozenset(self._keys)

    @staticmethod
    def _wrap_aad(context, kek_id):
        return b"assistant-plugin.dek.v1|" + context.encode() + b"|" + kek_id.encode()

    def seal(self, plaintext, aad, context):
        dek, data_nonce, wrap_nonce = AESGCM.generate_key(256), os.urandom(12), os.urandom(12)
        ciphertext = AESGCM(dek).encrypt(data_nonce, plaintext, aad)
        wrapped = AESGCM(self._keys[self.primary]).encrypt(wrap_nonce, dek, self._wrap_aad(context, self.primary))
        return Sealed(self.primary, wrap_nonce, wrapped, data_nonce, ciphertext)

    def _dek(self, sealed, context):
        key = self._keys.get(sealed.kek_id)
        if key is None or sealed.format != FORMAT:
            raise SecretUnavailable("master key unavailable")
        try:
            return AESGCM(key).decrypt(sealed.wrap_nonce, sealed.wrapped_dek, self._wrap_aad(context, sealed.kek_id))
        except InvalidTag:
            raise SecretUnavailable("envelope does not match its owner") from None

    def open(self, sealed, aad, context):
        try:
            return AESGCM(self._dek(sealed, context)).decrypt(sealed.data_nonce, sealed.ciphertext, aad)
        except InvalidTag:
            raise SecretUnavailable("envelope does not match its owner") from None

    def rewrap(self, sealed, context):
        if sealed.kek_id == self.primary:
            return sealed
        dek, wrap_nonce = self._dek(sealed, context), os.urandom(12)
        wrapped = AESGCM(self._keys[self.primary]).encrypt(wrap_nonce, dek, self._wrap_aad(context, self.primary))
        return Sealed(self.primary, wrap_nonce, wrapped, sealed.data_nonce, sealed.ciphertext, sealed.format)

    def derive(self, info):
        """A purpose-specific key (e.g. for secret fingerprints) derived from the primary key."""
        return HKDF(algorithm=hashes.SHA256(), length=32, salt=None, info=info).derive(self._keys[self.primary])
