"""Koil framing/lifecycle glue; tokenization remains upstream Gigatoken."""
import atexit
import hashlib
import os
import struct
import subprocess
import sys
import threading
import time

_REQ_MAGIC = 0x47544F4B
_RES_MAGIC = 0x4754414B
_LOAD_COLIBRI_C = 7
_ENCODE = 3
_LOCK = threading.Lock()
_CLIENTS = {}
_DISABLED = {}
_MAX_RESPONSE = 512 * 1024 * 1024
_CALL_TIMEOUT = 30


def _log(status, prompt, started, reason, tokens=None):
    print(f'[GigaToken] status={status} operation=exact_tokenization '
          f'pid={os.getpid()} inputChars={len(prompt)} '
          f'inputSha256={hashlib.sha256(prompt.encode("utf-8")).hexdigest()} '
          f'outputTokens={tokens if tokens is not None else "unknown"} '
          f'durationMs={(time.monotonic() - started) * 1000:.3f} reason={reason}',
          file=sys.stderr, flush=True)


class _Client:
    def __init__(self, executable, tokenizer):
        # Buffered pipes handle partial OS reads/writes, including large vectors.
        self.process = subprocess.Popen(
            [executable], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
            # Keep native diagnostics visible in the Colibri runtime log.
            stderr=None,
        )
        self.request_id = 0
        try:
            with open(tokenizer, 'rb') as source:
                self._call(_LOAD_COLIBRI_C, source.read())
        except Exception:
            self.close()
            raise

    def _call(self, operation, payload):
        self.request_id += 1
        request_id = self.request_id
        watchdog = threading.Timer(_CALL_TIMEOUT, self.process.kill)
        watchdog.daemon = True
        watchdog.start()
        try:
            self.process.stdin.write(struct.pack('<IIII', _REQ_MAGIC, operation,
                                                 request_id, len(payload)))
            self.process.stdin.write(payload)
            self.process.stdin.flush()
            header = self.process.stdout.read(16)
            if len(header) != 16:
                raise RuntimeError('bridge_stopped_or_timed_out')
            magic, status, response_id, length = struct.unpack('<IIII', header)
            if magic != _RES_MAGIC or response_id != request_id or length > _MAX_RESPONSE:
                raise RuntimeError('invalid_response_frame')
            body = self.process.stdout.read(length)
            if len(body) != length:
                raise RuntimeError('truncated_response')
            if status:
                # Upstream errors can contain input; log only the error category.
                raise RuntimeError('bridge_operation_failed')
            return body
        finally:
            watchdog.cancel()

    def encode(self, prompt):
        body = self._call(_ENCODE, prompt.encode('utf-8'))
        if len(body) < 4:
            raise RuntimeError('truncated_token_vector')
        count, = struct.unpack_from('<I', body)
        if len(body) != 4 + count * 4:
            raise RuntimeError('invalid_token_vector')
        return list(struct.unpack_from(f'<{count}I', body, 4))

    def close(self):
        if self.process.poll() is None:
            self.process.terminate()
            try:
                self.process.wait(timeout=2)
            except subprocess.TimeoutExpired:
                self.process.kill()
                self.process.wait(timeout=2)
        self.process.stdin.close()
        self.process.stdout.close()


def _qualified(tokenizer):
    allowed = {value.strip().lower() for value in
               os.environ.get('KOIL_GIGATOKEN_TOKENIZER_SHA256', '').split(',')
               if value.strip()}
    if not allowed:
        return False
    with open(tokenizer, 'rb') as source:
        return hashlib.sha256(source.read()).hexdigest() in allowed


def encode_colibri_prompt(model_directory, prompt):
    """Return exact IDs or a logged native fallback; never claim consumption."""
    started = time.monotonic()
    executable = os.environ.get('KOIL_GIGATOKEN_BRIDGE', '')
    tokenizer = os.path.join(model_directory, 'tokenizer.json')
    key = (executable, tokenizer)
    with _LOCK:
        try:
            reason = _DISABLED.get(key)
            if reason is None and (not executable or not os.path.isfile(executable)):
                reason = 'bridge_unavailable'
            client = _CLIENTS.get(key)
            if reason is None and client is None:
                if not os.path.isfile(tokenizer) or not _qualified(tokenizer):
                    reason = 'tokenizer_uncertified'
                else:
                    client = _Client(executable, tokenizer)
                    _CLIENTS[key] = client
            if reason:
                _DISABLED[key] = reason
                _log('fallback', prompt, started, reason)
                return None
            tokens = client.encode(prompt)
            _log('success', prompt, started, 'exact_ids_returned', len(tokens))
            return tokens
        except Exception as failure:
            failed = _CLIENTS.pop(key, None)
            if failed is not None:
                failed.close()
            reason = type(failure).__name__
            _DISABLED[key] = reason
            _log('fallback', prompt, started, reason)
            return None


@atexit.register
def _close_all():
    with _LOCK:
        for client in _CLIENTS.values():
            client.close()
        _CLIENTS.clear()
